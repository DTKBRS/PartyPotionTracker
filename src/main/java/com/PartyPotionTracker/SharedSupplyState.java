package com.PartyPotionTracker;

import java.util.*;
import java.util.function.Consumer;

/**
 * Keeps the party's accepted supply history in order.
 *
 * <p>Only the selected coordinator accepts reports and assigns revisions. Other members keep a
 * copy, retry their unconfirmed reports, and request a snapshot when they miss an update. Local
 * observations never directly change this shared history.
 *
 * <p>All calls are serialized by the plugin on the client thread. This class itself does not depend
 * on RuneLite client events or networking.
 */
final class SharedSupplyState {
    static final int MAX_EVENTS = 500;
    static final int SNAPSHOT_CHUNK_SIZE = 10;
    static final int COORDINATOR_TIMEOUT_TICKS = 20;
    private static final int MAX_PENDING_REPORTS = 100;
    private static final int MAX_DEFERRED_REPORTS = 100;
    private static final int MAX_QUEUED_SNAPSHOT_CHUNKS = 400;
    private static final int SNAPSHOT_CHUNKS_PER_TICK = 2;
    private static final int HEARTBEAT_INTERVAL_TICKS = 5;
    private static final int REPORT_RETRY_INTERVAL_TICKS = 5;
    private static final int HANDOFF_RETRY_INTERVAL_TICKS = 10;
    private static final int SNAPSHOT_REQUEST_INTERVAL_TICKS = 20;
    private static final int SNAPSHOT_RETRY_INTERVAL_TICKS = 60;

    private final long localMemberId;
    private final String scope;
    private final Consumer<SupplyStateMessage> transport;
    private final List<SupplyReport> history = new ArrayList<>();
    private final List<SupplyEventRecord> eventLog = new ArrayList<>();
    private final Map<String, SupplyReport> pending = new LinkedHashMap<>();
    private final Map<String, ReportWaitingForDrops> reportsWaitingForDrops = new LinkedHashMap<>();
    private final Map<String, Integer> acceptedEventIndexes = new HashMap<>();
    private final Map<String, SupplyReport> remainingDrops = new LinkedHashMap<>();
    private long coordinatorMemberId;
    // A unique ID for this coordinator's tenure. Old-tenure messages cannot change current state.
    private String term;
    private int tick;
    private int lastCoordinatorContactTick = -1000;
    private int lastSnapshotRequestTick = -1000;
    private boolean ready;
    private boolean frozen;
    private String request;
    private SnapshotAssembly recoverySnapshot;
    private boolean allowLowerRevisionSnapshot;
    private SnapshotAssembly handoffSnapshot;
    private long handoffTargetMemberId;
    private String handoffRequestId;
    private String nextTerm;
    private SupplyStateMessage redirect;
    private String problem = "";
    private long generation;
    private long latestCoordinatorRevision;
    private boolean transportConnected = true;
    private final Deque<SupplyStateMessage> snapshotOutbox = new ArrayDeque<>();

    private static final class ReportWaitingForDrops {
        final long sender;
        final SupplyReport report;
        final int tick;

        ReportWaitingForDrops(long sender, SupplyReport report, int tick) {
            this.sender = sender;
            this.report = report;
            this.tick = tick;
        }
    }

    private static final class SnapshotAssembly {
        final String id;
        final String term;
        final long revision;
        final int count;
        final Map<Integer, List<SupplyReport>> chunks = new HashMap<>();

        SnapshotAssembly(SupplyStateMessage message) {
            id = message.request;
            term = message.term;
            revision = message.revision;
            count = message.parts;
        }

        boolean add(SupplyStateMessage message) {
            if (!Objects.equals(id, message.request)
                    || !Objects.equals(term, message.term)
                    || revision != message.revision
                    || count != message.parts
                    || message.part < 0
                    || message.part >= count
                    || message.history == null
                    || message.history.size() > SNAPSHOT_CHUNK_SIZE) return false;
            List<SupplyReport> copy = new ArrayList<>();
            for (SupplyReport report : message.history) {
                if (report == null || !report.valid()) return false;
                copy.add(report.copy());
            }
            chunks.putIfAbsent(message.part, copy);
            return true;
        }

