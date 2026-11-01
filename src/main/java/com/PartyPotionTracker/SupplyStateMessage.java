package com.PartyPotionTracker;

import java.util.*;
import net.runelite.client.party.messages.PartyMemberMessage;

/** All custom traffic uses the existing RuneLite Party transport. */
public class SupplyStateMessage extends PartyMemberMessage {
    public int protocol = 3;
    public String scope;
    public String kind;
    public String term;
    public String request;
    public long target;
    public long revision;
    public long successor;
    public String nextTerm;
    public int part;
    public int parts;
    public boolean paused;
    public SupplyReport report;
    public List<SupplyReport> history;
}
