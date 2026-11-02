package com.PartyPotionTracker;

import lombok.Getter;

import net.runelite.api.*;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.client.party.PartyService;

import java.util.*;

public class PickupManager {
    private static final int OBSERVATION_RADIUS = 10;
    private static final int MISSING_GRACE_TICKS = 3;
    private static final int PICKUP_TIMEOUT_TICKS = 30;
    private static final int STALE_TICKS = 6000;

    public static final class ExpectedPickup {
        final int itemId;
        final WorldPoint location;

        ExpectedPickup(int itemId, WorldPoint location) {
            this.itemId = itemId;
            this.location = location;
        }
    }

    private final Client client;
    private final PartyService partyService;

    public PickupManager(Client client, PartyService partyService) {
        this.client = client;
        this.partyService = partyService;
    }

    private ExpectedPickup lastExpectedPickup = null;
    private final Map<TrackedItem, Integer> missingTicks = new HashMap<>();
    private final Map<TrackedItem, Integer> lastSeen = new HashMap<>();
    private int pickupAge;
    private final Map<TrackedItem, Integer> quantityMismatch = new HashMap<>();
    private final Set<TrackedItem> observed = new HashSet<>();

    @Getter private String sessionId = UUID.randomUUID().toString();
    @Getter private long sessionStartedAt = System.currentTimeMillis();
    private final Set<String> seenDrops = new HashSet<>();
    private final Set<String> seenPickups = new HashSet<>();
    private final List<PendingPickup> pendingPickups = new ArrayList<>();

    private static class PendingPickup {
        final PickedUpItemMessage message;
        int ticks;

        PendingPickup(PickedUpItemMessage message) {
            this.message = message;
        }
    }

    public void prepare(SupplyEventMessage message) {
        message.setProtocol(2);
        message.setSessionId(sessionId);
        message.setSentAt(System.currentTimeMillis());
        message.setWorld(client.getWorld());
        RaidLocation raid = RaidLocation.current(client);
        message.setRaid(raid == null ? null : raid.name());
    }

    private java.util.function.Consumer<SupplyEventMessage> reportSink;
    private java.util.function.BiFunction<Integer, WorldPoint, List<String>> pendingDropLookup;
    private boolean suppressChat;
    private final Set<String> uncertainObservations = new HashSet<>();

    void coordinate(
            java.util.function.Consumer<SupplyEventMessage> sink,
            java.util.function.BiFunction<Integer, WorldPoint, List<String>> lookup) {
        reportSink = sink;
        pendingDropLookup = lookup;
    }

    void submitDrop(TrackedItemMessage message) {
        if (reportSink != null) reportSink.accept(message);
        else if (onTrackedItemMessage(message) && partyService.isInParty())
            partyService.send(message);
    }

    void replaceSharedHistory(List<SupplyReport> history, boolean announceLast) {
        myDroppedItems.clear();
        seenDrops.clear();
        seenPickups.clear();
        pendingPickups.clear();
        suppressChat = true;
        try {
            for (int i = 0; i < history.size(); i++) {
                SupplyReport report = history.get(i);
                suppressChat = !announceLast || i != history.size() - 1;
                if (report.isDrop()) {
                    track(
                            new TrackedItem(
                                    report.getId(),
                                    report.getItemId(),
                                    report.getTile(),
                                    report.getActor(),
                                    report.getQuantity(),
                                    false));
                } else {
                    PickedUpItemMessage message =
                            new PickedUpItemMessage(
                                    report.getItemId(), report.getTile(), report.getActor());
                    message.setEventId(report.getId());
                    message.setQuantity(report.getQuantity());
                    message.setDropIds(report.getDropIds());
                    onPickedUpItemMessage(message);
                }
            }
        } finally {
            suppressChat = false;
        }
        Set<TrackedItem> active = new HashSet<>(myDroppedItems);
        observed.retainAll(active);
        missingTicks.keySet().retainAll(active);
        lastSeen.keySet().retainAll(active);
        quantityMismatch.keySet().retainAll(active);
        uncertainObservations.retainAll(seenDrops);
    }