        List<SupplyReport> complete() {
            if (chunks.size() != count) return null;
            List<SupplyReport> result = new ArrayList<>();
            for (int i = 0; i < count; i++) result.addAll(chunks.get(i));
            return result.size() == revision ? result : null;
        }
    }

    SharedSupplyState(
            long localMemberId,
            long coordinatorMemberId,
            String scope,
            Consumer<SupplyStateMessage> transport) {
        this.localMemberId = localMemberId;
        this.coordinatorMemberId = coordinatorMemberId;
        this.scope = scope;
        this.transport = transport;
        if (localMemberId == coordinatorMemberId) {
            term = UUID.randomUUID().toString();
            ready = true;
            lastCoordinatorContactTick = 0;
        }
    }

    long leader() {
        return coordinatorMemberId;
    }

    String term() {
        return term;
    }

    long revision() {
        return history.size();
    }

    long generation() {
        return generation;
    }

    int pendingCount() {
        return pending.size();
    }

    boolean synchronizedState() {
        return transportConnected
                && ready
                && !frozen
                && (coordinatorMemberId == localMemberId
                        || tick - lastCoordinatorContactTick <= COORDINATOR_TIMEOUT_TICKS);
    }

    List<SupplyReport> history() {
        List<SupplyReport> result = new ArrayList<>();
        history.forEach(report -> result.add(report.copy()));
        return result;
    }

    List<SupplyEventRecord> eventLog() {
        return Collections.unmodifiableList(new ArrayList<>(eventLog));
    }

    List<String> pendingDropIds(int item, net.runelite.api.coords.WorldPoint tile) {
        List<String> result = new ArrayList<>();
        pending.values().stream()
                .filter(
                        report ->
                                report.isDrop()
                                        && report.getItemId() == item
                                        && report.getTile().equals(tile))
                .forEach(report -> result.add(report.getId()));
        return result;
    }

    String status() {
        if (!problem.isEmpty()) return problem;
        if (!transportConnected) return "Paused: party connection unavailable";
        if (frozen) return "Paused while the shared supply list is handed over";
        if (!synchronizedState())
            return ready ? "Paused: waiting for the party supply list" : "Loading party supplies";
        return localMemberId == coordinatorMemberId
                ? "Coordinator selected"
                : "Party supplies are up to date";
    }

    private SupplyStateMessage createMessage(String kind, long target) {
        SupplyStateMessage message = new SupplyStateMessage();
        message.scope = scope;
        message.kind = kind;
        message.target = target;
        message.term = term;
        message.revision = revision();
        return message;
    }

    private void send(SupplyStateMessage message) {
        transport.accept(message);
    }

    void submit(SupplyReport report) {
        if (report == null || !report.valid() || pending.size() >= MAX_PENDING_REPORTS) {
            problem = "Some supply updates could not be recorded";
            return;
        }
        if (acceptedEventIndexes.containsKey(report.getId())) return;
        pending.putIfAbsent(report.getId(), report.copy());
        if (synchronizedState()) sendReport(report);
    }

    private void sendReport(SupplyReport report) {
        if (localMemberId == coordinatorMemberId) acceptReport(localMemberId, report);
        else {
            SupplyStateMessage message = createMessage("REPORT", coordinatorMemberId);
            message.report = report.copy();
            send(message);
        }
    }

    void tick() {
        tick++;
        for (int i = 0; i < SNAPSHOT_CHUNKS_PER_TICK && !snapshotOutbox.isEmpty(); i++)
            send(snapshotOutbox.removeFirst());
        if (localMemberId == coordinatorMemberId && tick % HEARTBEAT_INTERVAL_TICKS == 0)
            send(createMessage(frozen ? "FROZEN" : "HEARTBEAT", 0));
        if (handoffTargetMemberId != 0
                && localMemberId == coordinatorMemberId
                && tick % HANDOFF_RETRY_INTERVAL_TICKS == 0)
            sendSnapshot("TRANSFER", handoffTargetMemberId, handoffRequestId);
        if (localMemberId != coordinatorMemberId && !synchronizedState()) requestSnapshot();
        if (synchronizedState() && tick % REPORT_RETRY_INTERVAL_TICKS == 0)
            new ArrayList<>(pending.values()).forEach(this::sendReport);
        if (localMemberId == coordinatorMemberId && !frozen) {
            retryReportsWaitingForDrops();
        }
    }

