package com.PartyPotionTracker;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup("PotionTrackerConfig")
public interface PotionTrackerConfig extends Config {
    @ConfigItem(
            keyName = "usePotionHighlight",
            name = "Use potion highlight",
            description = "Whether to use the highlight feature or not")
    default boolean usePotionHighlight() {
        return true;
    }

    @ConfigItem(
            keyName = "showSupplyTotals",
            name = "Show carried supply totals",
            description = "Show current brew, restore and prayer doses on screen",
            position = 3)
    default boolean showSupplyTotals() {
        return true;
    }

    @ConfigItem(keyName = "showBrews", name = "Show brews", description = "Include brews in the supply table", position = 4)
    default boolean showBrews() { return true; }

    @ConfigItem(keyName = "showRestores", name = "Show restores", description = "Include restores in the supply table", position = 5)
    default boolean showRestores() { return true; }

    @ConfigItem(keyName = "showPrayer", name = "Show prayer potions", description = "Include prayer potions in the supply table", position = 6)
    default boolean showPrayer() { return true; }

    @ConfigItem(keyName = "lowSupplyThreshold", name = "Low supply threshold", description = "Warn when a player's combined tracked doses reach this value", position = 7)
    default int lowSupplyThreshold() { return 4; }

    @ConfigItem(
            keyName = "automaticCoordinator",
            name = "Automatic coordinator",
            description =
                    "Discover compatible players in this raid session and select a coordinator"
                        + " automatically",
            position = 0)
    default boolean automaticCoordinator() {
        return true;
    }

    @ConfigItem(
            keyName = "coordinatorName",
            name = "Manual coordinator name",
            description = "Only used when automatic coordinator selection is disabled",
            position = 1)
    default String coordinatorName() {
        return "";
    }

    @ConfigItem(
            keyName = "raidSessionCode",
            name = "Raid session code",
            description =
                    "Choose a fresh shared code for each raid run. All members must use the same"
                            + " code.",
            position = 2)
    default String raidSessionCode() {
        return "";
    }
}
