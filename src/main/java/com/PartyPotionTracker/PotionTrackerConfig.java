package com.PartyPotionTracker;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;
import net.runelite.client.config.Range;

@ConfigGroup("PotionTrackerConfig")
public interface PotionTrackerConfig extends Config {
    @ConfigSection(
            name = "Highlighting",
            description = "Choose which raid supplies are highlighted on the ground",
            position = 0)
    String HIGHLIGHT_SECTION = "highlight";

    @ConfigSection(
            name = "Panel",
            description = "Configure the raid supplies panel and warnings",
            position = 1)
    String PANEL_SECTION = "panel";

    @ConfigItem(
            keyName = "highlightOwnSupplies",
            name = "Highlight my supplies",
            description = "Show supplies identified as dropped by you",
            position = 1,
            section = HIGHLIGHT_SECTION)
    default boolean highlightOwnSupplies() {
        return true;
    }

    @ConfigItem(
            keyName = "highlightOtherSupplies",
            name = "Highlight other supplies",
            description = "Show supplies dropped by party members or with unknown ownership",
            position = 2,
            section = HIGHLIGHT_SECTION)
    default boolean highlightOtherSupplies() {
        return true;
    }

    @ConfigItem(
            keyName = "supplyHighlightStyle",
            name = "Supply highlight style",
            description = "How raid supplies appear on the ground",
            position = 3,
            section = HIGHLIGHT_SECTION)
    default SupplyHighlightStyle supplyHighlightStyle() {
        return SupplyHighlightStyle.DOT;
    }

    @ConfigItem(
            keyName = "potionOutlineWidth",
            name = "Potion outline width",
            description = "Set the width of the potion model outline",
            position = 4,
            section = HIGHLIGHT_SECTION)
    @Range(min = 1, max = 8)
    default int potionOutlineWidth() {
        return 4;
    }

    @ConfigItem(
            keyName = "lowSupplyThreshold",
            name = "Low supply warning",
            description = "Show a warning when a party member has this many tracked doses or fewer",
            position = 1,
            section = PANEL_SECTION)
    default int lowSupplyThreshold() {
        return 4;
    }

    @ConfigItem(
            keyName = "panelSortOrder",
            name = "Sort players by",
            description = "Choose the order used in the raid supplies panel",
            position = 2,
            section = PANEL_SECTION)
    default SupplyPanelSortOrder panelSortOrder() {
        return SupplyPanelSortOrder.NAME;
    }

    // Coordination is always automatic. These methods remain source-compatible with older
    // settings files, but are no longer shown as user-configurable options.
    @ConfigItem(keyName = "automaticCoordinator", name = "Automatic coordinator", description = "Legacy setting", hidden = true)
    default boolean automaticCoordinator() {
        return true;
    }

    @ConfigItem(keyName = "coordinatorName", name = "Coordinator name", description = "Legacy setting", hidden = true)
    default String coordinatorName() {
        return "";
    }

    @ConfigItem(keyName = "raidSessionCode", name = "Raid session code", description = "Legacy setting", hidden = true)
    default String raidSessionCode() {
        return "";
    }
}
