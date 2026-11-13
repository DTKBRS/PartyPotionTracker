package com.PartyPotionTracker;

import com.google.inject.Provides;

import lombok.AccessLevel;
import lombok.Getter;

import net.runelite.api.*;
import net.runelite.api.events.*;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.party.PartyMember;
import net.runelite.client.party.PartyService;
import net.runelite.client.party.WSClient;
import net.runelite.client.plugins.*;
import net.runelite.client.plugins.party.PartyPlugin;
import net.runelite.client.plugins.party.PartyPluginService;
import net.runelite.client.plugins.party.data.PartyMemberData;
import net.runelite.client.ui.overlay.OverlayManager;

import java.awt.Color;
import java.util.*;

import javax.inject.Inject;

@PluginDependency(PartyPlugin.class)
@PluginDescriptor(
        name = "Party Potion Tracker",
        description = "Tracks and highlights raid supplies for your party",
        tags = {"potion", "party", "toa", "cox", "tob", "raid", "team", "tracker"})
public class PotionTrackerPlugin extends Plugin {
    @Inject private net.runelite.client.ui.ClientToolbar clientToolbar;
    private SupplyInventoryTracker inventoryTracker;
    private SupplyInventoryPanel inventoryPanel;
    private net.runelite.client.ui.NavigationButton inventoryNavigation;
    @Inject private WSClient wsClient;
    @Inject private Client client;
    @Inject private ConfigManager configManager;
    @Inject private PartyService partyService;

    @Getter(AccessLevel.PACKAGE)
    @Inject
    private PartyPluginService partyPluginService;

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
    private CoordinatorElection election;
    private Boolean automaticMode;
    private final List<SupplyReport> reportsAwaitingElection = new ArrayList<>();
    private boolean discoveryQueueFull;
    private String sharedScope;
    private String selectedName;
    private long projectedGeneration = -1;
    private long projectedRevision;
    private String stateStatus = "Join a party and enter a supported raid";
    // Queued network callbacks from a previous party or raid must not affect the current session.
    private volatile long contextGeneration;
    private boolean selectionBlocked;
    private long selfMemberId;
    private boolean inventoryRefreshQueued;
    private final Map<String, SupplyEventRecord> observedEvents = new LinkedHashMap<>();

    @Provides
    PotionTrackerConfig provideConfig(ConfigManager manager) {
        return manager.getConfig(PotionTrackerConfig.class);
    }

    @Override
    protected void startUp() {
        trackedRaid = null;
        outsideRaidTicks = 0;
        partyId = partyService.getPartyId();
        pickupManager = new PickupManager(client, partyService);
        dropManager = new DropManager(client, partyService, pickupManager);
        pickupManager.coordinate(
                this::submitLocalSupplyEvent,
                (id, tile) -> {
                    List<String> ids =
                            shared == null ? new ArrayList<>() : shared.pendingDropIds(id, tile);
                    for (SupplyReport report : reportsAwaitingElection) {
                        if (report.isDrop()
                                && report.getItemId() == id
                                && report.getTile().equals(tile)) ids.add(report.getId());
                    }
                    return ids;
                });
        inventoryTracker = new SupplyInventoryTracker(client, partyService, config);
        SupplyInventoryTracker startedTracker = inventoryTracker;
        javax.swing.SwingUtilities.invokeLater(
                () -> {
                    if (inventoryTracker != startedTracker) return;
                    inventoryPanel = new SupplyInventoryPanel(config);
                    java.awt.image.BufferedImage icon =
                            new java.awt.image.BufferedImage(
                                    24, 24, java.awt.image.BufferedImage.TYPE_INT_ARGB);
                    java.awt.Graphics2D graphics = icon.createGraphics();
                    graphics.setColor(new Color(110, 180, 220));
                    graphics.fillRoundRect(5, 8, 14, 15, 6, 6);
                    graphics.setColor(new Color(210, 210, 220));
                    graphics.fillRect(9, 2, 6, 8);
                    graphics.dispose();
                    inventoryNavigation =
                            net.runelite.client.ui.NavigationButton.builder()
                                    .tooltip("Raid supplies")
                                    .icon(icon)
                                    .priority(7)
                                    .panel(inventoryPanel)
                                    .build();
                    clientToolbar.addNavigation(inventoryNavigation);
                });
        wsClient.registerMessage(CoordinatorPresenceMessage.class);
        wsClient.registerMessage(InventorySupplyMessage.class);
        wsClient.registerMessage(SupplyStateMessage.class);
        overlayManager.add(overlay);
        overlayManager.add(statusOverlay);
    }

