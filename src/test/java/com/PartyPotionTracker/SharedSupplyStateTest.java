package com.PartyPotionTracker;

import com.google.gson.Gson;
import java.util.*;
import net.runelite.api.coords.WorldPoint;
import org.junit.Test;
import static org.junit.Assert.*;

public class SharedSupplyStateTest {
    private static final String SCOPE = "301:TOMBS_OF_AMASCUT:test-run";
    private static final Gson GSON = new Gson();
    private static class Packet {
        long sender;
        SupplyStateMessage message;
        Packet(long sender, SupplyStateMessage message) { this.sender = sender; this.message = message; }
    }
    private static class Network {
        Map<Long, SharedSupplyState> nodes = new LinkedHashMap<>();
        List<Packet> queue = new ArrayList<>();
        SharedSupplyState add(long id) {
            SharedSupplyState state = new SharedSupplyState(id, 1, SCOPE, m -> queue.add(new Packet(id,
                    GSON.fromJson(GSON.toJson(m), SupplyStateMessage.class))));
            nodes.put(id, state); return state;
        }
        void deliver(Packet p) {
            for (Map.Entry<Long, SharedSupplyState> e : new ArrayList<>(nodes.entrySet())) {
                if (e.getKey() != p.sender && (p.message.target == 0 || p.message.target == e.getKey()))
                    e.getValue().receive(p.sender, "Player" + p.sender, p.message);
            }
        }
        void drain() {
            int max = 10000;
            while (!queue.isEmpty()) { if (--max == 0) fail("Message loop"); deliver(queue.remove(0)); }
        }
        void tick() { new ArrayList<>(nodes.values()).forEach(SharedSupplyState::tick); drain(); }
        void connect() { for (int i = 0; i < 5; i++) tick(); }
        void same() {
            String expected = GSON.toJson(nodes.get(1L).history());
            for (SharedSupplyState s : nodes.values()) assertEquals(expected, GSON.toJson(s.history()));
        }
    }
    private static SupplyReport drop(String id, long actor) {
        SupplyReport r = new SupplyReport(); r.setId(id); r.setActor("Player" + actor); r.setDrop(true);
        r.setItemId(2434); r.setTile(new WorldPoint(3200, 3200, 0)); r.setQuantity(1); return r;
    }
    private static SupplyReport take(String id, long actor, String... drops) {
        SupplyReport r = drop(id, actor); r.setDrop(false); r.setDropIds(Arrays.asList(drops)); return r;
    }

