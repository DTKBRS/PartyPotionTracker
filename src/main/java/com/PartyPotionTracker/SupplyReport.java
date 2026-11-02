package com.PartyPotionTracker;

import lombok.Data;
import lombok.NoArgsConstructor;

import net.runelite.api.coords.WorldPoint;

import java.util.*;

/**
 * A drop or pickup reported by one player. Copies keep incoming messages separate from stored
 * history.
 */
@Data
@NoArgsConstructor
public class SupplyReport {
    private String id;
    private boolean drop;
    private String actor;
    private int itemId;
    private WorldPoint tile;
    private int quantity;
    private List<String> dropIds = new ArrayList<>();

    static SupplyReport from(SupplyEventMessage event) {
        SupplyReport report = new SupplyReport();
        report.id = event.getEventId();
        report.quantity = event.getQuantity();
        if (event instanceof TrackedItemMessage) {
            TrackedItemMessage dropEvent = (TrackedItemMessage) event;
            report.drop = true;
            report.actor = dropEvent.getOwnerName();
            report.itemId = dropEvent.getItemId();
            report.tile = dropEvent.getWorldPoint();
        } else {
            PickedUpItemMessage take = (PickedUpItemMessage) event;
            report.actor = take.getPickerName();
            report.itemId = take.getItemId();
            report.tile = take.getWorldPoint();
            report.dropIds =
                    take.getDropIds() == null
                            ? new ArrayList<>()
                            : new ArrayList<>(take.getDropIds());
        }
        return report;
    }

    SupplyReport copy() {
        SupplyReport report = new SupplyReport();
        report.id = id;
        report.drop = drop;
        report.actor = actor;
        report.itemId = itemId;
        report.tile = tile;
        report.quantity = quantity;
        report.dropIds = dropIds == null ? null : new ArrayList<>(dropIds);
        return report;
    }

    boolean valid() {
        return id != null
                && id.length() <= 100
                && !id.isEmpty()
                && actor != null
                && !actor.isEmpty()
                && actor.length() <= 50
                && itemId >= 0
                && tile != null
                && tile.getPlane() >= 0
                && tile.getPlane() <= 3
                && quantity > 0
                && quantity <= 28
                && dropIds != null
                && dropIds.size() <= 100
                && dropIds.stream().allMatch(x -> x != null && !x.isEmpty() && x.length() <= 100)
                && (drop || !dropIds.isEmpty());
    }
}
