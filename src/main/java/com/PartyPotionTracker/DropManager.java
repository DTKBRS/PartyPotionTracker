package com.PartyPotionTracker;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.ItemSpawned;
import net.runelite.client.party.PartyService;

@Slf4j
@RequiredArgsConstructor
public class DropManager {

    private static class PendingDropItem {
        int itemId;
        WorldPoint location;
        public PendingDropItem(int itemId, WorldPoint location) {
            this.itemId = itemId;
            this.location = location;
        }
    }

    private Client client;
    private PartyService partyService;

    public DropManager(Client client, PartyService partyService) {
        this.client = client;
        this.partyService = partyService;
    }


    public void itemSpawned(ItemSpawned event) {

        TileItem item = event.getItem();
        int itemId = item.getId();

        if (!ToaPotions.isPotion(itemId)) {
            return;
        }

        int isMyPotion = item.getOwnership();

        if (isMyPotion == 1) {
            WorldPoint spawnLocation = event.getTile().getWorldLocation();
            if (partyService != null) {
                if (partyService.isInParty()) {
                    partyService.send(new TrackedItemMessage(itemId, spawnLocation, client.getLocalPlayer().getName()));
                }
            }
        }
    }
}
