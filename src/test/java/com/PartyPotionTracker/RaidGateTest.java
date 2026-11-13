package com.PartyPotionTracker;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import net.runelite.api.*;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.events.*;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.party.PartyService;

import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Field;

public class RaidGateTest {
    private final Client client = mock(Client.class);
    private final WorldView world = mock(WorldView.class);
    private final Player player = mock(Player.class);
    private final PickupManager pickups = mock(PickupManager.class);
    private final DropManager drops = mock(DropManager.class);
    private final PartyService party = mock(PartyService.class);
    private final PotionTrackerPlugin plugin = new PotionTrackerPlugin();

    private void inject(String name, Object value) throws Exception {
        Field field = PotionTrackerPlugin.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(plugin, value);
    }

    @Before
    public void setup() throws Exception {
        inject("client", client);
        inject("pickupManager", pickups);
        when(pickups.getSessionId()).thenReturn("test-session");
        inject("dropManager", drops);
        inject("partyService", party);
        ClientThread thread = mock(ClientThread.class);
        doAnswer(
                        call -> {
                            ((Runnable) call.getArgument(0)).run();
                            return null;
                        })
                .when(thread)
                .invokeLater(any(Runnable.class));
        inject("clientThread", thread);
        when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
        when(client.getLocalPlayer()).thenReturn(player);
        when(client.getTopLevelWorldView()).thenReturn(world);
        when(client.getWorldView(anyInt())).thenReturn(world);
        when(player.getLocalLocation()).thenReturn(new LocalPoint(64, 64));
    }

    private void region(int id) {
        when(world.getBaseX()).thenReturn((id >> 8) * 64);
        when(world.getBaseY()).thenReturn((id & 255) * 64);
    }

    @Test
    public void blocksAllHandlersAndOverlayOutsideRaids() {
        region(12850);
        plugin.onItemSpawned(new ItemSpawned(mock(Tile.class), mock(TileItem.class)));
        plugin.onMenuOptionClicked(mock(MenuOptionClicked.class));
        plugin.onItemContainerChanged(new ItemContainerChanged(93, mock(ItemContainer.class)));
        plugin.onSupplyStateMessage(new SupplyStateMessage());
        assertFalse(plugin.isInsideRaid());
        verifyNoInteractions(drops);
        verify(pickups, never()).track(any());
        verify(pickups, never()).onTrackedItemMessage(any());
        verify(pickups, never()).onPickedUpItemMessage(any());
    }

    @Test
    public void acceptsLocalEventsInsideEachRaid() {
        for (int id : new int[] {12613, 14160, 16471}) {
            region(id);
            assertTrue(plugin.isInsideRaid());
            ItemSpawned spawn = new ItemSpawned(mock(Tile.class), mock(TileItem.class));
            plugin.onItemSpawned(spawn);
            verify(drops).itemSpawned(spawn);
        }
        when(client.getVarbitValue(net.runelite.api.gameval.VarbitID.RAIDS_CLIENT_INDUNGEON))
                .thenReturn(1);
        region(0);
        assertTrue(plugin.isInsideRaid());
    }

    @Test
    public void exitStopsImmediatelyAndThenClearsRecords() {
        region(14160);
        plugin.onGameTick(new GameTick());
        clearInvocations(pickups);
        region(12850);
        plugin.onItemSpawned(new ItemSpawned(mock(Tile.class), mock(TileItem.class)));
        assertFalse(plugin.isInsideRaid());
        for (int i = 0; i < 3; i++) plugin.onGameTick(new GameTick());
        verify(pickups).clear();
        verify(pickups, never()).gameTick();
        verify(drops, never()).itemSpawned(any());
    }

    @Test
    public void roomLoadingPreservesRecordsAndSuppressesActivity() {
        region(14160);
        plugin.onGameTick(new GameTick());
        clearInvocations(pickups);
        when(client.getGameState()).thenReturn(GameState.LOADING);
        GameStateChanged loading = new GameStateChanged();
        loading.setGameState(GameState.LOADING);
        plugin.onGameStateChanged(loading);
        for (int i = 0; i < 5; i++) plugin.onGameTick(new GameTick());
        assertFalse(plugin.isInsideRaid());
        verify(pickups, never()).clear();
        when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
        region(15188);
        plugin.onGameTick(new GameTick());
        verify(pickups, never()).clear();
        verify(pickups).gameTick();
    }

