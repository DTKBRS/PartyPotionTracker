package com.PartyPotionTracker;

import java.util.*;

/** Receives complete per-player snapshots. Age uses the receiver's monotonic clock. */
final class SupplyInventoryLedger {
    static final long STALE_AFTER_NANOS = 15_000_000_000L;

    static final class Entry {
        final SupplyInventory inventory;
        final long receivedAt;
        final long sequence;

        Entry(SupplyInventory inventory, long receivedAt, long sequence) {
            this.inventory = inventory;
            this.receivedAt = receivedAt;
            this.sequence = sequence;
        }

        boolean fresh(long now) {
            return now - receivedAt < STALE_AFTER_NANOS;
        }
    }

    private final Map<Long, Entry> entries = new HashMap<>();

    void update(long memberId, SupplyInventory inventory, long sequence, long now) {
        Entry previous = entries.get(memberId);
        // Replayed messages must not refresh an old inventory's age.
        if (previous == null || sequence > previous.sequence) {
            entries.put(memberId, new Entry(inventory, now, sequence));
        }
    }

    Entry get(long memberId) {
        return entries.get(memberId);
    }

    void retainMembers(Set<Long> members) {
        entries.keySet().retainAll(members);
    }

    void clear() {
        entries.clear();
    }
}
