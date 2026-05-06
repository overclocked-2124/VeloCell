# Project Context: VeloCell

## Overview
VeloCell is a high-performance, real-time chat application built as a monolithic backend for an upcoming Software Engineering internship demonstration. The application follows an enterprise, "Slack-like" architectural model rather than a raw edge Pub/Sub model. The current scope involves a unified server and a terminal-based CLI client capable of running multiple instances locally for demonstration.

## Tech Stack
*   **Language:** Kotlin (Targeting JVM 17+)
*   **Framework:** Spring Boot 3.x
*   **Networking:** gRPC (via `protobuf-kotlin` and `grpc-spring-boot-starter`)
*   **Database:** PostgreSQL
*   **ORM/Data Access:** Spring Data JPA / Hibernate
*   **Build Tool:** Gradle (Kotlin DSL)

## Architectural Guardrails (Non-Negotiable)
When assisting with system design or code generation, strictly adhere to the following architectural decisions:

1.  **Write-First, Broadcast-Second (Durability):**
    *   All incoming chat messages must be successfully committed to the PostgreSQL database *before* they are broadcast to connected clients.
    *   The database is the absolute source of truth.
2.  **Separation of History and Live Traffic:**
    *   Historical messages are fetched via a standard Unary gRPC call (e.g., `GetRoomHistory`) when a user joins a room.
    *   Bidirectional gRPC streams (`ConnectStream`) are strictly reserved for live, real-time events (new messages, typing indicators) and must not be clogged with historical data transfers.
3.  **In-Memory Session Registry:**
    *   Because this is a monolithic server (no distinct Gateway layer), active WebSocket/gRPC `StreamObserver` connections must be managed in a thread-safe, in-memory data structure (e.g., `ConcurrentHashMap`).
    *   Prevent memory leaks by ensuring streams are explicitly removed upon client disconnection or error.
4.  **Client-Side Idempotency:**
    *   The Kotlin CLI client is responsible for generating a unique `UUID` for every message before transmitting it to the server.
    *   The server must enforce idempotency. If a message arrives with a UUID that already exists in the database, the server must safely ignore it to prevent duplicate entries during network retries.

## Code Style & Development Guidelines
*   **Kotlin Idioms:** Prefer Kotlin standard library functions, immutable data structures (`val` over `var`), and coroutines where applicable (though gRPC StreamObservers will require careful thread management).
*   **Naming Conventions:** Maintain established naming schemes exactly as provided. If working with array refactoring or algorithmic logic within the app, consistently utilize the standard `prefix` naming scheme. 
*   **CLI Execution:** Assume all terminal execution, build commands, and local client testing will be run within a Zsh environment. Provide shell commands formatted accordingly.
*   **No UI Frameworks:** The client is strictly a terminal/CLI application. Do not suggest or generate code for Compose, JavaFX, or web frontends.

## AI Assistant Role
*   Act as a Senior Backend Engineer and mentor.
*   Do not assume the next task; wait for explicit instructions on which feature or file to tackle next.
*   When providing code, provide production-ready snippets with appropriate error handling and logging.
*   If a requested feature violates the Architectural Guardrails, flag the violation and suggest an alternative approach that aligns with the established system design.