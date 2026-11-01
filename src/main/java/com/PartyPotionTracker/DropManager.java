package com.PartyPotionTracker;

import java.util.*;
import net.runelite.api.*;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.*;
import net.runelite.api.widgets.WidgetInfo;
import net.runelite.client.party.PartyService;

/** Correlates local Drop intent, inventory loss and ground evidence in either event order. */
public class DropManager {
    private static final int CONFIRM_TICKS = 3;
    private final Client client;
    private final PartyService partyService;
    private final PickupManager pickupManager;
    private final List<Intent> pending = new ArrayList<>();
    private Map<Integer, Integer> inventory;

    private static class Intent {
        final int itemId;
        final WorldPoint tile;
        final int tick;
        final String owner;
        boolean inventoryLost;
        boolean groundAdded;

        Intent(int itemId, WorldPoint tile, int tick, String owner) {
            this.itemId = itemId;
            this.tile = tile;
            this.tick = tick;
            this.owner = owner;
        }
    }

    public DropManager(Client client, PartyService partyService, PickupManager pickupManager) {
        this.client = client;
        this.partyService = partyService;
        this.pickupManager = pickupManager;
    }

    public void clear() {
        pending.clear();
        inventory = null;
    }

    public void menuOptionClicked(MenuOptionClicked event) {
        if (!"Drop".equalsIgnoreCase(event.getMenuOption())) {
            pending.clear();
            return;
        }
        if (event.isConsumed() || (event.getMenuAction() != MenuAction.CC_OP
                && event.getMenuAction() != MenuAction.CC_OP_LOW_PRIORITY)
                || event.getParam1() != WidgetInfo.INVENTORY.getId()
                || client.getLocalPlayer() == null || !RaidSupplies.isTrackable(client, event.getItemId())) return;
        if (inventory == null) inventory = snapshot(client.getItemContainer(InventoryID.INVENTORY));
        if (pending.size() >= 28) return;
        pending.add(new Intent(event.getItemId(), client.getLocalPlayer().getWorldLocation(),
                client.getTickCount(), client.getLocalPlayer().getName()));
    }

    public void itemContainerChanged(ItemContainerChanged event) {
        if (event.getContainerId() != InventoryID.INVENTORY.getId()) return;
        Map<Integer, Integer> current = snapshot(event.getItemContainer());
        if (inventory != null) {
            Map<Integer, Integer> losses = new HashMap<>();
            inventory.forEach((id, qty) -> losses.put(id, Math.max(0, qty - current.getOrDefault(id, 0))));
            for (Intent intent : pending) {
                int loss = losses.getOrDefault(intent.itemId, 0);
                if (!intent.inventoryLost && loss > 0 && fresh(intent)) {
                    intent.inventoryLost = true;
                    losses.put(intent.itemId, loss - 1);
                }
            }
        }
        inventory = current;
        confirm();
    }

    public void itemSpawned(ItemSpawned event) {
        groundAdded(event.getItem().getId(), event.getTile().getWorldLocation(), event.getItem().getQuantity());
    }

    public void itemQuantityChanged(ItemQuantityChanged event) {
        groundAdded(event.getItem().getId(), event.getTile().getWorldLocation(),
                event.getNewQuantity() - event.getOldQuantity());
    }

    private void groundAdded(int id, WorldPoint tile, int quantity) {
        for (Intent intent : pending) {
            if (quantity <= 0) break;
            if (!intent.groundAdded && fresh(intent) && intent.itemId == id && intent.tile.equals(tile)) {
                intent.groundAdded = true;
                quantity--;
            }
        }
        confirm();
    }

    private boolean fresh(Intent intent) {
        return client.getTickCount() - intent.tick <= CONFIRM_TICKS;
    }

    public void gameTick() {
        pending.removeIf(intent -> !fresh(intent));
        confirm();
    }

    private void confirm() {
        Iterator<Intent> it = pending.iterator();
        while (it.hasNext()) {
            Intent intent = it.next();
            if (!fresh(intent) || !intent.inventoryLost || !intent.groundAdded) continue;
            it.remove();
            TrackedItemMessage message = new TrackedItemMessage(intent.itemId, intent.tile, intent.owner);
            pickupManager.prepare(message);
            pickupManager.submitDrop(message);
        }
    }

    private static Map<Integer, Integer> snapshot(ItemContainer container) {
        Map<Integer, Integer> result = new HashMap<>();
        if (container != null) for (Item item : container.getItems()) {
            if (item.getId() >= 0) result.merge(item.getId(), item.getQuantity(), Integer::sum);
        }
        return result;
    }
}
