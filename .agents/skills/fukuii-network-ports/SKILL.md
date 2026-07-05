---
name: fukuii-network-ports
description: >-
  Canonical reference table of every port Fukuii binds, sourced directly from
  `src/main/resources/conf/base/network.conf` and `metrics.conf` — not re-derived from
  memory. Use when another skill (e.g. `fukuii-ephemeral`) needs to check for port
  conflicts before launching an additional instance, or when asked "what port does
  Fukuii use for X". Not a workflow — a lookup table other skills point to instead of
  duplicating port knowledge. Do NOT use this for editing port config (see
  `fukuii-node-configuration`) or for TLS-specific port/cert setup (see
  `fukuii-tls-operations`).
disable-model-invocation: true
user-invokable: false
---

# Fukuii network ports

Pure reference. No procedure, no side effects — a single source of truth other skills
(`fukuii-ephemeral` in particular) check against instead of re-deriving port numbers.

## Port table

Verified fresh against `src/main/resources/conf/base/network.conf` and
`src/main/resources/conf/base/metrics.conf` on this branch — re-verify against those
two files directly if this table is ever suspected stale, don't trust memory.

| Port | Protocol | Config key | Default | Notes |
|---|---|---|---|---|
| P2P listen | TCP + UDP | `fukuii.network.server-address.port` / `fukuii.network.discovery.port` | `30303` | **Same port for both TCP (Ethereum wire protocol) and UDP (discovery)** — a deliberate departure from Mantis's original `9076` default, documented inline in `network.conf`: peers expect TCP and UDP on the same port, and the split-port default broke inbound peer connectivity. |
| JSON-RPC HTTP | TCP | `fukuii.network.rpc.http.port` | `8546` | Not `8545` — fukuii's own HTTP RPC default is offset from the common Ethereum-client convention. |
| JSON-RPC WebSocket | TCP | `fukuii.network.rpc.http.websocket.port` | `8552` | Comment in `network.conf` notes this is "ETC standard" `8552` (vs. a naive `8545 - 1 = 8544`). |
| Engine API (authrpc) | TCP | `fukuii.network.rpc.engine.port` | `8551` | PoS/ETH-Sepolia only — pairs with a CL client via JWT-authenticated Engine API. See `fukuii-engine-api-setup`. |
| Metrics (Prometheus) | TCP | `fukuii.metrics.port` | `13798` | Only bound when `fukuii.metrics.enabled = true` (default `false`). |

## Conditional / not-always-bound

- **Metrics** (`13798`) only binds when metrics collection is explicitly enabled —
  don't flag it as a conflict candidate for a default-config ephemeral instance.
- **Engine API** (`8551`) is only meaningful for PoS/ETH-Sepolia deployments pairing
  with a CL client — a pure ETC/PoW instance still opens the port (it's not
  network-gated in config), but nothing will use it without a paired CL.

## Multi-instance offset convention

When running more than one Fukuii instance on the same host (see `fukuii-ephemeral`),
offset **every** port in this table by the same amount for a given instance — e.g.
instance 2 at +100: P2P `30403`, HTTP `8646`, WebSocket `8652`, Engine API `8651`,
metrics `13898`. This mirrors the pattern documented in `fukuii-run-labeling` memory
(new datadir = new instance = its own consistent port offset) and Erigon's
`erigon-network-ports`/`erigon-ephemeral` pairing, which this skill and
`fukuii-ephemeral` are modeled on.

## Keeping this table in sync

This table is a snapshot of `network.conf`/`metrics.conf` as of the date this skill
was written. If either file's port defaults change, update this table in the same
change — don't let it drift the way a stale reference doc silently misleads every
skill that depends on it.
