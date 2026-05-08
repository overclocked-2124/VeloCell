# VeloCell

A high-performance, real-time chat backend built on Kotlin, Spring Boot, gRPC, and PostgreSQL — modelled on the same Cloud-First architectural principles that power Slack.

---

## Quickstart

**Prerequisites:** JDK 21, PostgreSQL 14+, and a database named `velocell_db`.

The connection string lives in [`src/main/resources/application.properties`](src/main/resources/application.properties) — adjust the username/password if yours differ.

```bash
# Terminal 1 — start the server (gRPC listening on :9090)
./gradlew bootRun

# Terminal 2 — Alice
./gradlew runClient -Pusername=alice

# Terminal 3 — Bob
./gradlew runClient -Pusername=bob
```

Inside the CLI:

```
> /create general          # create a room
> /join general            # join it (membership persists)
> hello world              # send a message — broadcasts to all online members
> /list                    # fetch the last 50 messages from Postgres
```

To run the test suite (uses an in-memory H2 database — no Postgres required):

```bash
./gradlew test
```

> **Windows note:** if `./gradlew bootRun` fails to find a JDK, ensure `JAVA_HOME` points to a JDK 21 install. The repo's [`gradle.properties`](gradle.properties) pins `org.gradle.java.installations.paths` so Gradle's toolchain resolver can find a Microsoft JDK 21 install at the standard location even when the shell's `JAVA_HOME` isn't set.

---

# Architectural Summary & Design Ideology

This document is a technical manifesto rather than a feature list.

## 1. The Core Ideological Shift — Beyond Raw Pub/Sub

A common misconception in early system design is that chat applications are just simple Pub/Sub systems (MQTT, raw Redis pub/sub) where a client directly subscribes to a topic. **VeloCell explicitly rejects this model at the edge.**

**Why raw Pub/Sub fails for enterprise chat:**

- **Amnesia.** Pub/Sub brokers are "dumb pipes." If a client's connection drops for a millisecond while a message is in flight, that message is permanently lost to that client.
- **No source of truth.** Brokers lack native, ordered persistence — there's nowhere to ask "what did I miss?"
- **No authorization model.** Anyone subscribed to a topic gets every message, with no per-room ACL.

**The pivot.** VeloCell uses a **stateful server architecture over gRPC streams**. The server acts as an intelligent gateway: it persists every message to disk *before* it routes anything, combining the real-time speed of a long-lived stream with the durability of a relational database.

## 2. The Great Divide — WhatsApp vs. Slack Architecture

Two dominant industry models were evaluated before committing to a design.

| Axis | WhatsApp (Edge-First) | Slack (Cloud-First) |
|---|---|---|
| Philosophy | Privacy, device-centric | Ubiquity, seamless multi-device |
| Source of truth | Local SQLite on the device | Central, sharded server database |
| Server's role | Store-and-forward; deletes after delivery | Permanent system of record |
| New-device behaviour | Re-sync from peer/backup | Login, see everything instantly |
| Optimised for | End-to-end privacy | Searchable team-wide history |

**The VeloCell decision: Slack-style Cloud-First.** By backing the system with PostgreSQL, VeloCell guarantees that chat history, room memberships, and timestamps are globally synchronised and instantly available to any client joining the network — regardless of local state. This is the right model for an enterprise/team-collaboration product, where ubiquity beats peer-to-peer privacy.

## 3. Borrowed Enterprise Principles — The Slack Playbook

Four enterprise paradigms were lifted directly to elevate VeloCell from prototype to production-track.

### A. Write-First, Broadcast-Second (Durability Guarantee)

**Concept.** A message is not considered "sent" until it is safely written to disk.

**VeloCell implementation.** When the server receives a `ClientEvent` over the gRPC stream, [`MessageProcessor`](src/main/kotlin/io/auxia/vellocell/service/MessageProcessor.kt) opens a `@Transactional` boundary, validates the sender, validates room membership, then inserts the row into Postgres via Spring Data JPA. *Only after the commit returns* does [`VeloCellGrpcService.connectStream`](src/main/kotlin/io/auxia/vellocell/grpc/VeloCellGrpcService.kt) trigger fan-out via the in-memory registry.

**Benefit.** Crash resilience. If the server loses power a microsecond after receiving a message, the data is safe — Postgres has it. On reboot, every client's `/list` reflects reality. No "ghost messages" that were broadcast but never persisted.

### B. Separation of History and Live Traffic (Bandwidth Optimisation)

**Concept.** Real-time pipes should not be clogged with bulk historical dumps.

**VeloCell implementation.** gRPC's strong typing makes this enforceable at the contract level (see [`velocell.proto`](src/main/proto/velocell.proto)):

- **Unary RPC `GetRoomHistory`** — single request, single response. Used when a user enters a room; pulls the last 50 messages from Postgres in one round-trip.
- **Bidirectional stream `ConnectStream`** — long-lived, multiplexed. Carries *only* live, post-join events.

**Benefit.** Fast room-switching, predictable per-stream memory, and a clean operational signal: a slow history query never starves real-time delivery, because they ride entirely different RPC paths.

### C. In-Memory Session Registry (State Management)

**Concept.** A stateful routing table is required to know who is online at any given millisecond.

