package com.PartyPotionTracker;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.party.PartyService;
import javax.inject.Inject;
import java.util.*;

@Slf4j
public class PickupManager {
     static class PendingDespawn {
        final TrackedItem item;
        int ageTicks = 0;

        PendingDespawn(TrackedItem item) {
            this.item = item;
        }
    }


    private Client client;
    private PartyService partyService;
    private ClientThread clientThread;

    public PickupManager(Client client, PartyService partyService, ClientThread clientThread) {
        this.client = client;
        this.partyService = partyService;
        this.clientThread = clientThread;

    }

    public final List<PickupManager.PendingDespawn> pendingDespawnItem = new ArrayList<>();

    @Getter
    public final List<TrackedItem> myDroppedItems= new ArrayList<>();

    private Map<Integer, Integer> previousInventory = null;

    public void gameTick(){

        if (pendingDespawnItem.isEmpty()) return;

        Iterator<PickupManager.PendingDespawn> iter = pendingDespawnItem.iterator();
        while (iter.hasNext())
        {
            PickupManager.PendingDespawn pd = iter.next();
            pd.ageTicks++;

            // Give it time for PickedUpItemMessage to arrive
            if (pd.ageTicks < 2)
                continue;

            if (myDroppedItems.contains(pd.item))
            {

                myDroppedItems.remove(pd.item);
                String itemName = client.getItemDefinition(pd.item.getItemId()).getName();
                String msg = String.format("%s was picked up by someone outside your party.", itemName);
                client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", msg, null);
            }

            iter.remove();
        }
    }


    public void onPickedUpItemMessage(PickedUpItemMessage msg){

        TrackedItem matchedItem = null;
        for (TrackedItem cur : myDroppedItems)
        {
            if (cur.getItemId() == msg.getItemId() &&
                    cur.getWorldPoint().equals(msg.getWorldPoint()) &&
                    cur.getOwnerName().equalsIgnoreCase(msg.getPickerName()))
            {
                matchedItem = cur;
                break;
            }
        }

        if (matchedItem != null)
        {
            // Decrement quantity or remove
            myDroppedItems.remove(matchedItem);
            Iterator<PickupManager.PendingDespawn> iter = pendingDespawnItem.iterator();
            while (iter.hasNext())
            {
                PickupManager.PendingDespawn pd = iter.next();
                if (pd.item.equals(matchedItem))
                {
                    iter.remove();
                    break;
                }
            }
            return;
        }

        for (TrackedItem cur : myDroppedItems)
        {
            if (cur.getItemId() == msg.getItemId() &&
                    cur.getWorldPoint().equals(msg.getWorldPoint()))
            {
                matchedItem = cur;
                break;
            }
        }

        if (matchedItem != null){
            Iterator<PickupManager.PendingDespawn> iter = pendingDespawnItem.iterator();
            while (iter.hasNext())
            {
                PickupManager.PendingDespawn pd = iter.next();
                if (pd.item.equals(matchedItem))
                {
                    iter.remove();
                    break;
                }
            }
            String ownerName = matchedItem.getOwnerName();
            myDroppedItems.remove(matchedItem);
            clientThread.invokeLater(() ->
            {
                String itemName = client.getItemDefinition(msg.getItemId()).getName();
                String chatMessage = String.format("%s picked up a %s dropped by %s!", msg.getPickerName(), itemName, ownerName);
                client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", chatMessage, null);
            });
        }
    }

    public void itemContainerChanged(ItemContainerChanged event) {

        // Return early if it's not an inventory update
        if (event.getContainerId() != InventoryID.INVENTORY.getId()) {
            return;
        }

        ItemContainer itemContainer = event.getItemContainer();
        Item[] currentItems = itemContainer.getItems();
        Map<Integer, Integer> currentInventory = new HashMap<>();

        for (Item item : currentItems)
        {
            if (item.getId() != -1)
            {
                currentInventory.merge(item.getId(), item.getQuantity(), Integer::sum);
            }
        }

        // If this is the first time we're seeing inventory, just set inventory and return
        if (previousInventory == null)
        {
            previousInventory = currentInventory;
            return;
        }

        // Combine all unique item IDs from both inventories to catch missing (zeroed) items
        Set<Integer> allItemIds = new HashSet<>();
        allItemIds.addAll(previousInventory.keySet());
        allItemIds.addAll(currentInventory.keySet());

        for (int itemId : allItemIds)
        {
            if (itemId == -1 || !ToaPotions.isPotion(itemId))
                continue;

            int oldQty = previousInventory.getOrDefault(itemId, 0);
            int newQty = currentInventory.getOrDefault(itemId, 0);

            if (newQty > oldQty)
            {
                // Potion pickup detected
                WorldPoint location = client.getLocalPlayer().getWorldLocation();
                String localName = client.getLocalPlayer().getName();
                if (partyService != null) {
                    partyService.send(new PickedUpItemMessage(itemId, location, localName));
                }
            }
        }

        // Update inventory snapshot after processing
        previousInventory = currentInventory;
    }
}
