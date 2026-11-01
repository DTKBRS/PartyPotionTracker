package com.PartyPotionTracker;

import java.util.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import net.runelite.api.coords.WorldPoint;

/** An immutable-by-convention report copied at the protocol boundary. */
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
        SupplyReport r = new SupplyReport();
        r.id = event.getEventId();
        r.quantity = event.getQuantity();
        if (event instanceof TrackedItemMessage) {
            TrackedItemMessage d = (TrackedItemMessage) event;
            r.drop = true; r.actor = d.getOwnerName(); r.itemId = d.getItemId(); r.tile = d.getWorldPoint();
        } else {
            PickedUpItemMessage take = (PickedUpItemMessage) event;
            r.actor = take.getPickerName(); r.itemId = take.getItemId(); r.tile = take.getWorldPoint();
            r.dropIds = take.getDropIds() == null ? new ArrayList<>() : new ArrayList<>(take.getDropIds());
        }
        return r;
    }

    SupplyReport copy() {
        SupplyReport r = new SupplyReport();
        r.id = id; r.drop = drop; r.actor = actor; r.itemId = itemId; r.tile = tile; r.quantity = quantity;
        r.dropIds = dropIds == null ? null : new ArrayList<>(dropIds);
        return r;
    }

    boolean valid() {
        return id != null && id.length() <= 100 && !id.isEmpty() && actor != null && !actor.isEmpty()
                && actor.length() <= 50 && itemId >= 0 && tile != null && tile.getPlane() >= 0 && tile.getPlane() <= 3
                && quantity > 0 && quantity <= 28 && dropIds != null && dropIds.size() <= 100
                && dropIds.stream().allMatch(x -> x != null && !x.isEmpty() && x.length() <= 100)
                && (drop || !dropIds.isEmpty());
    }
}
