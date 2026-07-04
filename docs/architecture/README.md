# Architecture Documentation

This directory contains architectural documentation for the Fukuii EVM execution layer (EL) client — a Scala 3 Ethereum execution engine that supports PoW (Ethash) for Ethereum Classic and Engine API-driven PoS for post-Merge Ethereum networks under a pluggable consensus architecture.

## Contents

### Architecture Overview
- **[Architecture Overview](architecture-overview.md)** - High-level system architecture and component interactions, including the Engine API / Consensus Layer Integration subsystem
- **[Architecture Diagrams](ARCHITECTURE_DIAGRAMS.md)** - C4 architecture diagrams and visual representations
- **[Pluggable Consensus Vision](pluggable-consensus-vision.md)** - Three-layer `fukuii-core` / `fukuii-env` / consensus-module architecture for multi-network support with Orbita sidechains

### Wire Protocol
- **[Protocol Capability Negotiation](PROTOCOL_CAPABILITY_NEGOTIATION.md)** - How fukuii negotiates ETH/SNAP protocol versions and capabilities with peers
- **[Protocol Version Alignment](PROTOCOL_VERSION_ALIGNMENT.md)** - Keeping fukuii's wire-protocol version support aligned with reference clients

### SNAP Sync
- **[Implementation](SNAP_SYNC_IMPLEMENTATION.md)** - Overall SNAP sync design and implementation
- **[Actor Concurrency](SNAP_SYNC_ACTOR_CONCURRENCY.md)** - Actor-level concurrency model for SNAP sync
- **[Actor Implementation](SNAP_SYNC_ACTOR_IMPLEMENTATION.md)** - Per-actor implementation detail
- **[Bytecode Implementation](SNAP_SYNC_BYTECODE_IMPLEMENTATION.md)** - Bytecode range retrieval/validation
- **[Cleanup Implementation](SNAP_SYNC_CLEANUP_IMPLEMENTATION.md)** - Post-sync cleanup and healing
- **[Error Handling](SNAP_SYNC_ERROR_HANDLING.md)** - Error/retry/failure-mode handling
- **[Progress Monitoring Summary](SNAP_SYNC_PROGRESS_MONITORING_SUMMARY.md)** - Progress tracking and observability
- **[State Storage Review](SNAP_SYNC_STATE_STORAGE_REVIEW.md)** - State storage layer review
- **[State Validation](SNAP_SYNC_STATE_VALIDATION.md)** - State root / trie validation approach

### User Interfaces
- **[Console UI](console-ui.md)** - Console user interface design and implementation
- **[Console UI Mockup](console-ui-mockup.txt)** - Text-based UI mockup

## Related Documentation

- [Architecture Decision Records (ADRs)](../adr/README.md) - Detailed architectural decisions with context and rationale
- [Operations Runbooks](../runbooks/README.md) - Operational guides for running nodes
- [Deployment Guides](../deployment/README.md) - Docker and deployment documentation

## See Also

- [Documentation Home](../index.md)
- [Contributing Guide](../development/contributing.md)
