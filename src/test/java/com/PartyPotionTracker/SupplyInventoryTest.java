package com.PartyPotionTracker;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import net.runelite.api.*;

import org.junit.Test;

import java.util.*;

public class SupplyInventoryTest {
    private SupplyInventory inventory(int brews) {
        return new SupplyInventory(
                Map.of(SupplyInventory.BREWS, brews), Map.of("Saradomin brew(4)", 1));
    }

    @Test
    public void sumsDosesAndKeepsRaidVariantsSeparate() {
        Client client = mock(Client.class);
        ItemContainer container = mock(ItemContainer.class);
        when(container.getItems())
                .thenReturn(
                        new Item[] {
                            new Item(1, 1), new Item(2, 1), new Item(3, 1), new Item(4, 1)
                        });
        String[] names = {
            "Saradomin brew(4)", "Saradomin brew(1)", "Xeric's aid (+)(4)", "Super restore(4)"
        };
        for (int i = 0; i < names.length; i++) {
            ItemComposition item = mock(ItemComposition.class);
            when(client.getItemDefinition(i + 1)).thenReturn(item);
            when(item.getName()).thenReturn(names[i]);
            when(item.getInventoryActions()).thenReturn(new String[] {"Drink"});
            when(item.getNote()).thenReturn(i == 3 ? 799 : -1);
        }
        SupplyInventory result = SupplyInventory.read(client, container);
        assertEquals(5, result.doses(SupplyInventory.BREWS));
        assertEquals(4, result.doses("Xeric's aid (+)"));
        assertEquals(0, result.doses(SupplyInventory.RESTORES));
        assertEquals(Integer.valueOf(1), result.bottles.get("Saradomin brew(1)"));
    }

    @Test
    public void staleAndUnknownPlayersAreExcludedFromTotals() {
        SupplyInventoryView view =
                new SupplyInventoryView(
                        "",
                        Arrays.asList(
                                new SupplyInventoryView.Row("Alex", "Current", inventory(4)),
                                new SupplyInventoryView.Row("Sam", "Stale", inventory(8)),
                                new SupplyInventoryView.Row("Robin", "Unknown", null)));
        assertEquals("4", view.total(SupplyInventory.BREWS));
        assertEquals("0", view.total(SupplyInventory.PRAYER));
        assertEquals(1, view.reportingMembers);
        assertEquals("—", SupplyInventoryView.inactive("").total(SupplyInventory.BREWS));
    }

    @Test
    public void duplicateAndOutOfOrderReportsDoNotRefreshAge() {
        SupplyInventoryLedger ledger = new SupplyInventoryLedger();
        ledger.update(1, inventory(4), 2, 0);
        ledger.update(1, inventory(8), 1, 10_000_000_000L);
        ledger.update(1, inventory(4), 2, 14_000_000_000L);
        assertEquals(4, ledger.get(1).inventory.doses(SupplyInventory.BREWS));
        assertFalse(ledger.get(1).fresh(SupplyInventoryLedger.STALE_AFTER_NANOS));
        ledger.update(1, inventory(2), 3, SupplyInventoryLedger.STALE_AFTER_NANOS);
        assertTrue(ledger.get(1).fresh(SupplyInventoryLedger.STALE_AFTER_NANOS));
    }

    @Test
    public void leavingPartyRemovesInventoryButSnapshotRemainsImmutable() {
        SupplyInventoryLedger ledger = new SupplyInventoryLedger();
        Map<String, Integer> counts = new HashMap<>();
        counts.put(SupplyInventory.BREWS, 4);
        SupplyInventory supplies = new SupplyInventory(counts, Map.of());
        ledger.update(1, supplies, 1, 0);
        counts.put(SupplyInventory.BREWS, 100);
        assertEquals(4, ledger.get(1).inventory.doses(SupplyInventory.BREWS));
        ledger.retainMembers(Set.of(2L));
        assertNull(ledger.get(1));
    }

    @Test
    public void rejectsMalformedRemoteCounts() {
        InventorySupplyMessage message = new InventorySupplyMessage();
        message.doses = Map.of(SupplyInventory.BREWS, -1);
        message.bottles = Map.of();
        assertNull(SupplyInventory.from(message));
        message.doses = Map.of();
        assertNotNull(SupplyInventory.from(message));
        message.bottles = null;
        assertNull(SupplyInventory.from(message));
    }
}
