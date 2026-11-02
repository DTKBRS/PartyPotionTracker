package com.PartyPotionTracker;

import java.util.*;

/** Deterministic, client-free simulator for coordinator and inventory scenarios. */
final class RaidSupplySimulator {
    static final class PlayerState {
        final String name;
        int brews, restores, prayer;
        PlayerState(String name, int brews, int restores, int prayer) {
            this.name = name; this.brews = brews; this.restores = restores; this.prayer = prayer;
        }
        int total() { return brews + restores + prayer; }
    }

    private final Map<String, PlayerState> players = new LinkedHashMap<>();
    void join(String name, int brews, int restores, int prayer) {
        players.put(name, new PlayerState(name, brews, restores, prayer));
    }
    void drink(String name, String category) {
        PlayerState p = require(name);
        if ("brews".equals(category) && p.brews > 0) p.brews--;
        if ("restores".equals(category) && p.restores > 0) p.restores--;
        if ("prayer".equals(category) && p.prayer > 0) p.prayer--;
    }
    void leave(String name) { players.remove(name); }
    Collection<PlayerState> snapshot() { return Collections.unmodifiableCollection(new ArrayList<>(players.values())); }
    int total(String category) {
        return players.values().stream().mapToInt(p -> "brews".equals(category) ? p.brews : "restores".equals(category) ? p.restores : p.prayer).sum();
    }
    private PlayerState require(String name) {
        PlayerState p = players.get(name);
        if (p == null) throw new IllegalArgumentException("Unknown simulator player: " + name);
        return p;
    }
}
