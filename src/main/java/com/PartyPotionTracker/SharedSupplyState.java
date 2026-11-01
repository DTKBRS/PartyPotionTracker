package com.PartyPotionTracker;

import java.util.*;
import java.util.function.Consumer;

/** Single-writer replicated event history. No RuneLite/client thread dependencies. */
final class SharedSupplyState {
    static final int MAX_EVENTS = 500;
    static final int CHUNK = 10;
    static final int TIMEOUT = 20;
    private final long self;
    private final String scope;
    private final Consumer<SupplyStateMessage> transport;
    private final List<SupplyReport> history = new ArrayList<>();
    private final Map<String, SupplyReport> pending = new LinkedHashMap<>();
    private final Map<String, Deferred> deferred = new LinkedHashMap<>();
    private final Map<String, Integer> accepted = new HashMap<>();
    private final Map<String, SupplyReport> balances = new LinkedHashMap<>();
    private long leader;
    private String term;
    private int tick;
    private int lastContact = -1000;
    private int lastRequest = -1000;
    private boolean ready;
    private boolean frozen;
    private String request;
    private Assembly assembly;
    private Assembly transfer;
    private long transferTarget;
    private String transferId;
    private String nextTerm;
    private SupplyStateMessage redirect;
    private String problem = "";
    private long generation;
    private long advertisedRevision;
    private boolean transportConnected = true;
    private final Deque<SupplyStateMessage> snapshotOutbox = new ArrayDeque<>();

    private static final class Deferred {
        final long sender;
        final SupplyReport report;
        final int tick;
        Deferred(long sender, SupplyReport report, int tick) { this.sender = sender; this.report = report; this.tick = tick; }
    }
    private static final class Assembly {
        final String id;
        final String term;
        final long revision;
        final int count;
        final Map<Integer, List<SupplyReport>> chunks = new HashMap<>();
        Assembly(SupplyStateMessage m) { id = m.request; term = m.term; revision = m.revision; count = m.parts; }
        boolean add(SupplyStateMessage m) {
            if (!Objects.equals(id, m.request) || !Objects.equals(term, m.term) || revision != m.revision || count != m.parts
                    || m.part < 0 || m.part >= count || m.history == null || m.history.size() > CHUNK) return false;
            List<SupplyReport> copy = new ArrayList<>();
            for (SupplyReport r : m.history) { if (r == null || !r.valid()) return false; copy.add(r.copy()); }
            chunks.putIfAbsent(m.part, copy);
            return true;
        }
        List<SupplyReport> complete() {
            if (chunks.size() != count) return null;
            List<SupplyReport> result = new ArrayList<>();
            for (int i = 0; i < count; i++) result.addAll(chunks.get(i));
            return result.size() == revision ? result : null;
        }
    }

    SharedSupplyState(long self, long leader, String scope, Consumer<SupplyStateMessage> transport) {
        this.self = self; this.leader = leader; this.scope = scope; this.transport = transport;
        if (self == leader) { term = UUID.randomUUID().toString(); ready = true; lastContact = 0; }
    }

    long leader() { return leader; }
    String term() { return term; }
    long revision() { return history.size(); }
    long generation() { return generation; }
    int pendingCount() { return pending.size(); }
    boolean synchronizedState() { return transportConnected && ready && !frozen && (leader == self || tick - lastContact <= TIMEOUT); }
    List<SupplyReport> history() {
        List<SupplyReport> result = new ArrayList<>();
        history.forEach(r -> result.add(r.copy()));
        return result;
    }
    List<String> pendingDropIds(int item, net.runelite.api.coords.WorldPoint tile) {
        List<String> result = new ArrayList<>();
        pending.values().stream().filter(r -> r.isDrop() && r.getItemId() == item && r.getTile().equals(tile))
                .forEach(r -> result.add(r.getId()));
        return result;
    }
    String status() {
        if (!problem.isEmpty()) return problem;
        if (!transportConnected) return "Paused: party connection unavailable";
        if (frozen) return "Paused: coordinator handoff";
        if (!synchronizedState()) return ready ? "Paused: coordinator unavailable" : "Syncing with coordinator";
        return (self == leader ? "Coordinator" : "Synchronized") + " · revision " + revision() + " · pending " + pending.size();
    }
    private SupplyStateMessage message(String kind, long target) {
        SupplyStateMessage m = new SupplyStateMessage();
        m.scope = scope; m.kind = kind; m.target = target; m.term = term; m.revision = revision();
        return m;
    }
    private void send(SupplyStateMessage m) { transport.accept(m); }

