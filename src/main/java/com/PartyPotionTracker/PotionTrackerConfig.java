package com.PartyPotionTracker;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import java.awt.Color;

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
}
