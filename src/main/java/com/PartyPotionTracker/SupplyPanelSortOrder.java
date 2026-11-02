package com.PartyPotionTracker;

public enum SupplyPanelSortOrder {
    NAME("Player name"),
    LOWEST_SUPPLIES("Lowest supplies first");

    private final String label;

    SupplyPanelSortOrder(String label) {
        this.label = label;
    }

    @Override
    public String toString() {
        return label;
    }
}
