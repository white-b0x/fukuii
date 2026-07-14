# Investigation Reports

This directory contains detailed historical investigations of issues encountered during Fukuii development and operations. These reports document root cause analysis, debugging processes, and resolutions.

## Available Reports

- **[Contract Test Failure Analysis](CONTRACT_TEST_FAILURE_ANALYSIS.md)** — Investigation of gas calculation discrepancies in contract tests (✅ Resolved - test fixture data issue)
- **[Core-Geth SNAP Sync Genesis Analysis](CORE_GETH_SNAP_SYNC_GENESIS_ANALYSIS.md)** — Analysis of core-geth's SNAP sync genesis handling
- **[EIP-2124 ForkID Implementation Analysis](EIP-2124_IMPLEMENTATION_ANALYSIS.md)** — Comparative analysis of the EIP-2124 ForkID implementation
- **[FastSync Timeout Investigation](FASTSYNC_TIMEOUT_INVESTIGATION.md)** — Analysis of blockchain fast synchronization timeout scenarios
- **[Integration Test Classification](INTEGRATION_TEST_CLASSIFICATION.md)** — Categorization and analysis of integration test failures
- **[RLPx Handshake and Message Encoding Comparative Analysis](RLPX_HANDSHAKE_AND_MESSAGE_ENCODING_ANALYSIS.md)** — Comparison of RLPx handshake and message-encoding behavior against reference clients
- **[RLPx Hello Regression Investigation](rlpx-hello-regression.md)** — Investigation of an RLPx Hello handshake regression

## Purpose

These investigation reports serve as:
- Historical record of significant issues and their resolutions
- Reference material for similar future issues
- Documentation of debugging methodologies and approaches
- Knowledge base for the development team

## Related Documentation

- [Troubleshooting Guides](../troubleshooting/README.md) — Step-by-step solutions for common scenarios
- [Known Issues & Solutions](../runbooks/known-issues.md) — Current known issues with workarounds
- [Operations Runbooks](../runbooks/README.md) — Operational procedures and guides
