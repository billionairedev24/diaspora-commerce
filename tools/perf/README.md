# Performance and load testing

Scripts to stress **read-heavy** paths on a running stack (`make up` or a shared environment). They are meant for
engineering experiments—not CI gates—and they will hammer Postgres, Redis, and Elasticsearch if you point them at a
small local machine.

## Prerequisites

| Tool | Install (macOS) | Role |
|------|-----------------|------|
| [k6](https://grafana.com/docs/k6/) | `brew install k6` | Scenarios with thresholds, JSON metrics, easy ramp-up |
| Apache Bench (`ab`) | built-in | Quick single-URL numbers |
| [hey](https://github.com/rakyll/hey) | `go install github.com/rakyll/hey@latest` | Simple modern alternative to `ab` |
| [wrk](https://github.com/wg/wrk) | `brew install wrk` | High concurrency, latency histograms |

Ports match [`infra/local/PORTS.md`](../../infra/local/PORTS.md) (gateway-bff **8200**, search **8201**, …).

## Configuration

Defaults live in [`env.sh`](env.sh). Override in the shell or in **`.local/perf.env`** (gitignored), for example:

```bash
GATEWAY_BFF_URL=http://127.0.0.1:8200
SEARCH_URL=http://127.0.0.1:8201
PERF_MARKET=US
PERF_ZIP=77042
PERF_SEARCH_Q=egusi
PERF_VUS=20
PERF_DURATION=1m
PERF_AB_REQUESTS=3000
PERF_AB_CONCURRENCY=40
# PERF_BEARER_TOKEN=…   # optional, for authenticated BFF routes
```

Run `make seed` before search/home scenarios if the index and catalog are empty.

## k6

```bash
tools/perf/run-k6.sh smoke              # health checks only, ~15s
tools/perf/run-k6.sh gateway-readonly   # /healthz + GET /v1/home
tools/perf/run-k6.sh search             # GET /v1/search

K6_VUS=30 K6_DURATION=2m tools/perf/run-k6.sh search
```

Scenarios are under [`k6/scenarios/`](k6/scenarios/). Add a new file `my-scenario.js` and pass `run-k6.sh my-scenario`.

Makefile shortcuts: `make perf-k6 SCENARIO=search`, `make perf-k6-smoke`.

## Apache Bench

```bash
tools/perf/run-ab.sh
tools/perf/run-ab.sh 'http://127.0.0.1:8100/actuator/health'
PERF_AB_REQUESTS=500 PERF_AB_CONCURRENCY=10 tools/perf/run-ab.sh
```

`-k` enables HTTP keep-alive. For POST bodies, call `ab` yourself with `-p payload.json -T application/json`.

## hey

```bash
tools/perf/run-hey.sh
HEY_DURATION=45s tools/perf/run-hey.sh 'http://127.0.0.1:8200/v1/home?market=US&zip=77042'
```

## wrk

```bash
tools/perf/run-wrk.sh
WRK_THREADS=8 WRK_CONNECTIONS=64 WRK_DURATION=1m tools/perf/run-wrk.sh
```

## Tips

- **Warm up** Elasticsearch and the JVM once (single request) before recording numbers.
- **Do not** load-test checkout, payments capture, or webhooks against production without an explicit runbook.
- For realistic shopper flows (cart + checkout), extend k6 with a token from identity (`PERF_BEARER_TOKEN`) and keep
  concurrency low locally.
- Export k6 results: `k6 run --out json=tools/perf/results/run.json …` (add `tools/perf/results/` to `.gitignore` if
  you keep artifacts).

## Adding scenarios

1. Copy [`k6/scenarios/gateway-readonly.js`](k6/scenarios/gateway-readonly.js).
2. Use paths from `contracts/openapi/` and [`infra/local/PORTS.md`](../../infra/local/PORTS.md).
3. Set thresholds per SLO (p95 latency, error rate).
4. Document required seed data in this README.
