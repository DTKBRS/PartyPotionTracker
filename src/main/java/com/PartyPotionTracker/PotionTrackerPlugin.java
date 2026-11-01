package com.PartyPotionTracker;

import javax.inject.Inject;
import com.google.inject.Provides;
import lombok.AccessLevel;
import lombok.Getter;
import net.runelite.api.*;
import net.runelite.api.events.*;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.party.PartyService;
import net.runelite.client.party.PartyMember;
import net.runelite.client.party.WSClient;
import net.runelite.client.plugins.*;
import net.runelite.client.plugins.party.PartyPlugin;
import net.runelite.client.plugins.party.PartyPluginService;
import net.runelite.client.plugins.party.data.PartyMemberData;
import net.runelite.client.ui.overlay.OverlayManager;
import java.awt.Color;
import java.util.*;

@PluginDependency(PartyPlugin.class)
@PluginDescriptor(name = "Party Potion Tracker",
        description = "Shares confirmed raid supplies through an explicitly selected party coordinator",
        tags = {"potion", "party", "toa", "cox", "tob", "raid", "team", "tracker"})
public class PotionTrackerPlugin extends Plugin {
    @Inject private WSClient wsClient;
    @Inject private Client client;
    @Inject private ConfigManager configManager;
    @Inject private PartyService partyService;
    @Getter(AccessLevel.PACKAGE) @Inject private PartyPluginService partyPluginService;
    @Inject private OverlayManager overlayManager;
    @Inject private PotionTrackerOverlay overlay;
    @Inject private SupplyStatusOverlay statusOverlay;
    @Inject private net.runelite.client.callback.ClientThread clientThread;
    @Getter @Inject private PotionTrackerConfig config;
    @Getter private PickupManager pickupManager;
    private DropManager dropManager;
    private long partyId;
    private RaidLocation trackedRaid;
    private int outsideRaidTicks;
    private SharedSupplyState shared;
    private String sharedScope;
    private String selectedName;
    private long projectedGeneration = -1;
    private long projectedRevision;
    private String stateStatus = "Choose a coordinator and raid session code";
    private volatile long contextGeneration;
    private boolean selectionBlocked;
    private long selfMemberId;

    @Provides PotionTrackerConfig provideConfig(ConfigManager manager) { return manager.getConfig(PotionTrackerConfig.class); }

