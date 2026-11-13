package com.PartyPotionTracker;

import net.runelite.client.party.messages.PartyMemberMessage;

/**
 * Discovery is separate from supply updates: only compatible, same-session peers are candidates.
 */
public class CoordinatorPresenceMessage extends PartyMemberMessage {
    public int protocol = 1;
    public String scope;
    public long sequence;
    public String proposal;
    public long coordinator;
    public boolean previouslyParticipated;
    // A replacement election is authoritative for the coordinator that stopped reporting.
    public boolean failover;
    public long retiredCoordinator;
}
