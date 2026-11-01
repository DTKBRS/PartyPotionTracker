package com.PartyPotionTracker;

import java.util.Set;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Player;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.VarbitID;

/** Raid interiors only. Resolve instance template coordinates, not scene coordinates. */
enum RaidLocation {
    CHAMBERS_OF_XERIC(Set.of()),
    THEATRE_OF_BLOOD(Set.of(12613, 12869, 13125, 13122, 13123, 13379, 12612, 12611, 12867)),
    TOMBS_OF_AMASCUT(Set.of(14160, 15698, 15700, 14162, 14164, 15186, 15188,
            14674, 14676, 15184, 15696, 14672)),
    FRACTURED_ARCHIVE(Set.of(16471, 16214, 16727, 16216, 16728, 16215, 16726, 16472, 16474));

    private final Set<Integer> regions;

    RaidLocation(Set<Integer> regions) {
        this.regions = regions;
    }

    static RaidLocation current(Client client) {
        if (client.getGameState() != GameState.LOGGED_IN) return null;
        Player player = client.getLocalPlayer();
        if (player == null || player.getLocalLocation() == null || client.getWorldView(player.getLocalLocation().getWorldView()) == null) return null;
        int region = WorldPoint.fromLocalInstance(client, player.getLocalLocation()).getRegionID();
        return fromRegion(region, client.getVarbitValue(VarbitID.RAIDS_CLIENT_INDUNGEON) == 1);
    }

    static RaidLocation fromRegion(int region, boolean insideChambers) {
        if (insideChambers) return CHAMBERS_OF_XERIC;
        for (RaidLocation raid : values()) {
            if (raid.regions.contains(region)) return raid;
        }
        return null;
    }
}
