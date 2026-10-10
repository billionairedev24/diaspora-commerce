# OtelCollectorExportFailures

**Severity:** `ticket` · **Defined in:** `infra/observability/rules/services.yaml` · **Dashboard:** Grafana → AfroTransact / —

## What it means
The OpenTelemetry Collector is failing to export spans, metrics or logs for 10 minutes.

## Impact
Missing traces/metrics/logs; alerts based on pushed metrics may go blind.

## Diagnose
1. `kubectl -n observability logs deploy/otel-collector` — which exporter (otlp/tempo, prometheusremotewrite, otlphttp/loki)?
2. Is the backend healthy (Tempo distributor, Prometheus remote-write receiver, Loki gateway)?
3. Memory limiter refusing data? Check `otelcol_processor_refused_*`.

## Mitigate
- Restart/scale the failing backend; raise collector memory if refusing; check NetworkPolicies in `observability`.

## After the incident
- Link the incident ticket to this runbook and update the steps if they were wrong or incomplete.
- If the alert was noisy, tune the threshold in the rule file (then `make rules`) rather than silencing it permanently.
