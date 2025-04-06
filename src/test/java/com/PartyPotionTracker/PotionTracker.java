package com.PartyPotionTracker;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public class PotionTracker
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(PotionTrackerPlugin.class);
		RuneLite.main(args);
	}
}