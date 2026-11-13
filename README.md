# Party Potion Tracker

A RuneLite-only raid supply tracker with automatic initial coordinator selection.
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
2. Enter the raid. Automatic coordination uses the raid's in-game roster; there is no session
   code or coordinator name to configure.
3. Compatible participants announce themselves and compare their
   discovered rosters. After a stable discovery/agreement window (normally about
   13–18 seconds), the lowest member ID in that initial group becomes coordinator.
   A late joiner follows the already established coordinator, even with a lower ID.
   Other clients request a snapshot. The panel shows team totals, player counts, and a recent
   event log. Ground highlights are suppressed while the shared list is unavailable.

Tracking runs only inside Theatre of Blood, Tombs of Amascut, and The Fractured Archive.
Chambers of Xeric is detected but deliberately disabled because RuneLite does not expose a
reliable shared roster for it. External lobbies are excluded; internal supply/loot rooms are
included. New raid interiors need an entry in `RaidLocation`.

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
- Mixed-owner piles use gray highlights and uncertain-owner pickup records in the panel. No
  unexplained disappearance accuses another player of taking an item.

No wall-clock synchronization is needed for this protocol. Revisions and leadership
terms determine ordering; local game ticks drive timeouts and retries.

## Coordinator loss

An unavailable coordinator causes replicas to pause after 20 local game ticks
(about 12 seconds). Their last shared record remains in memory while the surviving
raid members automatically elect the lowest eligible replacement. The replacement
keeps its validated local history and announces a new coordinator term; followers
request a snapshot if their histories differ. Reports remain pending, within the
limits below, and retry after the replacement is synchronized.

There are no in-game chat commands. The panel is the user-facing status surface, and
automatic coordination advertises the selected coordinator to late joiners.

If the coordinator process is lost, the replacement election uses the surviving raid
roster and never creates a new empty session. A split roster can still produce
conflicting claims; those clients pause instead of merging histories.

## Boundaries and limitations

- Every contributing player needs this version. Older v2 messages are not registered.
- Discovery only considers compatible, same-roster plugin presence; party members without the
  plugin are not candidates.
- Automatic discovery is not partition-safe consensus: isolated groups can independently
  bootstrap before hearing each other. Conflicting coordinator claims pause tracking when
  discovered; histories are never silently merged. Re-entering with a new raid identity is
  required to recover.
  Do not treat this development build as a guarantee against split-brain during initial discovery.
- Coordination is automatic and follows the in-game raid roster.
- A snapshot recovers the coordinator's reported history, not actions nobody observed.
  A pickup without known drop references is still logged as an unresolved pickup, but it does not
  change the shared supply balance or claim an owner.
- Identical bottles from different owners cannot be uniquely identified by the game.
  The record retains that uncertainty; separate tiles are best for exact attribution.
- Room loading preserves history. Leaving raid interiors for three logged-in ticks,
  changing world/party, logging out or disabling the plugin resets local tracking.
  A replica can subsequently request the current coordinator's snapshot after a failover.
- First-version bounds: 500 accepted reports per raid, 100 local
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
late joins, coordinator loss/restart, concurrent pickups and interrupted handoffs. Election simulations cover simultaneous arrivals, members without
plugins, late joins, stale/replayed presence, incompatible scopes, partition conflicts and restart guards.
It is not a substitute for two or more actual RuneLite clients.

In-game checklist: configure two clients; confirm matching revision numbers; drop
and pick up supplies; walk out of range and return; cross room-loading boundaries;
join late; briefly interrupt a follower connection;
then stop the coordinator and confirm the others elect a replacement without deleting history.

Region references: [ToB](https://github.com/HarrySJ96/theatreofbloodstats/blob/master/src/main/java/hsj/external/theatreofbloodstats/TobConstants.java),
[ToA](https://github.com/LlemonDuck/tombs-of-amascut/blob/master/src/main/java/com/duckblade/osrs/toa/util/RaidRoom.java),
[Archive](https://github.com/LlemonDuck/the-fractured-archive/blob/master/src/main/java/com/duckblade/osrs/tfa/util/RaidRoom.java).


### Carried supply panel

Open **Raid supplies** in the RuneLite sidebar to see brew, super restore, and prayer potion doses for each reporting player, plus current team totals. The panel can sort players by name or lowest tracked supplies. Hover a player for their bottle breakdown, and the event log uses item names. Raid variants and other drinkable potions are listed separately by name, without converting them into standard potion equivalents. Ground supplies are excluded from inventory totals.

All participants must currently join the same RuneLite party and be in the same world and raid.
For ToA, ToB, and TFA, automatic mode builds the boundary from the game's actual raid names and
controller, so separate teams do not merge just because someone typed the same code. The session
code remains a compatibility fallback where the game does not expose a roster (including some
Chambers states).

Inventories are broadcast approximately every five game ticks. Reports become stale after 15 seconds without a newer snapshot, and stale reports are excluded from totals. Party members without a current report show a dash; this can mean they are not sharing, are outside this raid, or have not synchronized yet. It does not mean they have zero supplies. Departed members are removed from the inventory table.

The panel totals cover current reporting participants, not necessarily every raider. Inventory data is carried through RuneLite Party messaging with no custom backend. Live multi-client testing is still required.

The **Low supply warning** setting shows a small on-screen list for current party members whose tracked drinkable doses are at or below the selected threshold.

Under **Highlighting**, choose whether to show your supplies and other supplies, select the
highlight style, and set the potion model outline width from 1 to 8 pixels when using the
item outline style.
