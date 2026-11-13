package com.PartyPotionTracker;

import lombok.Getter;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.InventoryID;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.Tile;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.party.PartyService;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

public class PickupManager
{
    private static final int EXPECTED_PICKUP_TIMEOUT_TICKS = 5;

    static class ExpectedPickup
    {
        final int itemId;
        final WorldPoint location;
        final String playerName;
        final int createdTick;

        ExpectedPickup(int itemId, WorldPoint location, String playerName, int createdTick)
        {
            this.itemId = itemId;
            this.location = location;
            this.playerName = playerName;
            this.createdTick = createdTick;
        }
    }

    static class PendingDespawn
    {
        final int itemId;
        final WorldPoint location;

        PendingDespawn(int itemId, WorldPoint location)
        {
            this.itemId = itemId;
            this.location = location;
        }
    }

    private final Client client;
    private final PartyService partyService;
    private final ClientThread clientThread;

    private ExpectedPickup expectedPickup;

    /**
     * Owner metadata for potion ground items. The overlay only draws a record
     * when the corresponding ground item is currently present in the scene.
     */
    @Getter
    private final List<TrackedItem> myDroppedItems = new CopyOnWriteArrayList<>();

    private final List<PendingDespawn> pendingDespawns = new CopyOnWriteArrayList<>();

    private Map<Integer, Integer> previousInventory;

    public PickupManager(Client client, PartyService partyService, ClientThread clientThread)
    {
        this.client = client;
        this.partyService = partyService;
        this.clientThread = clientThread;
    }

    public void clear()
    {
        myDroppedItems.clear();
        pendingDespawns.clear();
        previousInventory = null;
        expectedPickup = null;
    }

    public void gameTick()
    {
        if (expectedPickup != null
                && client.getTickCount() - expectedPickup.createdTick > EXPECTED_PICKUP_TIMEOUT_TICKS)
        {
            expectedPickup = null;
        }

        processPendingDespawns();
    }

    /**
     * ItemDespawned describes a change to the currently loaded scene. It is
     * not safe to remove metadata immediately because scene rebuilds can emit
     * the same event while a potion still exists in the raid.
     */
    public void itemDespawned(int itemId, WorldPoint location)
    {
        if (location == null || findTrackedItem(itemId, location) == null)
        {
            return;
        }

        for (PendingDespawn pending : pendingDespawns)
        {
            if (pending.itemId == itemId && pending.location.equals(location))
            {
                return;
            }
        }

        pendingDespawns.add(new PendingDespawn(itemId, location));
    }

    public void trackDrop(TrackedItem item)
    {
        if (item == null || item.getWorldPoint() == null || !RaidPotions.isPotion(item.getItemId()))
        {
            return;
        }

        TrackedItem existing = findTrackedItem(item.getItemId(), item.getWorldPoint());
        if (existing != null)
        {
            if (equalsIgnoreCase(existing.getOwnerName(), item.getOwnerName()))
            {
                return;
            }

            myDroppedItems.remove(existing);
        }

        myDroppedItems.add(item);
    }

    public TrackedItem findTrackedItem(int itemId, WorldPoint location)
    {
        if (location == null)
        {
            return null;
        }

        for (TrackedItem item : myDroppedItems)
        {
            if (item.matches(itemId, location))
            {
                return item;
            }
        }

        return null;
    }

    private void removeTrackedItem(int itemId, WorldPoint location)
    {
        TrackedItem trackedItem = findTrackedItem(itemId, location);
        if (trackedItem != null)
        {
            myDroppedItems.remove(trackedItem);
        }

        pendingDespawns.removeIf(pending -> pending.itemId == itemId
                && pending.location.equals(location));
    }

    public void onPickedUpItemMessage(PickedUpItemMessage message)
    {
        TrackedItem trackedItem = findTrackedItem(message.getItemId(), message.getWorldPoint());
        if (trackedItem == null)
        {
            return;
        }

        myDroppedItems.remove(trackedItem);
        pendingDespawns.removeIf(pending -> pending.itemId == message.getItemId()
                && pending.location.equals(message.getWorldPoint()));

        // Only report a pickup by somebody other than the owner. The owner
        // already knows they picked up their own potion.
        if (!equalsIgnoreCase(trackedItem.getOwnerName(), message.getPickerName()))
        {
            clientThread.invokeLater(() ->
            {
                String itemName = client.getItemDefinition(message.getItemId()).getName();
                String chatMessage = String.format(
                        "%s picked up a %s dropped by %s!",
                        message.getPickerName(),
                        itemName,
                        trackedItem.getOwnerName());
                client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", chatMessage, null);
            });
        }
    }