    @Override
    protected void shutDown() {
        clearTracking();
        inventoryTracker = null;
        javax.swing.SwingUtilities.invokeLater(
                () -> {
                    if (inventoryNavigation != null)
                        clientToolbar.removeNavigation(inventoryNavigation);
                    inventoryNavigation = null;
                    inventoryPanel = null;
                });
        wsClient.unregisterMessage(CoordinatorPresenceMessage.class);
        wsClient.unregisterMessage(InventorySupplyMessage.class);
        overlayManager.remove(overlay);
        overlayManager.remove(statusOverlay);
        wsClient.unregisterMessage(SupplyStateMessage.class);
    }

    void submitLocalSupplyEvent(SupplyEventMessage event) {
        // Keep local highlights responsive while automatic coordination is still discovering the
        // raid roster. The coordinator later rebuilds this view from the accepted shared history.
        if (event instanceof TrackedItemMessage) pickupManager.onTrackedItemMessage((TrackedItemMessage) event);
        SupplyReport report = SupplyReport.from(event);
        rememberObservedEvent(report);
        ensureSharedState();
        if (shared != null && !selectionBlocked) {
            shared.submit(report);
            refreshTrackedSupplies(true);
        } else if (election != null && !election.blocked()) {
            if (reportsAwaitingElection.size() >= 100) {
                discoveryQueueFull = true;
                updateAutomaticCoordinator();
            } else reportsAwaitingElection.add(report);
        }
        refreshInventoryPanel();
    }

    @Subscribe
    public void onCoordinatorPresenceMessage(CoordinatorPresenceMessage message) {
        long generation = contextGeneration;
        clientThread.invokeLater(
                () -> {
                    if (generation != contextGeneration || !isTrackingActive()) return;
                    ensureSharedState();
                    PartyMember sender = partyService.getMemberById(message.getMemberId());
                    if (election == null || sender == null || !sender.isLoggedIn()) return;
                    election.receive(sender.getMemberId(), message);
                    updateAutomaticCoordinator();
                });
    }

    @Subscribe
    public void onInventorySupplyMessage(InventorySupplyMessage message) {
        long generation = contextGeneration;
        clientThread.invokeLater(
                () -> {
                    if (generation == contextGeneration && inventoryTracker != null)
                        inventoryTracker.receive(message);
                });
    }

    SupplyInventoryView inventoryView() {
        return inventoryTracker == null
                ? SupplyInventoryView.inactive("Tracking unavailable")
                : inventoryTracker.view();
    }

    List<SupplyEventRecord> eventLog() {
        List<SupplyEventRecord> result = new ArrayList<>();
        Set<String> knownIds = new HashSet<>();
        if (shared != null) {
            for (SupplyEventRecord event : shared.eventLog()) {
                result.add(withItemName(event));
                knownIds.add(event.eventId);
            }
        }
        // Show local observations while a party election or report acknowledgement is in flight.
        // Once accepted, the shared record wins and the ID is de-duplicated above.
        for (SupplyEventRecord event : observedEvents.values()) {
            if (knownIds.add(event.eventId)) result.add(withItemName(event));
        }
        return result;
    }

    private SupplyEventRecord withItemName(SupplyEventRecord event) {
        ItemComposition definition = client.getItemDefinition(event.itemId);
        String name = definition == null ? "Unknown supply" : definition.getName();
        return event.withItemName(name == null ? "Unknown supply" : name);
    }

    private void rememberObservedEvent(SupplyReport report) {
        if (report == null || !report.valid()) return;
        observedEvents.putIfAbsent(
                report.getId(),
                new SupplyEventRecord(0, report, "OBSERVED", System.currentTimeMillis()));
        while (observedEvents.size() > 100) observedEvents.remove(observedEvents.keySet().iterator().next());
    }

    private void refreshInventoryPanel() {
        SupplyInventoryTracker activeTracker = inventoryTracker;
        if (activeTracker == null || inventoryRefreshQueued) return;
        SupplyInventoryView view = activeTracker.view();
        List<SupplyEventRecord> events = eventLog();
        inventoryRefreshQueued = true;
        javax.swing.SwingUtilities.invokeLater(
                () -> {
                    try {
                        if (inventoryTracker == activeTracker && inventoryPanel != null)
                            inventoryPanel.render(view, events);
                    } finally {
                        inventoryRefreshQueued = false;
                    }
                });
    }