    // A pickup may arrive before its drop report because party messages can arrive out of order.
    private void retryReportsWaitingForDrops() {
        for (ReportWaitingForDrops waitingReport :
                new ArrayList<>(reportsWaitingForDrops.values())) {
            if (hasReferencedDrops(waitingReport.report)) {
                reportsWaitingForDrops.remove(waitingReport.report.getId());
                acceptReport(waitingReport.sender, waitingReport.report);
            } else if (tick - waitingReport.tick > COORDINATOR_TIMEOUT_TICKS) {
                reportsWaitingForDrops.remove(waitingReport.report.getId());
                reject(waitingReport.sender, waitingReport.report);
            }
        }
    }

    void disconnected() {
        lastCoordinatorContactTick = tick - COORDINATOR_TIMEOUT_TICKS - 1;
    }

    /** Promote this replica after the election has selected it as the replacement coordinator. */
    void promoteToCoordinator() {
        coordinatorMemberId = localMemberId;
        term = UUID.randomUUID().toString();
        ready = true;
        frozen = false;
        redirect = null;
        handoffTargetMemberId = 0;
        handoffRequestId = null;
        nextTerm = null;
        request = null;
        recoverySnapshot = null;
        handoffSnapshot = null;
        allowLowerRevisionSnapshot = false;
        latestCoordinatorRevision = revision();
        lastCoordinatorContactTick = tick;
        problem = "";
        generation++;
        send(createMessage("HEARTBEAT", 0));
    }

    /** Point a replica at a replacement coordinator and request its authoritative history. */
    void reassignCoordinator(long replacement) {
        if (replacement <= 0 || replacement == localMemberId) return;
        coordinatorMemberId = replacement;
        term = null;
        ready = false;
        frozen = false;
        redirect = null;
        handoffTargetMemberId = 0;
        handoffRequestId = null;
        nextTerm = null;
        request = null;
        recoverySnapshot = null;
        handoffSnapshot = null;
        allowLowerRevisionSnapshot = true;
        lastCoordinatorContactTick = tick - COORDINATOR_TIMEOUT_TICKS - 1;
        lastSnapshotRequestTick = tick - SNAPSHOT_REQUEST_INTERVAL_TICKS;
        problem = "";
        generation++;
    }

    void transportFailed() {
        transportConnected = false;
    }

    void transportRestored() {
        transportConnected = true;
    }

    private void requestSnapshot() {
        if (tick - lastSnapshotRequestTick < SNAPSHOT_REQUEST_INTERVAL_TICKS
                || (request != null
                        && tick - lastSnapshotRequestTick < SNAPSHOT_RETRY_INTERVAL_TICKS)) return;
        lastSnapshotRequestTick = tick;
        request = UUID.randomUUID().toString();
        recoverySnapshot = null;
        SupplyStateMessage message = createMessage("REQUEST", coordinatorMemberId);
        message.request = request;
        send(message);
    }

    void handoff(long target) {
        if (localMemberId != coordinatorMemberId
                || !synchronizedState()
                || target == 0
                || target == localMemberId) return;
        frozen = true;
        handoffTargetMemberId = target;
        handoffRequestId = UUID.randomUUID().toString();
        nextTerm = UUID.randomUUID().toString();
        send(createMessage("FROZEN", 0));
        sendSnapshot("TRANSFER", target, handoffRequestId);
    }

    void cancelHandoff() {
        if (localMemberId != coordinatorMemberId || handoffTargetMemberId == 0) return;
        snapshotOutbox.removeIf(message -> "TRANSFER".equals(message.kind));
        handoffTargetMemberId = 0;
        handoffRequestId = null;
        nextTerm = null;
        frozen = false;
        send(createMessage("HEARTBEAT", 0));
    }

