# Historical Documentation and Scripts

This directory contains historical documentation and one-time migration scripts that are preserved for reference but are no longer actively used.

## Contents

### `reviews/`

Four point-in-time SNAP sync / wire-protocol investigation and review documents from
December 2025 (self-flagged "archived 2026-06-12" at the top of each file — moved here
from a top-level `docs/reviews/` in 2026-07 for consistency with this directory's purpose).
Kept for historical reference on how SNAP sync compliance and wire-protocol compression
issues were diagnosed and resolved; **not maintained and may not reflect the current
implementation.**

### `rebrand.sh`
One-time rebranding script used to migrate the codebase from "Mantis" (IOHK) to "Fukuii" (Chippr Robotics).

**Status**: Migration completed. This script is preserved for historical reference.

**What it did**:
- Renamed packages from `io.iohk` to `com.chipprbots`
- Renamed directories and configuration files
- Updated string references throughout the codebase
- Created `docker/fukuii/` from `docker/mantis/`
- Created `ets/config/fukuii/` from `ets/config/mantis/`

**Note**: If you need to understand what changed during the rebrand, refer to:
1. This script
2. Git history around the rebrand date
3. [docs/adr/infrastructure/INF-001-scala-3-migration.md](../adr/infrastructure/INF-001-scala-3-migration.md) - Contains context about the overall migration

## Should I run these scripts?

**No.** These scripts are for historical reference only. The migrations they performed are already complete in the current codebase.

## Related Documentation

- [Migration History](MIGRATION_HISTORY.md) - Detailed history of the Scala 3 migration
- [INF-001: Scala 3 Migration](../adr/infrastructure/INF-001-scala-3-migration.md) - Architecture decision record
