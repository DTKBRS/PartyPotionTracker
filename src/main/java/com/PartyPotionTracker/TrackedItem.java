package com.PartyPotionTracker;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import net.runelite.api.coords.WorldPoint;

@Getter
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class TrackedItem {
    @EqualsAndHashCode.Include
    private final String dropId;
    private final int itemId;
    private final WorldPoint worldPoint;
    private final String ownerName;
    private int quantity;
    private boolean ambiguous;

    public TrackedItem(int itemId, WorldPoint point, String owner) {
        this(java.util.UUID.randomUUID().toString(), itemId, point, owner, 1, false);
    }

    public TrackedItem(String dropId, int itemId, WorldPoint point, String owner, int quantity, boolean ambiguous) {
        this.dropId = dropId;
        this.itemId = itemId;
        this.worldPoint = point;
        this.ownerName = owner;
        this.quantity = quantity;
        this.ambiguous = ambiguous;
    }

    void markAmbiguous() { ambiguous = true; }

    void consume(int amount) {
        quantity = Math.max(0, quantity - amount);
    }
}
