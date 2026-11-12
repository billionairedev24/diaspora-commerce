# Observability on Grafana Cloud (Alloy, alerts, dashboards)

Production runs on one OVH node with no in-cluster Prometheus, Loki or Grafana (ADR 0004,
[cloud-cost.md](../engineering/cloud-cost.md)). Everything is looked at in **Grafana Cloud**, one stack for the 8-day trial and for
production: **https://afrotransact.grafana.net/** (the stack URL is not a secret). **Nothing here has run against the live stack yet**:
every Grafana Cloud menu name, default data-source uid and limit below is **UNVERIFIED** (menus move; the owner's screen wins).

| Signal | Path | Where it is configured |
|---|---|---|
| Traces | services -> OTel collector (`observability` ns) -> Grafana Cloud OTLP | `infra/k8s/platform/envs/prod/opentelemetry-collector-gateway.yaml`, Secret `grafana-cloud-otlp` |
| Metrics | **Grafana Alloy** scrapes every 60 s -> cardinality guard -> `remote_write` to hosted Prometheus | `infra/k8s/platform/lean/alloy/config.alloy`, Secret `grafana-cloud-alloy` |
| Logs | Alloy tails pod logs -> drops probe noise -> redacts -> pushes to hosted Loki | same file |
| Alerts, dashboards | code in `infra/observability`, loaded by `infra/observability/scripts/grafana-cloud-load.sh` | this page, sections 6 and 7 |

The OTel collector's `metrics` and `logs` pipelines also point at Grafana Cloud over OTLP. Services export only traces over OTLP today
(the OTLP tracing block in their `application.yml`; no OTLP metrics or logs export is configured), so Alloy is the only metric and log path. If you ever turn on
OTLP metrics or logs in a service, remove that pipeline or you will pay for the same data twice.

## 1. One stack, two environments: the `env` label

Alloy puts the external label `env` on **every** series and **every** log line, from the Secret key `CLUSTER_ENV` (`trial` or `prod`,
rendered from `GRAFANA_ENV` in `secrets.local.env`, default = the profile, so `prod`). Consequences:

- Dashboards have an `env` variable that starts on `prod` (the loader sets it); alert rules keep `env` on every alert.
- **Notification routing sends only `env="prod"` alerts to the contact point.** Trial alerts go to a quiet receiver (section 8), so the trial cluster can
  fail all day without paging anyone.
- Each environment uses its **own access-policy token** (section 3), so one can be revoked without touching the other.
- Rules that must only exist for production (`absent(...)` alerts) hard-code `env="prod"`.

## 2. Open the stack (owner, once)

1. Sign in at https://grafana.com (the account that owns the stack) and open **My Account**, then your organisation, then the stack
   **afrotransact**. Confirm its **region is US East** (cloud-cost.md puts the whole platform in Virginia). A region cannot be changed later.
2. Click **Launch** (or open https://afrotransact.grafana.net/ directly). You land on the Grafana home page.

## 3. Get the endpoints, user ids and tokens (owner)

**Never paste a token into chat, an issue or a commit.** Tokens are shown once; copy each straight into the password manager or into
`secrets.local.env` on your machine.

### 3.1 Metrics endpoint and user id

1. On https://afrotransact.grafana.net/ open the left menu **Connections**, then **Hosted Prometheus metrics** (if the menu has no such
   entry: **Connections > Add new connection**, search "Hosted Prometheus metrics").
2. Open the **Send metrics** (remote write) tab. Copy the **Remote Write Endpoint**: it looks like
   `https://prometheus-prod-NN-<region>.grafana.net/api/prom/push`. -> `GRAFANA_PROM_URL`.
3. Copy the **Username / Instance ID** (a number). -> `GRAFANA_PROM_USER`.
4. Also note the **query URL** base `https://prometheus-prod-NN-<region>.grafana.net` (without `/api/prom/push`): -> `MIMIR_ADDRESS` for the rule loader (section 6).
   The instance id is the same number: -> `MIMIR_TENANT_ID`.

### 3.2 Logs endpoint and user id

1. **Connections > Hosted logs** (or **Add new connection** > "Hosted logs").
2. Copy the **URL** (`https://logs-prod-NNN.grafana.net/loki/api/v1/push`) -> `GRAFANA_LOKI_URL`, and the **User** (a number, different from the
   metrics one) -> `GRAFANA_LOKI_USER`.

### 3.3 Access-policy tokens (scoped, one per purpose)

Open **Administration > Cloud access policies** (older UIs: **Administration > Access policies**; if it is missing, use grafana.com > your
organisation > **Access Policies**). For each row below click **Create access policy**, set the name, **Realm: this stack only**
(`afrotransact`), add exactly the listed scopes, click **Create**, then **Add token** on the new policy (set an expiry, for example 1 year, and
put the date in the calendar) and **copy the `glc_...` value once**.

| Policy name | Scopes | Token goes to | Lives |
|---|---|---|---|
| `afrotransact-alloy-prod` | `metrics:write`, `logs:write` **only** | `GRAFANA_ALLOY_TOKEN` in the prod `secrets.local.env` (-> Secret key `GRAFANA_TOKEN`) | the prod cluster |
| `afrotransact-alloy-trial` | `metrics:write`, `logs:write` **only** | `GRAFANA_ALLOY_TOKEN` in the trial `secrets.local.env` | the trial cluster only; delete the policy when the trial ends |
| `afrotransact-rules-deploy` | `rules:read`, `rules:write` | env var `MIMIR_API_KEY` on **your machine** when you run the loader | never in the cluster |
| (existing) OTLP traces | `traces:write` (and the OTLP gateway as Grafana shows it) | `GRAFANA_API_TOKEN` (Secret `grafana-cloud-otlp`) | the cluster |

Dashboards use a Grafana **service account**, not an access policy: **Administration > Users and access > Service accounts > Add service
account** `afrotransact-dashboards`, role **Editor**, then **Add service account token** (expiry set). -> env var `GRAFANA_SA_TOKEN` on your machine.

The Alloy token cannot read data, change rules or touch dashboards. If it leaks, delete that one token and make a new one.

## 4. Put the values into our secrets

Same flow as every other secret (`infra/ops/secrets/README.md`): fill `infra/ovh-vps/secrets.local.env` (never committed), render, encrypt with
SOPS, apply.

| `secrets.local.env` key | Secret `grafana-cloud-alloy` (ns `observability`) key | Value |
|---|---|---|
| `GRAFANA_ENV` | `CLUSTER_ENV` | `prod` (on the 8-day trial cluster: `trial`) |
| `GRAFANA_PROM_URL` | `GRAFANA_PROM_URL` | endpoint from 3.1 |
| `GRAFANA_PROM_USER` | `GRAFANA_PROM_USER` | id from 3.1 |
| `GRAFANA_LOKI_URL` | `GRAFANA_LOKI_URL` | URL from 3.2 |
| `GRAFANA_LOKI_USER` | `GRAFANA_LOKI_USER` | user from 3.2 |
| `GRAFANA_ALLOY_TOKEN` | `GRAFANA_TOKEN` | the policy token from 3.3 for this env |
| (generated) | `VALKEY_PASSWORD`, `MEILI_MASTER_KEY` | copied by `secrets.sh` from the Valkey and Meilisearch values so Alloy can scrape them |

`infra/ops/secrets/apply.sh` refuses to apply the Secret while one of the first six keys is empty. Optional OKMS path: the same keys under
`afrotransact/grafana-cloud-alloy` (`lean/external-secrets/externalsecrets.yaml`).

```bash
cd infra/ovh-vps && PROFILE=prod STATE_DIR=$HOME/.afrotransact-prod SECRETS_OUT=$(mktemp -d) ./secrets.sh platform   # renders observability/grafana-cloud-alloy.yaml
# encrypt and apply as in infra/ops/secrets/README.md, then, if Alloy already runs:
kubectl -n observability rollout restart deploy/alloy
```

## 5. Deploy and verify data arrives

Alloy, kube-state-metrics and kafka-exporter are part of `lean-prod` (Argo CD sync of the `lean-prod` Application, wave 0, manual in prod like everything else;
the Secret must exist first). Check:

```bash
kubectl -n observability get pods                    # alloy-..., kube-state-metrics-..., kafka-exporter-...  all Ready
kubectl -n observability logs deploy/alloy | tail    # no "401", "403", "429", "context deadline" lines
kubectl -n observability port-forward deploy/alloy 12345 &   # http://localhost:12345 : Alloy UI, every component should be healthy
```

Then in Grafana (https://afrotransact.grafana.net/) **Explore**:

1. Data source: the hosted Prometheus one (named `grafanacloud-<stack>-prom`; UNVERIFIED). Run `count by (job) (up{env="prod"})`. Expected within
   2-3 minutes: one row per Java/Go service name (`identity`, `catalog`, ... 21 of them), plus `temporal`, `cloudflared`, `kafka-exporter`, `valkey`,
   `meilisearch`, `kube-state-metrics`, `kubelet`, `cadvisor`, `alloy`, all with value 1.
2. `http_server_requests_seconds_count{env="prod", service="orders"}` returns rows; `jvm_memory_used_bytes{service="catalog"}` too.
3. Data source: hosted Loki. `{env="prod", app="orders"}` shows JSON lines. Check the labels offered are only `app`, `namespace`, `pod`, `env`.
4. **Redaction check:** search `{env="prod"} |~ "@[a-z0-9-]+\\.[a-z]{2,}"` (any e-mail shape) and `{env="prod"} |~ "(?i)bearer [a-z0-9]"`: expect nothing,
   except `[redacted-...]` markers. Log in once on the live site and repeat.
5. **Probe noise check:** `{env="prod"} |= "/actuator/health"` returns nothing.

If a series is missing, the `up` row for that job says why (`up == 0`: network policy, wrong port annotation, the 4000-sample guard).
Common causes are in section 11.

## 6. Load the alert rules and dashboards

The loader needs `mimirtool` (https://grafana.com/docs/mimir/latest/manage/tools/mimirtool/), `curl` and `python3`, and the tokens from 3.3 as
environment variables **on your machine**:

```bash
export MIMIR_ADDRESS=https://prometheus-prod-NN-<region>.grafana.net   # 3.1, no /api/prom
export MIMIR_TENANT_ID=<metrics instance id>
export MIMIR_API_KEY=<token of afrotransact-rules-deploy>
export GRAFANA_SA_TOKEN=<service account token>
infra/observability/scripts/grafana-cloud-load.sh check        # offline: lint rules, validate dashboard JSON (no credentials)
DRY_RUN=1 infra/observability/scripts/grafana-cloud-load.sh all
infra/observability/scripts/grafana-cloud-load.sh all          # rules + dashboards; run again after every change (idempotent)
```

- **Rules** loaded: `services.yaml`, `kafka.yaml`, `inventory.yaml`, `payments-transfer-recovery.yaml`, `backup.yaml`, `catalog-tax-gate.yaml`, `launch.yaml` (in `infra/observability/rules`). The LogQL backup
  alerts in `infra/observability/loki-rules/backup.yaml` (they read the `backup_metrics` line the job logs; the stream carries `app="pg-backup"`, `namespace="ops"` and `env` from Alloy) load with
  `grafana-cloud-load.sh lokirules` (needs `LOKI_ADDRESS` = Hosted logs URL without `/loki/api/v1/push` and `LOKI_TENANT_ID` = the logs user id; UNVERIFIED against Grafana Cloud).
  `mimirtool rules sync` makes the ruler equal to these files, so do not hand-create rules in this stack. Not loaded yet: `slo-*.yaml` (their
  metrics are in the metrics contract but not in the code, section 9), `elasticsearch.yaml`, `postgres.yaml` (no exporters in the lean platform).
  Check in Grafana: **Alerting > Alert rules**, data-source managed rules.
- **Dashboards** loaded into the folder **AfroTransact**: `launch-platform` (node, pods, PVCs, Valkey, Meilisearch, Temporal, tunnel, backup age, Alloy),
  `launch-payments` (checkout and payments funnel), `platform-overview` (RED per service), `jvm` (JVM and Hikari), `kafka-consumer-lag`.
  Edit them in `infra/observability/scripts/generate_dashboards.py` and run `make dashboards`; never edit them in the UI (they are `editable: false`).
- The loader rewrites the data-source uids (`PROM_DS_UID`, default `grafanacloud-prom`; `LOKI_DS_UID`, default `grafanacloud-logs`; both UNVERIFIED):
  read the real uids under **Connections > Data sources** (the uid is in the page URL) and export them if they differ.
- The same rules and dashboards also exist as PrometheusRule / ConfigMap manifests for the kube-prometheus-stack profile (dev, uat) in
  `infra/k8s/platform/observability`; prod does not use them.

## 7. What was added to the cluster (and the memory it costs)

| Piece | Request / limit | Why |
|---|---|---|
| Alloy (1 pod, Deployment, strategy Recreate) | 96 Mi / 256 Mi | scrape, tail logs through the Kubernetes API, remote write; `GOMEMLIMIT 200MiB` |
| kafka-exporter (1 pod) | 24 Mi / 64 Mi | consumer-group lag and DLQ offsets (the metric names `rules/kafka.yaml` uses). A separate pod because Alloy's built-in Kafka exporter makes Alloy fail its initial load while the broker is unreachable (observed with the Alloy binary), which would blind everything during a Kafka outage |
| kube-state-metrics (1 pod) | 32 Mi / 96 Mi | pod restarts, OOMKilled reason, node conditions, CronJob state (restricted to 7 resources and an explicit metric allowlist) |
| **Total new** | **152 Mi requests**, 416 Mi limits | **UNMEASURED**: read `kubectl top pods -n observability` in the trial and adjust |

Other changes: the Kafka, Valkey, Meilisearch and Temporal NetworkPolicies admit the `observability` namespace on their metrics port; Temporal
gets `PROMETHEUS_ENDPOINT=0.0.0.0:9090` and the scrape annotations; Meilisearch gets `MEILI_EXPERIMENTAL_ENABLE_METRICS=true` (experimental in
v1.12; its `/metrics` needs the master key as bearer token, hence `MEILI_MASTER_KEY` in the Alloy Secret). The shared chart
`infra/helm/library/at-service` already put `prometheus.io/{scrape,path,port}` on every service pod, so no port or annotation had to be
added there; it **now also sets `MANAGEMENT_METRICS_DISTRIBUTION_SLO_HTTP_SERVER_REQUESTS`** (six latency buckets) on Java services, because without
buckets there is no `http_server_requests_seconds_bucket` and the p95 alert and dashboards would read nothing. A service's `extraEnv` can override it.

**Single node assumption.** One Alloy pod scrapes everything. With a second node, run Alloy as a DaemonSet or enable Alloy clustering, otherwise
the cluster-wide jobs (Kafka, Valkey, kube-state-metrics) are collected twice.

## 8. Contact points and routing (owner, in the UI)

No real address is in the repository: the placeholder is `ops-alerts@example.invalid`.

1. **Alerting > Contact points > Add contact point**: name `afrotransact-prod-email`, integration **Email**, address = the on-call mailbox
   (use a shared mailbox plus a phone-notification rule rather than one person's inbox). Click **Test**: an email must arrive.
2. Optional second contact point for urgent pages (Grafana IRM / OnCall, Slack, or a phone push; UNVERIFIED what the free tier includes).
3. **Alerting > Notification policies**: set the **default policy** to `afrotransact-prod-email`; group by `alertname`, `env`, `service`.
   Add a **nested policy**: matcher `env` `=` `trial` -> contact point `trial-quiet` (create it empty: a contact point with no integration), or attach a mute timing
   "always". Add a nested policy `env` `=` `prod` and `severity` `=` `page` with a repeat interval of 1 hour (tickets: 4 hours).
4. **Alerting > Alert rules**: confirm the loaded rules are in state Normal, not "no data" noise. Trigger one on purpose in the trial (scale a trial
   service to 0: `DeploymentUnavailable` for `env="trial"`) and check that **nothing** is mailed; then check prod routing with a test notification
   on the policy (**More > Test**).
5. Where the loaded rules deliver (Grafana Alertmanager or the Mimir Alertmanager) is UNVERIFIED: **Alerting > Admin** lists the Alertmanagers.
   `infra/observability/grafana-cloud/alertmanager.yaml` holds the same routing as code for the Mimir one (`grafana-cloud-load.sh alertmanager`).
6. Add an **external uptime check** (Grafana Synthetic Monitoring if the free tier has it (UNVERIFIED), or any outside service) for
   `https://www.afrotransact.com/`, the BFF health path (`/bff/healthz` on the API host) and the OIDC discovery URL: metrics alerts cannot see a dead
   Cloudflare Tunnel from outside, and TLS certificates are managed by Cloudflare (there is no cert-manager in the cluster).

## 9. Alert reference

Rules live in `infra/observability/rules/launch.yaml` unless a file is named. Status of the underlying metric, checked by grep of the code on
this branch: **confirmed** = a meter or collector exists in the code (or a standard exporter name); **UNCONFIRMED** = the alert is loaded but stays
silent until the series exists.

| You asked for | Alert (severity) | Metric | Status |
|---|---|---|---|
| Service down | `ServiceDown` (page, `services.yaml`), `DeploymentUnavailable` (page), `InfraTargetDown` (page), `KafkaBrokerDown` (page) | `up`, `kube_deployment_*`, `kafka_brokers` | confirmed. `DeploymentUnavailable` exists because a crash-looping pod is not a scrape target |
| 5xx rate | `HighErrorRate` (page, `services.yaml`) | `http_server_requests_seconds_count`, `http_server_requests_total` | confirmed |
| p95 latency | `HighLatency` (ticket, `services.yaml`) | `http_server_requests_seconds_bucket` (Java, needs the buckets this change adds), `http_server_request_duration_seconds_bucket` (Go) | confirmed; Java series appear after the next deploy of each service |
| Hikari pool exhaustion | `HikariPoolExhausted` (page), `HikariConnectionTimeouts` (ticket), `DatabasePoolSaturation` (ticket, `services.yaml`) | `hikaricp_connections_{active,max,pending,timeout_total}` | confirmed (Spring Boot default Hikari binder) |
| JVM heap near limit | `JvmHeapNearLimit` (ticket), `ContainerMemoryNearLimit` (ticket) | `jvm_memory_used_bytes`, `jvm_memory_max_bytes`, `container_memory_working_set_bytes`, `container_spec_memory_limit_bytes` | confirmed |
| Kafka consumer lag | `KafkaConsumerLagHigh`, `KafkaConsumerLagGrowing`, `KafkaDeadLetterMessages`, `KafkaDeadLetterWriteFailing`, `IndexerLagHigh` (`kafka.yaml`) | `kafka_consumergroup_lag` (Alloy's Kafka exporter), `events_dead_letter_failures_total`, `indexer_event_lag_seconds` | lag, DLQ: confirmed (exporter names); `events_dead_letter_failures_total`: confirmed (Micrometer `events.dead.letter.failures`); **`indexer_event_lag_seconds`: UNCONFIRMED** (contract only) |
| Outbox backlog | `OutboxBacklogGrowing` (ticket, `services.yaml`) | `outbox_pending_events` | **UNCONFIRMED**: no such meter in the code (only `events_outbox_purged_total`); silent. Kafka lag and `KafkaBrokerDown` cover the relay failing |
| Payment capture failures | `PaymentCaptureFailureRatioHigh` (page), `CheckoutFailuresHigh` (page) | `payments_webhooks_total{type="payment_intent.payment_failed"}` ; `checkout_attempts_total` | no capture meter exists. The first uses the confirmed webhook meter with Stripe event-type names (verify the `type` values in Explore); **the second is UNCONFIRMED** |
| Stripe webhook failures | `StripeWebhookProcessingFailed` (page), `StripeWebhookRetrying` (ticket) | `payments_webhooks_total{outcome="failed"\|"retry"}` | confirmed |
| Settlement and reconciliation | `SettlementTransferFailed`, `PaymentsReconciliationDrift` (ticket); `TransferReversalRefused*` (`payments-transfer-recovery.yaml`) | `payments_transfers_total`, `payments_reconciliation_drift_minor`, `payments_transfer_reversals_refused_total` | confirmed |
| Temporal workflow failures | `TemporalWorkflowFailures` (page), `TemporalDown` (page) | `workflow_failed` or `workflow_failed_total` (Temporal server) | **UNCONFIRMED names** for `auto-setup:1.28.1`; both spellings are matched; check Explore for `workflow_*` after the first workflows |
| Disk usage on PVCs | `PersistentVolumeFillingUp` (80%), `PersistentVolumeAlmostFull` (92%, page), `PersistentVolumeFillsWithin24h` | `kubelet_volume_stats_*` | standard kubelet names. Node root disk is **not** covered (no node-exporter) |
| Pod restarts, OOMKilled | `PodRestartingFrequently` (ticket), `ContainerOOMKilled` (page), `ContainerOOMEvents` (ticket) | `kube_pod_container_status_restarts_total`, `kube_pod_container_status_last_terminated_reason`, `container_oom_events_total` | kube-state-metrics: standard; `container_oom_events_total`: UNCONFIRMED (cadvisor version) |
| Node memory pressure | `NodeMemoryPressure` (page), `NodeMemoryWorkingSetHigh` (ticket) | `kube_node_status_condition`, `kube_node_status_allocatable` + cadvisor | standard names |
| Certificate / tunnel down | `CloudflareTunnelDown` (page) | `cloudflared_tunnel_ha_connections` | standard cloudflared name. There is no certificate in the cluster (Cloudflare terminates TLS): use the external uptime check of section 8 for the edge certificate |
| Backup failed / not run in 26 h | `BackupJobFailed` (page), `BackupCronJobSuspended` (ticket) in `launch.yaml`; `BackupStale` (page, 26 h), `BackupSizeHigh`, `BackupBucketSizeHigh` in `backup.yaml` | `kube_cronjob_status_last_{schedule,successful}_time`, `kube_cronjob_created` (our kube-state-metrics); `afrotransact_backup_*` (needs a Pushgateway, none runs: silent; the Loki variant `loki-rules/backup.yaml` reads the `backup_metrics` log line instead) | standard kube-state-metrics names. Keep the dead-man's-switch `HEALTHCHECK_URL` too: it also works when the whole cluster is down |
| `catalog_live_without_tax_decision` | `CatalogLiveWithoutTaxDecision` (page), `CatalogTaxGateClosedListings`, `CatalogTaxGateMetricMissing` (`catalog-tax-gate.yaml`) | `catalog_live_without_tax_decision`, `catalog_tax_gate_closed_total` | confirmed (`MicrometerTaxGateMetrics` in catalog); the Alloy allowlist passes `catalog_.*` |
| The shipper itself | `AlloyNotReporting` (page), `AlloyRemoteWriteFailing` (ticket), `ScrapeTargetCardinalityHigh` (ticket) | `up{service="alloy"}`, `prometheus_remote_storage_*`, `loki_write_dropped_*`, `scrape_samples_post_metric_relabeling` | `AlloyNotReporting` is the dead-man's switch: if Alloy or the node dies, every other alert goes blind |

Runbook links on the alerts point at this page. Business SLO alerts (`slo-*.yaml`) are not loaded until `checkout_attempts_total`,
`payments_webhook_lag_seconds`, `payouts_completed_total` and `notifications_delivery_duration_seconds` exist in the code: then add the file to `RULE_FILES` in the loader.

## 10. Watching usage so the free tier is not exceeded

I do not know the current free-tier numbers, and this repository does not either: cloud-cost.md lists "10k metric series, 50 GB logs, 50 GB traces"
as **UNVERIFIED**. Read the live numbers before the trial starts: on https://grafana.com open **My Account > your organisation > Billing**
(or **Usage**) and the stack's **Details** page, and write the limits you see into cloud-cost.md.

Where to watch:

1. **Billing / usage page** on grafana.com (per product: active metric series, log GB ingested, trace GB, plus what is included).
2. In the stack: the pre-built **Grafana Cloud billing/usage** dashboard (**Dashboards**, search "usage"; data source `grafanacloud-usage`, UNVERIFIED name).
3. Own check, any time, in Explore (Prometheus): `count({env="prod"})` (rough active series), `count by (job) ({env="prod"})` (which job is the biggest),
   `topk(15, count by (__name__) ({env="prod"}))` (which metric names). `max by (job) (scrape_samples_post_metric_relabeling)` is the per-target sample count.
4. Set **usage alerts** in the UI (Alerting > Alert rules > New alert rule, data source `grafanacloud-usage`): active series above 80 % of the
   included amount, and log ingestion above 80 % (the exact metric names are in that data source; UNVERIFIED). Route them to the prod contact point.
   Alloy's own `AlloyRemoteWriteFailing` alert fires when ingestion is rejected.

Rough expected volume (computed from the repository's configuration, **not measured**): 14 Java services at a few hundred series each after the
allowlist (HTTP buckets, JVM, Hikari, Kafka client, business meters), 7 Go services at ~100-200, kafka-exporter ~300-700 (lag and offsets per partition),
cadvisor and kube-state-metrics ~300-500, Valkey, Meilisearch, Temporal, cloudflared ~150. A plausible total is **5-10 thousand** series:
close to a 10 thousand limit if that figure is right. Measure it in the trial (point 3) before the real launch. Logs: JSON lines of ~21 services at launch traffic
are small, but one chatty service or an error loop can dominate; Alloy caps each pod at 100 lines/s.

## 11. If usage is near or over the limit (do these in order)

1. **Find the cause** with the queries in section 10 (point 3). Usually one job or one label (`uri`, `route`, `topic`, `partition`) is most of it.
2. **Tighten the allowlist** in `infra/k8s/platform/lean/alloy/config.alloy` (`prometheus.relabel "guard"`): remove metric families you do not alert or chart on
   (for example `ai_.*`, `tax_.*`, `bff_upstream_.*`, `jvm_buffer_memory_used_bytes`, per-partition `kafka_consumergroup_current_offset`), commit, sync `lean-prod`.
   The ConfigMap name changes with its content, so Alloy restarts by itself. Old series stop counting after they go stale (UNVERIFIED how fast).
3. **Drop a label**: add it to the `labeldrop` rule (for example `uri`, `partition`) after checking that no alert groups by it.
4. **Slow down**: `scrape_interval = "120s"` for the noisy jobs (cadvisor, kubelet, kafka).
5. **Logs**: add namespaces to the exclusion in `discovery.relabel "logs"` (for example `kafka`, `ingress`), lower `stage.limit rate`, add a `stage.drop` for the
   most frequent INFO line (Explore: `topk(10, sum by (app) (count_over_time({env="prod"}[1h])))`).
6. **Emergency stop**: `kubectl -n observability scale deploy/alloy --replicas=0` stops all ingestion immediately (alerts go blind and
   `AlloyNotReporting` fires; say so in the on-call channel). Scale back to 1 after the fix.
7. **Decide with the owner** whether a paid plan or a spending cap is acceptable (UNVERIFIED what Grafana Cloud does at the limit: reject, throttle or bill;
   check **Billing > Plan** and any spend-limit setting before relying on a free tier).
8. Trial data: delete the `afrotransact-alloy-trial` access policy when the trial ends so no stray cluster can still write `env="trial"`.

## 12. Alloy configuration notes

- **Syntax check.** `config.alloy` passes `alloy validate` and `alloy fmt` with the real `grafana/alloy:v1.10.2` binary (extracted from the image), and the log pipeline was
  run against sample lines (e-mail, Bearer header, card number, Stripe key redacted; probe line dropped; JSON kept). It was **not** run against a cluster or Grafana Cloud. To
  re-check after an edit: `docker run --rm -e CLUSTER_ENV=x -e GRAFANA_PROM_URL=x -e GRAFANA_PROM_USER=x -e GRAFANA_LOKI_URL=x -e GRAFANA_LOKI_USER=x -e GRAFANA_TOKEN=x -e VALKEY_PASSWORD=x -e MEILI_MASTER_KEY=x -v "$PWD/infra/k8s/platform/lean/alloy:/c" grafana/alloy:v1.10.2 validate /c/config.alloy`.
  The image tag `grafana/alloy:v1.10.2` and `danielqsj/kafka-exporter:v1.9.0` were seen to exist on Docker Hub; `kube-state-metrics:v2.16.0` on registry.k8s.io is **UNVERIFIED**
  (the registry was not reachable). Bump tags deliberately (no `latest`).
- **Cardinality guard** in `prometheus.relabel "guard"`: a metric-name allowlist, cadvisor reduced to real containers, noisy labels dropped; plus `sample_limit`
  per scrape (a target above it fails its scrape instead of flooding the stack). 60 s everywhere. `remote_write` sends no exemplars or metadata.
- **Logs**: JSON lines are sent as they are. Labels are only `app`, `namespace`, `pod` (+ `env`). Dropped: kube-probe and `/healthz`, `/readyz`, `/metrics`, `/actuator/health|prometheus` request lines.
  Redacted (the matched value is replaced, the line stays): Authorization header values, Bearer tokens, JWTs, Stripe keys and webhook secrets, `password|secret|token|api_key|client_secret|signature = value`,
  e-mail addresses, card-like numbers (four groups of digits, or 15-16 digits with a Visa/Mastercard/Amex/Discover prefix). **Not redacted**: names, street addresses, phone numbers,
  and anything the services log in a shape these patterns do not match. The real protection is not logging PII in the first place; review a day of logs after the trial.
- **Positions** are kept in an `emptyDir`: after an Alloy restart a few seconds of lines can be re-sent or missed.
- **Health checks and metrics** of the Alloy pod itself are on port 12345 (`/-/ready`, `/metrics`).

## 13. Troubleshooting

| Symptom | Likely cause |
|---|---|
| `up == 0` for one service | NetworkPolicy (the chart admits `observability`; check the namespace label `kubernetes.io/metadata.name`), annotation port differs from the container port, the 4000-sample guard |
| `up == 0` for `kafka-exporter` / `valkey` | the broker or Valkey NetworkPolicy does not admit `observability`; kafka-exporter crash-loops while Kafka is down (by design, isolated from Alloy); `VALKEY_PASSWORD` in `grafana-cloud-alloy` differs from the `valkey` Secret |
| `up == 0` for `meilisearch` | `MEILI_EXPERIMENTAL_ENABLE_METRICS` not set (pod not restarted), or `MEILI_MASTER_KEY` differs |
| Alloy logs `401`/`403` | wrong token, wrong user id, or the token lacks `metrics:write` / `logs:write` for this stack |
| Alloy logs `429` or "limit" | ingestion limits reached: section 11 |
| Java latency panels empty | the service has not been redeployed with the latency-bucket environment variable yet |
| Series or lines appear with an empty `env` | the Secret key `CLUSTER_ENV` is empty or missing (`apply.sh` refuses an empty one; a hand-made Secret does not) |
| Temporal panels empty | metric names differ: run `{__name__=~"workflow_.*"}` in Explore and adjust `launch.yaml` and the allowlist |
