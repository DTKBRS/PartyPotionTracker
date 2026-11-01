# Party Potion Tracker

A RuneLite-only raid supply tracker with one explicitly selected coordinator.
It uses RuneLite's existing Party messages; there is no custom backend or additional
HTTP API. This is a development build requiring live multi-client validation.

## Start the development client

Use JDK 11:

```sh
cd ~/Desktop/PartyPotionTracker
bash gradlew test shadowJar
java -ea -jar build/libs/PartyPotionTracker-1.0-SNAPSHOT-all.jar
```

Assertions (`-ea`) are required by RuneLite's development launcher. This launches a
separate client; it does not publish anything to the Plugin Hub.

## Set up the group

1. Everyone runs this version, enables RuneLite **Party**, and joins the same party/world.
2. In Party Potion Tracker settings, everyone enters the same **Coordinator name**
   (that player's in-game display name).
3. Everyone enters the same fresh **Raid session code**, for example `sunday-run-1`.
   Use a different code for every raid run. The group must actually be in the same
   instance; the code establishes agreement, not automatic game-instance detection.
4. Enter the raid. The coordinator initializes the shared record. Other clients
   request a snapshot. The status panel shows coordinator/synchronized, revision,
   and pending-report count. Ground highlights are suppressed while syncing or stale.

Tracking runs only inside Chambers of Xeric, Theatre of Blood, Tombs of Amascut,
and The Fractured Archive. External lobbies are excluded; internal supply/loot rooms
are included. New raid interiors need an entry in `RaidLocation`.

`RaidSupplies` detects unnoted drinkable items with dose suffixes, plus explicit
exceptions for supplies such as scarabs and dressings. Unique new-raid supplies
still require in-game verification.

## How shared state works

- A Drop action, inventory loss, and matching ground increase within three ticks
  produce a report with a unique ID. OSRS's ownership flag never assigns the owner.
- Take actions and matching inventory gains produce pickup reports referencing
  known drop IDs. Pending local drops can be referenced before acknowledgement.
- Only the coordinator accepts and sequences reports. A local report does not
  immediately change the displayed shared quantity; it remains pending.
- Accepted reports form a revisioned event history. Retries retain the same event
  ID. Duplicate reports are not counted again, including after handoff.
- Replicas apply the next revision or install a complete validated snapshot. Missing
  updates and late joins trigger snapshot requests. Partial snapshots stay hidden.
- Out-of-order pickups wait for their referenced drops, then are validated against
  the remaining recorded quantity. Impossible or unresolved reports are rejected.
- Local visibility, scene reloads, and render range do not delete shared history.
  They only affect local highlights and uncertainty. Thus expired/unobserved piles
  can remain in the historical record; it is not a guarantee of physical availability.
- Mixed-owner piles use gray highlights and uncertain-owner pickup messages. No
  unexplained disappearance accuses another player of taking an item.

No wall-clock synchronization is needed for this protocol. Revisions and leadership
terms determine ordering; local game ticks drive timeouts and retries.

## Coordinator loss and handoff

An unavailable coordinator causes replicas to pause after 20 local game ticks
(about 12 seconds). Their last shared record remains in memory. Reports remain
pending, within the limits below, and retry after the same authority is synchronized.
No client elects itself automatically.

For a graceful transfer, the **current coordinator** types in RuneLite chat:

```text
::ppt handoff Player Name
```

The old coordinator freezes updates, sends a complete history to that party member,
and waits for acknowledgement before committing a new leadership term. The target
must be running this version with the same session code and the current coordinator
selected. Existing synchronized peers follow the committed transfer.

Use `::ppt cancel` on the old coordinator to cancel a transfer **before commitment**.
Use `::ppt` to print the current status. After successful handoff, everyone should
change **Coordinator name** to the new coordinator, particularly before restarting
or inviting late joiners. Changing the setting alone does not promote a replica.
Keep the old coordinator connected until all peers confirm synchronization; it can
redirect clients that missed the commit.

If the coordinator process is lost before a safe transfer, do not silently promote
an arbitrary cache. Choose a new coordinator and a **fresh session code** to start
an explicitly empty record. Drops from before that reset cannot be reconstructed.
Recent coordinator session fingerprints are persisted to prevent accidentally
starting an empty authority with the same code after restarting. They contain no
raw party ID or session code; the actual shared history remains in memory only.

## Boundaries and limitations

- Every contributing player needs this version. Older v2 messages are not registered.
- Members must agree on the coordinator and session code. This is explicit authority,
  not a consensus protocol tolerating malicious or differently configured peers.
- A snapshot recovers the coordinator's reported history, not actions nobody observed.
  A pickup without known drop references cannot be attributed reliably.
- Identical bottles from different owners cannot be uniquely identified by the game.
  The record retains that uncertainty; separate tiles are best for exact attribution.
- Room loading preserves history. Leaving raid interiors for three logged-in ticks,
  changing world/party, logging out or disabling the plugin resets local tracking.
  A replica can subsequently request the coordinator's snapshot; a lost coordinator
  needs the recovery procedure above.
- First-version bounds: 500 accepted reports per raid session, 100 pending local
  reports, 100 deferred dependency reports, and 100 referenced drop IDs per pickup.
  The history limit freezes the authority rather than forgetting deduplication IDs.
  Snapshot chunks contain at most ten reports and drain at two chunks per game tick.
  Full histories can take several seconds to synchronize.
- Handoff interruptions, RuneLite Party transport limits, Jagex-account development
  login, and all four raid interiors need live testing before release.

## Design and verification

See [STATE_DESIGN.md](STATE_DESIGN.md) for invariants and failure handling.
`bash gradlew test jar shadowJar` runs local correlation tests, raid gating tests,
protocol serialization tests, and an in-memory multi-client network simulation.
The simulation covers duplicate/reordered reports, missing revisions, partial snapshots,
late joins, coordinator loss/restart, concurrent pickups and interrupted handoffs.
It is not a substitute for two or more actual RuneLite clients.

In-game checklist: configure two clients; confirm matching revision numbers; drop
and pick up supplies; walk out of range and return; cross room-loading boundaries;
join late; briefly interrupt a follower connection; perform and cancel handoffs;
then stop the coordinator and confirm the others visibly pause without deleting history.

Region references: [ToB](https://github.com/HarrySJ96/theatreofbloodstats/blob/master/src/main/java/hsj/external/theatreofbloodstats/TobConstants.java),
[ToA](https://github.com/LlemonDuck/tombs-of-amascut/blob/master/src/main/java/com/duckblade/osrs/toa/util/RaidRoom.java),
[Archive](https://github.com/LlemonDuck/the-fractured-archive/blob/master/src/main/java/com/duckblade/osrs/tfa/util/RaidRoom.java).
