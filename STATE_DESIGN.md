# RuneLite-only shared state

## Authority and scope

A group explicitly chooses a coordinator display name and a fresh shared raid-session
code in plugin settings. Party membership, world, raid type and that code scope every
message. No custom server or HTTP API is used. RuneLite party messages are the transport.
The code is a user-established instance identity, not a game-provided instance ID.

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
An unreachable coordinator leaves read-only stale copies. There is no automatic election.

A graceful handoff is initiated explicitly by the current coordinator. Clients that
receive its commit accept the target's new term; clients that miss the commit stay paused
and can recover via the old coordinator's redirect while it remains connected. After
handoff, update the configured coordinator name for late joins/restarts. If both authority
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

The initial coordinator is chosen through configuration, not an election. A recent
coordinator-scope fingerprint guard survives plugin restarts, preventing accidental
reuse of an empty coordinator record under the same code. History is not persisted;
crash recovery without a completed handoff deliberately requires a fresh code.

Snapshots contain the complete accepted history (bounded to 500 events), including
consumed drop IDs, so replay recreates balances and deduplication together. Transfer
freezes acceptance before capturing that prefix. Local observations cannot remove
history; in particular there is no replicated expiry inferred from one client's scene.

This is an explicit-authority protocol, not general-purpose consensus. It assumes
members agree on configuration and trust the selected coordinator. It cannot infer
server instance identity or recover unreported actions. Before release, test actual
RuneLite transport throughput, handoff interruptions and client restarts in-game.
