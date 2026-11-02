package com.PartyPotionTracker;

import net.runelite.client.party.messages.PartyMemberMessage;

import java.util.Map;

/** Each member publishes only their own carried supplies, independently of the coordinator. */
public class InventorySupplyMessage extends PartyMemberMessage {
    public int protocol = 1;
    public String scope;
    public long sequence;
    public Map<String, Integer> doses;
    public Map<String, Integer> bottles;
}
