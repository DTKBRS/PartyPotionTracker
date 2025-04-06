package com.PartyPotionTracker;

import javax.inject.Inject;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.*;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.party.PartyService;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayManager;
import java.awt.*;
import java.util.*;
import java.util.List;
import net.runelite.client.party.WSClient;




@Slf4j
@PluginDescriptor(
	name = "Party Potion Tracker",
		description = "Tracks and highlights potions in parties and who picks them up",
		tags = {"potion", "party", "toa", "team", "tracker"}
)
public class PotionTrackerPlugin extends Plugin
{
	private static class PendingDespawn {
		final TrackedItem item;
		int ageTicks = 0;

		PendingDespawn(TrackedItem item) {
			this.item = item;
		}
	}

	@Inject
	private WSClient wsClient;

	@Inject
	private Client client;

	@Inject
	private PartyService partyService;

	@Inject private OverlayManager overlayManager;

	@Inject private PotionTrackerOverlay overlay;

	@Inject
	private net.runelite.client.callback.ClientThread clientThread;

	@Getter
	private PickupManager pickupManager;
	private DropManager dropManager;

	private final Map<String, Color> partyColorMap = new HashMap<>();
	private final Color[] colorPool = {
			Color.CYAN, Color.MAGENTA, Color.ORANGE, Color.GREEN, Color.PINK, Color.YELLOW, Color.RED, Color.BLUE
	};

	private Boolean inToa = false;
	private int colorIndex = 0;

	private final List<PendingDespawn> pendingDespawnItem = new ArrayList<>();

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
		dropManager.pendingDrops.clear();
		pendingDespawnItem.clear();
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
			dropManager.menuOptionClicked(event);
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
			dropManager.pendingDrops.clear();
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
		if (partyColorMap.containsKey(name)) {
			return partyColorMap.get(name);
		}

		Color assigned = colorPool[colorIndex % colorPool.length];
		partyColorMap.put(name, assigned);
		colorIndex++;
		return assigned;
	}
}
