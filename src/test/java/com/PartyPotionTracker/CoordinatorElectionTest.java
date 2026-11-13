package com.PartyPotionTracker;

import static org.junit.Assert.*;

import com.google.gson.Gson;

import org.junit.Test;

import java.util.*;

public class CoordinatorElectionTest {
    private static final String SCOPE = "301:TOMBS_OF_AMASCUT:run";
    private static final Gson GSON = new Gson();

    private static final class Network {
        final Map<Long, CoordinatorElection> nodes = new TreeMap<>();
        final Set<Long> party = new HashSet<>();
        final List<Runnable> messages = new ArrayList<>();
        boolean partitioned;

        CoordinatorElection add(long id, boolean used) {
            party.add(id);
            CoordinatorElection node =
                    new CoordinatorElection(
                            id,
                            SCOPE,
                            used,
                            message -> {
                                String json = GSON.toJson(message);
                                for (Map.Entry<Long, CoordinatorElection> target :
                                        nodes.entrySet()) {
                                    if (target.getKey() == id || partitioned) continue;
                                    messages.add(
                                            () ->
                                                    target.getValue()
                                                            .receive(
                                                                    id,
                                                                    GSON.fromJson(
                                                                            json,
                                                                            CoordinatorPresenceMessage
                                                                                    .class)));
                                }
                            });
            nodes.put(id, node);
            return node;
        }

        void advance(int count) {
            for (int i = 0; i < count; i++) {
                for (CoordinatorElection node : nodes.values()) node.tick(party);
                Collections.reverse(messages);
                List<Runnable> delivery = new ArrayList<>(messages);
                messages.clear();
                delivery.forEach(Runnable::run);
            }
        }
    }

    @Test
    public void simultaneousJoinersAgreeDespiteReversedDelivery() {
        Network network = new Network();
        network.add(3, false);
        network.add(1, false);
        network.add(2, false);
        network.advance(45);
        for (CoordinatorElection node : network.nodes.values()) {
            assertEquals(1, node.coordinator());
            assertFalse(node.blocked());
        }
    }

    @Test
    public void partyMembersWithoutPluginAreNotCandidatesOrVoters() {
        Network network = new Network();
        network.party.add(1L);
        CoordinatorElection node = network.add(5, false);
        network.advance(40);
        assertEquals(5, node.coordinator());
        assertFalse(node.eligible(1));
    }

    @Test
    public void lateJoinerWithLowerIdKeepsExistingCoordinator() {
        Network network = new Network();
        network.add(5, false);
        network.advance(40);
        CoordinatorElection late = network.add(1, false);
        network.advance(40);
        assertEquals(5, late.coordinator());
        assertEquals(5, network.nodes.get(5L).coordinator());
    }

    @Test
    public void lostCoordinatorTriggersReplacementFromSurvivingRaidMembers() {
        Network network = new Network();
        network.add(1, false);
        CoordinatorElection follower = network.add(2, false);
        network.advance(40);
        network.nodes.remove(1L);
        network.party.remove(1L);
        network.advance(80);
        assertEquals(2, follower.coordinator());
        assertFalse(follower.eligible(1));
        assertEquals("Coordinator selected", follower.status());
    }

    @Test
    public void returnedCoordinatorAcceptsTheReplacementAuthority() {
        CoordinatorElection oldCoordinator =
                new CoordinatorElection(1, SCOPE, false, message -> {});
        oldCoordinator.tick(Set.of(1L, 2L));
        CoordinatorPresenceMessage replacement = presence(2, 2);
        replacement.failover = true;
        replacement.retiredCoordinator = 1;
        oldCoordinator.receive(2, replacement);
        assertEquals(2, oldCoordinator.coordinator());
        assertFalse(oldCoordinator.blocked());
    }