    @Test
    public void unsupportedChambersRaidIsNotTrackable() {
        when(client.getVarbitValue(net.runelite.api.gameval.VarbitID.RAIDS_CLIENT_INDUNGEON))
                .thenReturn(1);
        region(0);
        assertTrue(plugin.isInsideRaid());
        assertFalse(plugin.isSupportedRaid());
    }

    @Test
    public void quantityChangesOutsideRaidsCannotCreateDrops() {
        region(12850);
        plugin.onItemQuantityChanged(
                new ItemQuantityChanged(mock(TileItem.class), mock(Tile.class), 1, 2));
        verifyNoInteractions(drops);
    }

    @Test
    public void receivesRequestedCoordinatorSnapshotThroughPartyAdapter() throws Exception {
        region(14160);
        when(client.getWorld()).thenReturn(301);
        when(client.getItemCount()).thenReturn(100000);
        ItemComposition definition = mock(ItemComposition.class);
        when(definition.getNote()).thenReturn(-1);
        when(definition.getName()).thenReturn("Prayer potion(4)");
        when(definition.getInventoryActions()).thenReturn(new String[] {"Drink"});
        when(client.getItemDefinition(ItemID.PRAYER_POTION4)).thenReturn(definition);
        net.runelite.client.party.PartyMember local =
                mock(net.runelite.client.party.PartyMember.class);
        net.runelite.client.party.PartyMember coordinator =
                mock(net.runelite.client.party.PartyMember.class);
        when(local.getMemberId()).thenReturn(2L);
        when(coordinator.getMemberId()).thenReturn(1L);
        when(coordinator.getDisplayName()).thenReturn("Player1");
        when(party.getLocalMember()).thenReturn(local);
        when(party.isInParty()).thenReturn(true);
        when(party.getMemberByDisplayName("Player1")).thenReturn(coordinator);
        when(party.getMemberById(1)).thenReturn(coordinator);
        PotionTrackerConfig config = mock(PotionTrackerConfig.class);
        when(config.coordinatorName()).thenReturn("Player1");
        when(config.raidSessionCode()).thenReturn("run-code");
        inject("config", config);
        plugin.onGameTick(new GameTick());
        org.mockito.ArgumentCaptor<SupplyStateMessage> messages =
                org.mockito.ArgumentCaptor.forClass(SupplyStateMessage.class);
        verify(party).send(messages.capture());
        SupplyStateMessage request = messages.getValue();
        assertEquals("REQUEST", request.kind);
        assertFalse(plugin.hasFreshSharedState());
        SupplyStateMessage snapshot = new SupplyStateMessage();
        snapshot.setMemberId(1);
        snapshot.scope = request.scope;
        snapshot.target = 2;
        snapshot.kind = "SNAPSHOT";
        snapshot.term = "authority-term";
        snapshot.request = request.request;
        snapshot.parts = 1;
        snapshot.revision = 1;
        snapshot.history =
                java.util.Collections.singletonList(
                        SupplyReport.from(
                                new TrackedItemMessage(
                                        ItemID.PRAYER_POTION4,
                                        new net.runelite.api.coords.WorldPoint(3200, 3200, 0),
                                        "Player1")));
        plugin.onSupplyStateMessage(snapshot);
        assertTrue(plugin.hasFreshSharedState());
        verify(pickups).replaceSharedHistory(eq(snapshot.history), eq(false));
        when(config.coordinatorName()).thenReturn("Different name");
        plugin.onGameTick(new GameTick());
        assertFalse(plugin.hasFreshSharedState());
        assertEquals("Waiting for party supplies", plugin.trackingStatus());
    }

