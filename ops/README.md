# Operations Configuration

This directory contains operational configuration files and resources for running and monitoring Fukuii in production environments.

## Directory Structure

```
ops/
├── barad-dur/                                    # Barad-dûr - Kong API Gateway stack
│   ├── docker-compose.yml                        # Full stack with PostgreSQL
│   ├── docker-compose-dbless.yml                 # DB-less mode
│   ├── kong.yml                                  # Kong declarative config
│   ├── setup.sh                                  # Setup and initialization script
│   ├── test-api.sh                               # API testing script
│   ├── fukuii-conf/                              # Fukuii node configurations
│   ├── grafana/                                  # Grafana dashboards and provisioning
│   └── prometheus/                               # Prometheus configuration
├── cirith-ungol/                                 # Cirith Ungol - ETC mainnet testing environment
│   ├── conf/
│   │   ├── etc.conf                              # ETC mainnet configuration
│   │   └── logback.xml                           # DEBUG logging configuration
│   ├── docker-compose.yml                        # Docker Compose deployment
│   ├── start.sh                                  # Quick start/stop script
│   ├── README.md                                 # Cirith Ungol documentation
│   └── ISSUE_RESOLUTION.md                       # Issue tracking and resolution notes
├── gorgoroth/                                    # Gorgoroth - Internal test network
│   ├── conf/                                     # Configuration files for all nodes
│   ├── docker-compose-3nodes.yml                 # 3 Fukuii nodes
│   ├── docker-compose-6nodes.yml                 # 6 Fukuii nodes
│   ├── docker-compose-fukuii-geth.yml            # 3 Fukuii + 3 Core-Geth
│   ├── docker-compose-fukuii-besu.yml            # 3 Fukuii + 3 Besu
│   ├── docker-compose-mixed.yml                  # 3 Fukuii + 3 Besu + 3 Core-Geth
│   ├── deploy.sh                                 # Deployment management script
│   ├── collect-logs.sh                           # Log collection script
│   └── README.md                                 # Gorgoroth documentation
├── grafana/                                      # Grafana dashboard configurations (15 dashboards, 5 categories)
│   ├── Archive/                                  # Superseded dashboards, kept for reference
│   │   ├── fukuii-dashboard.json                # Control Tower - original main dashboard
│   │   ├── fukuii-casual-dashboard.json         # Casual View - minimalist at-a-glance
│   │   ├── fukuii-miners-dashboard.json         # Miners - mining-focused metrics
│   │   └── fukuii-fast-sync.json                # Legacy Fast Sync (pre-SNAP) view
│   ├── ETC Node/                                 # ETC/Mordor (PoW) node dashboards
│   │   ├── fukuii-node-health.json              # General node health overview
│   │   └── fukuii-node-troubleshooting-dashboard.json  # Debug: sync phase, GC, RPC, peer churn
│   ├── Network/                                  # Cross-network P2P dashboards
│   │   ├── fukuii-multi-network-resources.json  # Resource usage across networks
│   │   ├── fukuii-network-nodes.json            # Per-node network view
│   │   └── fukuii-network-overview.json         # Aggregate network overview
│   ├── Sepolia Consensus/                        # ETH/Sepolia (PoS) Engine API dashboards
│   │   ├── fukuii-engine-api-dashboard.json     # Engine API call overview
│   │   ├── fukuii-engine-api-detail.json        # Engine API detailed metrics
│   │   ├── fukuii-lighthouse.json               # Paired Lighthouse CL metrics
│   │   └── fukuii-sepolia-staking.json          # Sepolia staking/validator metrics
│   └── Sync/                                     # Sync-mode-specific dashboards
│       ├── fukuii-snap-sync.json                # SNAP sync progress/state
│       └── fukuii-sync-peers.json               # Sync-related peer metrics
├── prometheus/                                   # Prometheus configuration
├── run-007-research/                             # Research directory for investigations
└── README.md                                     # This file
```

## Grafana

The `grafana/` directory contains 15 pre-configured Grafana dashboards organized into 5
categories (`Archive/`, `ETC Node/`, `Network/`, `Sepolia Consensus/`, `Sync/`), designed
for Barad-dûr integration. There is currently no dedicated dashboard-contribution guide
documenting the provisioning/naming/versioning convention for adding a new one — see
`docs/research/best-practices/evm-clients/repo-patterns/erigon/repo-hygiene-pattern.md`
for Erigon's `creating-a-dashboard.md` as a reference model.

## Run Configurations

### Barad-dûr - Kong API Gateway Stack

The `barad-dur/` directory contains a production-ready API ops stack with Kong API Gateway, named after Sauron's Dark Tower - the fortified gateway to Fukuii.

**Purpose**: Production API gateway with high availability, monitoring, and security features.

**Features**:
- Kong API Gateway (3.9) with PostgreSQL backend
- Multiple Fukuii instances with load balancing
- Prometheus metrics collection
- Grafana visualization dashboards
- DB-less mode option for simpler deployments
- Automated setup and testing scripts

**Quick Start**:
```bash
cd ops/barad-dur
./setup.sh
```

For detailed information, see [barad-dur/README.md](barad-dur/README.md).

### Cirith Ungol - ETC Mainnet Testing Environment

The `cirith-ungol/` directory contains the testing configuration for running a Fukuii node on **ETC mainnet** with comprehensive logging, named after the pass of the spider in Mordor.

