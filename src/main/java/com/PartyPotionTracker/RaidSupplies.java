package com.PartyPotionTracker;

import java.util.Arrays;
import java.util.Set;
import java.util.regex.Pattern;
import net.runelite.api.Client;
import net.runelite.api.ItemComposition;
import net.runelite.api.ItemID;

/** Shared supply classification, independent of raid names and region IDs. */
final class RaidSupplies {
    private static final Pattern DOSE_SUFFIX = Pattern.compile(".*\\([1-9][0-9]*\\)$");

    // Non-drinkable supplies need explicit IDs. Extend this set for exceptions
    // from any raid; ordinary potions are recognised from their item definitions.
    private static final Set<Integer> SPECIAL_SUPPLY_IDS = Set.of(
            ItemID.BLESSED_CRYSTAL_SCARAB_1, ItemID.BLESSED_CRYSTAL_SCARAB_2,
            ItemID.SILK_DRESSING_1, ItemID.SILK_DRESSING_2);

    private RaidSupplies() {}

    static boolean isTrackable(Client client, int itemId) {
        if (itemId < 0) return false;
        ItemComposition item = client.getItemDefinition(itemId);
        if (item == null || item.getNote() != -1) return false;
        return SPECIAL_SUPPLY_IDS.contains(itemId)
                || isDrinkableDose(item.getName(), item.getInventoryActions());
    }

    static boolean isDrinkableDose(String name, String[] actions) {
        return name != null && DOSE_SUFFIX.matcher(name).matches() && actions != null
                && Arrays.stream(actions).anyMatch("Drink"::equalsIgnoreCase);
    }
}
