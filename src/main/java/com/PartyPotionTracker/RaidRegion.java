package com.PartyPotionTracker;

import java.util.HashMap;
import java.util.Map;

/**
 * Region IDs for the supported instanced raid areas. The overlay is still
 * restricted to these regions so normal-world potions are never highlighted.
 */
public enum RaidRegion
{
    // Chambers of Xeric, including challenge-mode rooms.
    COX(12889, 13136, 13137, 13138, 13139, 13140, 13141, 13145,
            13393, 13394, 13395, 13396, 13397, 13401),

    // Theatre of Blood, including entry and hard-mode room regions.
    TOB(12611, 12612, 12613, 12867, 12869, 13122, 13123, 13125, 13379),

    // Tombs of Amascut, including the lobby/chest-room regions.
    TOA(14160, 14162, 14164, 14672, 14674, 14676, 15184, 15186,
            15188, 15696, 15698, 15700),

    // The Fractured Archive (Raids 4).
    TFA(16214, 16726, 16215, 16727, 16216, 16728, 16472, 16474);

    private static final Map<Integer, RaidRegion> REGION_MAP = new HashMap<>();

    static
    {
        for (RaidRegion raidRegion : values())
        {
            for (int regionId : raidRegion.regionIds)
            {
                REGION_MAP.put(regionId, raidRegion);
            }
        }
    }

    private final int[] regionIds;

    RaidRegion(int... regionIds)
    {
        this.regionIds = regionIds;
    }

    public static RaidRegion fromRegionId(int regionId)
    {
        return REGION_MAP.get(regionId);
    }
}
