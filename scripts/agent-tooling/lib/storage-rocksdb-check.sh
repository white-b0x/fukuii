#!/bin/bash
# storage-rocksdb-check.sh — run the "Grep patterns for storage code review" checks
# from .claude/agent-protocols/storage-rocksdb.md in one call.
#
# Usage: storage-rocksdb-check.sh
#
# Why this exists: the doc's closing section lists 5 grep checks for the storage layer
# (iterator leaks, DataSource-contract bypasses, sync-write hot paths, EphemDataSource
# misuse, missing cache metrics). Running them one at a time is 5 tool calls; this runs
# all of them and reports counts.
#
# Consensus state paths (NodeStorage, AccountStorage, block/header/receipt stores,
# world state root) require FORGE review before any access-pattern change regardless
# of what this script reports — see storage-rocksdb.md's "Consensus state" section.
#
# Read-only, fast — no logging, no backgrounding needed.
# Exit code: 0 always (this is a report, not a CI gate).

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"
cd "$REPO_ROOT"

report() {
    local id="$1" desc="$2" target="$3" count="$4"
    if [ "$target" = "0" ]; then
        if [ "$count" -eq 0 ]; then
            printf '%-3s %-52s target=0   count=%-5d PASS\n' "$id" "$desc" "$count"
        else
            printf '%-3s %-52s target=0   count=%-5d FAIL\n' "$id" "$desc" "$count"
        fi
    else
        printf '%-3s %-52s %-11s count=%-5d\n' "$id" "$desc" "$target" "$count"
    fi
}

echo "### Storage/RocksDB Ratchet Check — storage-rocksdb.md"
echo

R1=$(grep -rn "newIterator\b" src/main/ --include="*.scala" | grep -v "withResources\|resource" | wc -l | tr -d ' ')
report R1 "Iterator without withResources (leak risk)" 0 "$R1"

R2=$(grep -rn "\.get(handle\|\.get(cf" src/main/ --include="*.scala" | grep -v "db/dataSource" | wc -l | tr -d ' ')
report R2 "Direct RocksDB.get outside db/ package" 0 "$R2"

R3=$(grep -rn "updateSync" src/main/ --include="*.scala" | wc -l | tr -d ' ')
report R3 "updateSync call sites (review: pivot/checkpoint only)" "info-only" "$R3"

R4=$(grep -rn "EphemDataSource" src/main/ --include="*.scala" | wc -l | tr -d ' ')
report R4 "EphemDataSource in main sources (test-only type)" 0 "$R4"

R5=$(grep -rn "class.*DataSource" src/main/ --include="*.scala" | grep -v "Ephem\|Component" | wc -l | tr -d ' ')
report R5 "DataSource impl classes (review: cacheStats wired?)" "info-only" "$R5"

echo
echo "R3: updateSync is correct for pivot/checkpoint commits, wrong in a hot loop —"
echo "read each hit's call site, don't treat count>0 as a violation on its own."
echo "R5: lists all DataSource implementations found — cross-check each has cacheStats"
echo "logging wired per storage-rocksdb.md's 'Statistics and metrics' section."