    public boolean onTrackedItemMessage(TrackedItemMessage message) {
        return track(
                new TrackedItem(
                        message.getEventId(),
                        message.getItemId(),
                        message.getWorldPoint(),
                        message.getOwnerName(),
                        message.getQuantity(),
                        false));
    }

    public boolean track(TrackedItem item) {
        if (item.getDropId() == null
                || item.getWorldPoint() == null
                || item.getOwnerName() == null
                || item.getQuantity() <= 0
                || item.getQuantity() > 28
                || !RaidSupplies.isTrackable(client, item.getItemId())) return false;
        if (seenDrops.size() >= 10000 || !seenDrops.add(item.getDropId())) return false;
        myDroppedItems.add(item);
        lastSeen.put(item, client.getTickCount());
        return true;
    }

    public void clear() {
        sessionId = UUID.randomUUID().toString();
        sessionStartedAt = System.currentTimeMillis();
        seenDrops.clear();
        seenPickups.clear();
        pendingPickups.clear();
        myDroppedItems.clear();
        uncertainObservations.clear();
        missingTicks.clear();
        quantityMismatch.clear();
        observed.clear();
        lastSeen.clear();
        previousInventory = null;
        lastExpectedPickup = null;
    }

    private Tile observedTile(TrackedItem item) {
        if (client.getLocalPlayer() == null || client.getTopLevelWorldView() == null) return null;
        if (client.getLocalPlayer().getWorldLocation().distanceTo(item.getWorldPoint())
                > OBSERVATION_RADIUS) return null;
        net.runelite.api.coords.LocalPoint lp =
                net.runelite.api.coords.LocalPoint.fromWorld(
                        client.getTopLevelWorldView(), item.getWorldPoint());
        if (lp == null) return null;
        return client.getTopLevelWorldView()
                .getScene()
                .getTiles()[item.getWorldPoint().getPlane()][lp.getSceneX()][lp.getSceneY()];
    }

    boolean canObserve(TrackedItem item) {
        return observedTile(item) != null;
    }

    boolean isVisible(TrackedItem item) {
        Tile tile = observedTile(item);
        return tile != null
                && tile.getGroundItems() != null
                && tile.getGroundItems().stream().anyMatch(i -> i.getId() == item.getItemId());
    }

    boolean isOwnershipUncertain(TrackedItem item) {
        return uncertainObservations.contains(item.getDropId()) || isSharedOwnershipUncertain(item);
    }

    private boolean isSharedOwnershipUncertain(TrackedItem item) {
        return item.isAmbiguous()
                || myDroppedItems.stream()
                        .anyMatch(
                                other ->
                                        other.getItemId() == item.getItemId()
                                                && other.getWorldPoint()
                                                        .equals(item.getWorldPoint())
                                                && (other.isAmbiguous()
                                                        || !other.getOwnerName()
                                                                .equalsIgnoreCase(
                                                                        item.getOwnerName())));
    }

    int visibleQuantity(TrackedItem item) {
        Tile tile = observedTile(item);
        if (tile == null || tile.getGroundItems() == null) return 0;
        return tile.getGroundItems().stream()
                .filter(i -> i.getId() == item.getItemId())
                .mapToInt(TileItem::getQuantity)
                .sum();
    }

    public void suspendObservation() {
        missingTicks.clear();
        quantityMismatch.clear();
        lastExpectedPickup = null;
    }

    private final List<TrackedItem> myDroppedItems = new ArrayList<>();

    public List<TrackedItem> getMyDroppedItems() {
        return Collections.unmodifiableList(myDroppedItems);
    }

    private Map<Integer, Integer> previousInventory = null;

