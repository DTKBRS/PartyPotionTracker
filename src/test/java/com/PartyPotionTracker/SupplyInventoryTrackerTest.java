package com.PartyPotionTracker;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import net.runelite.api.*;
import net.runelite.api.coords.LocalPoint;
import net.runelite.client.party.*;

import org.junit.*;

import java.util.*;

public class SupplyInventoryTrackerTest {
    private final Client client = mock(Client.class);
    private final PartyService party = mock(PartyService.class);
    private final PotionTrackerConfig config = mock(PotionTrackerConfig.class);
    private final PartyMember local = mock(PartyMember.class);
    private final PartyMember remote = mock(PartyMember.class);
    private SupplyInventoryTracker tracker;

    @Before
    public void setup() {
        Player player = mock(Player.class);
        WorldView world = mock(WorldView.class);
        when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
        when(client.getLocalPlayer()).thenReturn(player);
        when(client.getTopLevelWorldView()).thenReturn(world);
        when(client.getWorldView(anyInt())).thenReturn(world);
        when(player.getLocalLocation()).thenReturn(new LocalPoint(64, 64));
        when(world.getBaseX()).thenReturn((14160 >> 8) * 64);
        when(world.getBaseY()).thenReturn((14160 & 255) * 64);
        when(client.getWorld()).thenReturn(301);
        when(party.isInParty()).thenReturn(true);
        when(party.getPartyId()).thenReturn(10L);
        when(party.getLocalMember()).thenReturn(local);
        when(party.getMembers()).thenReturn(Arrays.asList(local, remote));
        when(local.getMemberId()).thenReturn(1L);
        when(local.getDisplayName()).thenReturn("You");
        when(local.isLoggedIn()).thenReturn(true);
        when(remote.getMemberId()).thenReturn(2L);
        when(remote.getDisplayName()).thenReturn("Alex");
        when(remote.isLoggedIn()).thenReturn(true);
        when(party.getMemberById(2L)).thenReturn(remote);
        when(config.raidSessionCode()).thenReturn("test");
        ItemContainer inventory = mock(ItemContainer.class);
        when(client.getItemContainer(InventoryID.INVENTORY)).thenReturn(inventory);
        when(inventory.getItems()).thenReturn(new Item[0]);
        tracker = new SupplyInventoryTracker(client, party, config);
        tracker.tick();
    }

    private InventorySupplyMessage report(String scope) {
        InventorySupplyMessage message = new InventorySupplyMessage();
        message.setMemberId(2L);
        message.scope = scope;
        message.sequence = 1;
        message.doses = Map.of(SupplyInventory.BREWS, 8);
        message.bottles = Map.of("Saradomin brew(4)", 2);
        return message;
    }

    @Test
    public void inventoriesSyncWithoutCoordinatorAndUnknownDoesNotMeanZero() {
        assertEquals(1, tracker.view().reportingMembers);
        assertTrue(
                tracker.view().rows.stream()
                        .anyMatch(row -> row.status.equals("Unknown") && row.inventory == null));
        tracker.receive(report("301:TOMBS_OF_AMASCUT:test"));
        tracker.tick();
        assertEquals("8", tracker.view().total(SupplyInventory.BREWS));
        assertEquals(2, tracker.view().reportingMembers);
        verify(config, never()).coordinatorName();
    }

    @Test
    public void rejectsDifferentWorldSessionAndUnknownSender() {
        tracker.receive(report("302:TOMBS_OF_AMASCUT:test"));
        tracker.receive(report("301:TOMBS_OF_AMASCUT:other"));
        InventorySupplyMessage stranger = report("301:TOMBS_OF_AMASCUT:test");
        stranger.setMemberId(99L);
        tracker.receive(stranger);
        tracker.tick();
        assertEquals("0", tracker.view().total(SupplyInventory.BREWS));
    }

    @Test
    public void departureAndSessionChangeRemoveRemoteSupplies() {
        tracker.receive(report("301:TOMBS_OF_AMASCUT:test"));
        tracker.tick();
        assertEquals("8", tracker.view().total(SupplyInventory.BREWS));
        when(party.getMembers()).thenReturn(List.of(local));
        tracker.tick();
        assertEquals("0", tracker.view().total(SupplyInventory.BREWS));
        when(config.raidSessionCode()).thenReturn("next");
        tracker.tick();
        tracker.receive(report("301:TOMBS_OF_AMASCUT:test"));
        assertEquals("0", tracker.view().total(SupplyInventory.BREWS));
    }

    @Test
    public void outsideRaidDoesNotPublishAndClearsView() {
        clearInvocations(party);
        when(client.getGameState()).thenReturn(GameState.LOGIN_SCREEN);
        tracker.tick();
        assertEquals(0, tracker.view().reportingMembers);
        verify(party, never()).send(any());
    }
}
