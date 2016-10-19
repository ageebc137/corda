package com.r3corda.testing.node

import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import com.r3corda.core.ThreadBox
import com.r3corda.core.messaging.*
import com.r3corda.core.serialization.SingletonSerializeAsToken
import com.r3corda.core.utilities.trace
import com.r3corda.node.services.api.MessagingServiceBuilder
import com.r3corda.node.utilities.AffinityExecutor
import com.r3corda.node.utilities.JDBCHashSet
import com.r3corda.node.utilities.databaseTransaction
import com.r3corda.testing.node.InMemoryMessagingNetwork.InMemoryMessaging
import org.jetbrains.exposed.sql.Database
import org.slf4j.LoggerFactory
import rx.Observable
import rx.subjects.PublishSubject
import java.time.Duration
import java.time.Instant
import java.util.*
import java.util.concurrent.LinkedBlockingQueue
import javax.annotation.concurrent.ThreadSafe
import kotlin.concurrent.schedule
import kotlin.concurrent.thread

/**
 * An in-memory network allows you to manufacture [InMemoryMessaging]s for a set of participants. Each
 * [InMemoryMessaging] maintains a queue of messages it has received, and a background thread that dispatches
 * messages one by one to registered handlers. Alternatively, a messaging system may be manually pumped, in which
 * case no thread is created and a caller is expected to force delivery one at a time (this is useful for unit
 * testing).
 */
@ThreadSafe
class InMemoryMessagingNetwork(val sendManuallyPumped: Boolean) : SingletonSerializeAsToken() {
    companion object {
        val MESSAGES_LOG_NAME = "messages"
        private val log = LoggerFactory.getLogger(MESSAGES_LOG_NAME)
    }

    private var counter = 0   // -1 means stopped.
    private val handleEndpointMap = HashMap<Handle, InMemoryMessaging>()

    data class MessageTransfer(val sender: InMemoryMessaging, val message: Message, val recipients: MessageRecipients) {
        override fun toString() = "${message.topicSession} from '${sender.myAddress}' to '$recipients'"
    }

    // All sent messages are kept here until pumpSend is called, or manuallyPumped is set to false
    // The corresponding sentMessages stream reflects when a message was pumpSend'd
    private val messageSendQueue = LinkedBlockingQueue<MessageTransfer>()
    private val _sentMessages = PublishSubject.create<MessageTransfer>()
    @Suppress("unused") // Used by the visualiser tool.
    /** A stream of (sender, message, recipients) triples */
    val sentMessages: Observable<MessageTransfer>
        get() = _sentMessages

    // All messages are kept here until the messages are pumped off the queue by a caller to the node class.
    // Queues are created on-demand when a message is sent to an address: the receiving node doesn't have to have
    // been created yet. If the node identified by the given handle has gone away/been shut down then messages
    // stack up here waiting for it to come back. The intent of this is to simulate a reliable messaging network.
    // The corresponding stream reflects when a message was pumpReceive'd
    private val messageReceiveQueues = HashMap<Handle, LinkedBlockingQueue<MessageTransfer>>()
    private val _receivedMessages = PublishSubject.create<MessageTransfer>()

    @Suppress("unused") // Used by the visualiser tool.
    /** A stream of (sender, message, recipients) triples */
    val receivedMessages: Observable<MessageTransfer>
        get() = _receivedMessages

    val endpoints: List<InMemoryMessaging> @Synchronized get() = handleEndpointMap.values.toList()

    /**
     * Creates a node and returns the new object that identifies its location on the network to senders, and the
     * [InMemoryMessaging] that the recipient/in-memory node uses to receive messages and send messages itself.
     *
     * If [manuallyPumped] is set to true, then you are expected to call the [InMemoryMessaging.pump] method on the [InMemoryMessaging]
     * in order to cause the delivery of a single message, which will occur on the thread of the caller. If set to false
     * then this class will set up a background thread to deliver messages asynchronously, if the handler specifies no
     * executor.
     *
     * @param persistenceTx a lambda to wrap message handling in a transaction if necessary. Defaults to a no-op.
     */
    @Synchronized
    fun createNode(manuallyPumped: Boolean,
                   executor: AffinityExecutor,
                   database: Database)
            : Pair<Handle, com.r3corda.node.services.api.MessagingServiceBuilder<InMemoryMessaging>> {
        check(counter >= 0) { "In memory network stopped: please recreate." }
        val builder = createNodeWithID(manuallyPumped, counter, executor, database = database) as Builder
        counter++
        val id = builder.id
        return Pair(id, builder)
    }

    /**
     * Creates a node at the given address: useful if you want to recreate a node to simulate a restart.
     *
     * @param manuallyPumped see [createNode].
     * @param id the numeric ID to use, e.g. set to whatever ID the node used last time.
     * @param description text string that identifies this node for message logging (if is enabled) or null to autogenerate.
     * @param persistenceTx a lambda to wrap message handling in a transaction if necessary.
     */
    fun createNodeWithID(manuallyPumped: Boolean, id: Int, executor: AffinityExecutor, description: String? = null,
                         database: Database)
            : MessagingServiceBuilder<InMemoryMessaging> {
        return Builder(manuallyPumped, Handle(id, description ?: "In memory node $id"), executor, database = database)
    }

