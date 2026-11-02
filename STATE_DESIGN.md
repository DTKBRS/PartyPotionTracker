# RuneLite-only shared state

## Authority and scope

A coordinator is selected through automatic participant discovery. The raid roster, world,
raid type, and controller scope every message. No custom server or HTTP API is used; RuneLite
party messages are the transport.

Only the selected coordinator sequences accepted reports. Replicas never change the
shared record because of visibility, loading, or a local action. Local reports remain
pending until accepted. Ground observations are local display information.

## Invariants

* An event ID is applied at most once within a session, including after snapshot recovery.
* Revision N identifies a prefix of the coordinator's accepted event history.
* Replicas apply only the next revision, or replace their copy with a complete validated
  snapshot. Partial snapshots are never exposed.
* Updates must come from the accepted coordinator and match the leadership term.
* A timeout means stale/paused, never permission to elect another coordinator.
* A handoff freezes the old coordinator, transfers the complete history, and requires
  the target to acknowledge it before the old coordinator commits the new term.
* Requests, snapshots and handoffs are bounded. Exhausting a limit pauses rather than
  silently discarding deduplication history.
* The record is evidence of reported actions, not a guarantee of physical bottle identity.

## Recovery

Heartbeats expose the latest revision. A replica with a gap asks for a snapshot;
late joiners do the same. Snapshots are split into bounded chunks and correlated with
request IDs. Pending local reports are retried with the same IDs after synchronization.
An unreachable coordinator leaves read-only stale copies. A timeout never bootstraps a replacement authority.

A graceful handoff is initiated explicitly by the current coordinator. Clients that
receive its commit accept the target's new term; clients that miss the commit stay paused
and can recover via the old coordinator's redirect while it remains connected. After
handoff, automatic presence advertises the successor for late joins. In manual mode,
update the configured coordinator name for late joins/restarts. If both authority
and its transfer evidence are lost, use a fresh shared code to start a new empty session;
never silently promote an arbitrary cached replica.

## Verification

Simulate several clients with a controllable message queue: reordered/duplicate reports,
missing updates, partial snapshots, late joining, interrupted handoff, stale old-leader
traffic, reconnects and simultaneous pickups. Compare accepted histories and derived
quantities. Keep the existing local-action correlation and raid-boundary tests.

## Implemented protocol

`REPORT` carries a client's confirmed action. `UPDATE` carries one accepted report
and its next revision. `HEARTBEAT`/`FROZEN` communicate authority liveness and revision.
`REQUEST` and correlated `SNAPSHOT` chunks repair a missing prefix. `REJECT` resolves
an invalid pending report without changing quantities. `TRANSFER`, `TRANSFER_ACK`
and `COMMIT` implement a graceful handoff; the old authority retains a redirect.
All transitions occur on RuneLite's client thread. The protocol engine itself has
no client/event-bus dependencies and is exercised by a serialized in-memory transport.

Automatic initial election uses `CoordinatorPresenceMessage` protocol 1. The roster
contains only recent compatible presence messages from logged-in members of the same
RuneLite party and matching world/raid/session scope. Members without this plugin are
not candidates. After 15 stable ticks and 6 agreement ticks, the lowest member ID can
bootstrap if every discovered participant advertises the same roster. Existing authority
claims take precedence over a new election, including hints from followers whose leader
is temporarily unavailable. New arrivals never displace an established coordinator.

Presence is repeated every 3 ticks; duplicate/out-of-order sequences do not refresh its
20-tick freshness window. Missing presence prevents processing shared-state traffic from
that peer. A missing coordinator pauses tracking but does not elect an empty replacement.
Local action reports are buffered during discovery (maximum 100); overflow pauses the
session instead of silently proceeding with an incomplete local history.

A recent session-scope fingerprint guard survives plugin restarts. Every automatic-mode
participant records it when authority becomes known, preventing accidental bootstrap of
an empty coordinator record under a previously used code. History is not persisted;
crash recovery without a completed handoff deliberately requires a fresh code.

Snapshots contain the complete accepted history (bounded to 500 events), including
consumed drop IDs, so replay recreates balances and deduplication together. Transfer
freezes acceptance before capturing that prefix. Local observations cannot remove
history; in particular there is no replicated expiry inferred from one client's scene.

This is an explicit-authority protocol, not general-purpose consensus. It assumes
members agree on configuration and trust the selected coordinator. It cannot infer
server instance identity or recover unreported actions. Before release, test actual
RuneLite transport throughput, handoff interruptions and client restarts in-game.


## Bootstrap partition limitation

The discovery roster is not an authoritative global electorate. A member without the
plugin cannot acknowledge presence, and silence cannot distinguish that situation from
a disconnected compatible plugin. Therefore disconnected subgroups can each bootstrap
before discovering one another. On conflicting coordinator claims, ground updates and
fresh highlights stop; a fresh session code is required. An authenticated shared-state
handoff can resolve a transient conflict caused by reordered presence/commit delivery.
Old coordinator claims after that handoff are ignored.

This is deliberately **not** Raft or automatic crash failover. The accepted history is
not quorum-committed or persisted, so promoting a cached replica after a timeout could
lose accepted events. Implementing that stronger guarantee requires a separately agreed
membership configuration, durable voting state and quorum-backed log commitment. The
inventory panel remains independent of coordinator availability.
