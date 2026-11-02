package com.PartyPotionTracker;

import lombok.Getter;
import lombok.Setter;

import net.runelite.client.party.messages.PartyMemberMessage;

/** Versioned envelope. Missing fields from older plugin versions fail validation. */
@Getter
@Setter
public abstract class SupplyEventMessage extends PartyMemberMessage {
    private int protocol;
    private String eventId;
    private String sessionId;
    private String raid;
    private long sentAt;
    private int world;
    private int quantity;
}