    public void gameTick() {
        Iterator<PendingPickup> pending = pendingPickups.iterator();
        while (pending.hasNext()) {
            PendingPickup pickup = pending.next();
            if (seenDrops.containsAll(pickup.message.getDropIds())) {
                applyPickup(pickup.message);
                pending.remove();
            } else if (++pickup.ticks > 5) {
                pending.remove();
            }
        }
        if (lastExpectedPickup != null && ++pickupAge > PICKUP_TIMEOUT_TICKS)
            lastExpectedPickup = null;
        Iterator<TrackedItem> iter = myDroppedItems.iterator();
        while (iter.hasNext()) {
            TrackedItem item = iter.next();
            boolean visible = isVisible(item);
            if (visible) {
                int groundQuantity = visibleQuantity(item);
                int recordedQuantity =
                        myDroppedItems.stream()
                                .filter(
                                        other ->
                                                other.getItemId() == item.getItemId()
                                                        && other.getWorldPoint()
                                                                .equals(item.getWorldPoint()))
                                .mapToInt(TrackedItem::getQuantity)
                                .sum();
                if (groundQuantity > 0 && groundQuantity != recordedQuantity) {
                    if (quantityMismatch.merge(item, 1, Integer::sum) >= MISSING_GRACE_TICKS) {
                        if (reportSink == null) item.markAmbiguous();
                        else uncertainObservations.add(item.getDropId());
                    }
                } else {
                    quantityMismatch.remove(item);
                }
                observed.add(item);
                missingTicks.remove(item);
                lastSeen.put(item, client.getTickCount());
            }
            // Only reconcile absence well inside the server's local area. A scene unload
            // or leaving render range is not evidence of a pickup.
            boolean nearby =
                    client.getLocalPlayer() != null
                            && client.getLocalPlayer()
                                            .getWorldLocation()
                                            .distanceTo(item.getWorldPoint())
                                    <= OBSERVATION_RADIUS
                            && canObserve(item);
            int missing =
                    nearby && observed.contains(item) && !visible
                            ? missingTicks.merge(item, 1, Integer::sum)
                            : 0;
            if (!nearby) {
                missingTicks.remove(item);
                quantityMismatch.remove(item);
            }
            boolean stale =
                    client.getTickCount()
                                    - lastSeen.getOrDefault(item, client.getTickCount())
                            > STALE_TICKS;
            if ((reportSink == null && missing >= MISSING_GRACE_TICKS) || stale) {
                iter.remove();
                missingTicks.remove(item);
                lastSeen.remove(item);
                observed.remove(item);
                quantityMismatch.remove(item);
            }
        }
    }

    private void remove(TrackedItem item) {
        myDroppedItems.remove(item);
        missingTicks.remove(item);
        lastSeen.remove(item);
        observed.remove(item);
        quantityMismatch.remove(item);
    }

    public void onPickedUpItemMessage(PickedUpItemMessage msg) {
        if (msg.getEventId() == null
                || msg.getWorldPoint() == null
                || msg.getPickerName() == null
                || msg.getQuantity() <= 0
                || msg.getQuantity() > 28
                || msg.getDropIds() == null
                || msg.getDropIds().isEmpty()
                || msg.getDropIds().size() > 100
                || msg.getDropIds().stream().anyMatch(Objects::isNull)
                || seenPickups.size() >= 10000
                || !seenPickups.add(msg.getEventId())) return;
        if (!seenDrops.containsAll(msg.getDropIds())) {
            if (pendingPickups.size() < 100) pendingPickups.add(new PendingPickup(msg));
            return;
        }
        applyPickup(msg);
    }