    private void sendSnapshot(String kind, long target, String id) {
        if (snapshotOutbox.stream()
                .anyMatch(
                        message ->
                                kind.equals(message.kind)
                                        && message.target == target
                                        && Objects.equals(message.request, id))) return;
        snapshotOutbox.removeIf(message -> kind.equals(message.kind) && message.target == target);
        int count = Math.max(1, (history.size() + SNAPSHOT_CHUNK_SIZE - 1) / SNAPSHOT_CHUNK_SIZE);
        if (snapshotOutbox.size() + count > MAX_QUEUED_SNAPSHOT_CHUNKS) return;
        for (int i = 0; i < count; i++) {
            SupplyStateMessage message = createMessage(kind, target);
            message.request = id;
            message.part = i;
            message.parts = count;
            message.nextTerm = nextTerm;
            message.paused = frozen;
            message.history = new ArrayList<>();
            for (int j = i * SNAPSHOT_CHUNK_SIZE;
                    j < Math.min(history.size(), (i + 1) * SNAPSHOT_CHUNK_SIZE);
                    j++) message.history.add(history.get(j).copy());
            snapshotOutbox.addLast(message);
        }
    }

    void receive(long sender, String senderName, SupplyStateMessage message) {
        if (message == null
                || message.protocol != 3
                || !scope.equals(message.scope)
                || message.kind == null
                || message.revision < 0
                || message.revision > MAX_EVENTS
                || (message.target != 0 && message.target != localMemberId)) return;
        if ("REQUEST".equals(message.kind)) {
            handleSnapshotRequest(sender, message);
            return;
        }
        if ("REPORT".equals(message.kind)) {
            handleIncomingReport(sender, senderName, message);
            return;
        }
        if ("TRANSFER_ACK".equals(message.kind)) {
            handleHandoffAcknowledgement(sender, message);
            return;
        }
        if (sender != coordinatorMemberId) return;
        if ("COMMIT".equals(message.kind)) {
            handleHandoffCommit(message);
            return;
        }
        if (term != null && !Objects.equals(term, message.term)) {
            problem = "Paused: coordinator restarted; the party supply list needs to be rebuilt";
            ready = false;
            return;
        }
        if (message.term == null || message.term.length() > 100) return;
        if ("HEARTBEAT".equals(message.kind) || "FROZEN".equals(message.kind)) {
            handleCoordinatorHeartbeat(message);
            return;
        }
        if ("TRANSFER".equals(message.kind)) {
            handleHandoffSnapshot(message);
            return;
        }
        if ("SNAPSHOT".equals(message.kind)) {
            handleRecoverySnapshot(message);
            return;
        }
        if (!Objects.equals(term, message.term)) return;
        if ("REJECT".equals(message.kind)) {
            handleRejectedReport(message);
            return;
        }
        if ("UPDATE".equals(message.kind) && message.report != null && message.report.valid()) {
            handleAcceptedUpdate(message);
            return;
        }
    }

    private void handleSnapshotRequest(long sender, SupplyStateMessage message) {
        if (message.request == null || message.request.length() > 100) return;
        if (localMemberId == coordinatorMemberId) sendSnapshot("SNAPSHOT", sender, message.request);
        else if (redirect != null) send(redirect);
    }

    private void handleIncomingReport(long sender, String senderName, SupplyStateMessage message) {
        if (localMemberId == coordinatorMemberId
                && synchronizedState()
                && Objects.equals(term, message.term)
                && message.report != null
                && message.report.valid()
                && message.report.getActor().equalsIgnoreCase(senderName))
            acceptReport(sender, message.report);
    }

    // The old coordinator commits the handoff only after the successor has the complete history.
    private void handleHandoffAcknowledgement(long sender, SupplyStateMessage message) {
        if (localMemberId == coordinatorMemberId
                && frozen
                && sender == handoffTargetMemberId
                && Objects.equals(message.request, handoffRequestId)
                && Objects.equals(term, message.term)
                && message.revision == revision()) {
            SupplyStateMessage commit = createMessage("COMMIT", 0);
            commit.successor = handoffTargetMemberId;
            commit.nextTerm = nextTerm;
            commit.request = handoffRequestId;
            redirect = commit;
            snapshotOutbox.clear();
            coordinatorMemberId = handoffTargetMemberId;
            term = nextTerm;
            handoffTargetMemberId = 0;
            frozen = false;
            ready = false;
            send(commit);
        }
    }

