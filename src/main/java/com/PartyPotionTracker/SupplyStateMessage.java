package com.PartyPotionTracker;

import net.runelite.client.party.messages.PartyMemberMessage;

import java.util.*;

/** All custom traffic uses the existing RuneLite Party transport. */
public class SupplyStateMessage extends PartyMemberMessage {
    public int protocol = 3;
    // World, raid, and session code: messages from another run must be ignored.
    public String scope;
    public String kind;
    // Identifies a coordinator tenure; changes after an agreed handoff.
    public String term;
    // Correlates snapshot chunks and handoff acknowledgements with their request.
    public String request;
    // Party member ID, or zero for a broadcast.
    public long target;
    // Number of accepted reports in the coordinator history.
    public long revision;
    public long successor;
    public String nextTerm;
    // Zero-based chunk index and total chunk count for a complete snapshot.
    public int part;
    public int parts;
    public boolean paused;
    public SupplyReport report;
    public List<SupplyReport> history;
}
