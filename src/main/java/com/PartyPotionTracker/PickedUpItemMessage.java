package com.PartyPotionTracker;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.party.messages.PartyMemberMessage;

@EqualsAndHashCode(callSuper = false)
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PickedUpItemMessage extends PartyMemberMessage {
    private int itemId;
    private WorldPoint worldPoint;
    private String pickerName;
}
