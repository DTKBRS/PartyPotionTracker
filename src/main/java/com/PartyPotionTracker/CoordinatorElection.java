package com.PartyPotionTracker;

import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Bootstraps a coordinator after a discovery window and matching participant proposals.
 *
 * <p>Discovery selects the initial coordinator. If that coordinator stops advertising, the
 * remaining raid members run a deterministic replacement election. Conflicting authority claims
 * still pause the session instead of merging incompatible histories.
 */
final class CoordinatorElection {
    static final int DISCOVERY_TICKS = 15;
    static final int FAILOVER_DISCOVERY_TICKS = 3;
    static final int PEER_TIMEOUT_TICKS = 20;
    private static final int ANNOUNCE_INTERVAL_TICKS = 3;
    private static final int AGREEMENT_TICKS = 6;
    private static final int FAILOVER_AGREEMENT_TICKS = 2;
    private static final int MAX_PEERS = 100;

    private static final class Peer {
        final long sequence;
        final int receivedAt;
        final String proposal;
        final long coordinator;
        final boolean previouslyParticipated;

        Peer(CoordinatorPresenceMessage message, int tick) {
            sequence = message.sequence;
            receivedAt = tick;
            proposal = message.proposal;
            coordinator = message.coordinator;
            previouslyParticipated = message.previouslyParticipated;
        }
    }

    private final long self;
    private final String scope;
    private final Consumer<CoordinatorPresenceMessage> transport;
    private final Map<Long, Peer> peers = new HashMap<>();
    private boolean previouslyParticipated;
    private Set<Long> partyMembers = Collections.emptySet();
    private String proposal = "";
    private int tick;
    private int rosterChangedAt;
    private int agreementStartedAt = -1;
    private long sequence = System.currentTimeMillis();
    private long coordinator;
    private boolean conflictingAuthorities;
    private boolean failover;
    private long retiredCoordinator;
    // Handoffs can arrive before an older presence message. Do not let that old claim undo a
    // handoff.
    private final Set<Long> retiredCoordinators = new HashSet<>();

    CoordinatorElection(
            long self,
            String scope,
            boolean previouslyParticipated,
            Consumer<CoordinatorPresenceMessage> transport) {
        this.self = self;
        this.scope = scope;
        this.previouslyParticipated = previouslyParticipated;
        this.transport = transport;
    }

    long coordinator() {
        return coordinator;
    }

    boolean blocked() {
        return conflictingAuthorities;
    }

    boolean eligible(long member) {
        Peer peer = peers.get(member);
        return partyMembers.contains(member)
                && (member == self || peer != null && tick - peer.receivedAt <= PEER_TIMEOUT_TICKS);
    }

    void tick(Set<Long> partyMembers) {
        this.partyMembers = new HashSet<>(partyMembers);
        tick++;
        peers.keySet().retainAll(partyMembers);
        if (coordinator != 0 && !eligible(coordinator)) beginFailover(coordinator);
        Set<Long> participants = new TreeSet<>();
        if (partyMembers.contains(self)) participants.add(self);
        peers.forEach(
                (member, peer) -> {
                    if (eligible(member)) participants.add(member);
                });
        String currentProposal =
                participants.stream().map(String::valueOf).collect(Collectors.joining(","));
        if (!currentProposal.equals(proposal)) {
            proposal = currentProposal;
            rosterChangedAt = tick;
            agreementStartedAt = -1;
        }

        if (coordinator == 0 && !conflictingAuthorities && !participants.isEmpty()) {
            boolean usedSession =
                    previouslyParticipated
                            || peers.values().stream()
                                    .anyMatch(peer -> peer.previouslyParticipated);
            int discoveryTicks = failover ? FAILOVER_DISCOVERY_TICKS : DISCOVERY_TICKS;
            int agreementTicks = failover ? FAILOVER_AGREEMENT_TICKS : AGREEMENT_TICKS;
            boolean agreed =
                    tick - rosterChangedAt >= discoveryTicks
                            && participants.stream()
                                    .allMatch(
                                            member ->
                                                    member == self
                                                            || proposal.equals(
                                                                    peers.get(member).proposal));
            if ((failover || !usedSession) && agreed) {
                if (agreementStartedAt < 0) agreementStartedAt = tick;
                if (tick - agreementStartedAt >= agreementTicks
                        && participants.iterator().next() == self) {
                    coordinator = self;
                    previouslyParticipated = true;
                }
            } else agreementStartedAt = -1;
        }
        if (tick % ANNOUNCE_INTERVAL_TICKS == 0) announce();
    }

