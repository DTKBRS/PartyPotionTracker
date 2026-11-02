package com.PartyPotionTracker;

import java.util.*;

/** A single consistent view shared by the sidebar and overlay. */
final class SupplyInventoryView {
    static final class Row {
        final String name;
        final String status;
        final SupplyInventory inventory;

        Row(String name, String status, SupplyInventory inventory) {
            this.name = name;
            this.status = status;
            this.inventory = inventory;
        }
    }

    final String status;
    final List<Row> rows;
    final Map<String, Integer> totals;
    final int reportingMembers;

    SupplyInventoryView(String status, List<Row> rows) {
        this.status = status;
        this.rows = Collections.unmodifiableList(new ArrayList<>(rows));
        Map<String, Integer> totals = new TreeMap<>();
        int reporting = 0;
        for (Row row : rows) {
            if (!"Current".equals(row.status) || row.inventory == null) continue;
            reporting++;
            row.inventory.doses.forEach((name, count) -> totals.merge(name, count, Integer::sum));
        }
        this.reportingMembers = reporting;
        this.totals = Collections.unmodifiableMap(totals);
    }

    String total(String potion) {
        return reportingMembers == 0 ? "—" : Integer.toString(totals.getOrDefault(potion, 0));
    }

    static SupplyInventoryView inactive(String status) {
        return new SupplyInventoryView(status, Collections.emptyList());
    }
}
