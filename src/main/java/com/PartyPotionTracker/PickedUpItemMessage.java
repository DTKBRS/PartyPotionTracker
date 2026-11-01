package com.PartyPotionTracker;

import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import net.runelite.api.coords.WorldPoint;

@Getter
@Setter
@NoArgsConstructor
public class PickedUpItemMessage extends SupplyEventMessage {
    private int itemId;
    private WorldPoint worldPoint;
    private String pickerName;
    private java.util.List<String> dropIds;

    public PickedUpItemMessage(int itemId, WorldPoint worldPoint, String name) {
        this.itemId = itemId;
        this.worldPoint = worldPoint;
        this.pickerName = name;
        setEventId(java.util.UUID.randomUUID().toString());
        setQuantity(1);
    }
}
