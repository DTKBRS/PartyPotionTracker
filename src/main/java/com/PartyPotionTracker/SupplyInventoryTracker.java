package com.PartyPotionTracker;

import net.runelite.api.*;
import net.runelite.client.party.*;

import java.util.*;

/** Client-thread adapter for inventory sharing. No coordinator is required. */
final class SupplyInventoryTracker {
    private final Client client;
    private final PartyService party;
    private final PotionTrackerConfig config;
    private final SupplyInventoryLedger ledger = new SupplyInventoryLedger();
    private String scope;
    private long partyId;
    private long localMemberId;
    private long sequence = System.currentTimeMillis();
    private int ticks;
    private volatile SupplyInventoryView view =
            SupplyInventoryView.inactive("Enter a raid to track supplies");

    SupplyInventoryTracker(Client client, PartyService party, PotionTrackerConfig config) {
        this.client = client;
        this.party = party;
        this.config = config;
    }

    SupplyInventoryView view() {
        return view;
    }

    private String currentScope() {
        RaidLocation raid = RaidLocation.current(client);
        if (raid == null || !raid.supported() || !party.isInParty()) return null;
        String identity = RaidIdentity.resolve(client, raid);
        if (identity != null) return identity;
        String code = config.raidSessionCode().trim();
        return code.isEmpty() || code.length() > 64
                ? null
                : client.getWorld() + ":" + raid.name() + ":" + code;
    }

    void tick() {
        String currentScope = currentScope();
        PartyMember local = party.getLocalMember();
        long currentLocalId = local == null ? 0 : local.getMemberId();
        if (!Objects.equals(scope, currentScope)
                || partyId != party.getPartyId()
                || localMemberId != currentLocalId) {
            ledger.clear();
            scope = currentScope;
            partyId = party.getPartyId();
            localMemberId = currentLocalId;
            ticks = 0;
        }
        if (scope == null || local == null) {
            view =
                    SupplyInventoryView.inactive(
                            RaidLocation.current(client) == null
                                    ? (RaidLocation.current(client) == RaidLocation.CHAMBERS_OF_XERIC
                                            ? "Chambers of Xeric is not supported"
                                            : "Enter a raid to track supplies")
                                    : "Join party and enter the raid lobby");
            return;
        }
        long now = System.nanoTime();
        Set<Long> members = new HashSet<>();
        for (PartyMember member : party.getMembers()) members.add(member.getMemberId());
        ledger.retainMembers(members);
        ItemContainer inventory = client.getItemContainer(InventoryID.INVENTORY);
        if (inventory != null) {
            SupplyInventory supplies = SupplyInventory.read(client, inventory);
            sequence = Math.max(sequence + 1, System.currentTimeMillis());
            ledger.update(localMemberId, supplies, sequence, now);
            // Complete snapshots let late joiners recover without requesting old deltas.
            if (ticks++ % 5 == 0) {
                InventorySupplyMessage message = new InventorySupplyMessage();
                message.scope = scope;
                message.sequence = sequence;
                message.doses = supplies.doses;
                message.bottles = supplies.bottles;
                try {
                    party.send(message);
                } catch (IllegalStateException disconnected) {
                    /* Remote reports age out naturally. */
                }
            }
        }
        List<SupplyInventoryView.Row> rows = new ArrayList<>();
        for (PartyMember member : party.getMembers()) {
            SupplyInventoryLedger.Entry entry = ledger.get(member.getMemberId());
            String name = member.getDisplayName();
            if (name == null) name = "Party member";
            boolean fresh = entry != null && entry.fresh(now) && member.isLoggedIn();
            rows.add(
                    new SupplyInventoryView.Row(
                            name,
                            entry == null ? "Unknown" : fresh ? "Current" : "Stale",
                            entry == null ? null : entry.inventory));
        }
        rows.sort(Comparator.comparing(row -> row.name, String.CASE_INSENSITIVE_ORDER));
        view = new SupplyInventoryView("Current reports only", rows);
    }

    void receive(InventorySupplyMessage message) {
        String current = currentScope();
        if (scope == null
                || !scope.equals(current)
                || partyId != party.getPartyId()
                || message.protocol != 1
                || !scope.equals(message.scope)
                || message.sequence <= 0
                || message.getMemberId() == localMemberId
                || party.getMemberById(message.getMemberId()) == null) return;
        SupplyInventory inventory = SupplyInventory.from(message);
        if (inventory != null)
            ledger.update(message.getMemberId(), inventory, message.sequence, System.nanoTime());
    }

    void clear() {
        ledger.clear();
        scope = null;
        view = SupplyInventoryView.inactive("Enter a raid to track supplies");
    }
}
