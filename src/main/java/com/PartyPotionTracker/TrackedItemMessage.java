package com.PartyPotionTracker;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import net.runelite.api.coords.WorldPoint;

@Getter
@Setter
@NoArgsConstructor
public class TrackedItemMessage extends SupplyEventMessage {
    private int itemId;
    private WorldPoint worldPoint;
    private String ownerName;

    public TrackedItemMessage(int itemId, WorldPoint worldPoint, String name) {
        this.itemId = itemId;
        this.worldPoint = worldPoint;
        this.ownerName = name;
        setEventId(java.util.UUID.randomUUID().toString());
        setQuantity(1);
    }
}
