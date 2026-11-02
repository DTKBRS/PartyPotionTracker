package com.PartyPotionTracker;

public enum SupplyHighlightStyle {
    DOT("Dot"),
    ITEM_OUTLINE("Potion outline"),
    TILE_OUTLINE("Tile outline"),
    TILE_FILL("Tile fill"),
    LABEL("Item label");

    private final String label;

    SupplyHighlightStyle(String label) {
        this.label = label;
    }

    @Override
    public String toString() {
        return label;
    }
}
