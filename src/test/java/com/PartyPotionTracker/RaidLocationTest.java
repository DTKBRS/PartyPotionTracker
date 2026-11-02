package com.PartyPotionTracker;

import org.junit.Test;
import static org.junit.Assert.*;

public class RaidLocationTest {
    @Test public void recognisesAllRaidInteriors() {
        assertEquals(RaidLocation.CHAMBERS_OF_XERIC, RaidLocation.fromRegion(0, true));
        for (int region : new int[]{12613, 12869, 13125, 13122, 13123, 13379, 12612, 12611, 12867})
            assertEquals(RaidLocation.THEATRE_OF_BLOOD, RaidLocation.fromRegion(region, false));
        for (int region : new int[]{14160, 15698, 15700, 14162, 14164, 15186, 15188, 14674, 14676, 15184, 15696, 14672})
            assertEquals(RaidLocation.TOMBS_OF_AMASCUT, RaidLocation.fromRegion(region, false));
        for (int region : new int[]{16471, 16214, 16727, 16216, 16728, 16215, 16726, 16472, 16474})
            assertEquals(RaidLocation.FRACTURED_ARCHIVE, RaidLocation.fromRegion(region, false));
    }

    @Test public void excludesExternalLobbiesAndOrdinaryWorld() {
        for (int region : new int[]{14642, 13454, 16470, 12850, 12892, 12597, -1})
            assertNull(RaidLocation.fromRegion(region, false));
    }
}
