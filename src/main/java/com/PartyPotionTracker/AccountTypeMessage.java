package com.PartyPotionTracker;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import net.runelite.client.party.messages.PartyMemberMessage;

/**
 * Publishes the sender's local account type to other Party Potion Tracker
 * clients in the same RuneLite party.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = false)
public class AccountTypeMessage extends PartyMemberMessage
{
    private int accountTypeId;
}