    private void applyPickup(PickedUpItemMessage msg) {
        List<TrackedItem> matches = new ArrayList<>();
        for (TrackedItem item : myDroppedItems) {
            if (msg.getDropIds().contains(item.getDropId())
                    && item.getItemId() == msg.getItemId()
                    && item.getWorldPoint().equals(msg.getWorldPoint())) matches.add(item);
        }
        if (matches.isEmpty()) return;
        int total = matches.stream().mapToInt(TrackedItem::getQuantity).sum();
        if (msg.getQuantity() > total)
            return; // Do not turn inconsistent evidence into ownership claims.
        Set<String> owners = new HashSet<>();
        matches.forEach(item -> owners.add(item.getOwnerName().toLowerCase(Locale.ROOT)));
        boolean ambiguous =
                owners.size() > 1 || matches.stream().anyMatch(this::isSharedOwnershipUncertain);
        String owner = ambiguous ? null : matches.get(0).getOwnerName();
        // Keep original drop IDs so concurrent pickups can reference the same stack.
        // Mixed-owner depletion is deterministic bookkeeping, never attribution.
        matches.sort(Comparator.comparing(TrackedItem::getDropId));
        int toConsume = msg.getQuantity();
        for (TrackedItem item : matches) {
            if (ambiguous) item.markAmbiguous();
            int consumed = Math.min(toConsume, item.getQuantity());
            item.consume(consumed);
            toConsume -= consumed;
            if (item.getQuantity() == 0) remove(item);
        }
        if (owner != null && owner.equalsIgnoreCase(msg.getPickerName())) return;
        String itemName = client.getItemDefinition(msg.getItemId()).getName();
        String chatMessage =
                owner == null
                        ? String.format(
                                "%s picked up %d x %s; original owner is uncertain.",
                                msg.getPickerName(), msg.getQuantity(), itemName)
                        : String.format(
                                "%s picked up %d x %s dropped by %s!",
                                msg.getPickerName(), msg.getQuantity(), itemName, owner);
        if (!suppressChat)
            client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", chatMessage, null);
    }

    public void menuOptionClicked(MenuOptionClicked event) {
        lastExpectedPickup = null;
        if (!event.isConsumed()
                && "Take".equalsIgnoreCase(event.getMenuOption())
                && event.getMenuAction() == MenuAction.GROUND_ITEM_THIRD_OPTION
                && client.getLocalPlayer() != null
                && client.getTopLevelWorldView() != null) {
            int itemId = event.getMenuEntry().getIdentifier();
            if (!RaidSupplies.isTrackable(client, itemId)) return;

            WorldPoint location =
                    WorldPoint.fromScene(
                            client, event.getParam0(), event.getParam1(), client.getPlane());
            if (previousInventory == null) {
                ItemContainer inventory = client.getItemContainer(InventoryID.INVENTORY);
                if (inventory != null) {
                    previousInventory = new HashMap<>();
                    for (Item item : inventory.getItems()) {
                        if (item.getId() >= 0)
                            previousInventory.merge(item.getId(), item.getQuantity(), Integer::sum);
                    }
                }
            }
            lastExpectedPickup = new ExpectedPickup(itemId, location);
            pickupAge = 0;
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

        for (Item item : currentItems) {
            if (item.getId() != -1) {
                currentInventory.merge(item.getId(), item.getQuantity(), Integer::sum);
            }
        }

        // If this is the first time we're seeing inventory, just set inventory and return
        if (previousInventory == null || lastExpectedPickup == null) {
            previousInventory = currentInventory;
            return;
        }

        int itemId = lastExpectedPickup.itemId;
        int gainedQuantity =
                currentInventory.getOrDefault(itemId, 0)
                        - previousInventory.getOrDefault(itemId, 0);
        boolean gained = gainedQuantity > 0;
        if (gained
                && client.getLocalPlayer() != null
                && client.getLocalPlayer()
                                .getWorldLocation()
                                .distanceTo(lastExpectedPickup.location)
                        <= 1) {
            PickedUpItemMessage message =
                    new PickedUpItemMessage(
                            itemId, lastExpectedPickup.location, client.getLocalPlayer().getName());
            List<String> ids = new ArrayList<>();
            for (TrackedItem item : myDroppedItems) {
                if (item.getItemId() == itemId
                        && item.getWorldPoint().equals(lastExpectedPickup.location))
                    ids.add(item.getDropId());
            }
            if (pendingDropLookup != null) {
                for (String id : pendingDropLookup.apply(itemId, lastExpectedPickup.location))
                    if (!ids.contains(id)) ids.add(id);
            }
            message.setDropIds(ids);
            message.setQuantity(gainedQuantity);
            prepare(message);
            if (reportSink != null) reportSink.accept(message);
            else {
                if (!ids.isEmpty()) onPickedUpItemMessage(message);
                if (partyService.isInParty()) partyService.send(message);
            }
        }
        if (gained) lastExpectedPickup = null;
        previousInventory = currentInventory;
    }
}