    @Subscribe
    public void onSupplyStateMessage(SupplyStateMessage message) {
        long generation = contextGeneration;
        clientThread.invokeLater(
                () -> {
                    if (generation != contextGeneration || !isTrackingActive()) return;
                    ensureSharedState();
                    if (shared == null || !partyService.isInParty()) return;
                    // A valid handoff commit may arrive after the successor's presence message.
                    if (election != null && !election.eligible(message.getMemberId())) return;
                    if (selectionBlocked && (election == null || !"COMMIT".equals(message.kind)))
                        return;
                    PartyMember sender = partyService.getMemberById(message.getMemberId());
                    if (sender == null
                            || sender.getDisplayName() == null
                            || partyService.getLocalMember() == null
                            || sender.getMemberId() == partyService.getLocalMember().getMemberId())
                        return;
                    if (message.report != null && !validSupply(message.report)) return;
                    if (message.history != null
                            && (message.history.size() > SharedSupplyState.SNAPSHOT_CHUNK_SIZE
                                    || message.history.stream().anyMatch(r -> !validSupply(r))))
                        return;
                    long previousCoordinator = shared.leader();
                    shared.receive(sender.getMemberId(), sender.getDisplayName(), message);
                    if (election != null && shared.leader() != previousCoordinator) {
                        election.acceptedHandoff(shared.leader());
                        updateAutomaticCoordinator();
                    }
                    refreshTrackedSupplies(
                            "UPDATE".equals(message.kind) || "REPORT".equals(message.kind));
                });
    }

    @Subscribe
    public void onMenuOptionClicked(MenuOptionClicked event) {
        if (isTrackingActive()) {
            ensureSharedState();
            dropManager.menuOptionClicked(event);
            pickupManager.menuOptionClicked(event);
        }
    }

    @Subscribe
    public void onItemSpawned(ItemSpawned event) {
        if (isTrackingActive()) dropManager.itemSpawned(event);
    }

    @Subscribe
    public void onItemQuantityChanged(ItemQuantityChanged event) {
        if (isTrackingActive()) dropManager.itemQuantityChanged(event);
    }

    @Subscribe
    public void onItemContainerChanged(ItemContainerChanged event) {
        if (isTrackingActive()) {
            dropManager.itemContainerChanged(event);
            pickupManager.itemContainerChanged(event);
        }
    }

    @Subscribe
    public void onGameStateChanged(GameStateChanged event) {
        if (event.getGameState() == GameState.LOADING) {
            pickupManager.suspendObservation();
            dropManager.clear();
            if (shared != null) shared.disconnected();
        }
        if (event.getGameState() == GameState.LOGIN_SCREEN
                || event.getGameState() == GameState.HOPPING) {
            clearTracking();
            trackedRaid = null;
            outsideRaidTicks = 0;
            if (inventoryTracker != null) inventoryTracker.clear();
            refreshInventoryPanel();
        }
    }

    @Subscribe
    public void onGameTick(GameTick tick) {
        if (client.getGameState() == GameState.LOADING) return;
        if (inventoryTracker != null) {
            inventoryTracker.tick();
            refreshInventoryPanel();
        }
        if (partyId != partyService.getPartyId()) {
            clearTracking();
            partyId = partyService.getPartyId();
        }
        if (client.getGameState() != GameState.LOGGED_IN || client.getLocalPlayer() == null) return;
        if (isTrackingActive()) {
            ensureSharedState();
            if (election != null) {
                Set<Long> members = new HashSet<>();
                for (PartyMember member : partyService.getMembers()) {
                    if (member.isLoggedIn()) members.add(member.getMemberId());
                }
                election.tick(members);
                updateAutomaticCoordinator();
            }
            if (shared != null && !selectionBlocked) {
                if (partyService.getMemberById(shared.leader()) == null) shared.disconnected();
                shared.tick();
                refreshTrackedSupplies(false);
            }
            dropManager.gameTick();
            pickupManager.gameTick();
        } else {
            pickupManager.suspendObservation();
            dropManager.clear();
            if (++outsideRaidTicks >= 3 && trackedRaid != null) {
                clearTracking();
                trackedRaid = null;
            }
        }
    }

