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
import net.runelite.client.plugins.party.data.PartyData;
import net.runelite.client.ui.overlay.OverlayManager;
import java.awt.*;
import net.runelite.client.party.WSClient;




@PluginDependency(PartyPlugin.class)
@Slf4j
@PluginDescriptor(
	name = "Party Potion Tracker",
		description = "Tracks and highlights potions dropped in parties, and shows who picks them up (only works in ToA)",
		tags = {"potion", "party", "toa", "team", "tracker"}
)
public class PotionTrackerPlugin extends Plugin
{
	@Inject
	private WSClient wsClient;

	@Inject
	private Client client;

	@Inject
	private PartyService partyService;

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

	private Boolean inToa = false;


	@Provides
	PotionTrackerConfig provideConfig(ConfigManager configManager) {
		return configManager.getConfig(PotionTrackerConfig.class);
	}

	@Override
	protected void startUp() throws Exception
	{
		dropManager = new DropManager(client, partyService);
		pickupManager = new PickupManager(client, partyService, clientThread);
		wsClient.registerMessage(TrackedItemMessage.class);
		wsClient.registerMessage(PickedUpItemMessage.class);
		overlayManager.add(overlay);
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
		TrackedItem tracked = new TrackedItem(msg.getItemId(), msg.getWorldPoint(), msg.getOwnerName());
		pickupManager.myDroppedItems.add(tracked);
	}


	@Subscribe
	public void onMenuOptionClicked(MenuOptionClicked event) {
		if (inToa){
			if (event.getMenuOption().equalsIgnoreCase("Take")){
				pickupManager.menuOptionClicked(event);
			}
		}
	}

	@Subscribe
	public void onPickedUpItemMessage(PickedUpItemMessage msg) {
		if (inToa) {
			pickupManager.onPickedUpItemMessage(msg);
		}
	}


	@Subscribe
	public void onItemDespawned(ItemDespawned event) {
		if (inToa){
			TileItem item = event.getItem();
			WorldPoint location = event.getTile().getWorldLocation();

			TrackedItem matched = pickupManager.myDroppedItems.stream()
					.filter(i -> i.getItemId() == item.getId() && i.getWorldPoint().equals(location))
					.findFirst()
					.orElse(null);

			if (matched != null)
			{
				pickupManager.pendingDespawnItem.add(new PickupManager.PendingDespawn(matched)); // ✅ Delay removal
				log.info("Queued despawn of itemId {} at {} for delayed processing", item.getId(), location);
			}
		}
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged gameState) {
		if (gameState.getGameState().equals(GameState.LOADING)){
			pickupManager.pendingDespawnItem.clear();
			pickupManager.myDroppedItems.clear();
		}
	}

	@Subscribe
	public void onGameTick(GameTick tick) {
		LocalPoint lp = client.getLocalPlayer().getLocalLocation();
		int regionId = lp == null ? -1 : WorldPoint.fromLocalInstance(client, lp).getRegionID();
		ToaRegion currentRegion = ToaRegion.fromRegionID(regionId);
        inToa = currentRegion != null;

		if (inToa){
			pickupManager.gameTick();
		}
	}

	@Subscribe
	public void onItemSpawned(ItemSpawned event) {
		if (inToa){
			dropManager.itemSpawned(event);
		}
	}

	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged event) {
		pickupManager.itemContainerChanged(event);
	}

	public Color getColorForPartyMember(String name)
	{
		PartyData data = null;
		if (partyService != null && partyService.isInParty()){
			data = partyPluginService.getPartyData(partyService.getMemberByDisplayName(name).getMemberId());
		}

		if (data != null){
			return data.getColor();
		}

		return Color.BLACK;
	}
}
