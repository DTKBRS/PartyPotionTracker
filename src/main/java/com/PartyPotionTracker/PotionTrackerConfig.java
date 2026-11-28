package com.PartyPotionTracker;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigSection;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.Range;

@ConfigGroup("PotionTrackerConfig")
public interface PotionTrackerConfig extends Config{
    String HIGHLIGHT_SECTION = "highlightSection";
    String PICKUP_SECTION = "pickupSection";

    @ConfigSection(
            name = "Potion highlighting",
            description = "Controls which ground potions are highlighted and how they look",
            position = 0
    )
    String highlightSection = "highlightSection";

    @ConfigSection(
            name = "Pickup behavior",
            description = "Controls which potion pickup actions are available",
            position = 1
    )
    String pickupSection = "pickupSection";

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
        return HighlightStyle.ITEM_OUTLINE;
    }

    @Range(min = 1, max = 6)
    @ConfigItem(
            keyName = "outlineWidth",
            name = "Outline width",
            description = "Width of the potion model outline and geometric markers",
            section = HIGHLIGHT_SECTION,
            position = 5
    )
    default int outlineWidth(){
        return 2;
    }

    @ConfigItem(
            keyName = "showIronmanIcons",
            name = "Show Ironman icons",
            description = "Show an Ironman icon beside potions owned by a known Ironman party member",
            section = HIGHLIGHT_SECTION,
            position = 6
    )
    default boolean showIronmanIcons(){
        return true;
    }

    @ConfigItem(
            keyName = "removeIronmanTakeOption",
            name = "Remove Take from Ironman potions",
            description = "Remove the Take option when a known Ironman party member owns the potion",
            section = PICKUP_SECTION,
            position = 1
    )
    default boolean removeIronmanTakeOption(){
        return false;
    }

    @ConfigItem(
            keyName = "showPickupMessages",
            name = "Show pickup messages",
            description = "Show a game message when another player picks up a tracked potion",
            section = PICKUP_SECTION,
            position = 2
    )
    default boolean showPickupMessages(){
        return true;
    }

    enum HighlightStyle
    {
        ITEM_OUTLINE,
        DOT,
        CORNERS;

        @Override
        public String toString()
        {
            switch (this)
            {
                case ITEM_OUTLINE:
                    return "Potion outline";
                case CORNERS:
                    return "Corner markers";
                case DOT:
                default:
                    return "Dot";
            }
        }
    }
}