    @Override protected void startUp() {
        trackedRaid = null; outsideRaidTicks = 0; partyId = partyService.getPartyId();
        pickupManager = new PickupManager(client, partyService);
        dropManager = new DropManager(client, partyService, pickupManager);
        pickupManager.coordinate(event -> {
            ensureSharedState();
            if (shared != null && !selectionBlocked) { shared.submit(SupplyReport.from(event)); project(true); }
        }, (id, tile) -> shared == null ? Collections.emptyList() : shared.pendingDropIds(id, tile));
        wsClient.registerMessage(SupplyStateMessage.class);
        overlayManager.add(overlay); overlayManager.add(statusOverlay);
    }
    @Override protected void shutDown() {
        clearTracking(); overlayManager.remove(overlay); overlayManager.remove(statusOverlay);
        wsClient.unregisterMessage(SupplyStateMessage.class);
    }
    @Subscribe public void onSupplyStateMessage(SupplyStateMessage message) {
        long generation = contextGeneration;
        clientThread.invokeLater(() -> {
            if (generation != contextGeneration || !isTrackingActive()) return;
            ensureSharedState();
            if (shared == null || selectionBlocked || !partyService.isInParty()) return;
            PartyMember sender = partyService.getMemberById(message.getMemberId());
            if (sender == null || sender.getDisplayName() == null || partyService.getLocalMember() == null
                    || sender.getMemberId() == partyService.getLocalMember().getMemberId()) return;
            if (message.report != null && !validSupply(message.report)) return;
            if (message.history != null && (message.history.size() > SharedSupplyState.CHUNK
                    || message.history.stream().anyMatch(r -> !validSupply(r)))) return;
            shared.receive(sender.getMemberId(), sender.getDisplayName(), message);
            project("UPDATE".equals(message.kind) || "REPORT".equals(message.kind));
        });
    }
    @Subscribe public void onMenuOptionClicked(MenuOptionClicked event) {
        if (isTrackingActive()) { ensureSharedState(); dropManager.menuOptionClicked(event); pickupManager.menuOptionClicked(event); }
    }
    @Subscribe public void onItemSpawned(ItemSpawned event) {
        if (isTrackingActive()) dropManager.itemSpawned(event);
    }
    @Subscribe public void onItemQuantityChanged(ItemQuantityChanged event) {
        if (isTrackingActive()) dropManager.itemQuantityChanged(event);
    }
    @Subscribe public void onItemContainerChanged(ItemContainerChanged event) {
        if (isTrackingActive()) { dropManager.itemContainerChanged(event); pickupManager.itemContainerChanged(event); }
    }
    @Subscribe public void onGameStateChanged(GameStateChanged event) {
        if (event.getGameState() == GameState.LOADING) {
            pickupManager.suspendObservation(); dropManager.clear();
            if (shared != null) shared.disconnected();
        }
        if (event.getGameState() == GameState.LOGIN_SCREEN || event.getGameState() == GameState.HOPPING) {
            clearTracking(); trackedRaid = null; outsideRaidTicks = 0;
        }
    }
    @Subscribe public void onGameTick(GameTick tick) {
        if (partyId != partyService.getPartyId()) { clearTracking(); partyId = partyService.getPartyId(); }
        if (client.getGameState() != GameState.LOGGED_IN || client.getLocalPlayer() == null) return;
        if (isTrackingActive()) {
            ensureSharedState();
            if (shared != null && !selectionBlocked) {
                if (partyService.getMemberById(shared.leader()) == null) shared.disconnected();
                shared.tick(); project(false);
            }
            dropManager.gameTick(); pickupManager.gameTick();
        } else {
            pickupManager.suspendObservation(); dropManager.clear();
            if (++outsideRaidTicks >= 3 && trackedRaid != null) { clearTracking(); trackedRaid = null; }
        }
    }
    @Subscribe public void onCommandExecuted(CommandExecuted event) {
        if (!"ppt".equalsIgnoreCase(event.getCommand()) || !isTrackingActive()) return;
        ensureSharedState();
        String[] args = event.getArguments();
        if (shared == null || args.length == 0) { chat(trackingStatus()); return; }
        if ("handoff".equalsIgnoreCase(args[0]) && args.length > 1) {
            String targetName = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
            PartyMember target = partyService.getMemberByDisplayName(targetName);
            if (target == null) { chat("Handoff target must be in your RuneLite party."); return; }
            shared.handoff(target.getMemberId());
        } else if ("cancel".equalsIgnoreCase(args[0])) shared.cancelHandoff();
        chat(trackingStatus());
    }
    private void chat(String text) { client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", "Party supplies: " + text, null); }
    private void clearTracking() {
        pickupManager.clear(); dropManager.clear(); shared = null; sharedScope = null; selectedName = null;
        projectedGeneration = -1; projectedRevision = 0; selectionBlocked = false; contextGeneration++;
    }
    boolean isInsideRaid() { return RaidLocation.current(client) != null; }
    boolean hasFreshSharedState() { return shared != null && !selectionBlocked && shared.synchronizedState(); }
    String trackingStatus() { return shared == null || selectionBlocked ? stateStatus : shared.status(); }

    private boolean isTrackingActive() {
        if (partyId != partyService.getPartyId()) { clearTracking(); partyId = partyService.getPartyId(); }
        RaidLocation current = RaidLocation.current(client);
        if (current == null) return false;
        if (trackedRaid != current) { clearTracking(); trackedRaid = current; }
        outsideRaidTicks = 0; return true;
    }
    private void ensureSharedState() {
        if (config == null) return;
        String name = config.coordinatorName().trim();
        String code = config.raidSessionCode().trim();
        PartyMember local = partyService.getLocalMember();
        if (trackedRaid == null || name.isEmpty() || code.isEmpty() || code.length() > 64 || !partyService.isInParty()) {
            if (shared != null) { pickupManager.clear(); dropManager.clear(); shared = null; contextGeneration++; }
            stateStatus = "Set coordinator + session code; join RuneLite party"; return;
        }
        String scope = client.getWorld() + ":" + trackedRaid.name() + ":" + code;
        if (local == null) { if (shared != null) shared.disconnected(); stateStatus = "Waiting for party connection"; return; }
        PartyMember selected = partyService.getMemberByDisplayName(name);
        if (shared != null && !scope.equals(sharedScope)) {
            pickupManager.clear(); dropManager.clear(); shared = null; contextGeneration++;
        }
        if (shared != null && selfMemberId != local.getMemberId()) {
            selectionBlocked = true; stateStatus = "Party identity changed: use a fresh session code"; return;
        }
        if (shared != null && scope.equals(sharedScope) && name.equalsIgnoreCase(selectedName)) { selectionBlocked = false; return; }
        if (shared != null && scope.equals(sharedScope) && selected != null && selected.getMemberId() == shared.leader()) {
            selectedName = name; selectionBlocked = false; return; // Persist the selection after an authenticated handoff.
        }
        if (shared != null && scope.equals(sharedScope)) {
            stateStatus = "Coordinator changed: use handoff or a fresh session code";
            selectionBlocked = true; return;
        }
        if (selected == null) { stateStatus = "Waiting for selected coordinator"; return; }
        if (selected.getMemberId() == local.getMemberId() && previouslyCoordinated(scope)) {
            stateStatus = "Coordinator restarted: choose a fresh raid session code"; return;
        }
        pickupManager.clear(); dropManager.clear(); projectedGeneration = -1; projectedRevision = 0;
        sharedScope = scope; selectedName = name; selfMemberId = local.getMemberId(); selectionBlocked = false; contextGeneration++;
        shared = new SharedSupplyState(local.getMemberId(), selected.getMemberId(), scope, this::sendState);
        if (shared.leader() == local.getMemberId()) rememberCoordinatorScope(scope);
    }
    private String scopeFingerprint(String scope) {
        return com.google.common.hash.Hashing.sha256().hashString(partyId + ":" + scope,
                java.nio.charset.StandardCharsets.UTF_8).toString();
    }
    private boolean previouslyCoordinated(String scope) {
        if (configManager == null) return false;
        String saved = configManager.getConfiguration("PotionTrackerConfig", "coordinatedScopes");
        return saved != null && Arrays.asList(saved.split(",")).contains(scopeFingerprint(scope));
    }
    private void rememberCoordinatorScope(String scope) {
        if (configManager == null || previouslyCoordinated(scope)) return;
        String saved = configManager.getConfiguration("PotionTrackerConfig", "coordinatedScopes");
        List<String> scopes = new ArrayList<>();
        if (saved != null && !saved.isEmpty()) scopes.addAll(Arrays.asList(saved.split(",")));
        scopes.add(scopeFingerprint(scope));
        while (scopes.size() > 100) scopes.remove(0);
        configManager.setConfiguration("PotionTrackerConfig", "coordinatedScopes", String.join(",", scopes));
    }
    private boolean validSupply(SupplyReport report) {
        return report != null && report.valid() && report.getItemId() < client.getItemCount()
                && RaidSupplies.isTrackable(client, report.getItemId());
    }
    private void sendState(SupplyStateMessage message) {
        if (!partyService.isInParty()) return;
        try { partyService.send(message); if (shared != null) shared.transportRestored(); }
        catch (IllegalStateException ex) { if (shared != null) shared.transportFailed(); }
    }
    private void project(boolean announce) {
        if (shared == null || projectedGeneration == shared.generation()) return;
        if (shared.leader() == selfMemberId && shared.synchronizedState()) rememberCoordinatorScope(sharedScope);
        pickupManager.replaceSharedHistory(shared.history(), announce && projectedRevision + 1 == shared.revision());
        projectedGeneration = shared.generation(); projectedRevision = shared.revision();
    }
    public Color getColorForPartyMember(String name) {
        PartyMember member = partyService.isInParty() ? partyService.getMemberByDisplayName(name) : null;
        PartyMemberData data = member == null ? null : partyPluginService.getPartyData(member.getMemberId());
        return data == null || data.getColor() == null ? Color.WHITE : data.getColor();
    }
}
