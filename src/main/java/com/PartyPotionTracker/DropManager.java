package com.PartyPotionTracker;

import net.runelite.api.*;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.ItemSpawned;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.client.party.PartyService;

public class DropManager {
    private static final int EXPECTED_DROP_TIMEOUT_TICKS = 3;

    private Client client;
    private PartyService partyService;
    private ExpectedDrop expectedDrop;

    private static class ExpectedDrop
    {
        private final int itemId;
        private final int createdTick;

        private ExpectedDrop(int itemId, int createdTick)
        {
            this.itemId = itemId;
            this.createdTick = createdTick;
        }
    }

    public DropManager(Client client, PartyService partyService) {
        this.client = client;
        this.partyService = partyService;
    }


    public TrackedItem itemSpawned(ItemSpawned event) {

        TileItem item = event.getItem();
        int itemId = item.getId();

        if (!RaidPotions.isPotion(itemId)) {
            return null;
        }

        if (client.getLocalPlayer() == null) {
            return null;
        }

        boolean ownedByLocalPlayer = item.getOwnership() == TileItem.OWNERSHIP_SELF;
        boolean expectedLocalDrop = expectedDrop != null
                && expectedDrop.itemId == itemId
                && client.getTickCount() - expectedDrop.createdTick <= EXPECTED_DROP_TIMEOUT_TICKS;

        if (!ownedByLocalPlayer && !expectedLocalDrop) {
            return null;
        }

        WorldPoint spawnLocation = event.getTile().getWorldLocation();
        if (spawnLocation == null) {
            return null;
        }

        if (expectedLocalDrop) {
            expectedDrop = null;
        }

        String ownerName = client.getLocalPlayer().getName();
        TrackedItem trackedItem = new TrackedItem(itemId, spawnLocation, ownerName);

        if (partyService != null && partyService.isInParty()) {
            partyService.send(new TrackedItemMessage(itemId, spawnLocation, ownerName));
        }

        return trackedItem;
    }

    public void menuOptionClicked(MenuOptionClicked event)
    {
        if (event == null || client.getLocalPlayer() == null)
        {
            return;
        }

        String option = event.getMenuOption();
        if (option == null || !option.equalsIgnoreCase("Drop"))
        {
            expectedDrop = null;
            return;
        }

        int itemId = event.getId();
        if (RaidPotions.isPotion(itemId))
        {
            expectedDrop = new ExpectedDrop(itemId, client.getTickCount());
        }
        else
        {
            expectedDrop = null;
        }
    }

    public void gameTick()
    {
        if (expectedDrop != null
                && client.getTickCount() - expectedDrop.createdTick > EXPECTED_DROP_TIMEOUT_TICKS)
        {
            expectedDrop = null;
        }
    }

    public boolean isExpectedLocalDrop(int itemId)
    {
        return expectedDrop != null
                && expectedDrop.itemId == itemId
                && client.getTickCount() - expectedDrop.createdTick <= EXPECTED_DROP_TIMEOUT_TICKS;
    }

    public void clear()
    {
        expectedDrop = null;
    }
}