    private void handleHandoffCommit(SupplyStateMessage message) {
        if ((term != null && !Objects.equals(term, message.term))
                || message.term == null
                || message.nextTerm == null
                || message.successor == 0
                || message.successor == coordinatorMemberId) return;
        if (message.successor == localMemberId) {
            if (handoffSnapshot == null
                    || !Objects.equals(handoffSnapshot.id, message.request)
                    || handoffSnapshot.revision != message.revision
                    || handoffSnapshot.complete() == null
                    || !installSnapshot(handoffSnapshot.complete())) return;
            coordinatorMemberId = localMemberId;
            term = message.nextTerm;
            ready = true;
            frozen = false;
            handoffSnapshot = null;
            request = null;
            lastCoordinatorContactTick = tick;
            send(createMessage("HEARTBEAT", 0));
        } else {
            coordinatorMemberId = message.successor;
            term = message.nextTerm;
            ready = false;
            frozen = false;
            request = null;
            lastSnapshotRequestTick = tick - SNAPSHOT_REQUEST_INTERVAL_TICKS;
            requestSnapshot();
        }
    }

    private void handleCoordinatorHeartbeat(SupplyStateMessage message) {
        if (term == null) term = message.term;
        latestCoordinatorRevision = Math.max(latestCoordinatorRevision, message.revision);
        lastCoordinatorContactTick = tick;
        frozen = "FROZEN".equals(message.kind);
        if (message.revision != revision() || !ready) {
            ready = false;
            requestSnapshot();
        }
    }

    private void handleHandoffSnapshot(SupplyStateMessage message) {
        if (!isValidSnapshotChunk(message) || message.nextTerm == null) return;
        if (term == null) term = message.term;
        if (handoffSnapshot == null || !Objects.equals(handoffSnapshot.id, message.request))
            handoffSnapshot = new SnapshotAssembly(message);
        if (!handoffSnapshot.add(message)) return;
        if (handoffSnapshot.complete() != null && isValidHistory(handoffSnapshot.complete())) {
            frozen = true;
            SupplyStateMessage ack = createMessage("TRANSFER_ACK", coordinatorMemberId);
            ack.request = message.request;
            ack.revision = message.revision;
            send(ack);
        }
    }

    private void handleRecoverySnapshot(SupplyStateMessage message) {
        if (request == null
                || !request.equals(message.request)
                || !isValidSnapshotChunk(message)
                || (message.revision < revision() && !allowLowerRevisionSnapshot)) return;
        if (recoverySnapshot == null) recoverySnapshot = new SnapshotAssembly(message);
        if (!recoverySnapshot.add(message)) return;
        List<SupplyReport> all = recoverySnapshot.complete();
        if (all != null && installSnapshot(all)) {
            term = message.term;
            ready = revision() >= latestCoordinatorRevision;
            frozen = message.paused;
            lastCoordinatorContactTick = tick;
            request = null;
            recoverySnapshot = null;
            allowLowerRevisionSnapshot = false;
            problem = "";
        }
    }

    private void handleRejectedReport(SupplyStateMessage message) {
        if (message.report != null && pending.remove(message.report.getId()) != null)
            problem = "Report could not be reconciled";
    }

    // A revision gap means we need a snapshot; applying later events alone could lose a drop.
    private void handleAcceptedUpdate(SupplyStateMessage message) {
        lastCoordinatorContactTick = tick;
        latestCoordinatorRevision = Math.max(latestCoordinatorRevision, message.revision);
        if (message.revision <= revision()) {
            if (acceptedEventIndexes.containsKey(message.report.getId()))
                pending.remove(message.report.getId());
            return;
        }
        if (message.revision != revision() + 1 || !ready || !appendAcceptedReport(message.report)) {
            ready = false;
            requestSnapshot();
            return;
        }
        pending.remove(message.report.getId());
    }

    private boolean isValidSnapshotChunk(SupplyStateMessage message) {
        return message.request != null
                && message.request.length() <= 100
                && message.revision >= 0
                && message.revision <= MAX_EVENTS
                && message.parts
                        == Math.max(
                                1,
                                ((int) message.revision + SNAPSHOT_CHUNK_SIZE - 1)
                                        / SNAPSHOT_CHUNK_SIZE);
    }

