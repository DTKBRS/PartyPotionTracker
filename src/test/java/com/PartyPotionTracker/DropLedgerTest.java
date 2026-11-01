package com.PartyPotionTracker;

import java.util.Arrays;
import net.runelite.api.*;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.party.PartyService;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class DropLedgerTest {
    private final Client client = mock(Client.class);
    private final int id = ItemID.PRAYER_POTION4;
    private final WorldPoint tile = new WorldPoint(3200, 3200, 0);
    private PickupManager ledger;

    @Before public void setup() {
        ItemComposition definition = mock(ItemComposition.class);
        when(client.getItemDefinition(id)).thenReturn(definition);
        when(definition.getNote()).thenReturn(-1);
        when(definition.getName()).thenReturn("Prayer potion(4)");
        when(definition.getInventoryActions()).thenReturn(new String[]{"Drink"});
        ledger = new PickupManager(client, mock(PartyService.class));
    }

    private TrackedItem drop(String event, String owner, int quantity) {
        TrackedItem item = new TrackedItem(event, id, tile, owner, quantity, false);
        ledger.track(item);
        return item;
    }

    private PickedUpItemMessage pickup(int quantity, String... ids) {
        PickedUpItemMessage message = new PickedUpItemMessage(id, tile, "Picker");
        message.setQuantity(quantity);
        message.setDropIds(Arrays.asList(ids));
        return message;
    }

    @Test public void duplicateDropCannotResurrectConsumedRecord() {
        TrackedItem item = drop("a", "Owner", 1);
        ledger.onPickedUpItemMessage(pickup(1, "a"));
        assertFalse(ledger.track(item));
        assertTrue(ledger.getMyDroppedItems().isEmpty());
    }

    @Test public void delayedPickupCannotConsumeNewDropOnSameTile() {
        drop("a", "Owner", 1);
        ledger.onPickedUpItemMessage(pickup(1, "a"));
        drop("b", "Owner", 1);
        ledger.onPickedUpItemMessage(pickup(1, "a"));
        assertEquals("b", ledger.getMyDroppedItems().get(0).getDropId());
    }

    @Test public void pickupArrivingBeforeDropIsAppliedOnceAfterDropArrives() {
        PickedUpItemMessage message = pickup(1, "a");
        ledger.onPickedUpItemMessage(message);
        ledger.onPickedUpItemMessage(message);
        drop("a", "Owner", 2);
        ledger.gameTick();
        assertEquals(1, ledger.getMyDroppedItems().get(0).getQuantity());
        verify(client, times(1)).addChatMessage(any(), anyString(), anyString(), any());
    }

    @Test public void concurrentPickupsDecrementSameOriginalDrop() {
        drop("a", "Owner", 3);
        ledger.onPickedUpItemMessage(pickup(1, "a"));
        ledger.onPickedUpItemMessage(pickup(1, "a"));
        assertEquals(1, ledger.getMyDroppedItems().get(0).getQuantity());
    }

    @Test public void mixedOwnersBecomeUncertainInsteadOfInventingBottleIdentity() {
        drop("a", "Alice", 1);
        drop("b", "Bob", 2);
        ledger.onPickedUpItemMessage(pickup(1, "a", "b"));
        assertEquals(2, ledger.getMyDroppedItems().get(0).getQuantity());
        assertTrue(ledger.getMyDroppedItems().get(0).isAmbiguous());
        org.mockito.ArgumentCaptor<String> chat = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(client).addChatMessage(any(), anyString(), chat.capture(), any());
        assertTrue(chat.getValue().contains("uncertain"));
        assertFalse(chat.getValue().contains("Alice"));
        assertFalse(chat.getValue().contains("Bob"));
    }

    @Test public void impossibleOrLegacyPickupDoesNotConsumeAnything() {
        drop("a", "Owner", 1);
        ledger.onPickedUpItemMessage(pickup(2, "a"));
        ledger.onPickedUpItemMessage(new PickedUpItemMessage(id, tile, "Picker"));
        assertEquals(1, ledger.getMyDroppedItems().get(0).getQuantity());
        verify(client, never()).addChatMessage(any(), anyString(), anyString(), any());
    }

    @Test public void sessionResetDiscardsPendingPickupAndRotatesSessionId() {
        String before = ledger.getSessionId();
        ledger.onPickedUpItemMessage(pickup(1, "a"));
        ledger.clear();
        drop("a", "Owner", 1);
        ledger.gameTick();
        assertNotEquals(before, ledger.getSessionId());
        assertEquals(1, ledger.getMyDroppedItems().size());
    }
    @Test public void wireRoundTripPreservesIdentityQuantityAndReferences() {
        PickedUpItemMessage message = pickup(2, "a", "b");
        message.setProtocol(2);
        message.setWorld(301);
        message.setSessionId("session");
        message.setRaid("TOMBS_OF_AMASCUT");
        message.setSentAt(1234);
        com.google.gson.Gson gson = new com.google.gson.Gson();
        PickedUpItemMessage decoded = gson.fromJson(gson.toJson(message), PickedUpItemMessage.class);
        assertEquals(message.getEventId(), decoded.getEventId());
        assertEquals(message.getDropIds(), decoded.getDropIds());
        assertEquals(2, decoded.getQuantity());
        assertEquals(2, decoded.getProtocol());
        assertEquals(301, decoded.getWorld());
        assertEquals("session", decoded.getSessionId());
        assertEquals("TOMBS_OF_AMASCUT", decoded.getRaid());
        assertEquals(1234, decoded.getSentAt());
    }
    @Test public void snapshotProjectionReconstructsQuantitiesWithoutReplayingChat() {
        ledger.coordinate(event -> {}, (item, point) -> java.util.Collections.emptyList());
        TrackedItemMessage d = new TrackedItemMessage(id, tile, "Owner");
        d.setQuantity(3);
        PickedUpItemMessage take = pickup(1, d.getEventId());
        java.util.List<SupplyReport> history = Arrays.asList(SupplyReport.from(d), SupplyReport.from(take));
        ledger.replaceSharedHistory(history, false);
        ledger.replaceSharedHistory(history, false);
        assertEquals(2, ledger.getMyDroppedItems().get(0).getQuantity());
        verify(client, never()).addChatMessage(any(), anyString(), anyString(), any());
    }
}
