package com.PartyPotionTracker;

import org.junit.Test;
import net.runelite.api.Client;
import net.runelite.api.ItemComposition;
import net.runelite.api.ItemID;
import static org.mockito.Mockito.*;
import static org.junit.Assert.*;

public class RaidSuppliesTest {
    @Test public void recognisesRaidAndOrdinaryDoseNames() {
        for (String name : new String[]{"Prayer potion(4)", "Xeric's aid (+)(4)", "Overload (-)(1)", "New raid potion(2)"}) {
            assertTrue(name, RaidSupplies.isDrinkableDose(name, new String[]{"Drink", null, "Drop"}));
        }
    }
    @Test public void excludesEmptyVialsAndUnrelatedChargedItems() {
        assertFalse(RaidSupplies.isDrinkableDose("Vial", new String[]{"Drop"}));
        assertFalse(RaidSupplies.isDrinkableDose("Ring of dueling(8)", new String[]{"Wear", "Rub"}));
        assertFalse(RaidSupplies.isDrinkableDose(null, null));
    }

    @Test public void classifiesDefinitionsWithoutRaidOrItemWhitelist() {
        Client client = mock(Client.class);
        ItemComposition item = mock(ItemComposition.class);
        when(client.getItemDefinition(999999)).thenReturn(item);
        when(item.getNote()).thenReturn(-1);
        when(item.getInventoryActions()).thenReturn(new String[]{"Drink", null, "Drop"});
        for (String name : new String[]{"Prayer potion(4)", "Xeric's aid (+)(4)",
                "Nectar(4)", "Ambrosia(2)", "Tears of elidinis(4)", "Future raid potion(3)"}) {
            when(item.getName()).thenReturn(name);
            assertTrue(name, RaidSupplies.isTrackable(client, 999999));
        }
        when(item.getNote()).thenReturn(799);
        assertFalse(RaidSupplies.isTrackable(client, 999999));
        assertFalse(RaidSupplies.isTrackable(client, -1));
        assertFalse(RaidSupplies.isTrackable(client, 123456));
    }

    @Test public void retainsSpecialSuppliesWithoutDrinkActions() {
        Client client = mock(Client.class);
        ItemComposition item = mock(ItemComposition.class);
        when(item.getNote()).thenReturn(-1);
        for (int id : new int[]{ItemID.BLESSED_CRYSTAL_SCARAB_1, ItemID.BLESSED_CRYSTAL_SCARAB_2,
                ItemID.SILK_DRESSING_1, ItemID.SILK_DRESSING_2}) {
            when(client.getItemDefinition(id)).thenReturn(item);
            assertTrue(RaidSupplies.isTrackable(client, id));
        }
    }
}
