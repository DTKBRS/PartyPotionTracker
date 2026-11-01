package com.PartyPotionTracker;

import net.runelite.api.*;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.client.party.PartyService;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class PickupManagerTest {
    private final Client client = mock(Client.class);
    private final WorldView world = mock(WorldView.class);
    private final Player player = mock(Player.class);
    private final PartyService party = mock(PartyService.class);
    private PickupManager manager;
    private final int potion = ItemID.PRAYER_POTION4;
    private final WorldPoint tile = new WorldPoint(3200, 3200, 0);
    private final TrackedItem dropped = new TrackedItem(potion, tile, "Owner");

    @Before public void setUp() {
        ItemComposition definition = mock(ItemComposition.class);
        when(client.getItemDefinition(potion)).thenReturn(definition);
        when(definition.getNote()).thenReturn(-1);
        when(definition.getName()).thenReturn("Prayer potion(4)");
        when(definition.getInventoryActions()).thenReturn(new String[]{"Drink"});
        when(client.getLocalPlayer()).thenReturn(player);
        when(client.getTopLevelWorldView()).thenReturn(world);
        when(world.getBaseX()).thenReturn(3150);
        when(world.getBaseY()).thenReturn(3150);
        when(player.getWorldLocation()).thenReturn(tile);
        when(player.getName()).thenReturn("Picker");
        manager = spy(new PickupManager(client, party));
        doReturn(true).when(manager).isVisible(any());
        doReturn(true).when(manager).canObserve(any());
        manager.track(dropped);
        manager.gameTick();
    }

    @Test public void leavingRenderRangePreservesOwnershipWithoutChat() {
        when(player.getWorldLocation()).thenReturn(new WorldPoint(3300, 3200, 0));
        doReturn(false).when(manager).isVisible(any());
        for (int i = 0; i < 20; i++) manager.gameTick();
        assertEquals(1, manager.getMyDroppedItems().size());
        when(player.getWorldLocation()).thenReturn(tile);
        doReturn(true).when(manager).isVisible(any());
        manager.gameTick();
        assertEquals(dropped, manager.getMyDroppedItems().get(0));
        verify(client, never()).addChatMessage(any(), anyString(), anyString(), any());
    }

    @Test public void confirmedNearbyAbsenceRemovesSilentlyAfterGracePeriod() {
        doReturn(false).when(manager).isVisible(any());
        manager.gameTick();
        manager.gameTick();
        assertEquals(1, manager.getMyDroppedItems().size());
        manager.gameTick();
        assertTrue(manager.getMyDroppedItems().isEmpty());
        verify(client, never()).addChatMessage(any(), anyString(), anyString(), any());
    }

    @Test public void privatePartyDropIsNotRemovedBeforeItHasBeenSeen() {
        manager.clear();
        manager.track(dropped);
        doReturn(false).when(manager).isVisible(any());
        for (int i = 0; i < 10; i++) manager.gameTick();
        assertEquals(1, manager.getMyDroppedItems().size());
    }

    @Test public void unavailableSceneDoesNotCountAsMissing() {
        doReturn(false).when(manager).isVisible(any());
        doReturn(false).when(manager).canObserve(any());
        for (int i = 0; i < 10; i++) manager.gameTick();
        assertEquals(1, manager.getMyDroppedItems().size());
    }

    @Test public void loadingPreservesOwnershipAndResetsMissingGrace() {
        doReturn(false).when(manager).isVisible(any());
        manager.gameTick();
        manager.gameTick();
        manager.suspendObservation();
        manager.gameTick();
        assertEquals(1, manager.getMyDroppedItems().size());
    }

    @Test public void duplicateSpawnDoesNotDuplicateOwnership() {
        assertFalse(manager.track(dropped));
        assertEquals(1, manager.getMyDroppedItems().size());
    }

    private void inventory(int count) {
        ItemContainer container = mock(ItemContainer.class);
        when(container.getItems()).thenReturn(count == 0 ? new Item[0] : new Item[]{new Item(potion, count)});
        manager.itemContainerChanged(new ItemContainerChanged(InventoryID.INVENTORY.getId(), container));
    }

    private void take() {
        when(client.getBaseX()).thenReturn(3150);
        when(client.getBaseY()).thenReturn(3150);
        MenuEntry entry = mock(MenuEntry.class);
        when(entry.getOption()).thenReturn("Take");
        when(entry.getType()).thenReturn(MenuAction.GROUND_ITEM_THIRD_OPTION);
        when(entry.getIdentifier()).thenReturn(potion);
        when(entry.getParam0()).thenReturn(50);
        when(entry.getParam1()).thenReturn(50);
        manager.menuOptionClicked(new MenuOptionClicked(entry));
    }

    @Test public void pickupUsesClickedTileAndSurvivesUnrelatedInventoryUpdate() {
        when(party.isInParty()).thenReturn(true);
        inventory(0);
        take();
        inventory(0);
        when(player.getWorldLocation()).thenReturn(new WorldPoint(3201, 3200, 0));
        doReturn(false).when(manager).isVisible(any());
        inventory(1);
        org.mockito.ArgumentCaptor<PickedUpItemMessage> message = org.mockito.ArgumentCaptor.forClass(PickedUpItemMessage.class);
        verify(party).send(message.capture());
        assertEquals(tile, message.getValue().getWorldPoint());
        assertTrue(manager.getMyDroppedItems().isEmpty());
    }

    @Test public void pickupDecrementsConfirmedStackQuantity() {
        manager.clear();
        TrackedItem stack = new TrackedItem("stack", potion, tile, "Owner", 3, false);
        manager.track(stack);
        PickedUpItemMessage message = new PickedUpItemMessage(potion, tile, "Picker");
        message.setDropIds(java.util.Collections.singletonList("stack"));
        manager.onPickedUpItemMessage(message);
        assertEquals(2, manager.getMyDroppedItems().get(0).getQuantity());
        manager.onPickedUpItemMessage(message);
        assertEquals(2, manager.getMyDroppedItems().get(0).getQuantity());
    }

    @Test public void staleClickCannotClaimLaterInventoryGain() {
        inventory(0);
        take();
        for (int i = 0; i < 31; i++) manager.gameTick();
        inventory(1);
        verify(party, never()).send(any());
    }

    @Test public void sessionResetClearsTrackingAndPendingPickup() {
        inventory(0);
        take();
        manager.clear();
        inventory(1);
        assertTrue(manager.getMyDroppedItems().isEmpty());
        verify(party, never()).send(any());
    }
    @Test public void unexplainedPileQuantityChangeMakesOwnershipUncertain() {
        manager.clear();
        TrackedItem stack = new TrackedItem("stack", potion, tile, "Owner", 3, false);
        manager.track(stack);
        doReturn(1).when(manager).visibleQuantity(any());
        for (int i = 0; i < 3; i++) manager.gameTick();
        assertTrue(manager.isOwnershipUncertain(stack));
        verify(client, never()).addChatMessage(any(), anyString(), anyString(), any());
    }
    @Test public void replicaDoesNotDeleteSharedRecordsWhenGroundItemsDisappear() {
        manager.coordinate(event -> {}, (id, point) -> java.util.Collections.emptyList());
        doReturn(false).when(manager).isVisible(any());
        for (int i = 0; i < 10; i++) manager.gameTick();
        assertEquals(1, manager.getMyDroppedItems().size());
    }

    @Test public void replicaReportsPickupWithoutChangingSharedQuantity() {
        java.util.List<SupplyEventMessage> reports = new java.util.ArrayList<>();
        manager.coordinate(reports::add, (id, point) -> java.util.Collections.emptyList());
        inventory(0); take(); inventory(1);
        assertEquals(1, reports.size());
        assertTrue(reports.get(0) instanceof PickedUpItemMessage);
        assertEquals(1, manager.getMyDroppedItems().size());
        assertEquals(1, manager.getMyDroppedItems().get(0).getQuantity());
        verify(party, never()).send(any());
    }
}
