package com.PartyPotionTracker;

import net.runelite.api.*;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.ItemSpawned;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.party.PartyService;

@Slf4j
public class DropManager {
    private static final boolean DEBUG_LOGGING = Boolean.getBoolean("ppt.debug");

    private Client client;
    private PartyService partyService;

    public DropManager(Client client, PartyService partyService) {
        this.client = client;
        this.partyService = partyService;
    }


    public TrackedItem itemSpawned(ItemSpawned event) {

        TileItem item = event.getItem();
        int itemId = item.getId();

        if (DEBUG_LOGGING)
        {
            log.info("[PPT DEBUG] item spawned: id={}, quantity={}, ownership={}, tile={}, recognizedPotion={}, localPlayer={}",
                    itemId,
                    item.getQuantity(),
                    item.getOwnership(),
                    event.getTile() == null ? null : event.getTile().getWorldLocation(),
                    RaidPotions.isPotion(itemId),
                    client.getLocalPlayer() == null ? null : client.getLocalPlayer().getName());
        }

        if (!RaidPotions.isPotion(itemId)) {
            debug("item spawn ignored: item is not in RaidPotions (id={})", itemId);
            return null;
        }

        if (item.getOwnership() != TileItem.OWNERSHIP_SELF
                || client.getLocalPlayer() == null) {
            debug("item spawn ignored: ownership/local player check failed (id={}, ownership={}, localPlayerPresent={})",
                    itemId, item.getOwnership(), client.getLocalPlayer() != null);
            return null;
        }

        WorldPoint spawnLocation = event.getTile().getWorldLocation();
        if (spawnLocation == null) {
            debug("item spawn ignored: tile has no world location (id={})", itemId);
            return null;
        }

        String ownerName = client.getLocalPlayer().getName();
        TrackedItem trackedItem = new TrackedItem(itemId, spawnLocation, ownerName);

        if (partyService != null && partyService.isInParty()) {
            partyService.send(new TrackedItemMessage(itemId, spawnLocation, ownerName));
        }

        return trackedItem;
    }

    private void debug(String message, Object... arguments)
    {
        if (DEBUG_LOGGING)
        {
            log.info("[PPT DEBUG] " + message, arguments);
        }
    }
}
