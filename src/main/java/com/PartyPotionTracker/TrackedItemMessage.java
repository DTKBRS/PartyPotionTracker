package com.PartyPotionTracker;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.party.messages.PartyMemberMessage;

@Getter
@Setter
@NoArgsConstructor
public class TrackedItemMessage extends PartyMemberMessage
{
    private int itemId;
    private WorldPoint worldPoint;
    private String ownerName;

    public TrackedItemMessage(int itemId, WorldPoint worldPoint, String ownerName)
    {
        this.itemId = itemId;
        this.worldPoint = worldPoint;
        this.ownerName = ownerName;
    }
}