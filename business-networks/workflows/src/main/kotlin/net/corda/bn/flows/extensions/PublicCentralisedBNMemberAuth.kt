package net.corda.bn.flows.extensions

import net.corda.bn.states.MembershipState

class PublicCentralisedBNMemberAuth : BNMemberAuth {
    override fun canRequestMembership(membership: MembershipState) = membership.identity.name.commonName == "BNO"

    override fun canActivateMembership(membership: MembershipState) = membership.identity.name.commonName == "BNO"

    override fun canSuspendMembership(membership: MembershipState) = membership.identity.name.commonName == "BNO"

    override fun canRevokeMembership(membership: MembershipState) = membership.identity.name.commonName == "BNO"
}