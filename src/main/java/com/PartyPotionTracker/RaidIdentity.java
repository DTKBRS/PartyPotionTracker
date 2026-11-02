package com.PartyPotionTracker;

import net.runelite.api.Client;
import net.runelite.api.gameval.VarClientID;
import net.runelite.api.gameval.VarPlayerID;

import java.util.*;

/** Builds a stable boundary from the game's raid roster instead of a typed session code. */
final class RaidIdentity {
    private RaidIdentity() {}

    static String resolve(Client client, RaidLocation raid) {
        if (raid == null) return null;
        List<String> names = new ArrayList<>();
        int base;
        int count = 8;
        int status;
        int controller;
        switch (raid) {
            case TOMBS_OF_AMASCUT:
                base = VarClientID.TOA_CLIENT_NAME0;
                status = client.getVarbitValue(net.runelite.api.gameval.VarbitID.TOA_CLIENT_PARTYSTATUS);
                controller = client.getVarpValue(VarPlayerID.TOA_MYCONTROLLER);
                break;
            case THEATRE_OF_BLOOD:
                base = VarClientID.TOB_CLIENT_NAME0;
                count = 5;
                status = client.getVarbitValue(net.runelite.api.gameval.VarbitID.TOB_CLIENT_PARTYSTATUS);
                controller = client.getVarpValue(VarPlayerID.TOB_MYCONTROLLER);
                break;
            case FRACTURED_ARCHIVE:
                base = VarClientID.TFA_CLIENT_NAME0;
                status = client.getVarbitValue(net.runelite.api.gameval.VarbitID.TFA_CLIENT_PARTYSTATUS);
                controller = client.getVarpValue(VarPlayerID.TFA_MYCONTROLLER);
                break;
            default:
                return null;
        }
        for (int i = 0; i < count; i++) {
            String name = client.getVarcStrValue(base + i);
            if (name != null && !name.trim().isEmpty()) names.add(name.trim().toLowerCase(Locale.ROOT));
        }
        if (names.isEmpty() || status == 0) return null;
        Collections.sort(names);
        return raid.name() + ":" + client.getWorld() + ":" + controller + ":" + String.join(",", names);
    }
}