    @Test
    public void coordinatorCannotRestartAnEmptyAuthorityUnderAUsedCode() throws Exception {
        region(14160);
        when(client.getWorld()).thenReturn(301);
        net.runelite.client.party.PartyMember local =
                mock(net.runelite.client.party.PartyMember.class);
        when(local.getMemberId()).thenReturn(1L);
        when(party.getLocalMember()).thenReturn(local);
        when(party.isInParty()).thenReturn(true);
        when(party.getMemberByDisplayName("Player1")).thenReturn(local);
        when(party.getMemberById(1)).thenReturn(local);
        PotionTrackerConfig config = mock(PotionTrackerConfig.class);
        when(config.coordinatorName()).thenReturn("Player1");
        when(config.raidSessionCode()).thenReturn("guard-run-1");
        inject("config", config);
        net.runelite.client.config.ConfigManager configs =
                mock(net.runelite.client.config.ConfigManager.class);
        java.util.Map<String, String> saved = new java.util.HashMap<>();
        when(configs.getConfiguration("PotionTrackerConfig", "coordinatedScopes"))
                .thenAnswer(call -> saved.get("scopes"));
        doAnswer(
                        call -> {
                            saved.put("scopes", call.getArgument(2).toString());
                            return null;
                        })
                .when(configs)
                .setConfiguration(eq("PotionTrackerConfig"), eq("coordinatedScopes"), any());
        inject("configManager", configs);
        plugin.onGameTick(new GameTick());
        assertTrue(plugin.hasFreshSharedState());
        assertNotNull(saved.get("scopes"));
        GameStateChanged hop = new GameStateChanged();
        hop.setGameState(GameState.HOPPING);
        plugin.onGameStateChanged(hop);
        plugin.onGameTick(new GameTick());
        assertFalse(plugin.hasFreshSharedState());
        assertEquals("Waiting for party supplies", plugin.trackingStatus());
        when(config.raidSessionCode()).thenReturn("guard-run-2");
        plugin.onGameTick(new GameTick());
        assertTrue(plugin.hasFreshSharedState());
    }

    private PotionTrackerConfig setupAutomaticParty() throws Exception {
        region(14160);
        when(client.getWorld()).thenReturn(301);
        net.runelite.client.party.PartyMember local =
                mock(net.runelite.client.party.PartyMember.class);
        when(local.getMemberId()).thenReturn(2L);
        when(local.getDisplayName()).thenReturn("Player2");
        when(local.isLoggedIn()).thenReturn(true);
        when(party.getLocalMember()).thenReturn(local);
        when(party.isInParty()).thenReturn(true);
        when(party.getMemberById(2)).thenReturn(local);
        when(party.getMembers()).thenReturn(java.util.List.of(local));
        PotionTrackerConfig config = mock(PotionTrackerConfig.class);
        when(config.automaticCoordinator()).thenReturn(true);
        when(config.raidSessionCode()).thenReturn("auto-run");
        inject("config", config);
        return config;
    }

    @Test
    public void automaticallyElectsWithoutCoordinatorNameAndFlushesEarlyDrop() throws Exception {
        PotionTrackerConfig config = setupAutomaticParty();
        plugin.onGameTick(new GameTick());
        assertFalse(plugin.hasFreshSharedState());
        TrackedItemMessage drop =
                new TrackedItemMessage(
                        ItemID.PRAYER_POTION4,
                        new net.runelite.api.coords.WorldPoint(3200, 3200, 0),
                        "Player2");
        plugin.submitLocalSupplyEvent(drop);
        verify(pickups, never()).replaceSharedHistory(any(), anyBoolean());
        for (int i = 0; i < 40; i++) plugin.onGameTick(new GameTick());
        assertTrue(plugin.hasFreshSharedState());
        org.mockito.ArgumentCaptor<java.util.List<SupplyReport>> history =
                org.mockito.ArgumentCaptor.forClass(java.util.List.class);
        verify(pickups).replaceSharedHistory(history.capture(), eq(false));
        assertEquals(drop.getEventId(), history.getValue().get(0).getId());
    }

