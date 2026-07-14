---
name: fukuii-ephemeral
description: >-
  Launch a temporary, throwaway Fukuii node instance in a `mktemp`-created datadir,
  with automatic port-conflict avoidance against any already-running instance. Use
  when asked to "spin up a test node", "run a second/throwaway Fukuii instance", "try
  this change against a fresh node", or when validating a config/code change without
  touching a real datadir. Always prints the full invocation before launching,
  backgrounds the process, and reports its PID. Includes an independent leftover-
  detection step for abandoned ephemeral instances from prior sessions. Do NOT use
  this for a real/persistent node bring-up (see `fukuii-first-start`) or for cloning
  an existing datadir (not yet supported — see "Clone mode" below).
disable-model-invocation: true
user-invokable: true
argument-hint: "network (etc|mordor|sepolia|holesky)"
---

# Fukuii ephemeral instance

Bring up a disposable Fukuii node for quick experimentation, without risking a real
datadir or colliding with an already-running instance's ports.

## When to use

- Validate a config change, a code change, or a hive-adapter change against a live
  node without touching `~/.fukuii/<network>/`.
- Run a second instance alongside an existing one (e.g. to test peer discovery
  between two local nodes) without hand-picking non-conflicting ports.
- Quick smoke-test after a build (`fukuii-build`) before committing to a longer
  workflow (`fukuii-test-hive`, manual RPC probing, etc.).

## Prerequisites

- A built assembly jar (`target/scala-3.*/fukuii-assembly-*.jar`) or `./bin/fukuii`
  distribution — run `fukuii-build` first if neither exists yet.
- `lsof` available (used for port-conflict checks; works for both TCP and UDP,
  cross-platform-safer than Linux-only `ss`).

## Procedure

### 1. Create the datadir

Always via `mktemp -d`, never a hand-constructed path:

```bash
DATADIR=$(mktemp -d /tmp/fukuii-ephemeral.XXXXXX)
```

The `fukuii-ephemeral.` prefix is load-bearing — it's what Step 4's leftover-detection
scan greps for. Never omit it or use a different prefix.

### 2. Pick a non-conflicting port set

Start at the base ports from `fukuii-network-ports` (P2P `30303`, HTTP `8546`,
WebSocket `8552`, Engine API `8551`, metrics `13798`). Check each with `lsof` (TCP
and UDP, since the P2P port binds both):

```bash
check_port() { lsof -iTCP:"$1" -sTCP:LISTEN -t >/dev/null 2>&1 || lsof -iUDP:"$1" -t >/dev/null 2>&1; }
```

If any of the five ports is already bound, escalate the **entire set** by +100, then
+200, then +300 (matching `fukuii-network-ports`' multi-instance offset convention —
never offset only the conflicting port in isolation, offset all five together so the
instance's port set stays internally consistent and easy to reason about).

### 3. Launch, always printing the invocation first

```bash
JAR=$(ls target/scala-3.*/fukuii-assembly-*.jar | head -1)
echo "Launching: java -Dfukuii.datadir=$DATADIR \
  -Dfukuii.network.server-address.port=$P2P_PORT \
  -Dfukuii.network.discovery.port=$P2P_PORT \
  -Dfukuii.network.rpc.http.port=$HTTP_PORT \
  -Dfukuii.network.rpc.http.websocket.port=$WS_PORT \
  -Dfukuii.network.rpc.engine.port=$ENGINE_PORT \
  -Dfukuii.metrics.port=$METRICS_PORT \
  -jar $JAR $NETWORK"
java -Dfukuii.datadir="$DATADIR" \
  -Dfukuii.network.server-address.port="$P2P_PORT" \
  -Dfukuii.network.discovery.port="$P2P_PORT" \
  -Dfukuii.network.rpc.http.port="$HTTP_PORT" \
  -Dfukuii.network.rpc.http.websocket.port="$WS_PORT" \
  -Dfukuii.network.rpc.engine.port="$ENGINE_PORT" \
  -Dfukuii.metrics.port="$METRICS_PORT" \
  -jar "$JAR" "$NETWORK" > "$DATADIR/ephemeral.log" 2>&1 &
PID=$!
echo "PID: $PID  datadir: $DATADIR  log: $DATADIR/ephemeral.log"
```

Never skip the "Launching: ..." echo — printing the full invocation before running it
is what makes a throwaway instance's exact config reproducible after the fact, and
lets a human catch a bad port/datadir combination before the JVM even starts.

Launch via the calling tool's background-execution option (`run_in_background: true`)
so the session isn't blocked on a long-running node process; report the PID back
immediately, don't wait for full sync.

### 4. Leftover detection (run independently, any time)

Abandoned ephemeral datadirs from prior sessions (crashed sessions, forgotten
cleanup) can pile up under `/tmp`. Scan for them and check whether a process is
still attached:

```bash
for d in /tmp/fukuii-ephemeral.*; do
  [ -d "$d" ] || continue
  pid=$(pgrep -f "fukuii.datadir=$d" || true)
  if [ -n "$pid" ]; then
    echo "$d — still running (PID $pid)"
  else
    echo "$d — abandoned, no process attached ($(du -sh "$d" 2>/dev/null | cut -f1))"
  fi
done
```

Ask before deleting anything found abandoned — don't auto-remove without
confirmation, per the guarded-write protocol in `.claude/skills/CONVENTIONS.md`.

### 5. Cleanup (when done with this instance)

```bash
kill "$PID"
rm -rf "$DATADIR"   # confirm first — irreversible
```

## Clone mode — explicitly deferred

Erigon's equivalent skill (`erigon-ephemeral`) supports a second mode: clone an
existing datadir instead of starting from empty, via a companion `erigon-datadir`
skill that handles precondition checks, filesystem-aware copy-on-write, and disk-space
verification. **No `fukuii-datadir`-equivalent building block exists yet in fukuii's
skill set** — this is a documented gap, not a silent omission. Building it would need
to account for fukuii's actual datadir layout (`node.key`, `rocksdb/`, `keystore/`,
`logs/`) the way `erigon-datadir` accounts for Erigon's (`nodekey`, `snapshots/`,
`chaindata/`). Until that exists, `fukuii-ephemeral` only supports the empty-datadir
mode above.

## Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| JVM fails to bind a port immediately after launch | Port-conflict check missed a process bound only on the other protocol (TCP vs UDP) | Re-run Step 2's check against both `-iTCP` and `-iUDP` explicitly for the P2P port |
| Leftover datadirs consuming disk over time | Session ended without running Step 5 | Run Step 4 periodically; ask before deleting |
| Two ephemeral instances can't peer with each other | Both offset to the same +N tier by coincidence, or one wasn't actually offset | Confirm each instance's printed invocation (Step 3) shows a distinct port set before assuming a networking bug |