    public void menuOptionClicked(MenuOptionClicked event)
    {
        if (!"Take".equalsIgnoreCase(event.getMenuOption()))
        {
            expectedPickup = null;
            return;
        }

        int itemId = event.getId();
        if (!RaidPotions.isPotion(itemId))
        {
            return;
        }

        WorldPoint location = getInteractionLocation(event);
        if (location == null || client.getLocalPlayer() == null)
        {
            return;
        }

        expectedPickup = new ExpectedPickup(
                itemId,
                location,
                client.getLocalPlayer().getName(),
                client.getTickCount());
    }

    public void itemContainerChanged(ItemContainerChanged event)
    {
        if (event.getContainerId() != InventoryID.INVENTORY.getId())
        {
            return;
        }

        Map<Integer, Integer> currentInventory = snapshot(event.getItemContainer());
        if (previousInventory == null)
        {
            previousInventory = currentInventory;
            return;
        }

        ExpectedPickup pickup = expectedPickup;
        if (pickup != null)
        {
            int oldQuantity = previousInventory.getOrDefault(pickup.itemId, 0);
            int newQuantity = currentInventory.getOrDefault(pickup.itemId, 0);

            if (newQuantity > oldQuantity)
            {
                removeTrackedItem(pickup.itemId, pickup.location);

                if (partyService != null && partyService.isInParty())
                {
                    partyService.send(new PickedUpItemMessage(
                            pickup.itemId,
                            pickup.location,
                            pickup.playerName));
                }

                expectedPickup = null;
            }
        }

        previousInventory = currentInventory;
    }

    private WorldPoint getInteractionLocation(MenuOptionClicked event)
    {
        WorldView worldView = client.getTopLevelWorldView();
        if (worldView != null && worldView.getScene() != null)
        {
            Tile[][][] tiles = worldView.getScene().getTiles();
            int plane = worldView.getPlane();
            int sceneX = event.getParam0();
            int sceneY = event.getParam1();

            if (plane >= 0 && plane < tiles.length
                    && sceneX >= 0 && sceneX < tiles[plane].length
                    && sceneY >= 0 && sceneY < tiles[plane][sceneX].length
                    && tiles[plane][sceneX][sceneY] != null)
            {
                return tiles[plane][sceneX][sceneY].getWorldLocation();
            }

            Tile selectedTile = worldView.getSelectedSceneTile();
            if (selectedTile != null)
            {
                return selectedTile.getWorldLocation();
            }
        }

        return null;
    }

    private void processPendingDespawns()
    {
        for (PendingDespawn pending : pendingDespawns)
        {
            Tile currentTile = getLoadedTile(pending.location);
            if (currentTile == null)
            {
                // The tile is outside the current scene. Keep the metadata so
                // it can be used if the player walks back into render range.
                continue;
            }

            boolean stillPresent = currentTile.getGroundItems() != null
                    && currentTile.getGroundItems().stream()
                    .anyMatch(item -> item.getId() == pending.itemId);

            if (!stillPresent)
            {
                removeTrackedItem(pending.itemId, pending.location);
            }
            else
            {
                pendingDespawns.remove(pending);
            }
        }
    }

    private Tile getLoadedTile(WorldPoint location)
    {
        WorldView worldView = client.getTopLevelWorldView();
        if (worldView == null || worldView.getScene() == null)
        {
            return null;
        }

        LocalPoint localPoint = LocalPoint.fromWorld(worldView, location);
        if (localPoint == null || !localPoint.isInScene())
        {
            return null;
        }

        Tile[][][] tiles = worldView.getScene().getTiles();
        int plane = worldView.getPlane();
        int sceneX = localPoint.getSceneX();
        int sceneY = localPoint.getSceneY();
        if (plane < 0 || plane >= tiles.length
                || sceneX < 0 || sceneX >= tiles[plane].length
                || sceneY < 0 || sceneY >= tiles[plane][sceneX].length)
        {
            return null;
        }

        return tiles[plane][sceneX][sceneY];
    }

    private static Map<Integer, Integer> snapshot(ItemContainer container)
    {
        Map<Integer, Integer> snapshot = new HashMap<>();
        if (container == null)
        {
            return snapshot;
        }

        for (Item item : container.getItems())
        {
            if (item.getId() >= 0)
            {
                snapshot.merge(item.getId(), item.getQuantity(), Integer::sum);
            }
        }

        return snapshot;
    }

    private static boolean equalsIgnoreCase(String first, String second)
    {
        return first == null ? second == null : first.equalsIgnoreCase(second);
    }
}
