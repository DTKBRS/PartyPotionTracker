package com.PartyPotionTracker;

import java.time.Instant;

/** Immutable local audit entry for an accepted supply event. */
final class SupplyEventRecord {
    final long revision;
    final String eventId;
    final String actor;
    final int itemId;
    final String itemName;
    final int quantity;
    final boolean drop;
    final String confidence;
    final long timestamp;

    SupplyEventRecord(long revision, SupplyReport report, String confidence, long timestamp) {
        this(
                revision,
                report.getId(),
                report.getActor(),
                report.getItemId(),
                report.getQuantity(),
                report.isDrop(),
                confidence,
                timestamp,
                null);
    }

    SupplyEventRecord withItemName(String name) {
        return new SupplyEventRecord(
                revision, eventId, actor, itemId, quantity, drop, confidence, timestamp, name);
    }

    private SupplyEventRecord(
            long revision,
            String eventId,
            String actor,
            int itemId,
            int quantity,
            boolean drop,
            String confidence,
            long timestamp,
            String itemName) {
        this.revision = revision;
        this.eventId = eventId;
        this.actor = actor;
        this.itemId = itemId;
        this.itemName = itemName;
        this.quantity = quantity;
        this.drop = drop;
        this.confidence = confidence;
        this.timestamp = timestamp;
    }

    String time() { return Instant.ofEpochMilli(timestamp).toString(); }
}
