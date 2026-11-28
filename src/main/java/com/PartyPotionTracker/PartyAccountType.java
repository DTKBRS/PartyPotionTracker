package com.PartyPotionTracker;

/**
 * Account type reported by a Party Potion Tracker client.
 *
 * <p>The value is informational and self-reported. It is never used as proof
 * of who owns a ground item.</p>
 */
public enum PartyAccountType
{
    UNKNOWN(-1),
    NORMAL(0),
    IRONMAN(1),
    ULTIMATE_IRONMAN(2),
    HARDCORE_IRONMAN(3),
    GROUP_IRONMAN(4),
    HARDCORE_GROUP_IRONMAN(5),
    UNRANKED_GROUP_IRONMAN(6);

    private final int id;

    PartyAccountType(int id)
    {
        this.id = id;
    }

    public int getId()
    {
        return id;
    }

    public static PartyAccountType fromId(int id)
    {
        for (PartyAccountType type : values())
        {
            if (type.id == id)
            {
                return type;
            }
        }

        return UNKNOWN;
    }

    public boolean isIronman()
    {
        return this == IRONMAN || this == ULTIMATE_IRONMAN || this == HARDCORE_IRONMAN;
    }

    public boolean isGroupIronman()
    {
        return this == GROUP_IRONMAN || this == HARDCORE_GROUP_IRONMAN || this == UNRANKED_GROUP_IRONMAN;
    }
}