    void submit(SupplyReport report) {
        if (report == null || !report.valid() || pending.size() >= 100) { problem = "Report rejected: invalid or pending limit"; return; }
        if (accepted.containsKey(report.getId())) return;
        pending.putIfAbsent(report.getId(), report.copy());
        if (synchronizedState()) sendReport(report);
    }
    private void sendReport(SupplyReport report) {
        if (self == leader) acceptReport(self, report);
        else { SupplyStateMessage m = message("REPORT", leader); m.report = report.copy(); send(m); }
    }
    void tick() {
        tick++;
        for (int i = 0; i < 2 && !snapshotOutbox.isEmpty(); i++) send(snapshotOutbox.removeFirst());
        if (self == leader && tick % 5 == 0) send(message(frozen ? "FROZEN" : "HEARTBEAT", 0));
        if (transferTarget != 0 && self == leader && tick % 10 == 0) sendSnapshot("TRANSFER", transferTarget, transferId);
        if (self != leader && !synchronizedState()) requestSnapshot();
        if (synchronizedState() && tick % 5 == 0) new ArrayList<>(pending.values()).forEach(this::sendReport);
        if (self == leader && !frozen) {
            for (Deferred d : new ArrayList<>(deferred.values())) {
                if (dependenciesKnown(d.report)) { deferred.remove(d.report.getId()); acceptReport(d.sender, d.report); }
                else if (tick - d.tick > TIMEOUT) { deferred.remove(d.report.getId()); reject(d.sender, d.report); }
            }
        }
    }
    void disconnected() { lastContact = tick - TIMEOUT - 1; }
    void transportFailed() { transportConnected = false; }
    void transportRestored() { transportConnected = true; }
    private void requestSnapshot() {
        if (tick - lastRequest < 20 || (request != null && tick - lastRequest < 60)) return;
        lastRequest = tick; request = UUID.randomUUID().toString(); assembly = null;
        SupplyStateMessage m = message("REQUEST", leader); m.request = request; send(m);
    }
    void handoff(long target) {
        if (self != leader || !synchronizedState() || target == 0 || target == self) return;
        frozen = true; transferTarget = target; transferId = UUID.randomUUID().toString(); nextTerm = UUID.randomUUID().toString();
        send(message("FROZEN", 0));
        sendSnapshot("TRANSFER", target, transferId);
    }
    void cancelHandoff() {
        if (self != leader || transferTarget == 0) return;
        snapshotOutbox.removeIf(m -> "TRANSFER".equals(m.kind));
        transferTarget = 0; transferId = null; nextTerm = null; frozen = false;
        send(message("HEARTBEAT", 0));
    }
    private void sendSnapshot(String kind, long target, String id) {
        if (snapshotOutbox.stream().anyMatch(m -> kind.equals(m.kind) && m.target == target && Objects.equals(m.request, id))) return;
        snapshotOutbox.removeIf(m -> kind.equals(m.kind) && m.target == target);
        int count = Math.max(1, (history.size() + CHUNK - 1) / CHUNK);
        if (snapshotOutbox.size() + count > 400) return;
        for (int i = 0; i < count; i++) {
            SupplyStateMessage m = message(kind, target);
            m.request = id; m.part = i; m.parts = count; m.nextTerm = nextTerm; m.paused = frozen;
            m.history = new ArrayList<>();
            for (int j = i * CHUNK; j < Math.min(history.size(), (i + 1) * CHUNK); j++) m.history.add(history.get(j).copy());
            snapshotOutbox.addLast(m);
        }
    }
    void receive(long sender, String senderName, SupplyStateMessage m) {
        if (m == null || m.protocol != 3 || !scope.equals(m.scope) || m.kind == null
                || m.revision < 0 || m.revision > MAX_EVENTS || (m.target != 0 && m.target != self)) return;
        if ("REQUEST".equals(m.kind)) {
            if (m.request == null || m.request.length() > 100) return;
            if (self == leader) sendSnapshot("SNAPSHOT", sender, m.request);
            else if (redirect != null) send(redirect);
            return;
        }
        if ("REPORT".equals(m.kind)) {
            if (self == leader && synchronizedState() && Objects.equals(term, m.term) && m.report != null
                    && m.report.valid() && m.report.getActor().equalsIgnoreCase(senderName)) acceptReport(sender, m.report);
            return;
        }
        if ("TRANSFER_ACK".equals(m.kind)) {
            if (self == leader && frozen && sender == transferTarget && Objects.equals(m.request, transferId)
                    && Objects.equals(term, m.term) && m.revision == revision()) {
                SupplyStateMessage commit = message("COMMIT", 0);
                commit.successor = transferTarget; commit.nextTerm = nextTerm; commit.request = transferId;
                redirect = commit;
                snapshotOutbox.clear();
                leader = transferTarget; term = nextTerm; transferTarget = 0; frozen = false; ready = false;
                send(commit);
            }
            return;
        }
        if (sender != leader) return;
        if ("COMMIT".equals(m.kind)) {
            if ((term != null && !Objects.equals(term, m.term)) || m.term == null || m.nextTerm == null || m.successor == 0 || m.successor == leader) return;
            if (m.successor == self) {
                if (transfer == null || !Objects.equals(transfer.id, m.request) || transfer.revision != m.revision
                        || transfer.complete() == null || !install(transfer.complete())) return;
                leader = self; term = m.nextTerm; ready = true; frozen = false; transfer = null; request = null;
                lastContact = tick; send(message("HEARTBEAT", 0));
            } else {
                leader = m.successor; term = m.nextTerm; ready = false; frozen = false; request = null;
                lastRequest = tick - 20; requestSnapshot();
            }
            return;
        }
        if (term != null && !Objects.equals(term, m.term)) {
            problem = "Paused: coordinator restarted; use a new session code"; ready = false; return;
        }
        if (m.term == null || m.term.length() > 100) return;
        if ("HEARTBEAT".equals(m.kind) || "FROZEN".equals(m.kind)) {
            if (term == null) term = m.term;
            advertisedRevision = Math.max(advertisedRevision, m.revision);
            lastContact = tick; frozen = "FROZEN".equals(m.kind);
            if (m.revision != revision() || !ready) { ready = false; requestSnapshot(); }
            return;
        }
        if ("TRANSFER".equals(m.kind)) {
            if (!validChunk(m) || m.nextTerm == null) return;
            if (term == null) term = m.term;
            if (transfer == null || !Objects.equals(transfer.id, m.request)) transfer = new Assembly(m);
            if (!transfer.add(m)) return;
            if (transfer.complete() != null && validHistory(transfer.complete())) {
                frozen = true;
                SupplyStateMessage ack = message("TRANSFER_ACK", leader);
                ack.request = m.request; ack.revision = m.revision; send(ack);
            }
            return;
        }
        if ("SNAPSHOT".equals(m.kind)) {
            if (request == null || !request.equals(m.request) || !validChunk(m) || m.revision < revision()) return;
            if (assembly == null) assembly = new Assembly(m);
            if (!assembly.add(m)) return;
            List<SupplyReport> all = assembly.complete();
            if (all != null && install(all)) {
                term = m.term; ready = revision() >= advertisedRevision; frozen = m.paused;
                lastContact = tick; request = null; assembly = null;
                problem = "";
            }
            return;
        }
        if (!Objects.equals(term, m.term)) return;
        if ("REJECT".equals(m.kind)) {
            if (m.report != null && pending.remove(m.report.getId()) != null) problem = "Report could not be reconciled";
            return;
        }
        if ("UPDATE".equals(m.kind) && m.report != null && m.report.valid()) {
            lastContact = tick;
            advertisedRevision = Math.max(advertisedRevision, m.revision);
            if (m.revision <= revision()) { if (accepted.containsKey(m.report.getId())) pending.remove(m.report.getId()); return; }
            if (m.revision != revision() + 1 || !ready || !append(m.report)) { ready = false; requestSnapshot(); return; }
            pending.remove(m.report.getId());
        }
    }
    private boolean validChunk(SupplyStateMessage m) {
        return m.request != null && m.request.length() <= 100 && m.revision >= 0 && m.revision <= MAX_EVENTS
                && m.parts == Math.max(1, ((int) m.revision + CHUNK - 1) / CHUNK);
    }
    private boolean dependenciesKnown(SupplyReport r) {
        return r.isDrop() || accepted.keySet().containsAll(r.getDropIds());
    }
    private void acceptReport(long sender, SupplyReport r) {
        Integer index = accepted.get(r.getId());
        if (index != null) {
            pending.remove(r.getId()); SupplyStateMessage m = message("UPDATE", sender);
            m.report = history.get(index).copy(); m.revision = index + 1; if (sender != self) send(m); return;
        }
        if (!dependenciesKnown(r)) {
            if (deferred.size() < 100) deferred.putIfAbsent(r.getId(), new Deferred(sender, r.copy(), tick));
            else reject(sender, r);
            return;
        }
        if (history.size() >= MAX_EVENTS) { problem = "Paused: session history limit reached"; frozen = true; return; }
        if (!append(r)) { reject(sender, r); return; }
        pending.remove(r.getId()); SupplyStateMessage m = message("UPDATE", 0); m.report = r.copy(); send(m);
    }
    private void reject(long sender, SupplyReport r) {
        if (sender == self) { pending.remove(r.getId()); problem = "Report could not be reconciled"; }
        else { SupplyStateMessage m = message("REJECT", sender); m.report = r.copy(); send(m); }
    }
    private boolean append(SupplyReport r) {
        if (!r.valid() || accepted.containsKey(r.getId()) || history.size() >= MAX_EVENTS) return false;
        if (!r.isDrop()) {
            int total = 0;
            for (String id : new HashSet<>(r.getDropIds())) {
                SupplyReport balance = balances.get(id);
                if (balance != null && balance.getItemId() == r.getItemId() && balance.getTile().equals(r.getTile())) total += balance.getQuantity();
            }
            if (total < r.getQuantity()) return false;
            int remaining = r.getQuantity();
            List<String> ids = new ArrayList<>(new TreeSet<>(r.getDropIds()));
            for (String id : ids) {
                SupplyReport balance = balances.get(id);
                if (balance == null || balance.getItemId() != r.getItemId() || !balance.getTile().equals(r.getTile())) continue;
                int take = Math.min(remaining, balance.getQuantity()); balance.setQuantity(balance.getQuantity() - take); remaining -= take;
            }
        } else balances.put(r.getId(), r.copy());
        accepted.put(r.getId(), history.size()); history.add(r.copy()); generation++;
        return true;
    }
    private boolean validHistory(List<SupplyReport> all) {
        SharedSupplyState check = new SharedSupplyState(1, 1, scope, m -> {});
        for (SupplyReport r : all) if (r == null || !check.dependenciesKnown(r) || !check.append(r)) return false;
        return true;
    }
    private boolean install(List<SupplyReport> all) {
        if (!validHistory(all)) return false;
        history.clear(); accepted.clear(); balances.clear();
        for (SupplyReport r : all) append(r);
        pending.keySet().removeAll(accepted.keySet()); generation++;
        return true;
    }
}
