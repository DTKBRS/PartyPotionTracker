package com.PartyPotionTracker;

import javax.inject.Inject;

import com.google.inject.Provides;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.*;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.party.PartyService;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDependency;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.party.PartyPlugin;
import net.runelite.client.plugins.party.PartyPluginService;
import net.runelite.client.plugins.party.data.PartyMemberData;
import net.runelite.client.ui.overlay.OverlayManager;
import java.awt.*;
import net.runelite.client.party.WSClient;




@PluginDependency(PartyPlugin.class)
@Slf4j
@PluginDescriptor(
	name = "Party Potion Tracker",
		 description = "Tracks and highlights raid supplies dropped in parties, and shows who picks them up",
		tags = {"potion", "party", "toa", "tob", "tfa", "cox", "team", "tracker"}
)
public class PotionTrackerPlugin extends Plugin
{
	private static final boolean DEBUG_LOGGING = Boolean.getBoolean("ppt.debug");

	@Inject
	private WSClient wsClient;

	@Inject
	private Client client;

	@Inject
	private PartyService partyService;

	@Inject
	private ConfigManager configManager;

	@Getter(AccessLevel.PACKAGE)
	@Inject
	private PartyPluginService partyPluginService;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private PotionTrackerOverlay overlay;

	@Inject
	private net.runelite.client.callback.ClientThread clientThread;

	@Getter
	@Inject
	private PotionTrackerConfig config;

	@Getter
	private PickupManager pickupManager;
	private DropManager dropManager;

	private boolean inRaid = false;
	private int lastRegionId = Integer.MIN_VALUE;
	private RaidRegion lastRaidRegion;


	@Provides
	PotionTrackerConfig provideConfig(ConfigManager configManager) {
		return configManager.getConfig(PotionTrackerConfig.class);
	}

	@Override
	protected void startUp() throws Exception
	{
		migrateRemovedHighlightStyles();
		dropManager = new DropManager(client, partyService);
		pickupManager = new PickupManager(client, partyService, clientThread);
		wsClient.registerMessage(TrackedItemMessage.class);
		wsClient.registerMessage(PickedUpItemMessage.class);
		overlayManager.add(overlay);
		debug("plugin started: highlightStyle={}, own={}, party={}, unknown={}",
				config.highlightStyle(), config.highlightOwnPotions(),
				config.highlightPartyPotions(), config.highlightUnknownPotions());
	}

	private void migrateRemovedHighlightStyles()
	{
		String savedStyle = configManager.getConfiguration("PotionTrackerConfig", "highlightStyle");
		if ("TILE_OUTLINE".equals(savedStyle)
				|| "TILE_FILL".equals(savedStyle)
				|| "CIRCLE".equals(savedStyle))
		{
			configManager.setConfiguration(
					"PotionTrackerConfig",
					"highlightStyle",
					PotionTrackerConfig.HighlightStyle.ITEM_OUTLINE.name());
		}
	}

	@Override
	protected void shutDown() throws Exception
	{

		// Clean up
		overlayManager.remove(overlay);
		wsClient.unregisterMessage(PickedUpItemMessage.class);
		wsClient.unregisterMessage(TrackedItemMessage.class);
	}

	@Subscribe
	public void onTrackedItemMessage(TrackedItemMessage msg) {
		if (!inRaid || msg.getWorldPoint() == null || !RaidPotions.isPotion(msg.getItemId())) {
			return;
		}

		pickupManager.trackDrop(new TrackedItem(msg.getItemId(), msg.getWorldPoint(), msg.getOwnerName()));
	}


	@Subscribe
	public void onMenuOptionClicked(MenuOptionClicked event) {
		debug("menu option: option={}, id={}, param0={}, param1={}, inRaid={}",
				event.getMenuOption(), event.getId(), event.getParam0(), event.getParam1(), inRaid);
		if (inRaid){
			pickupManager.menuOptionClicked(event);
		}
	}

	@Subscribe
	public void onPickedUpItemMessage(PickedUpItemMessage msg) {
		if (inRaid) {
			pickupManager.onPickedUpItemMessage(msg);
		}
	}

	@Subscribe
	public void onItemDespawned(ItemDespawned event) {
		if (inRaid) {
			pickupManager.itemDespawned(
					event.getItem().getId(),
					event.getTile().getWorldLocation());
		}
	}


	@Subscribe
	public void onGameStateChanged(GameStateChanged gameState) {
		if (gameState.getGameState().equals(GameState.LOADING)){
			pickupManager.clear();
		}
	}

	@Subscribe
	public void onGameTick(GameTick tick) {
		if (client.getLocalPlayer() == null) {
			if (inRaid) {
				pickupManager.clear();
			}
			inRaid = false;
			return;
		}

		LocalPoint lp = client.getLocalPlayer().getLocalLocation();
		WorldPoint mappedPoint = lp == null ? null : WorldPoint.fromLocalInstance(client, lp);
		int regionId = mappedPoint == null ? -1 : mappedPoint.getRegionID();
		RaidRegion currentRegion = RaidRegion.fromRegionId(regionId);
		if (DEBUG_LOGGING && (regionId != lastRegionId || currentRegion != lastRaidRegion))
		{
			debug("raid state check: mappedPoint={}, regionId={}, raidRegion={}, inInstancedRegion={}, mapRegions={}",
					mappedPoint,
					regionId,
					currentRegion,
					client.isInInstancedRegion(),
					client.getTopLevelWorldView() == null ? null : java.util.Arrays.toString(client.getTopLevelWorldView().getMapRegions()));
			lastRegionId = regionId;
			lastRaidRegion = currentRegion;
		}
		boolean wasInRaid = inRaid;
		inRaid = currentRegion != null;
		if (DEBUG_LOGGING && wasInRaid != inRaid)
		{
			debug("raid state changed: {} -> {}", wasInRaid, inRaid);
		}

		if (wasInRaid && !inRaid) {
			pickupManager.clear();
		}

		if (inRaid){
			pickupManager.gameTick();
		}
	}

	@Subscribe
	public void onItemSpawned(ItemSpawned event) {
		debug("item spawned event received: id={}, tile={}, inRaid={}",
				event.getItem() == null ? null : event.getItem().getId(),
				event.getTile() == null ? null : event.getTile().getWorldLocation(),
				inRaid);
		if (inRaid){
			TrackedItem trackedItem = dropManager.itemSpawned(event);
			if (trackedItem != null) {
				pickupManager.trackDrop(trackedItem);
			}
		}
	}

	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged event) {
		if (inRaid) {
			pickupManager.itemContainerChanged(event);
		}
	}

	public Color getColorForPartyMember(String name)
	{
		if (name == null) {
			return Color.LIGHT_GRAY;
		}

		PartyMemberData data = null;
		if (partyService != null && partyService.isInParty() && partyPluginService != null){
			net.runelite.client.party.PartyMember member = partyService.getMemberByDisplayName(name);
			if (member != null) {
				data = partyPluginService.getPartyData(member.getMemberId());
			}
		}

		if (data != null && data.getColor() != null){
			return data.getColor();
		}

		return Color.LIGHT_GRAY;
	}

	boolean isInRaid()
	{
		return inRaid;
	}

	private void debug(String message, Object... arguments)
	{
		if (DEBUG_LOGGING)
		{
			log.info("[PPT DEBUG] " + message, arguments);
		}
	}
}
