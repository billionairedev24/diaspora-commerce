# Load tests (k6)

k6 scripts for the two latency-critical paths (backlog S200):

| Script | What it drives | Budgets (thresholds) |
|---|---|---|
| `search.js` | free-text query, faceted filtering (disjunctive facets + sort), typeahead — through the BFF (`/v1/search`, `/v1/search/suggestions`) or straight at the search service (`/v1/search`, `/v1/suggest`) | search p95 < 150 ms · facets p95 < 200 ms · typeahead p95 < 100 ms · errors < 1 % · checks > 99 % |
| `checkout.js` | search → add to cart → checkout summary → place order (`Idempotency-Key`, plus an idempotent replay) through the BFF; `flash` proves no overselling | add-to-cart p95 < 300 ms · summary p95 < 500 ms · place-order p95 < 1.5 s · flow errors < 1 % · flash: exactly N orders placed, 25 refused |

A run fails (exit code 99) when any threshold is crossed, so the scripts can gate a pipeline.

## Scenarios

Pick one with `-e SCENARIO=…` (default `smoke`):

| Scenario | Shape | Use |
|---|---|---|
| `smoke` | 1 VU — search: 30 s with 0.5 s think time; checkout: 3 iterations | after every deploy; proves script + environment |
| `load` | arrival rate ramps to `RATE` over `RAMP` (1 m), holds for `DURATION` (5 m) | the S200 budget check at target load |
| `stress` | ramps to `RATE`, 2×, 3× and holds 3× for 5 m (≈ 15 min) | find the knee; search does not abort early here |
| `flash` (checkout only) | `FLASH_UNITS + 25` customers order one offer at the same moment | no overselling: exactly `FLASH_UNITS` succeed |

## Configuration (env, pass with `-e NAME=value`)

| Var | Default | Meaning |
|---|---|---|
| `BASE_URL` | `http://localhost:8200` | gateway BFF origin |
| `TARGET` | `bff` | search.js only: `search` hits the search service at `SEARCH_URL` |
| `SEARCH_URL` | `http://localhost:8201` | search service origin |
| `TOKEN` | — | customer bearer token (checkout needs one; search works anonymously) |
| `TOKENS` | — | comma-separated tokens, one per VU — use for checkout at concurrency and for `flash` (one cart per customer) |
| `SESSION` | — | alternatively a BFF session id, sent as the `at_session` cookie |
| `SCENARIO` | `smoke` | `smoke` \| `load` \| `stress` \| `flash` |
| `RATE` | 50 (search) / 5 (checkout) | iterations per second at steady state (search iteration ≈ 2.6 requests) |
| `RAMP`, `DURATION` | `1m`, `5m` | load ramp and hold |
| `MAX_VUS` | 200 | VU ceiling for arrival-rate executors (k6 reports `dropped_iterations` when it is too low) |
| `ITERATIONS` | 3 | checkout smoke iterations |
| `PLACE_ORDER` | `1` | `0` stops checkout at the summary (no orders, no payments) |
| `FLASH_OFFER_ID`, `FLASH_UNITS` | —, 10 | flash: an offer with exactly `FLASH_UNITS` units in stock |

## Install k6

```bash
brew install k6                                   # macOS
go install go.k6.io/k6@latest                     # anywhere with Go (binary in ~/go/bin)
docker run --rm -i --network host -v "$PWD/tests/load:/scripts" grafana/k6 run /scripts/search.js
k6 inspect tests/load/search.js                   # validate a script and print its options without running it
```

## Run locally against docker compose

```bash
make up                                           # Postgres, Valkey, Kafka, Elasticsearch, … (wait for healthy)
make apps-up SERVICE=search                       # search on :8201 (compose profile "apps")

# Search service directly
k6 run -e TARGET=search tests/load/search.js
k6 run -e TARGET=search -e SCENARIO=load -e RATE=100 tests/load/search.js

# Through the BFF (not in compose yet: run it from source)
(cd go/services/gateway-bff && APP_ENV=local go run ./cmd/gateway-bff)   # :8200
k6 run tests/load/search.js
```

The index is empty on a fresh stack; seed it through the indexer (catalog events) or bulk-load a fixture set into
`products-us` before measuring — latency on an empty index says nothing about relevance scoring cost.

Checkout needs the whole order path (identity, cart, offers, inventory, orders, payments with Stripe test mode)
and a customer token:

```bash
export TOKEN=$(…obtain an access token for a test customer from identity on :8100…)
k6 run -e TOKEN=$TOKEN tests/load/checkout.js                         # smoke: 3 orders
k6 run -e TOKEN=$TOKEN -e PLACE_ORDER=0 -e SCENARIO=load tests/load/checkout.js
```

Local numbers validate the scripts and catch gross regressions; budgets are only meaningful on dev/uat-sized infra.

## Run against dev

```bash
export BASE_URL=https://api.dev.afrotransact.com  # gateway BFF in dev (api.${DEV_BASE_DOMAIN}, as in security.yml)
export TOKENS=$(paste -sd, load-test-tokens.txt)  # tokens of dedicated load-test customers (never real users)
k6 run -e SCENARIO=smoke tests/load/search.js
k6 run -e SCENARIO=load  -e RATE=200 tests/load/search.js            # S200: p95 < 150 ms at target load
k6 run -e SCENARIO=load  -e RATE=5 -e TOKENS=$TOKENS tests/load/checkout.js
k6 run -e SCENARIO=flash -e FLASH_OFFER_ID=<offer seeded with 10 units> -e FLASH_UNITS=10 -e TOKENS=$TOKENS tests/load/checkout.js
```

Etiquette for shared environments: announce load/stress runs in the team channel, use load-test accounts and a
load-test seller/offer (orders are real records in dev), keep `stress` for uat or a dedicated window, and add
`--out experimental-prometheus-rw` (with `K6_PROMETHEUS_RW_SERVER_URL`) to see k6 metrics next to the service
dashboards in Grafana.

## Results so far

| Date | Target | Scenario | Result |
|---|---|---|---|
| 2026-10-10 | search service (local, ES 9.3 single node, 200 synthetic docs) | smoke 20 s, 1 VU | all thresholds pass · search p95 12 ms · facets p95 13 ms · suggest p95 7 ms · 0 % errors |
| 2026-10-10 | same | load 150 it/s (≈ 280 req/s) for 30 s | all thresholds pass · search p95 11 ms · facets p95 10 ms · suggest p95 6 ms · 0 % errors |