    @Test public void onlyCoordinatorCommitsAndDuplicateReportsAreIdempotent() {
        Network n = new Network(); SharedSupplyState leader = n.add(1), replica = n.add(2); n.connect();
        replica.submit(drop("a", 2));
        assertEquals(0, replica.revision()); assertEquals(1, replica.pendingCount());
        Packet report = n.queue.get(0); n.deliver(report); n.drain(); n.deliver(report); n.drain();
        assertEquals(1, leader.revision()); assertEquals(0, replica.pendingCount()); n.same();
    }
    @Test public void lostReportIsRetriedWithoutLocalOptimisticMutation() {
        Network n = new Network(); n.add(1); SharedSupplyState replica = n.add(2); n.connect();
        replica.submit(drop("a", 2)); n.queue.clear();
        assertEquals(0, replica.revision());
        for (int i = 0; i < 10; i++) n.tick();
        assertEquals(1, replica.revision()); n.same();
    }
    @Test public void reorderedUpdatesRecoverThroughSnapshot() {
        Network n = new Network(); SharedSupplyState leader = n.add(1); SharedSupplyState replica = n.add(2); n.connect();
        leader.submit(drop("a", 1)); leader.submit(drop("b", 1));
        List<Packet> updates = new ArrayList<>(n.queue); n.queue.clear(); Collections.reverse(updates);
        updates.forEach(n::deliver); n.drain();
        for (int i = 0; i < 30; i++) n.tick();
        assertEquals(2, replica.revision()); assertTrue(replica.synchronizedState()); n.same();
    }
    @Test public void pickupBeforeDropWaitsForItsDependencies() {
        Network n = new Network(); n.add(1); SharedSupplyState replica = n.add(2); n.connect();
        replica.submit(take("pickup", 2, "drop")); replica.submit(drop("drop", 2)); n.drain(); n.tick();
        assertEquals(2, replica.revision()); assertTrue(replica.history().get(0).isDrop()); n.same();
    }
    @Test public void unlinkedPickupIsLoggedWithoutChangingSupplyBalances() {
        Network n = new Network(); SharedSupplyState leader = n.add(1), replica = n.add(2); n.connect();
        replica.submit(take("pickup", 2)); n.drain();
        assertEquals(1, leader.revision());
        assertFalse(leader.history().get(0).isDrop());
        assertEquals(1, leader.eventLog().size());
        assertEquals("UNRESOLVED", leader.eventLog().get(0).confidence);
        n.same();
    }
    @Test public void lateJoinGetsCompleteHistoryAndPartialSnapshotIsNeverPublished() {
        Network n = new Network(); SharedSupplyState leader = n.add(1);
        for (int i = 0; i < 12; i++) leader.submit(drop("d" + i, 1)); n.queue.clear();
        SharedSupplyState replica = n.add(2); replica.tick();
        n.deliver(n.queue.remove(0)); leader.tick();
        Packet first = n.queue.remove(0); n.queue.clear(); n.deliver(first);
        assertEquals(0, replica.revision()); assertFalse(replica.synchronizedState());
        for (int i = 0; i < 70; i++) n.tick();
        assertEquals(12, replica.revision()); n.same();
    }
    @Test public void leaderTimeoutPausesAndDoesNotElectOrEraseReplica() {
        Network n = new Network(); SharedSupplyState leader = n.add(1); SharedSupplyState replica = n.add(2); n.connect();
        leader.submit(drop("a", 1)); n.drain(); n.nodes.remove(1L);
        for (int i = 0; i < 25; i++) n.tick();
        assertFalse(replica.synchronizedState()); assertEquals(1, replica.leader()); assertEquals(1, replica.revision());
        replica.submit(drop("b", 2)); assertEquals(1, replica.revision()); assertEquals(1, replica.pendingCount());
    }
    @Test public void restartedCoordinatorCannotSilentlyReplaceExistingTerm() {
        Network n = new Network(); n.add(1); SharedSupplyState replica = n.add(2); n.connect();
        String term = replica.term(); n.add(1); for (int i = 0; i < 10; i++) n.tick();
        assertFalse(replica.synchronizedState()); assertEquals(term, replica.term());
        assertTrue(replica.status().contains("restarted"));
    }
    @Test public void gracefulHandoffTransfersHistoryAndDeduplication() {
        Network n = new Network(); SharedSupplyState old = n.add(1), successor = n.add(2); n.add(3); n.connect();
        old.submit(drop("a", 1)); n.drain(); old.handoff(2); n.drain();
        for (int i = 0; i < 25; i++) n.tick();
        assertEquals(2, old.leader()); assertEquals(2, successor.leader()); assertTrue(successor.synchronizedState());
        successor.submit(drop("a", 1)); assertEquals(1, successor.revision());
        successor.submit(take("pickup", 2, "a")); n.drain(); n.same();
        assertEquals(2, successor.revision());
    }
    @Test public void interruptedHandoffCannotPromoteTargetWithoutCommit() {
        Network n = new Network(); SharedSupplyState old = n.add(1), target = n.add(2); n.connect();
        old.handoff(2); old.tick();
        while (!n.queue.isEmpty() && !"TRANSFER_ACK".equals(n.queue.get(0).message.kind)) n.deliver(n.queue.remove(0));
        Packet delayedAck = n.queue.remove(0); old.cancelHandoff(); n.deliver(delayedAck); n.drain();
        assertEquals(1, old.leader()); assertEquals(1, target.leader()); assertTrue(old.synchronizedState());
    }
    @Test public void wrongSenderScopeAndActorCannotUpdateAuthority() {
        Network n = new Network(); SharedSupplyState leader = n.add(1), replica = n.add(2); n.connect();
        SupplyStateMessage forged = new SupplyStateMessage(); forged.scope = SCOPE; forged.term = replica.term();
        forged.kind = "UPDATE"; forged.revision = 1; forged.report = drop("a", 3);
        replica.receive(3, "Player3", forged); assertEquals(0, replica.revision());
        forged.kind = "REPORT"; leader.receive(2, "Player2", forged); assertEquals(0, leader.revision());
        forged.report = drop("a", 2); forged.scope = "different raid";
        leader.receive(2, "Player2", forged); assertEquals(0, leader.revision());
    }
    @Test public void concurrentPickupsAreOrderedAndCannotOverdraw() {
        Network n = new Network(); SharedSupplyState leader = n.add(1), a = n.add(2), b = n.add(3); n.connect();
        leader.submit(drop("bottle", 1)); n.drain();
        a.submit(take("take-a", 2, "bottle")); b.submit(take("take-b", 3, "bottle")); n.drain();
        assertEquals(2, leader.revision()); assertEquals(0, a.pendingCount()); assertEquals(0, b.pendingCount()); n.same();
    }
    @Test public void lateJoinCanRecoverHandoffThroughOldCoordinatorsRedirect() {
        Network n = new Network(); SharedSupplyState old = n.add(1), next = n.add(2); n.connect();
        old.submit(drop("a", 1)); n.drain(); old.handoff(2);
        for (int i = 0; i < 25; i++) n.tick();
        SharedSupplyState late = n.add(3);
        for (int i = 0; i < 25; i++) n.tick();
        assertEquals(2, late.leader()); assertEquals(next.term(), late.term());
        assertTrue(late.synchronizedState()); n.same();
        SupplyStateMessage stale = new SupplyStateMessage(); stale.scope = SCOPE; stale.kind = "UPDATE";
        stale.term = "obsolete"; stale.revision = 2; stale.report = drop("stale", 1);
        late.receive(1, "Player1", stale);
        assertEquals(1, late.revision());
    }
}
