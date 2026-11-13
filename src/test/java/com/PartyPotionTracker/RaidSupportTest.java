package com.PartyPotionTracker;

import net.runelite.api.ItemID;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class RaidSupportTest
{
    @Test
    public void recognizesSupportedRaidRegions()
    {
        assertEquals(RaidRegion.TOB, RaidRegion.fromRegionId(12611));
        assertEquals(RaidRegion.TOA, RaidRegion.fromRegionId(14160));
        assertEquals(RaidRegion.TFA, RaidRegion.fromRegionId(16214));
        assertEquals(RaidRegion.COX, RaidRegion.fromRegionId(12889));
    }

    @Test
    public void recognizesRaidSpecificSupplies()
    {
        assertTrue(RaidPotions.isPotion(ItemID.STAMINA_POTION4));
        assertTrue(RaidPotions.isPotion(ItemID.MENAPHITE_REMEDY4));
        assertTrue(RaidPotions.isPotion(ItemID.SUPER_COMBAT_POTION4));
    }

    @Test
    public void excludesSuppliesThatAreNotTracked()
    {
        assertFalse(RaidPotions.isPotion(ItemID.FORGOTTEN_BREW4));
        assertFalse(RaidPotions.isPotion(ItemID.BATTLEMAGE_POTION4));
        assertFalse(RaidPotions.isPotion(ItemID.KODAI_POTION_4));
        assertFalse(RaidPotions.isPotion(ItemID.TWISTED_POTION_4));
        assertFalse(RaidPotions.isPotion(ItemID.ELDER_POTION_4));
        assertFalse(RaidPotions.isPotion(ItemID.OVERLOAD_4));
    }
}
