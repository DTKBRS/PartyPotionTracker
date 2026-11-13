package com.PartyPotionTracker;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigSection;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.Range;

@ConfigGroup("PotionTrackerConfig")
public interface PotionTrackerConfig extends Config{
    String HIGHLIGHT_SECTION = "highlightSection";

    @ConfigSection(
            name = "Potion highlighting",
            description = "Controls which ground potions are highlighted and how they look",
            position = 0
    )
    String highlightSection = "highlightSection";

    @ConfigItem(
            keyName = "highlightOwnPotions",
            name = "Highlight my potions",
            description = "Highlight potions dropped by you",
            section = HIGHLIGHT_SECTION,
            position = 1
    )
    default boolean highlightOwnPotions(){
        return true;
    }

    @ConfigItem(
            keyName = "highlightPartyPotions",
            name = "Highlight party potions",
            description = "Highlight potions associated with a party member",
            section = HIGHLIGHT_SECTION,
            position = 2
    )
    default boolean highlightPartyPotions(){
        return true;
    }

    @ConfigItem(
            keyName = "highlightUnknownPotions",
            name = "Highlight unknown potions",
            description = "Highlight potion ground items whose owner is not known",
            section = HIGHLIGHT_SECTION,
            position = 3
    )
    default boolean highlightUnknownPotions(){
        return false;
    }

    @ConfigItem(
            keyName = "highlightStyle",
            name = "Highlight style",
            description = "How a potion is drawn on the ground",
            section = HIGHLIGHT_SECTION,
            position = 4
    )
    default HighlightStyle highlightStyle(){
        return HighlightStyle.TILE_OUTLINE;
    }

    @Range(min = 1, max = 6)
    @ConfigItem(
            keyName = "outlineWidth",
            name = "Outline width",
            description = "Width of the potion tile outline",
            section = HIGHLIGHT_SECTION,
            position = 5
    )
    default int outlineWidth(){
        return 2;
    }

    enum HighlightStyle
    {
        TILE_OUTLINE,
        CIRCLE,
        DOT;

        @Override
        public String toString()
        {
            switch (this)
            {
                case TILE_OUTLINE:
                    return "Tile outline";
                case CIRCLE:
                    return "Circle";
                case DOT:
                default:
                    return "Dot";
            }
        }
    }
}