    private boolean hasReferencedDrops(SupplyReport report) {
        return report.isDrop()
                || report.getDropIds().isEmpty()
                || acceptedEventIndexes.keySet().containsAll(report.getDropIds());
    }

    private void acceptReport(long sender, SupplyReport report) {
        Integer index = acceptedEventIndexes.get(report.getId());
        if (index != null) {
            pending.remove(report.getId());
            SupplyStateMessage message = createMessage("UPDATE", sender);
            message.report = history.get(index).copy();
            message.revision = index + 1;
            if (sender != localMemberId) send(message);
            return;
        }
        if (!hasReferencedDrops(report)) {
            if (reportsWaitingForDrops.size() < MAX_DEFERRED_REPORTS)
                reportsWaitingForDrops.putIfAbsent(
                        report.getId(), new ReportWaitingForDrops(sender, report.copy(), tick));
            else reject(sender, report);
            return;
        }
        if (history.size() >= MAX_EVENTS) {
            problem = "Paused: session history limit reached";
            frozen = true;
            return;
        }
        if (!appendAcceptedReport(report)) {
            reject(sender, report);
            return;
        }
        pending.remove(report.getId());
        SupplyStateMessage message = createMessage("UPDATE", 0);
        message.report = report.copy();
        send(message);
    }

    private void reject(long sender, SupplyReport report) {
        if (sender == localMemberId) {
            pending.remove(report.getId());
            problem = "Report could not be reconciled";
        } else {
            SupplyStateMessage message = createMessage("REJECT", sender);
            message.report = report.copy();
            send(message);
        }
    }

    // Check quantities before changing balances so two pickups cannot consume the same supply.
    private boolean appendAcceptedReport(SupplyReport report) {
        if (!report.valid()
                || acceptedEventIndexes.containsKey(report.getId())
                || history.size() >= MAX_EVENTS) return false;
        if (!report.isDrop() && !report.getDropIds().isEmpty()) {
            int total = 0;
            for (String id : new HashSet<>(report.getDropIds())) {
                SupplyReport balance = remainingDrops.get(id);
                if (balance != null
                        && balance.getItemId() == report.getItemId()
                        && balance.getTile().equals(report.getTile()))
                    total += balance.getQuantity();
            }
            if (total < report.getQuantity()) return false;
            int remaining = report.getQuantity();
            List<String> ids = new ArrayList<>(new TreeSet<>(report.getDropIds()));
            for (String id : ids) {
                SupplyReport balance = remainingDrops.get(id);
                if (balance == null
                        || balance.getItemId() != report.getItemId()
                        || !balance.getTile().equals(report.getTile())) continue;
                int take = Math.min(remaining, balance.getQuantity());
                balance.setQuantity(balance.getQuantity() - take);
                remaining -= take;
            }
        } else if (report.isDrop()) remainingDrops.put(report.getId(), report.copy());
        acceptedEventIndexes.put(report.getId(), history.size());
        history.add(report.copy());
        String confidence = report.isDrop() || !report.getDropIds().isEmpty()
                ? "CONFIRMED"
                : "UNRESOLVED";
        eventLog.add(new SupplyEventRecord(history.size(), report, confidence, System.currentTimeMillis()));
        generation++;
        return true;
    }

    private boolean isValidHistory(List<SupplyReport> all) {
        SharedSupplyState check = new SharedSupplyState(1, 1, scope, message -> {});
        for (SupplyReport report : all)
            if (report == null
                    || !check.hasReferencedDrops(report)
                    || !check.appendAcceptedReport(report)) return false;
        return true;
    }

    // Validate the entire snapshot before replacing anything visible to the rest of the plugin.
    private boolean installSnapshot(List<SupplyReport> all) {
        if (!isValidHistory(all)) return false;
        history.clear();
        eventLog.clear();
        acceptedEventIndexes.clear();
        remainingDrops.clear();
        for (SupplyReport report : all) appendAcceptedReport(report);
        pending.keySet().removeAll(acceptedEventIndexes.keySet());
        generation++;
        return true;
    }
}