    void receive(long sender, CoordinatorPresenceMessage message) {
        if (message == null
                || message.protocol != 1
                || !scope.equals(message.scope)
                || sender == self
                || !partyMembers.contains(sender)
                || message.sequence <= 0
                || message.proposal == null
                || message.proposal.length() > 2200
                || message.coordinator < 0
                || message.retiredCoordinator < 0
                || peers.size() >= MAX_PEERS && !peers.containsKey(sender)) return;
        Peer previous = peers.get(sender);
        if (previous != null && message.sequence <= previous.sequence) return;
        peers.put(sender, new Peer(message, tick));
        if (message.failover) {
            if (message.retiredCoordinator == 0) return;
            failover = true;
            previouslyParticipated = true;
            retiredCoordinator = message.retiredCoordinator;
            retiredCoordinators.add(message.retiredCoordinator);
            if (message.coordinator == 0) {
                if (coordinator == message.retiredCoordinator) coordinator = 0;
                else if (coordinator != 0 && retiredCoordinator != message.retiredCoordinator)
                    conflictingAuthorities = true;
                return;
            }
            // Prefer the replacement's own announcement. A follower may relay a claim before the
            // replacement's presence has reached us, so do not retire that candidate prematurely.
            if (sender != message.coordinator && !eligible(message.coordinator)) return;
            if (coordinator == 0
                    || coordinator == message.retiredCoordinator
                    || !eligible(coordinator)) {
                coordinator = message.coordinator;
                conflictingAuthorities = false;
                return;
            }
            if (coordinator != message.coordinator) conflictingAuthorities = true;
            return;
        }
        if (message.coordinator == 0 || retiredCoordinators.contains(message.coordinator)) return;
        if (coordinator != 0 && coordinator != message.coordinator) {
            conflictingAuthorities = true;
            return;
        }
        // A follower's hint keeps an established coordinator authoritative until a failover
        // announcement retires it.
        coordinator = message.coordinator;
        previouslyParticipated = true;
    }

    void acceptedHandoff(long successor) {
        if (successor <= 0 || successor == coordinator) return;
        conflictingAuthorities = false;
        if (coordinator != 0) retiredCoordinators.add(coordinator);
        coordinator = successor;
        previouslyParticipated = true;
        announce();
    }

    String status() {
        if (conflictingAuthorities)
            return "Paused: party members disagree about the raid";
        if (coordinator != 0 && !eligible(coordinator))
            return "Paused: elected coordinator unavailable";
        if (failover && coordinator == 0) return "Choosing a replacement coordinator";
        if (coordinator != 0) return "Coordinator selected";
        if (previouslyParticipated
                || peers.values().stream().anyMatch(peer -> peer.previouslyParticipated))
            return "Waiting for the party supply list";
        return "Discovering raid participants and choosing coordinator";
    }

    private void beginFailover(long missingCoordinator) {
        failover = true;
        retiredCoordinator = missingCoordinator;
        retiredCoordinators.add(missingCoordinator);
        coordinator = 0;
        conflictingAuthorities = false;
        agreementStartedAt = -1;
        announce();
    }

    private void announce() {
        CoordinatorPresenceMessage message = new CoordinatorPresenceMessage();
        message.scope = scope;
        message.sequence = sequence = Math.max(sequence + 1, System.currentTimeMillis());
        message.proposal = proposal;
        message.coordinator = coordinator;
        message.previouslyParticipated = previouslyParticipated;
        message.failover = failover;
        message.retiredCoordinator = retiredCoordinator;
        transport.accept(message);
    }
}