**Purpose**: General testing and validation environment for ETC mainnet operations.

**Features**:
- Network: ETC mainnet
- DEBUG logging configuration
- Docker Compose deployment ready
- Quick start script for easy management
- Historical issue resolution documentation

**Quick Start**:
```bash
cd ops/cirith-ungol
./start.sh start
```

For detailed information, see [cirith-ungol/README.md](cirith-ungol/README.md) and [cirith-ungol/ISSUE_RESOLUTION.md](cirith-ungol/ISSUE_RESOLUTION.md).

### Gorgoroth - Internal Test Network

The `gorgoroth/` directory contains configurations for an internal private test network, named after the plateau in Mordor where Sauron trained his armies.

**Purpose**: Private network testing for multi-client interoperability and Fukuii validation.

**Features**:
- Network: Private test network (Mordor-aligned; Chain ID: 0x3f / 63; Network ID: 7)
- Discovery disabled (static peer connections)
- Multiple deployment configurations:
  - 3 Fukuii nodes
  - 6 Fukuii nodes
  - 3 Fukuii + 3 Core-Geth
  - 3 Fukuii + 3 Besu
  - 3 Fukuii + 3 Besu + 3 Core-Geth (mixed)
- Easy deployment and log collection scripts
- Pre-configured genesis with funded accounts

**Quick Start**:
```bash
cd ops/gorgoroth
fukuii-cli start 3nodes
```

**New to Gorgoroth?** See the [Quick Start Guide](gorgoroth/QUICKSTART.md) for a complete step-by-step setup walkthrough.

For detailed information, see [gorgoroth/README.md](gorgoroth/README.md).

### Run 007 - Research

The `run-007-research/` directory contains research and investigation notes related to network protocol analysis.

For detailed information, see [run-007-research/README.md](run-007-research/README.md).

### Available Dashboards

#### ETC Node/ — general node health & debugging
- **fukuii-node-health.json** — general node health overview
- **fukuii-node-troubleshooting-dashboard.json** — sync phase, JVM heap/GC, RPC latency,
  peer churn, network message rates, file descriptor usage

#### Network/ — cross-network P2P visibility
- **fukuii-multi-network-resources.json** — resource usage across all running networks
- **fukuii-network-nodes.json** — per-node network view
- **fukuii-network-overview.json** — aggregate network/peer overview

#### Sepolia Consensus/ — ETH/Sepolia PoS Engine API
- **fukuii-engine-api-dashboard.json** — Engine API call overview
- **fukuii-engine-api-detail.json** — Engine API detailed metrics
- **fukuii-lighthouse.json** — paired Lighthouse consensus-layer metrics
- **fukuii-sepolia-staking.json** — Sepolia staking/validator metrics

#### Sync/ — sync-mode-specific
- **fukuii-snap-sync.json** — SNAP sync progress/state
- **fukuii-sync-peers.json** — sync-related peer metrics

#### Archive/ — superseded, kept for reference
- **fukuii-dashboard.json** (Control Tower — original main dashboard), **fukuii-casual-
  dashboard.json** (minimalist at-a-glance), **fukuii-miners-dashboard.json** (mining
  metrics), **fukuii-fast-sync.json** (legacy pre-SNAP fast-sync view)

### Using the Dashboards

1. Import the dashboards into your Grafana instance:
   - Navigate to Grafana UI (typically `http://localhost:3000`)
   - Go to Dashboards → Import
   - Upload any of the dashboard JSON files from `ops/grafana/`
   - Select your Prometheus datasource
   - Click Import

2. The dashboards require:
   - Grafana 7.0 or later
   - Prometheus datasource configured
   - Fukuii metrics enabled (`fukuii.metrics.enabled = true`)

### Recommended Setup

| Use Case | Dashboard |
|----------|-----------|
| Day-to-day ETC node monitoring | `ETC Node/fukuii-node-health.json` |
| Debugging a specific node | `ETC Node/fukuii-node-troubleshooting-dashboard.json` |
| Multi-network/peer visibility | `Network/fukuii-network-overview.json` |
| ETH/Sepolia Engine API + Lighthouse pairing | `Sepolia Consensus/fukuii-engine-api-dashboard.json` + `fukuii-lighthouse.json` |
| SNAP sync progress | `Sync/fukuii-snap-sync.json` |
| Simple/casual wall-display view | `Archive/fukuii-casual-dashboard.json` (superseded, still usable) |

### Dashboard Requirements

The dashboard expects the following Prometheus scrape jobs to be configured:

```yaml
scrape_configs:
  - job_name: 'fukuii-node'
    static_configs:
      - targets: ['localhost:13798']  # Fukuii metrics endpoint
  
  - job_name: 'fukuii-pekko'
    static_configs:
      - targets: ['localhost:9095']   # JMX/Kamon metrics endpoint
```

## Metrics Configuration

For detailed information about metrics, logging, and monitoring, see:
- [Metrics and Monitoring Guide](../docs/operations/metrics-and-monitoring.md)

## Prometheus Configuration

Example Prometheus configuration files can be found in:
- `docker/fukuii/prometheus/prometheus.yml`

## Related Documentation

- [Operations Runbooks](../docs/runbooks/README.md)
- [Docker Documentation](../docs/deployment/docker.md)
- [Architecture Overview](../docs/architecture/architecture-overview.md)
