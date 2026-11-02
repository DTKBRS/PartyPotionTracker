package com.PartyPotionTracker;

import net.runelite.api.*;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.*;
import net.runelite.api.widgets.WidgetInfo;
import net.runelite.client.party.PartyService;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class DropManagerTest {
    private final Client client = mock(Client.class);
    private final PartyService party = mock(PartyService.class);
    private final Player player = mock(Player.class);
    private final int id = ItemID.PRAYER_POTION4;
    private final WorldPoint point = new WorldPoint(3200, 3200, 0);
    private PickupManager ledger;
    private DropManager drops;

    @Before public void setup() {
        when(client.getLocalPlayer()).thenReturn(player);
        when(player.getWorldLocation()).thenReturn(point);
        when(player.getName()).thenReturn("Owner");
        ItemComposition definition = mock(ItemComposition.class);
        when(client.getItemDefinition(id)).thenReturn(definition);
        when(definition.getNote()).thenReturn(-1);
        when(definition.getName()).thenReturn("Prayer potion(4)");
        when(definition.getInventoryActions()).thenReturn(new String[]{"Drink"});
        ledger = new PickupManager(client, party);
        drops = new DropManager(client, party, ledger);
        inventory(2);
    }

    private void inventory(int quantity) {
        ItemContainer container = mock(ItemContainer.class);
        when(container.getItems()).thenReturn(quantity == 0 ? new Item[0] : new Item[]{new Item(id, quantity)});
        when(client.getItemContainer(InventoryID.INVENTORY)).thenReturn(container);
        drops.itemContainerChanged(new ItemContainerChanged(93, container));
    }

    private void click() {
        MenuOptionClicked event = mock(MenuOptionClicked.class);
        when(event.getMenuOption()).thenReturn("Drop");
        when(event.getMenuAction()).thenReturn(MenuAction.CC_OP);
        when(event.getParam1()).thenReturn(WidgetInfo.INVENTORY.getId());
        when(event.getItemId()).thenReturn(id);
        drops.menuOptionClicked(event);
    }

    private void ground() {
        Tile tile = mock(Tile.class);
        when(tile.getWorldLocation()).thenReturn(point);
        TileItem item = mock(TileItem.class);
        when(item.getId()).thenReturn(id);
        when(item.getQuantity()).thenReturn(1);
        when(item.getOwnership()).thenReturn(TileItem.OWNERSHIP_NONE);
        drops.itemSpawned(new ItemSpawned(tile, item));
    }

    @Test public void confirmsWithoutGameOwnershipInEitherEventOrder() {
        click(); ground(); inventory(1);
        assertEquals(1, ledger.getMyDroppedItems().size());
        click(); inventory(0); ground();
        assertEquals(2, ledger.getMyDroppedItems().size());
        assertNotEquals(ledger.getMyDroppedItems().get(0).getDropId(), ledger.getMyDroppedItems().get(1).getDropId());
    }

    @Test public void spawnOrInventoryLossAloneCannotInventOwner() {
        ground(); inventory(1);
        assertTrue(ledger.getMyDroppedItems().isEmpty());
        click(); ground();
        assertTrue(ledger.getMyDroppedItems().isEmpty());
    }

    @Test public void rapidDropsUseEachInventoryLossAndGroundAdditionOnce() {
        click(); click(); inventory(0); ground(); ground(); ground();
        assertEquals(2, ledger.getMyDroppedItems().size());
    }

    @Test public void confirmsDropIntoExistingPileFromQuantityDelta() {
        click(); inventory(1);
        Tile tile = mock(Tile.class);
        when(tile.getWorldLocation()).thenReturn(point);
        TileItem item = mock(TileItem.class);
        when(item.getId()).thenReturn(id);
        drops.itemQuantityChanged(new ItemQuantityChanged(item, tile, 3, 4));
        assertEquals(1, ledger.getMyDroppedItems().size());
    }

    @Test public void timeoutAndLoadingCancelUnconfirmedDrops() {
        click();
        when(client.getTickCount()).thenReturn(4);
        drops.gameTick(); inventory(1); ground();
        assertTrue(ledger.getMyDroppedItems().isEmpty());
        click(); drops.clear(); inventory(0); ground();
        assertTrue(ledger.getMyDroppedItems().isEmpty());
    }

    @Test public void confirmedDropBroadcastsUniqueVersionedEvent() {
        when(party.isInParty()).thenReturn(true);
        click(); ground(); inventory(1); ground();
        org.mockito.ArgumentCaptor<TrackedItemMessage> captured = org.mockito.ArgumentCaptor.forClass(TrackedItemMessage.class);
        verify(party).send(captured.capture());
        assertEquals(2, captured.getValue().getProtocol());
        assertEquals(ledger.getSessionId(), captured.getValue().getSessionId());
        assertEquals(ledger.getMyDroppedItems().get(0).getDropId(), captured.getValue().getEventId());
        assertEquals(1, captured.getValue().getQuantity());
    }
    @Test public void replicaReportsConfirmedDropWithoutCreatingAuthoritativeRecord() {
        java.util.List<SupplyEventMessage> reports = new java.util.ArrayList<>();
        ledger.coordinate(reports::add, (item, tile) -> java.util.Collections.emptyList());
        click(); ground(); inventory(1);
        assertEquals(1, reports.size());
        assertTrue(reports.get(0) instanceof TrackedItemMessage);
        assertTrue(ledger.getMyDroppedItems().isEmpty());
        verify(party, never()).send(any());
    }
}
