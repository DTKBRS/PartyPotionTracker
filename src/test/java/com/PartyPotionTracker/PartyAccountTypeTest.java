package com.PartyPotionTracker;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PartyAccountTypeTest
{
    @Test
    public void mapsRuneLiteAccountTypeIds()
    {
        assertEquals(PartyAccountType.NORMAL, PartyAccountType.fromId(0));
        assertEquals(PartyAccountType.IRONMAN, PartyAccountType.fromId(1));
        assertEquals(PartyAccountType.ULTIMATE_IRONMAN, PartyAccountType.fromId(2));
        assertEquals(PartyAccountType.HARDCORE_IRONMAN, PartyAccountType.fromId(3));
        assertEquals(PartyAccountType.GROUP_IRONMAN, PartyAccountType.fromId(4));
        assertEquals(PartyAccountType.HARDCORE_GROUP_IRONMAN, PartyAccountType.fromId(5));
        assertEquals(PartyAccountType.UNRANKED_GROUP_IRONMAN, PartyAccountType.fromId(6));
        assertEquals(PartyAccountType.UNKNOWN, PartyAccountType.fromId(99));
    }

    @Test
    public void keepsRegularAndGroupIronmanChecksSeparate()
    {
        assertTrue(PartyAccountType.IRONMAN.isIronman());
        assertFalse(PartyAccountType.IRONMAN.isGroupIronman());
        assertTrue(PartyAccountType.GROUP_IRONMAN.isGroupIronman());
        assertFalse(PartyAccountType.GROUP_IRONMAN.isIronman());
    }
}