    interface LatencyCalculator {
        fun between(sender: SingleMessageRecipient, receiver: SingleMessageRecipient): Duration
    }

    /** This can be set to an object which can inject artificial latency between sender/recipient pairs. */
    @Volatile var latencyCalculator: LatencyCalculator? = null
    private val timer = Timer()

    @Synchronized
    private fun msgSend(from: InMemoryMessaging, message: Message, recipients: MessageRecipients) {
        val transfer = MessageTransfer(from, message, recipients)
        messageSendQueue.add(transfer)
    }

    @Synchronized
    private fun netNodeHasShutdown(handle: Handle) {
        handleEndpointMap.remove(handle)
    }

    @Synchronized
    private fun getQueueForHandle(recipients: Handle) = messageReceiveQueues.getOrPut(recipients) { LinkedBlockingQueue() }

    val everyoneOnline: AllPossibleRecipients = object : AllPossibleRecipients {}

    fun stop() {
        val nodes = synchronized(this) {
            counter = -1
            handleEndpointMap.values.toList()
        }

        for (node in nodes)
            node.stop()

        handleEndpointMap.clear()
        messageReceiveQueues.clear()
    }

    inner class Builder(val manuallyPumped: Boolean, val id: Handle, val executor: AffinityExecutor, val database: Database)
    : com.r3corda.node.services.api.MessagingServiceBuilder<InMemoryMessaging> {
        override fun start(): ListenableFuture<InMemoryMessaging> {
            synchronized(this@InMemoryMessagingNetwork) {
                val node = InMemoryMessaging(manuallyPumped, id, executor, database)
                handleEndpointMap[id] = node
                return Futures.immediateFuture(node)
            }
        }
    }

    class Handle(val id: Int, val description: String) : SingleMessageRecipient {
        override fun toString() = description
        override fun equals(other: Any?) = other is Handle && other.id == id
        override fun hashCode() = id.hashCode()
    }

    // If block is set to true this function will only return once a message has been pushed onto the recipients' queues
    fun pumpSend(block: Boolean): MessageTransfer? {
        val transfer = (if (block) messageSendQueue.take() else messageSendQueue.poll()) ?: return null
        val recipients = transfer.recipients
        val from = transfer.sender.myAddress

        log.trace { transfer.toString() }
        val calc = latencyCalculator
        if (calc != null && recipients is SingleMessageRecipient) {
            val messageSent = SettableFuture.create<Unit>()
            // Inject some artificial latency.
            timer.schedule(calc.between(from, recipients).toMillis()) {
                pumpSendInternal(transfer)
                messageSent.set(Unit)
            }
            if (block) {
                messageSent.get()
            }
        } else {
            pumpSendInternal(transfer)
        }

        return transfer
    }

    fun pumpSendInternal(transfer: MessageTransfer) {
        when (transfer.recipients) {
            is Handle -> getQueueForHandle(transfer.recipients).add(transfer)

            is AllPossibleRecipients -> {
                // This means all possible recipients _that the network knows about at the time_, not literally everyone
                // who joins into the indefinite future.
                for (handle in handleEndpointMap.keys)
                    getQueueForHandle(handle).add(transfer)
            }
            else -> throw IllegalArgumentException("Unknown type of recipient handle")
        }
        _sentMessages.onNext(transfer)
    }

