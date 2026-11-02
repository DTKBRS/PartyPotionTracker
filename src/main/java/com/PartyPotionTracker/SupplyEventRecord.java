package com.PartyPotionTracker;

import java.time.Instant;

/** Immutable local audit entry for an accepted supply event. */
final class SupplyEventRecord {
    final long revision;
    final String eventId;
    final String actor;
    final int itemId;
    final int quantity;
    final boolean drop;
    final String confidence;
    final long timestamp;

    SupplyEventRecord(long revision, SupplyReport report, String confidence, long timestamp) {
        this.revision = revision;
        this.eventId = report.getId();
        this.actor = report.getActor();
        this.itemId = report.getItemId();
        this.quantity = report.getQuantity();
        this.drop = report.isDrop();
        this.confidence = confidence;
        this.timestamp = timestamp;
    }

    String time() { return Instant.ofEpochMilli(timestamp).toString(); }
}
