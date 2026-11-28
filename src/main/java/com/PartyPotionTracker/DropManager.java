package com.PartyPotionTracker;

import net.runelite.api.*;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.ItemSpawned;
import net.runelite.client.party.PartyService;

public class DropManager {
    private Client client;
    private PartyService partyService;

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

        if (item.getOwnership() != TileItem.OWNERSHIP_SELF
                || client.getLocalPlayer() == null) {
            return null;
        }

        WorldPoint spawnLocation = event.getTile().getWorldLocation();
        if (spawnLocation == null) {
            return null;
        }

        String ownerName = client.getLocalPlayer().getName();
        TrackedItem trackedItem = new TrackedItem(itemId, spawnLocation, ownerName);

        if (partyService != null && partyService.isInParty()) {
            partyService.send(new TrackedItemMessage(itemId, spawnLocation, ownerName));
        }

        return trackedItem;
    }
}