    @Test
    public void joinsAdvertisedCoordinatorAndPromotesReplacementWhenHeartbeatExpires() throws Exception {
        setupAutomaticParty();
        net.runelite.client.party.PartyMember coordinator =
                mock(net.runelite.client.party.PartyMember.class);
        when(coordinator.getMemberId()).thenReturn(1L);
        when(coordinator.getDisplayName()).thenReturn("Player1");
        when(coordinator.isLoggedIn()).thenReturn(true);
        when(party.getMemberById(1)).thenReturn(coordinator);
        net.runelite.client.party.PartyMember local = party.getLocalMember();
        when(party.getMembers()).thenReturn(java.util.List.of(local, coordinator));
        plugin.onGameTick(new GameTick());
        CoordinatorPresenceMessage presence = new CoordinatorPresenceMessage();
        presence.setMemberId(1);
        presence.scope = "301:TOMBS_OF_AMASCUT:auto-run";
        presence.sequence = 1;
        presence.proposal = "1,2";
        presence.coordinator = 1;
        plugin.onCoordinatorPresenceMessage(presence);
        java.util.List<SupplyStateMessage> requests = new java.util.ArrayList<>();
        doAnswer(
                        call -> {
                            Object message = call.getArgument(0);
                            if (message instanceof SupplyStateMessage)
                                requests.add((SupplyStateMessage) message);
                            return null;
                        })
                .when(party)
                .send(any());
        plugin.onGameTick(new GameTick());
        SupplyStateMessage request =
                requests.stream()
                        .filter(message -> "REQUEST".equals(message.kind))
                        .findFirst()
                        .get();
        SupplyStateMessage snapshot = new SupplyStateMessage();
        snapshot.setMemberId(1);
        snapshot.scope = request.scope;
        snapshot.target = 2;
        snapshot.kind = "SNAPSHOT";
        snapshot.term = "existing-authority";
        snapshot.request = request.request;
        snapshot.parts = 1;
        snapshot.history = java.util.Collections.emptyList();
        plugin.onSupplyStateMessage(snapshot);
        assertTrue(plugin.hasFreshSharedState());
        for (int i = 0; i < 60; i++) plugin.onGameTick(new GameTick());
        assertTrue(plugin.hasFreshSharedState());
        assertEquals("Party supplies are up to date", plugin.trackingStatus());
    }

    @Test
    public void automaticElectionRejectsOtherRunAndIncompatiblePresence() throws Exception {
        setupAutomaticParty();
        net.runelite.client.party.PartyMember other =
                mock(net.runelite.client.party.PartyMember.class);
        when(other.getMemberId()).thenReturn(1L);
        when(other.isLoggedIn()).thenReturn(true);
        when(party.getMemberById(1)).thenReturn(other);
        net.runelite.client.party.PartyMember local = party.getLocalMember();
        when(party.getMembers()).thenReturn(java.util.List.of(local, other));
        plugin.onGameTick(new GameTick());
        CoordinatorPresenceMessage presence = new CoordinatorPresenceMessage();
        presence.setMemberId(1);
        presence.scope = "302:TOMBS_OF_AMASCUT:auto-run";
        presence.sequence = 1;
        presence.proposal = "1,2";
        presence.coordinator = 1;
        plugin.onCoordinatorPresenceMessage(presence);
        presence.scope = "301:TOMBS_OF_AMASCUT:auto-run";
        presence.protocol = 99;
        plugin.onCoordinatorPresenceMessage(presence);
        for (int i = 0; i < 40; i++) plugin.onGameTick(new GameTick());
        assertTrue(plugin.hasFreshSharedState());
        assertEquals("Party supplies are up to date", plugin.trackingStatus());
    }

    @Test
    public void changingModeCannotOverrideAutomaticSessionGuard() throws Exception {
        PotionTrackerConfig config = setupAutomaticParty();
        for (int i = 0; i < 40; i++) plugin.onGameTick(new GameTick());
        assertTrue(plugin.hasFreshSharedState());
        when(config.automaticCoordinator()).thenReturn(false);
        plugin.onGameTick(new GameTick());
        assertTrue(plugin.hasFreshSharedState());
        when(config.automaticCoordinator()).thenReturn(true);
        plugin.onGameTick(new GameTick());
        assertTrue(plugin.hasFreshSharedState());
    }
}
