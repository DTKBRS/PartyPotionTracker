package com.PartyPotionTracker;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.ItemSpawned;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.client.party.PartyService;
import java.awt.*;
import java.util.*;
import java.util.List;

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



    public final List<DropManager.PendingDropItem> pendingDrops = new ArrayList<>();

    private final Color[] colorPool = {
            Color.CYAN, Color.MAGENTA, Color.ORANGE, Color.GREEN, Color.PINK, Color.YELLOW, Color.RED, Color.BLUE
    };

    public void menuOptionClicked(MenuOptionClicked event){
        if (event.getMenuOption().equalsIgnoreCase("Drop")) {
            int itemId = event.getMenuEntry().getItemId();
            if (!ToaPotions.isPotion(itemId)) {
                return;
            }
            WorldPoint location = client.getLocalPlayer().getWorldLocation();
            pendingDrops.add(new DropManager.PendingDropItem(itemId, location));
        }
    }

    public void itemSpawned(ItemSpawned event) {
        TileItem item = event.getItem();
        int itemId = item.getId();
        if (!ToaPotions.isPotion(itemId)) {
            return;
        }
        WorldPoint spawnLocation = event.getTile().getWorldLocation();
        DropManager.PendingDropItem matchedDrop = null;
        Iterator<DropManager.PendingDropItem> iter = pendingDrops.iterator();

        while (iter.hasNext()) {
            DropManager.PendingDropItem drop = iter.next();

            if (drop.itemId == itemId &&
                    drop.location.distanceTo(spawnLocation) <= 2)
            {
                matchedDrop = drop;
                iter.remove(); // Clean it up
                break;
            }
        }

        if (matchedDrop != null) {
            // Optionally: send message to party
            if (partyService != null) {
                partyService.send(new TrackedItemMessage(itemId, spawnLocation, client.getLocalPlayer().getName()));
            }
        }
    }
}