    @Subscribe
    public void onCommandExecuted(CommandExecuted event) {
        if (!"ppt".equalsIgnoreCase(event.getCommand()) || !isTrackingActive()) return;
        ensureSharedState();
        String[] args = event.getArguments();
        if (shared == null || args.length == 0) {
            chat(trackingStatus());
            return;
        }
        if ("handoff".equalsIgnoreCase(args[0]) && args.length > 1) {
            String targetName = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
            PartyMember target = partyService.getMemberByDisplayName(targetName);
            if (target == null) {
                chat("Handoff target must be in your RuneLite party.");
                return;
            }
            if (election != null && !election.eligible(target.getMemberId())) {
                chat("Handoff target must be in the same raid.");
                return;
            }
            shared.handoff(target.getMemberId());
        } else if ("cancel".equalsIgnoreCase(args[0])) shared.cancelHandoff();
        chat(trackingStatus());
    }

    private void chat(String text) {
        client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", "Party supplies: " + text, null);
    }

    private void clearTracking() {
        pickupManager.clear();
        dropManager.clear();
        shared = null;
        election = null;
        reportsAwaitingElection.clear();
        discoveryQueueFull = false;
        sharedScope = null;
        selectedName = null;
        projectedGeneration = -1;
        projectedRevision = 0;
        selectionBlocked = false;
        observedEvents.clear();
        contextGeneration++;
    }

    boolean isInsideRaid() {
        return RaidLocation.current(client) != null;
    }

    boolean isSupportedRaid() {
        RaidLocation current = RaidLocation.current(client);
        return current != null && current.supported();
    }

    boolean hasFreshSharedState() {
        return shared != null && !selectionBlocked && shared.synchronizedState();
    }

    String trackingStatus() {
        if (trackedRaid == RaidLocation.CHAMBERS_OF_XERIC) return "Chambers of Xeric is not supported";
        if (trackedRaid == null) return "Enter a supported raid";
        if (!partyService.isInParty()) return "Tracking your supplies";
        if (shared == null || selectionBlocked) return "Waiting for party supplies";
        return "Party supplies are up to date";
    }

    private boolean isTrackingActive() {
        if (partyId != partyService.getPartyId()) {
            clearTracking();
            partyId = partyService.getPartyId();
        }
        RaidLocation current = RaidLocation.current(client);
        if (current == null || !current.supported()) {
            if (current == RaidLocation.CHAMBERS_OF_XERIC) stateStatus = "Chambers of Xeric is not supported";
            return false;
        }
        if (trackedRaid != current) {
            clearTracking();
            trackedRaid = current;
        }
        outsideRaidTicks = 0;
        return true;
    }

    private void ensureSharedState() {
        if (config == null) return;
        // Read legacy settings without exposing the old controls to new installations.
        if (!Objects.toString(config.coordinatorName(), "").trim().isEmpty()
                && !Objects.toString(config.raidSessionCode(), "").trim().isEmpty()) {
            ensureLegacyManualState();
            return;
        }
        automaticMode = true;
        ensureAutomaticElection();
    }

    /** Compatibility for configurations saved by pre-automatic versions; hidden from new users. */
    private void ensureLegacyManualState() {
        PartyMember local = partyService.getLocalMember();
        String name = Objects.toString(config.coordinatorName(), "").trim();
        String code = Objects.toString(config.raidSessionCode(), "").trim();
        if (trackedRaid == null || !partyService.isInParty() || local == null || code.isEmpty()) {
            stateStatus = "Join the RuneLite party and enter a raid";
            return;
        }
        PartyMember selected = partyService.getMemberByDisplayName(name);
        if (selected == null) {
            if (shared != null) {
                selectionBlocked = true;
                stateStatus = "Coordinator changed; waiting for the party supply list";
            } else stateStatus = "Waiting for the party coordinator";
            return;
        }
        String scope = client.getWorld() + ":" + trackedRaid.name() + ":" + code;
        if (shared != null && Objects.equals(scope, sharedScope)) {
            if (selectedName != null && !name.equalsIgnoreCase(selectedName)) {
                selectionBlocked = true;
                stateStatus = "Coordinator changed; waiting for the party supply list";
            }
            return;
        }
        if (selected.getMemberId() == local.getMemberId() && previouslyCoordinated(scope)) {
            stateStatus = "Coordinator restarted; enter a fresh raid identity";
            return;
        }
        sharedScope = scope;
        selfMemberId = local.getMemberId();
        selectedName = name;
        selectionBlocked = false;
        shared = new SharedSupplyState(selfMemberId, selected.getMemberId(), scope, this::sendState);
    }