    @Test
    public void failoverCanReplaceAPreviouslyUsedSession() {
        CoordinatorElection follower = new CoordinatorElection(2, SCOPE, true, message -> {});
        follower.tick(Set.of(1L, 2L));
        follower.receive(1, presence(1, 1));
        assertEquals(1, follower.coordinator());
        for (int i = 0; i < 80; i++) follower.tick(Set.of(2L));
        assertEquals(2, follower.coordinator());
        assertFalse(follower.blocked());
    }

    @Test
    public void survivingMembersChooseTheSameReplacement() {
        Network network = new Network();
        network.add(1, false);
        CoordinatorElection second = network.add(2, false);
        CoordinatorElection third = network.add(3, false);
        network.advance(40);
        network.nodes.remove(1L);
        network.party.remove(1L);
        network.advance(40);
        assertEquals(2, second.coordinator());
        assertEquals(2, third.coordinator());
        assertFalse(second.blocked());
        assertFalse(third.blocked());
    }

    @Test
    public void usedSessionCannotBootstrapButCanRejoinExistingAuthority() {
        Network network = new Network();
        CoordinatorElection used = network.add(2, true);
        network.advance(40);
        assertEquals(0, used.coordinator());
        CoordinatorPresenceMessage message = presence(1, 1);
        network.party.add(1L);
        network.advance(1);
        used.receive(1, message);
        assertEquals(1, used.coordinator());
    }

    @Test
    public void wrongScopeVersionAndNonPartySendersCannotVote() {
        CoordinatorElection node = new CoordinatorElection(2, SCOPE, false, message -> {});
        node.tick(Set.of(1L, 2L));
        CoordinatorPresenceMessage message = presence(1, 1);
        message.scope = "other";
        node.receive(1, message);
        message.scope = SCOPE;
        message.protocol = 99;
        node.receive(1, message);
        message.protocol = 1;
        node.receive(99, message);
        assertEquals(0, node.coordinator());
        assertFalse(node.eligible(1));
    }

    @Test
    public void duplicatePresenceCannotKeepDisconnectedPeerEligible() {
        CoordinatorElection node = new CoordinatorElection(2, SCOPE, false, message -> {});
        Set<Long> members = Set.of(1L, 2L);
        node.tick(members);
        CoordinatorPresenceMessage message = presence(1, 1);
        node.receive(1, message);
        for (int i = 0; i < 25; i++) {
            node.tick(members);
            node.receive(1, message);
        }
        assertFalse(node.eligible(1));
    }

    @Test
    public void separateBootstrapsPauseOnDiscoveringConflictingAuthorities() {
        Network network = new Network();
        network.partitioned = true;
        CoordinatorElection first = network.add(1, false);
        CoordinatorElection second = network.add(2, false);
        network.advance(40);
        assertEquals(1, first.coordinator());
        assertEquals(2, second.coordinator());
        network.partitioned = false;
        network.advance(5);
        assertTrue(first.blocked());
        assertTrue(second.blocked());
    }

    @Test
    public void authenticatedHandoffOverridesOutOfOrderPresenceAndRetiresOldAuthority() {
        CoordinatorElection node = new CoordinatorElection(3, SCOPE, false, message -> {});
        node.tick(Set.of(1L, 2L, 3L));
        node.receive(1, presence(1, 1));
        node.receive(2, presence(1, 2));
        assertTrue(node.blocked());
        node.acceptedHandoff(
                2); // Called only after SharedSupplyState validates the old coordinator's commit.
        assertFalse(node.blocked());
        assertEquals(2, node.coordinator());
        node.receive(1, presence(2, 1));
        assertFalse(node.blocked());
        assertEquals(2, node.coordinator());
    }

    private static CoordinatorPresenceMessage presence(long sequence, long coordinator) {
        CoordinatorPresenceMessage message = new CoordinatorPresenceMessage();
        message.scope = SCOPE;
        message.sequence = sequence;
        message.proposal = "1,2,3";
        message.coordinator = coordinator;
        return message;
    }
}
