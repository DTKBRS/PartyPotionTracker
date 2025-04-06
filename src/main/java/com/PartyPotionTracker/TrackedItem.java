package com.PartyPotionTracker;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import net.runelite.api.coords.WorldPoint;

@Getter
@EqualsAndHashCode
public class TrackedItem
{
    private final int itemId;
    private final WorldPoint worldPoint;
    private final String ownerName;

    public TrackedItem(int itemId, WorldPoint worldPoint, String ownerName){
        this.itemId = itemId;
        this.worldPoint = worldPoint;
        this.ownerName = ownerName;
    }

}