    private void ensureAutomaticElection() {
        PartyMember local = partyService.getLocalMember();
        if (trackedRaid == null) {
            stateStatus = "Enter a supported raid";
            return;
        }
        if (!partyService.isInParty()) {
            // Local drop tracking and highlights do not require RuneLite Party. Only the shared
            // coordinator state is unavailable when nobody is connected.
            shared = null;
            election = null;
            sharedScope = null;
            selectionBlocked = false;
            stateStatus = "Local raid tracking";
            return;
        }
        if (local == null) {
            selectionBlocked = true;
            stateStatus = "Waiting for party connection";
            return;
        }
        String scope = RaidIdentity.resolve(client, trackedRaid);
        if (scope == null) {
            // Compatibility fallback for older test clients and older RuneLite builds. It is not
            // shown in settings and is never needed when the game exposes its raid roster.
            String legacyCode = Objects.toString(config.raidSessionCode(), "").trim();
            if (legacyCode.isEmpty() || legacyCode.length() > 64) {
                selectionBlocked = true;
                stateStatus = "Waiting for the in-game raid roster";
                return;
            }
            scope = client.getWorld() + ":" + trackedRaid.name() + ":" + legacyCode;
        }
        if (election != null && (!Objects.equals(scope, sharedScope) || selfMemberId != local.getMemberId()))
            clearTracking();
        if (election == null) {
            sharedScope = scope;
            selfMemberId = local.getMemberId();
            election =
                    new CoordinatorElection(
                            selfMemberId, scope, previouslyCoordinated(scope), this::sendPresence);
        }
        updateAutomaticCoordinator();
    }

    private void updateAutomaticCoordinator() {
        if (election == null) return;
        long coordinator = election.coordinator();
        selectionBlocked =
                election.blocked() || coordinator == 0 || !election.eligible(coordinator);
        stateStatus = election.status();
        if (discoveryQueueFull) {
            selectionBlocked = true;
            stateStatus = "Paused: too many supply updates are waiting";
        }
        if (coordinator != 0) rememberCoordinatorScope(sharedScope);
        if (selectionBlocked) return;
        if (shared == null) {
            shared = new SharedSupplyState(selfMemberId, coordinator, sharedScope, this::sendState);
            projectedGeneration = -1;
            projectedRevision = 0;
            for (SupplyReport report : reportsAwaitingElection) shared.submit(report);
            reportsAwaitingElection.clear();
        } else if (shared.leader() != coordinator) {
            if (coordinator == selfMemberId) shared.promoteToCoordinator();
            else shared.reassignCoordinator(coordinator);
            projectedGeneration = -1;
        }
        for (SupplyReport report : reportsAwaitingElection) shared.submit(report);
        reportsAwaitingElection.clear();
    }

    private void sendPresence(CoordinatorPresenceMessage message) {
        if (!partyService.isInParty()) return;
        try {
            partyService.send(message);
        } catch (IllegalStateException disconnected) {
            if (shared != null) shared.transportFailed();
        }
    }

    private String scopeFingerprint(String scope) {
        return com.google.common.hash.Hashing.sha256()
                .hashString(partyId + ":" + scope, java.nio.charset.StandardCharsets.UTF_8)
                .toString();
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
        configManager.setConfiguration(
                "PotionTrackerConfig", "coordinatedScopes", String.join(",", scopes));
    }

    private boolean validSupply(SupplyReport report) {
        return report != null
                && report.valid()
                && report.getItemId() < client.getItemCount()
                && RaidSupplies.isTrackable(client, report.getItemId());
    }

    private void sendState(SupplyStateMessage message) {
        if (!partyService.isInParty()) return;
        try {
            partyService.send(message);
            if (shared != null) shared.transportRestored();
        } catch (IllegalStateException ex) {
            if (shared != null) shared.transportFailed();
        }
    }

    // Rebuild the overlay's view only when shared history changes. Recovery must not replay old
    // chat.
    private void refreshTrackedSupplies(boolean announce) {
        if (shared == null || projectedGeneration == shared.generation()) return;
        if (shared.leader() == selfMemberId && shared.synchronizedState())
            rememberCoordinatorScope(sharedScope);
        pickupManager.replaceSharedHistory(
                shared.history(), announce && projectedRevision + 1 == shared.revision());
        projectedGeneration = shared.generation();
        projectedRevision = shared.revision();
    }

    public Color getColorForPartyMember(String name) {
        PartyMember member =
                partyService.isInParty() ? partyService.getMemberByDisplayName(name) : null;
        PartyMemberData data =
                member == null ? null : partyPluginService.getPartyData(member.getMemberId());
        return data == null || data.getColor() == null ? Color.WHITE : data.getColor();
    }
}
