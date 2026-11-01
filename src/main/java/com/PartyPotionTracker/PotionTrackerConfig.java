package com.PartyPotionTracker;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup("PotionTrackerConfig")
public interface PotionTrackerConfig extends Config{
    @ConfigItem(
            keyName = "usePotionHighlight",
            name="Use potion highlight",
            description = "Whether to use the highlight feature or not"
    )
    default boolean usePotionHighlight(){
        return true;
    }
    @ConfigItem(keyName = "coordinatorName", name = "Coordinator name",
            description = "All members select the same coordinator's in-game display name", position = 1)
    default String coordinatorName() { return ""; }

    @ConfigItem(keyName = "raidSessionCode", name = "Raid session code",
            description = "Choose a fresh shared code for each raid run. All members must use the same code.", position = 2)
    default String raidSessionCode() { return ""; }
}
