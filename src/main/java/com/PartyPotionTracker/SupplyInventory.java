package com.PartyPotionTracker;

import net.runelite.api.*;

import java.util.*;
import java.util.regex.*;

/** Immutable inventory summary. Standard potions and raid variants never share a total. */
final class SupplyInventory {
    static final String BREWS = "Saradomin brew";
    static final String RESTORES = "Super restore";
    static final String PRAYER = "Prayer potion";
    private static final Pattern DOSES = Pattern.compile("^(.+?)\\s*\\(([1-9][0-9]*)\\)$");
    final Map<String, Integer> doses;
    final Map<String, Integer> bottles;

    SupplyInventory(Map<String, Integer> doses, Map<String, Integer> bottles) {
        this.doses = Collections.unmodifiableMap(new TreeMap<>(doses));
        this.bottles = Collections.unmodifiableMap(new TreeMap<>(bottles));
    }

    static SupplyInventory read(Client client, ItemContainer inventory) {
        Map<String, Integer> doses = new TreeMap<>();
        Map<String, Integer> bottles = new TreeMap<>();
        for (Item item : inventory.getItems()) {
            if (item == null || item.getId() < 0 || item.getQuantity() <= 0) continue;
            ItemComposition definition = client.getItemDefinition(item.getId());
            if (definition == null
                    || definition.getNote() != -1
                    || !RaidSupplies.isDrinkableDose(
                            definition.getName(), definition.getInventoryActions())) continue;
            Matcher match = DOSES.matcher(definition.getName());
            if (!match.matches()) continue;
            int quantity = Integer.parseInt(match.group(2));
            doses.merge(match.group(1), quantity * item.getQuantity(), Integer::sum);
            bottles.merge(definition.getName(), item.getQuantity(), Integer::sum);
        }
        return new SupplyInventory(doses, bottles);
    }

    static SupplyInventory from(InventorySupplyMessage message) {
        if (!validCounts(message.doses, 1000) || !validCounts(message.bottles, 28)) return null;
        return new SupplyInventory(message.doses, message.bottles);
    }

    private static boolean validCounts(Map<String, Integer> counts, int maximum) {
        return counts != null
                && counts.size() <= 28
                && counts.entrySet().stream()
                        .allMatch(
                                entry ->
                                        entry.getKey() != null
                                                && !entry.getKey().isEmpty()
                                                && entry.getKey().length() <= 80
                                                && entry.getValue() != null
                                                && entry.getValue() > 0
                                                && entry.getValue() <= maximum);
    }

    int doses(String potion) {
        return doses.getOrDefault(potion, 0);
    }

    String bottleSummary() {
        List<String> parts = new ArrayList<>();
        bottles.forEach((name, count) -> parts.add(count + " × " + name));
        return parts.isEmpty() ? "No drinkable potions carried" : String.join(", ", parts);
    }
}