**VeloCell implementation.** [`VeloCellRegistry`](src/main/kotlin/io/auxia/vellocell/service/VeloCellRegistry.kt) is a thread-safe `ConcurrentHashMap<Long, Channel<ServerEvent>>` mapping `userId` to their active stream's coroutine `Channel`. Every gRPC stream registers on connect and is evicted on disconnect. The channel is bounded — a slow consumer that fills its buffer is automatically evicted, so one laggy client cannot stall the fan-out path for everyone else.

**Benefit.** When broadcasting to a room with 1,000 members, the server reads the membership list from Postgres (the database knows every member, online or not) and routes the payload via the in-memory map only to the small subset currently online. No wasted I/O on offline users; dead connections clean themselves up.

### D. Client-Side Idempotency (Network Resilience)

**Concept.** Prevent duplicate messages when a flaky network causes the client to retry.

**VeloCell implementation.** The primary key of the `messages` table is a UUID **generated by the client**, not the server. The Kotlin CLI calls `UUID.randomUUID()` per outgoing message. The server's idempotency check happens inside `MessageProcessor`'s `@Transactional` boundary, with the database's unique-PK constraint as a backstop for the rare race window between two concurrent inserts of the same UUID — the loser's `DataIntegrityViolationException` is caught at the call site and treated as "already persisted."

**Benefit.** If the client times out mid-send and retries, Postgres rejects the duplicate, and the server silently skips it. No double-broadcast, no duplicate row, no application-level dedup state to maintain.

## 4. Tech Stack Synergy

| Component | Why this choice |
|---|---|
| **Kotlin + Coroutines** | Lightweight concurrency lets the CLI client block on terminal input *and* listen for incoming server events on the same thread without freezing. Server-side, `channelFlow` + `coroutineScope` model the bidi stream's lifecycle in a way that raw `StreamObserver` callbacks can't match for readability. |
| **gRPC over HTTP/2** | Bidirectional multiplexing on a single TCP connection. Binary Protobuf payloads are an order of magnitude more wire-efficient than JSON-over-WebSockets. The strict service contract enforces the "history vs live traffic" split at the type level — it's literally impossible to clog the live stream with history. |
| **Spring Boot 4 + Spring Data JPA** | Handles the orchestration: dependency injection, transaction lifecycle, gRPC server bean lifecycle, and connection pooling via HikariCP. The `@Transactional` annotation gives us the durability guarantee with one line. |
| **PostgreSQL** | The source of truth. Chosen over a document store because the access pattern is heavily relational (rooms have members, members have messages, messages reference users) and we need strict PK uniqueness for the idempotency story. |

---

## Project Structure

```
src/main/
├── proto/velocell.proto                 # gRPC service contract (5 RPCs)
├── resources/application.properties     # DB + gRPC config
└── kotlin/io/auxia/vellocell/
    ├── VellocellApplication.kt          # Spring Boot entry point
    ├── entity/                          # JPA: User, Room, Membership, Message
    ├── repository/                      # Spring Data interfaces
    ├── service/
    │   ├── VeloCellRegistry.kt          # In-memory session map
    │   └── MessageProcessor.kt          # Transactional persist + validate
    ├── grpc/
    │   └── VeloCellGrpcService.kt       # All 5 RPC implementations
    └── client/
        └── VeloCellClient.kt            # Terminal CLI

src/test/                                # Integration + unit tests (H2-backed)
```

## gRPC Surface

| RPC | Type | Purpose |
|---|---|---|
| `GetOrCreateUser` | Unary | Idempotent user upsert by username |
| `CreateRoom` | Unary | Idempotent room upsert by name |
| `JoinRoom` | Unary | Persist a `(user, room)` membership |
| `GetRoomHistory` | Unary | Last 50 messages, oldest-first |
| `ConnectStream` | Bidi-stream | Live message ingest + fan-out |

## Test Coverage

The suite uses an in-memory H2 database (PostgreSQL compatibility mode) so it runs anywhere with no external dependencies.

- `MessageProcessorTest` — happy-path persist, duplicate-UUID rejection, unknown-sender rejection, non-member rejection.
- `VeloCellRegistryTest` — re-register evicts stale session, full buffer evicts slow consumer, `remove` closes the channel, successful delivery round-trip.
- `VellocellApplicationTests.contextLoads` — full Spring context boot including gRPC server lifecycle.

## Production-Hardening Roadmap

The current build is a deliberate demo. The gaps below are scope cuts, not oversights:

- **Authentication.** `sender_id` is trusted from the wire. A real deployment would add a gRPC interceptor that verifies a JWT and replaces the wire-supplied ID with the authenticated principal — flagged with `SECURITY:` comments at the relevant call sites.
- **Horizontal scaling.** The in-memory registry is single-node. A multi-instance deployment would replace it with Redis pub/sub for cross-node fan-out, keeping the per-node `ConcurrentHashMap` as an L1 cache.
- **Backpressure on history.** `GetRoomHistory` returns a fixed top-50. A cursor-based paginator (`before_id` / `since_id`) would let clients scroll further back without server-side memory pressure.
- **Observability.** Metrics (Micrometer + Prometheus) for fan-out latency, registry size, dropped-on-overflow events, and per-RPC P99 would close the operational loop.
- **TLS.** The CLI uses `usePlaintext()`. Production would terminate TLS at the gRPC server (or at an upstream Envoy/ingress).