    /**
     * An [InMemoryMessaging] provides a [MessagingService] that isn't backed by any kind of network or disk storage
     * system, but just uses regular queues on the heap instead. It is intended for unit testing and developer convenience
     * when all entities on 'the network' are being simulated in-process.
     *
     * An instance can be obtained by creating a builder and then using the start method.
     */
    @ThreadSafe
    inner class InMemoryMessaging(private val manuallyPumped: Boolean,
                                  private val handle: Handle,
                                  private val executor: AffinityExecutor,
                                  private val database: Database)
    : SingletonSerializeAsToken(), com.r3corda.node.services.api.MessagingServiceInternal {
        inner class Handler(val topicSession: TopicSession,
                            val callback: (Message, MessageHandlerRegistration) -> Unit) : MessageHandlerRegistration

        @Volatile
        private var running = true

        private inner class InnerState {
            val handlers: MutableList<Handler> = ArrayList()
            val pendingRedelivery = JDBCHashSet<Message>("pending_messages",loadOnInit = true)
        }

        private val state = ThreadBox(InnerState())
        private val processedMessages: MutableSet<UUID> = Collections.synchronizedSet(HashSet<UUID>())

        override val myAddress: Handle get() = handle

        private val backgroundThread = if (manuallyPumped) null else
            thread(isDaemon = true, name = "In-memory message dispatcher") {
                while (!Thread.currentThread().isInterrupted) {
                    try {
                        pumpReceiveInternal(true)
                    } catch(e: InterruptedException) {
                        break
                    }
                }
            }

        override fun addMessageHandler(topic: String, sessionID: Long, callback: (Message, MessageHandlerRegistration) -> Unit): MessageHandlerRegistration
                = addMessageHandler(TopicSession(topic, sessionID), callback)

        override fun addMessageHandler(topicSession: TopicSession, callback: (Message, MessageHandlerRegistration) -> Unit): MessageHandlerRegistration {
            check(running)
            val (handler, items) = state.locked {
                val handler = Handler(topicSession, callback).apply { handlers.add(this) }
                val pending = ArrayList<Message>()
                databaseTransaction(database) {
                    pending.addAll(pendingRedelivery)
                    pendingRedelivery.clear()
                }
                Pair(handler, pending)
            }
            for (message in items) {
                send(message, handle)
            }
            return handler
        }

        override fun removeMessageHandler(registration: MessageHandlerRegistration) {
            check(running)
            state.locked { check(handlers.remove(registration as Handler)) }
        }

        override fun send(message: Message, target: MessageRecipients) {
            check(running)
            msgSend(this, message, target)
            if (!sendManuallyPumped) {
                pumpSend(false)
            }
        }

        override fun stop() {
            if (backgroundThread != null) {
                backgroundThread.interrupt()
                backgroundThread.join()
            }
            running = false
            netNodeHasShutdown(handle)
        }

        /** Returns the given (topic & session, data) pair as a newly created message object. */
        override fun createMessage(topicSession: TopicSession, data: ByteArray, uuid: UUID): Message {
            return object : Message {
                override val topicSession: TopicSession get() = topicSession
                override val data: ByteArray get() = data
                override val debugTimestamp: Instant = Instant.now()
                override fun serialise(): ByteArray = this.serialise()
                override val uniqueMessageId: UUID = uuid

                override fun toString() = topicSession.toString() + "#" + String(data)
            }
        }

        /**
         * Delivers a single message from the internal queue. If there are no messages waiting to be delivered and block
         * is true, waits until one has been provided on a different thread via send. If block is false, the return
         * result indicates whether a message was delivered or not.
         *
         * @return the message that was processed, if any in this round.
         */
        fun pumpReceive(block: Boolean): MessageTransfer? {
            check(manuallyPumped)
            check(running)
            executor.flush()
            try {
                return pumpReceiveInternal(block)
            } finally {
                executor.flush()
            }
        }

        /**
         * Get the next transfer, and matching queue, that is ready to handle. Any pending transfers without handlers
         * are placed into `pendingRedelivery` to try again later.
         *
         * @param block if this should block until a message it can process.
         */
        private fun getNextQueue(q: LinkedBlockingQueue<MessageTransfer>, block: Boolean): Pair<MessageTransfer, List<Handler>>? {
            var deliverTo: List<Handler>? = null
            // Pop transfers off the queue until we run out (and are not blocking), or find something we can process
            while (deliverTo == null) {
                val transfer = (if (block) q.take() else q.poll()) ?: return null
                deliverTo = state.locked {
                    val h = handlers.filter { if (it.topicSession.isBlank()) true else transfer.message.topicSession == it.topicSession }

                    if (h.isEmpty()) {
                        // Got no handlers for this message yet. Keep the message around and attempt redelivery after a new
                        // handler has been registered. The purpose of this path is to make unit tests that have multi-threading
                        // reliable, as a sender may attempt to send a message to a receiver that hasn't finished setting
                        // up a handler for yet. Most unit tests don't run threaded, but we want to test true parallelism at
                        // least sometimes.
                        log.warn("Message to ${transfer.message.topicSession} could not be delivered")
                        databaseTransaction(database) {
                            pendingRedelivery.add(transfer.message)
                        }
                        null
                    } else {
                        h
                    }
                }
                if (deliverTo != null) {
                    return Pair(transfer, deliverTo)
                }
            }
            return null
        }

        private fun pumpReceiveInternal(block: Boolean): MessageTransfer? {
            val q = getQueueForHandle(handle)
            val next = getNextQueue(q, block) ?: return null
            val (transfer, deliverTo) = next

            if (transfer.message.uniqueMessageId !in processedMessages) {
                executor.execute {
                    databaseTransaction(database) {
                        for (handler in deliverTo) {
                            try {
                                handler.callback(transfer.message, handler)
                            } catch(e: Exception) {
                                log.error("Caught exception in handler for $this/${handler.topicSession}", e)
                            }
                        }
                        _receivedMessages.onNext(transfer)
                        processedMessages += transfer.message.uniqueMessageId
                    }
                }
            } else {
                log.info("Drop duplicate message ${transfer.message.uniqueMessageId}")
            }
            return transfer
        }
    }
}
