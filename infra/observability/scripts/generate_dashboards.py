#!/usr/bin/env python3
"""Generates the Grafana dashboards in infra/observability/grafana/dashboards/*.json.

Dashboards are code: edit this file and run `make dashboards` (or `python3 infra/observability/scripts/generate_dashboards.py`).
The same JSON is loaded locally (file provisioning) and in Kubernetes (Grafana sidecar ConfigMaps, see
infra/k8s/platform/dashboards/kustomization.yaml).

Label conventions (identical locally and in Kubernetes):
  service  — service name from PORTS.md (catalog, search, ...)
  env      — local | dev | uat | prod
Metric conventions:
  Java (Micrometer): http_server_requests_seconds_{count,sum,bucket}{status,uri,method}
  Go (go/pkg/httpserver): http_server_requests_total{status,route,method}, http_server_request_duration_seconds_bucket{route,method}
"""
import json
import pathlib

OUT = pathlib.Path(__file__).resolve().parent.parent / "grafana" / "dashboards"
K8S = pathlib.Path(__file__).resolve().parents[2] / "k8s" / "platform" / "observability" / "dashboards"
PROM = {"type": "prometheus", "uid": "prometheus"}
LOKI = {"type": "loki", "uid": "loki"}

RI = "$__rate_interval"
SEL = 'env=~"$env", service=~"$service"'


class Board:
    def __init__(self, uid, title, tags, description, variables=None, refresh="30s"):
        self.uid, self.title, self.tags, self.description = uid, title, tags, description
        self.variables = variables or []
        self.refresh = refresh
        self.panels = []
        self.y = 0
        self.x = 0
        self.row_h = 0
        self.next_id = 1

    def _place(self, w, h):
        if self.x + w > 24:
            self.y += self.row_h
            self.x, self.row_h = 0, 0
        pos = {"x": self.x, "y": self.y, "w": w, "h": h}
        self.x += w
        self.row_h = max(self.row_h, h)
        return pos

    def row(self, title):
        if self.x:
            self.y += self.row_h
            self.x, self.row_h = 0, 0
        self.panels.append({"id": self._id(), "type": "row", "title": title, "collapsed": False,
                            "gridPos": {"x": 0, "y": self.y, "w": 24, "h": 1}, "panels": []})
        self.y += 1

    def _id(self):
        i = self.next_id
        self.next_id += 1
        return i

    def panel(self, ptype, title, targets, w=12, h=8, unit=None, description=None, ds=PROM, options=None,
              thresholds=None, overrides=None, min_=None, max_=None, custom=None, mappings=None, decimals=None):
        defaults = {}
        if unit:
            defaults["unit"] = unit
        if min_ is not None:
            defaults["min"] = min_
        if max_ is not None:
            defaults["max"] = max_
        if decimals is not None:
            defaults["decimals"] = decimals
        if thresholds:
            defaults["thresholds"] = {"mode": "absolute", "steps": thresholds}
        if mappings:
            defaults["mappings"] = mappings
        if custom:
            defaults["custom"] = custom
        p = {
            "id": self._id(),
            "type": ptype,
            "title": title,
            "datasource": ds,
            "gridPos": self._place(w, h),
            "targets": [dict(t, refId=chr(65 + i), datasource=ds) for i, t in enumerate(targets)],
            "fieldConfig": {"defaults": defaults, "overrides": overrides or []},
            "options": options or {},
        }
        if description:
            p["description"] = description
        self.panels.append(p)
        return p

    def ts(self, title, exprs, **kw):
        """Time series. exprs: list of (expr, legend)."""
        kw.setdefault("options", {"legend": {"displayMode": "table", "placement": "bottom", "calcs": ["mean", "max", "lastNotNull"]},
                                  "tooltip": {"mode": "multi", "sort": "desc"}})
        kw.setdefault("custom", {"drawStyle": "line", "lineWidth": 1, "fillOpacity": 10, "showPoints": "never"})
        return self.panel("timeseries", title, [{"expr": e, "legendFormat": l, "range": True} for e, l in exprs], **kw)

    def stat(self, title, expr, legend="", w=6, h=4, **kw):
        kw.setdefault("options", {"reduceOptions": {"calcs": ["lastNotNull"], "fields": "", "values": False},
                                  "colorMode": "background", "graphMode": "area", "textMode": "auto"})
        return self.panel("stat", title, [{"expr": expr, "legendFormat": legend, "instant": True}], w=w, h=h, **kw)

    def gauge(self, title, expr, w=6, h=6, **kw):
        kw.setdefault("options", {"reduceOptions": {"calcs": ["lastNotNull"], "fields": "", "values": False},
                                  "showThresholdLabels": False, "showThresholdMarkers": True})
        return self.panel("gauge", title, [{"expr": expr, "instant": True}], w=w, h=h, **kw)

    def table(self, title, expr, w=12, h=8, **kw):
        kw.setdefault("options", {"showHeader": True, "sortBy": [{"displayName": "Value", "desc": True}]})
        p = self.panel("table", title, [{"expr": expr, "format": "table", "instant": True}], w=w, h=h, **kw)
        p["transformations"] = [{"id": "organize", "options": {"excludeByName": {"Time": True}}}]
        return p

    def logs(self, title, expr, w=24, h=10):
        return self.panel("logs", title, [{"expr": expr}], w=w, h=h, ds=LOKI,
                          options={"showTime": True, "wrapLogMessage": True, "sortOrder": "Descending",
                                   "enableLogDetails": True})

    def to_json(self):
        return {
            "uid": self.uid,
            "title": self.title,
            "description": self.description,
            "tags": ["afrotransact"] + self.tags,
            "timezone": "browser",
            "editable": False,
            "graphTooltip": 1,
            "schemaVersion": 39,
            "version": 1,
            "refresh": self.refresh,
            "time": {"from": "now-6h", "to": "now"},
            "timepicker": {},
            "annotations": {"list": [{
                "builtIn": 1, "datasource": {"type": "grafana", "uid": "-- Grafana --"}, "enable": True,
                "hide": True, "iconColor": "rgba(0, 211, 255, 1)", "name": "Annotations & Alerts", "type": "dashboard"}]},
            "templating": {"list": self.variables},
            "links": [{"type": "dashboards", "tags": ["afrotransact"], "asDropdown": True, "title": "AfroTransact"}],
            "panels": self.panels,
        }


def var_query(name, query, label=None, multi=True, include_all=True, all_value=".*", hide=0):
    return {
        "name": name, "label": label or name, "type": "query", "datasource": PROM,
        "query": {"query": query, "refId": "PrometheusVariableQueryEditor-VariableQuery"},
        "definition": query, "refresh": 2, "sort": 1, "multi": multi, "includeAll": include_all,
        "allValue": all_value if include_all else None, "hide": hide,
        "current": {"selected": True, "text": ["All"], "value": ["$__all"]} if include_all else {},
        "options": [],
    }


ENV_VAR = var_query("env", "label_values(up, env)", "Environment")

GREEN_RED = [{"color": "green", "value": None}, {"color": "red", "value": 80}]


def java_go(java_expr, go_expr):
    return f"({java_expr}) or ({go_expr})"


def rate_total(by="service", extra=""):
    j = f'sum by ({by}) (rate(http_server_requests_seconds_count{{{SEL}{extra}}}[{RI}]))'
    g = f'sum by ({by}) (rate(http_server_requests_total{{{SEL}{extra}}}[{RI}]))'
    return java_go(j, g)


def p_quantile(q, by="service"):
    j = f'histogram_quantile({q}, sum by ({by}, le) (rate(http_server_requests_seconds_bucket{{{SEL}}}[{RI}])))'
    g = f'histogram_quantile({q}, sum by ({by}, le) (rate(http_server_request_duration_seconds_bucket{{{SEL}}}[{RI}])))'
    return java_go(j, g)


# --------------------------------------------------------------------------------------------- platform overview
def platform_overview():
    b = Board("at-platform-overview", "AfroTransact / Platform overview (RED)", ["red", "services"],
              "Rate, errors and duration for every Java and Go service.",
              [ENV_VAR, var_query("service", 'label_values(up{job!~"prometheus|alertmanager|otel-collector|grafana|loki|tempo|postgres|elasticsearch|kafka|redis|temporal|openfga|minio"}, service)', "Service")])
    b.row("Fleet")
    b.stat("Services up", f'count(up{{{SEL}}} == 1)', w=4, thresholds=[{"color": "red", "value": None}, {"color": "green", "value": 1}])
    b.stat("Services down", f'count(up{{{SEL}}} == 0) or vector(0)', w=4, thresholds=[{"color": "green", "value": None}, {"color": "red", "value": 1}])
    b.stat("Requests / s", f'sum({rate_total()})', w=4, unit="reqps", thresholds=[{"color": "blue", "value": None}])
    b.stat("5xx ratio", f'sum({rate_total(extra=", status=~\"5..\"")}) / sum({rate_total()})', w=4, unit="percentunit", decimals=3,
           thresholds=[{"color": "green", "value": None}, {"color": "orange", "value": 0.001}, {"color": "red", "value": 0.01}])
    b.stat("Firing alerts", 'count(ALERTS{alertstate="firing", env=~"$env"}) or vector(0)', w=8,
           thresholds=[{"color": "green", "value": None}, {"color": "red", "value": 1}])
    b.row("Rate")
    b.ts("Requests / s by service", [(rate_total(), "{{service}}")], w=24, unit="reqps")
    b.row("Errors")
    b.ts("5xx ratio by service", [(f'({rate_total(extra=", status=~\"5..\"")}) / ({rate_total()})', "{{service}}")], unit="percentunit",
         thresholds=[{"color": "green", "value": None}, {"color": "red", "value": 0.01}])
    b.ts("4xx / s by service", [(rate_total(extra=', status=~"4.."'), "{{service}}")], unit="reqps")
    b.row("Duration")
    b.ts("p95 latency by service", [(p_quantile(0.95), "{{service}}")], unit="s")
    b.ts("p99 latency by service", [(p_quantile(0.99), "{{service}}")], unit="s")
    b.row("Endpoints")
    b.table("Top endpoints by request rate (Java)",
            f'topk(15, sum by (service, method, uri) (rate(http_server_requests_seconds_count{{{SEL}}}[5m])))', unit="reqps")
    b.table("Top endpoints by request rate (Go)",
            f'topk(15, sum by (service, method, route) (rate(http_server_requests_total{{{SEL}}}[5m])))', unit="reqps")
    b.table("Scrape targets", f'up{{{SEL}}}', w=24, mappings=[{"type": "value", "options": {"0": {"text": "DOWN", "color": "red"}, "1": {"text": "UP", "color": "green"}}}])
    b.logs("Error logs", '{service_name=~"$service", deployment_environment=~"$env"} |~ "(?i)level\\W+(error|fatal)"')
    return b


# --------------------------------------------------------------------------------------------- JVM
def jvm():
    sel = 'env=~"$env", service=~"$service", instance=~"$instance"'
    b = Board("at-jvm", "AfroTransact / JVM (Spring Boot)", ["jvm", "java"],
              "Micrometer JVM, GC, threads, HikariCP and Kafka client metrics for the Java services.",
              [ENV_VAR, var_query("service", "label_values(jvm_memory_used_bytes, service)", "Service"),
               var_query("instance", 'label_values(jvm_memory_used_bytes{service=~"$service"}, instance)', "Instance")])
    b.row("Memory")
    b.ts("Heap used vs max", [(f'sum by (instance) (jvm_memory_used_bytes{{{sel}, area="heap"}})', "used {{instance}}"),
                              (f'sum by (instance) (jvm_memory_max_bytes{{{sel}, area="heap"}})', "max {{instance}}")], unit="bytes")
    b.ts("Non-heap used", [(f'sum by (instance, id) (jvm_memory_used_bytes{{{sel}, area="nonheap"}})', "{{instance}} {{id}}")], unit="bytes")
    b.row("GC & CPU")
    b.ts("GC pause time / s", [(f'sum by (instance, gc) (rate(jvm_gc_pause_seconds_sum{{{sel}}}[{RI}]))', "{{instance}} {{gc}}")], unit="s")
    b.ts("GC pauses / s", [(f'sum by (instance, gc) (rate(jvm_gc_pause_seconds_count{{{sel}}}[{RI}]))', "{{instance}} {{gc}}")], unit="ops")
    b.ts("Process CPU", [(f'process_cpu_usage{{{sel}}}', "{{instance}}")], unit="percentunit", max_=1, min_=0)
    b.ts("Uptime", [(f'process_uptime_seconds{{{sel}}}', "{{instance}}")], unit="s")
    b.row("Threads")
    b.ts("Live threads", [(f'jvm_threads_live_threads{{{sel}}}', "{{instance}} live"),
                          (f'jvm_threads_daemon_threads{{{sel}}}', "{{instance}} daemon")])
    b.ts("Thread states", [(f'sum by (state) (jvm_threads_states_threads{{{sel}}})', "{{state}}")])
    b.row("Database pool (HikariCP)")
    b.ts("Connections", [(f'sum by (instance, pool) (hikaricp_connections_active{{{sel}}})', "active {{instance}}"),
                         (f'sum by (instance, pool) (hikaricp_connections_idle{{{sel}}})', "idle {{instance}}"),
                         (f'sum by (instance, pool) (hikaricp_connections_max{{{sel}}})', "max {{instance}}")])
    b.ts("Pending threads / acquire p95", [(f'sum by (instance) (hikaricp_connections_pending{{{sel}}})', "pending {{instance}}"),
                                           (f'histogram_quantile(0.95, sum by (instance, le) (rate(hikaricp_connections_acquire_seconds_bucket{{{sel}}}[{RI}])))', "acquire p95 {{instance}}")])
    b.row("Kafka producer")
    b.ts("Records sent / s", [(f'sum by (instance) (rate(kafka_producer_record_send_total{{{sel}}}[{RI}]))', "{{instance}}")], unit="ops")
    b.ts("Outbox backlog", [(f'sum by (instance) (outbox_pending_events{{{sel}}})', "{{instance}}")],
         description="Unrelayed transactional-outbox rows (gauge outbox_pending_events from platform-events).")
    return b


# --------------------------------------------------------------------------------------------- Go runtime
def go_runtime():
    sel = 'env=~"$env", service=~"$service", instance=~"$instance"'
    b = Board("at-go-runtime", "AfroTransact / Go runtime", ["go"],
              "Go runtime and process metrics (client_golang default collectors) for the Go services.",
              [ENV_VAR, var_query("service", "label_values(go_goroutines, service)", "Service"),
               var_query("instance", 'label_values(go_goroutines{service=~"$service"}, instance)', "Instance")])
    b.row("Runtime")
    b.ts("Goroutines", [(f'go_goroutines{{{sel}}}', "{{service}} {{instance}}")])
    b.ts("OS threads", [(f'go_threads{{{sel}}}', "{{service}} {{instance}}")])
    b.ts("Heap in use / alloc", [(f'go_memstats_heap_inuse_bytes{{{sel}}}', "inuse {{instance}}"),
                                 (f'go_memstats_heap_alloc_bytes{{{sel}}}', "alloc {{instance}}")], unit="bytes")
    b.ts("Allocation rate", [(f'rate(go_memstats_alloc_bytes_total{{{sel}}}[{RI}])', "{{instance}}")], unit="Bps")
    b.ts("GC duration (quantiles)", [(f'go_gc_duration_seconds{{{sel}, quantile=~"0.5|1"}}', "{{instance}} q{{quantile}}")], unit="s")
    b.ts("GC cycles / s", [(f'rate(go_gc_duration_seconds_count{{{sel}}}[{RI}])', "{{instance}}")], unit="ops")
    b.row("Process")
    b.ts("CPU", [(f'rate(process_cpu_seconds_total{{{sel}}}[{RI}])', "{{instance}}")], unit="percentunit")
    b.ts("Resident memory", [(f'process_resident_memory_bytes{{{sel}}}', "{{instance}}")], unit="bytes")
    b.ts("Open file descriptors", [(f'process_open_fds{{{sel}}}', "open {{instance}}"), (f'process_max_fds{{{sel}}}', "max {{instance}}")])
    b.ts("In-flight / request rate", [(f'sum by (instance) (rate(http_server_requests_total{{{sel}}}[{RI}]))', "req/s {{instance}}")], unit="reqps")
    return b


# --------------------------------------------------------------------------------------------- Elasticsearch
def elasticsearch():
    sel = 'env=~"$env", cluster=~"$cluster"'
    b = Board("at-elasticsearch", "AfroTransact / Elasticsearch", ["elasticsearch", "search"],
              "Cluster health, indexing/search throughput and latency, product index size, indexer lag and slow queries (S177).",
              [ENV_VAR, var_query("cluster", "label_values(elasticsearch_cluster_health_status, cluster)", "Cluster")])
    b.row("Health")
    b.stat("Cluster status",
           f'max(elasticsearch_cluster_health_status{{{sel}, color="red"}}) * 2 + max(elasticsearch_cluster_health_status{{{sel}, color="yellow"}})',
           w=4, mappings=[{"type": "value", "options": {"0": {"text": "GREEN", "color": "green"}, "1": {"text": "YELLOW", "color": "yellow"},
                                                         "2": {"text": "RED", "color": "red"}}}],
           thresholds=[{"color": "green", "value": None}, {"color": "yellow", "value": 1}, {"color": "red", "value": 2}])
    b.stat("Nodes", f'max(elasticsearch_cluster_health_number_of_nodes{{{sel}}})', w=4, thresholds=[{"color": "blue", "value": None}])
    b.stat("Active shards", f'max(elasticsearch_cluster_health_active_shards{{{sel}}})', w=4, thresholds=[{"color": "blue", "value": None}])
    b.stat("Unassigned shards", f'max(elasticsearch_cluster_health_unassigned_shards{{{sel}}})', w=4,
           thresholds=[{"color": "green", "value": None}, {"color": "orange", "value": 1}])
    b.stat("Pending tasks", f'max(elasticsearch_cluster_health_number_of_pending_tasks{{{sel}}})', w=4,
           thresholds=[{"color": "green", "value": None}, {"color": "orange", "value": 5}])
    b.stat("Indexer lag", f'max(indexer_event_lag_seconds{{env=~"$env"}}) or vector(0)', w=4, unit="s",
           thresholds=[{"color": "green", "value": None}, {"color": "orange", "value": 30}, {"color": "red", "value": 60}],
           description="Age of the newest event the indexer has applied (gauge indexer_event_lag_seconds). Alert at > 60 s.")
    b.row("Throughput & latency")
    b.ts("Search queries / s", [(f'sum by (index) (rate(elasticsearch_indices_search_query_total{{{sel}}}[{RI}]))', "{{index}}")], unit="ops")
    b.ts("Avg query latency", [(f'sum by (index) (rate(elasticsearch_indices_search_query_time_seconds{{{sel}}}[{RI}])) / sum by (index) (rate(elasticsearch_indices_search_query_total{{{sel}}}[{RI}]))', "{{index}}")], unit="s")
    b.ts("Indexing ops / s", [(f'sum by (index) (rate(elasticsearch_indices_indexing_index_total{{{sel}}}[{RI}]))', "{{index}}")], unit="ops")
    b.ts("Search service p95 (SLO 150 ms)",
         [(f'histogram_quantile(0.95, sum by (le, route) (rate(http_server_request_duration_seconds_bucket{{env=~"$env", service="search"}}[{RI}])))', "{{route}}")],
         unit="s", thresholds=[{"color": "green", "value": None}, {"color": "red", "value": 0.15}],
         custom={"drawStyle": "line", "lineWidth": 1, "fillOpacity": 10, "showPoints": "never", "thresholdsStyle": {"mode": "line"}})
    b.row("Resources")
    b.ts("JVM heap used %", [(f'elasticsearch_jvm_memory_used_bytes{{{sel}, area="heap"}} / elasticsearch_jvm_memory_max_bytes{{{sel}, area="heap"}}', "{{name}}")], unit="percentunit", max_=1)
    b.ts("Disk free", [(f'elasticsearch_filesystem_data_available_bytes{{{sel}}}', "{{name}} {{mount}}")], unit="bytes")
    b.table("Documents per index (primaries)", f'sum by (index) (elasticsearch_indices_docs_primary{{{sel}}})')
    b.table("Store size per index", f'sum by (index) (elasticsearch_indices_store_size_bytes_primary{{{sel}}})', unit="bytes")
    b.ts("Indexer lag (s) & Kafka lag", [(f'max(indexer_event_lag_seconds{{env=~"$env"}})', "lag seconds"),
                                         (f'sum(kafka_consumergroup_lag{{env=~"$env", consumergroup=~"indexer.*"}})', "kafka messages behind")], w=24)
    b.logs("Slow queries (slowlog)", '{deployment_environment=~"$env"} |~ "index\\.search\\.slowlog|elasticsearch\\.slowlog"')
    return b


# --------------------------------------------------------------------------------------------- Kafka
def kafka():
    sel = 'env=~"$env", consumergroup=~"$group", topic=~"$topic"'
    b = Board("at-kafka-consumer-lag", "AfroTransact / Kafka consumer lag", ["kafka", "events"],
              "Consumer group lag per topic and partition (kafka-exporter), produce and consume rates for at.<aggregate>.events.",
              [ENV_VAR, var_query("group", "label_values(kafka_consumergroup_lag, consumergroup)", "Consumer group"),
               var_query("topic", "label_values(kafka_topic_partitions, topic)", "Topic")])
    b.row("Lag")
    b.stat("Total lag (messages)", f'sum(kafka_consumergroup_lag{{{sel}}}) or vector(0)', w=6,
           thresholds=[{"color": "green", "value": None}, {"color": "orange", "value": 1000}, {"color": "red", "value": 10000}])
    b.stat("Groups lagging (>1000)", f'count(sum by (consumergroup) (kafka_consumergroup_lag{{{sel}}}) > 1000) or vector(0)', w=6,
           thresholds=[{"color": "green", "value": None}, {"color": "red", "value": 1}])
    b.stat("Brokers", 'max(kafka_brokers{env=~"$env"})', w=6, thresholds=[{"color": "red", "value": None}, {"color": "green", "value": 1}])
    b.stat("Topics (at.*)", 'count(count by (topic) (kafka_topic_partitions{env=~"$env", topic=~"at\\\\..*"}))', w=6,
           thresholds=[{"color": "blue", "value": None}])
    b.ts("Lag by group / topic", [(f'sum by (consumergroup, topic) (kafka_consumergroup_lag{{{sel}}})', "{{consumergroup}} · {{topic}}")], w=24)
    b.ts("Estimated time to catch up", [(f'sum by (consumergroup, topic) (kafka_consumergroup_lag{{{sel}}}) / clamp_min(sum by (consumergroup, topic) (rate(kafka_consumergroup_current_offset{{{sel}}}[5m])), 0.001)', "{{consumergroup}} · {{topic}}")], unit="s")
    b.ts("Consume rate (msg/s)", [(f'sum by (consumergroup, topic) (rate(kafka_consumergroup_current_offset{{{sel}}}[{RI}]))', "{{consumergroup}} · {{topic}}")], unit="ops")
    b.ts("Produce rate (msg/s)", [(f'sum by (topic) (rate(kafka_topic_partition_current_offset{{env=~"$env", topic=~"$topic"}}[{RI}]))', "{{topic}}")], unit="ops")
    b.table("Lag by partition", f'sum by (consumergroup, topic, partition) (kafka_consumergroup_lag{{{sel}}}) > 0')
    b.table("Dead-letter topics with messages", 'sum by (topic) (kafka_topic_partition_current_offset{env=~"$env", topic=~".*\\\\.dlq"}) > 0', w=24)
    return b


# --------------------------------------------------------------------------------------------- inventory drift
def inventory():
    sel = 'env=~"$env", fc=~"$fc"'
    b = Board("at-inventory-reconciliation", "AfroTransact / Inventory reconciliation drift", ["inventory", "business"],
              "Difference between the inventory ledger and physical / channel counts (gauge inventory_reconciliation_drift{fc,sku}, units; signed).",
              [ENV_VAR, var_query("fc", "label_values(inventory_reconciliation_drift, fc)", "Fulfilment centre")])
    b.stat("SKUs drifting", f'count(inventory_reconciliation_drift{{{sel}}} != 0) or vector(0)', w=6,
           thresholds=[{"color": "green", "value": None}, {"color": "orange", "value": 1}, {"color": "red", "value": 25}])
    b.stat("Absolute drift (units)", f'sum(abs(inventory_reconciliation_drift{{{sel}}})) or vector(0)', w=6,
           thresholds=[{"color": "green", "value": None}, {"color": "orange", "value": 1}, {"color": "red", "value": 100}])
    b.stat("Net drift (units)", f'sum(inventory_reconciliation_drift{{{sel}}}) or vector(0)', w=6, thresholds=[{"color": "blue", "value": None}])
    b.stat("Last reconciliation", f'time() - max(inventory_reconciliation_last_run_timestamp_seconds{{env=~"$env"}})', w=6, unit="s",
           thresholds=[{"color": "green", "value": None}, {"color": "orange", "value": 3600}, {"color": "red", "value": 7200}])
    b.ts("Absolute drift by FC", [(f'sum by (fc) (abs(inventory_reconciliation_drift{{{sel}}}))', "{{fc}}")], w=24)
    b.table("Top drifting SKUs", f'topk(25, abs(inventory_reconciliation_drift{{{sel}}}) > 0)', w=12, h=10)
    b.ts("Reservations vs commits", [(f'sum(rate(inventory_reservations_total{{env=~"$env"}}[{RI}]))', "reservations/s"),
                                     (f'sum(rate(inventory_reservations_total{{env=~"$env", outcome="expired"}}[{RI}]))', "expired/s")], w=12, h=10)
    return b


# --------------------------------------------------------------------------------------------- business SLOs
def slo():
    b = Board("at-business-slo", "AfroTransact / Checkout & business SLOs", ["slo", "business"],
              "SLIs, 30-day error budget and burn rate for the platform SLOs (rules in infra/observability/rules/slo-*.yaml).",
              [ENV_VAR])
    env = 'env=~"$env"'
    slos = [
        ("Checkout success", "checkout", 0.999),
        ("Search p95 < 150 ms", "search_latency", 0.95),
        ("Payment webhook < 60 s", "payment_webhook_lag", 0.99),
        ("Payout on time", "payout_on_time", 0.995),
        ("Notification < 30 s", "notification_delivery", 0.99),
    ]
    b.row("SLI (30 days) & error budget remaining")
    for title, slo_id, target in slos:
        b.stat(f"{title} — SLI 30d", f'1 - max(slo:sli_error:ratio_rate30d{{{env}, slo="{slo_id}"}})', w=4, h=5,
               unit="percentunit", decimals=3,
               thresholds=[{"color": "red", "value": None}, {"color": "green", "value": target}],
               description=f"Objective {target * 100:g}%")
    for title, slo_id, target in slos:
        b.gauge(f"{title} — budget left", f'1 - max(slo:sli_error:ratio_rate30d{{{env}, slo="{slo_id}"}}) / {1 - target:.4f}',
                w=4, h=6, unit="percentunit", min_=0, max_=1,
                thresholds=[{"color": "red", "value": None}, {"color": "orange", "value": 0.25}, {"color": "green", "value": 0.5}])
    b.row("Burn rate (1h / 6h; 14.4 = page, 6 = page, 1 = budget exactly spent in 30d)")
    b.ts("Burn rate 1h", [(f'max by (slo) (slo:sli_error:ratio_rate1h{{{env}}}) / on (slo) group_left max by (slo) (slo:error_budget:ratio)', "{{slo}}")],
         thresholds=[{"color": "green", "value": None}, {"color": "orange", "value": 6}, {"color": "red", "value": 14.4}],
         custom={"drawStyle": "line", "lineWidth": 1, "fillOpacity": 0, "showPoints": "never", "thresholdsStyle": {"mode": "line"}})
    b.ts("Burn rate 6h", [(f'max by (slo) (slo:sli_error:ratio_rate6h{{{env}}}) / on (slo) group_left max by (slo) (slo:error_budget:ratio)', "{{slo}}")],
         thresholds=[{"color": "green", "value": None}, {"color": "orange", "value": 1}, {"color": "red", "value": 6}],
         custom={"drawStyle": "line", "lineWidth": 1, "fillOpacity": 0, "showPoints": "never", "thresholdsStyle": {"mode": "line"}})
    b.row("Checkout")
    b.ts("Checkouts / min by outcome", [(f'sum by (outcome) (rate(checkout_attempts_total{{{env}}}[{RI}])) * 60', "{{outcome}}")], unit="short")
    b.ts("Checkout success ratio (5m)", [(f'1 - max(slo:sli_error:ratio_rate5m{{{env}, slo="checkout"}})', "success")], unit="percentunit",
         thresholds=[{"color": "red", "value": None}, {"color": "green", "value": 0.999}])
    b.ts("Checkout p95 duration", [(f'histogram_quantile(0.95, sum by (le) (rate(checkout_duration_seconds_bucket{{{env}}}[{RI}])))', "p95")], unit="s")
    b.ts("Orders service 5xx ratio", [(f'sum(rate(http_server_requests_seconds_count{{{env}, service="orders", status=~"5.."}}[{RI}])) / sum(rate(http_server_requests_seconds_count{{{env}, service="orders"}}[{RI}]))', "orders")], unit="percentunit")
    b.row("Payments, payouts, notifications")
    b.ts("Payment webhook lag p95 / p99", [(f'histogram_quantile(0.95, sum by (le) (rate(payments_webhook_lag_seconds_bucket{{{env}}}[{RI}])))', "p95"),
                                           (f'histogram_quantile(0.99, sum by (le) (rate(payments_webhook_lag_seconds_bucket{{{env}}}[{RI}])))', "p99")], unit="s",
         thresholds=[{"color": "green", "value": None}, {"color": "red", "value": 60}])
    b.ts("Payouts by on-time", [(f'sum by (on_time) (increase(payouts_completed_total{{{env}}}[1h]))', "on_time={{on_time}}")], unit="short")
    b.ts("Notification delivery p95 by channel", [(f'histogram_quantile(0.95, sum by (le, channel) (rate(notifications_delivery_duration_seconds_bucket{{{env}}}[{RI}])))', "{{channel}}")], unit="s",
         thresholds=[{"color": "green", "value": None}, {"color": "red", "value": 30}])
    b.ts("Search p95", [(f'histogram_quantile(0.95, sum by (le) (rate(http_server_request_duration_seconds_bucket{{{env}, service="search", route=~"GET /v1/(search|suggest)"}}[{RI}])))', "p95")], unit="s",
         thresholds=[{"color": "green", "value": None}, {"color": "red", "value": 0.15}])
    return b


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    boards = {
        "platform-overview.json": platform_overview(),
        "jvm.json": jvm(),
        "go-runtime.json": go_runtime(),
        "elasticsearch.json": elasticsearch(),
        "kafka-consumer-lag.json": kafka(),
        "inventory-reconciliation.json": inventory(),
        "business-slo.json": slo(),
    }
    K8S.mkdir(parents=True, exist_ok=True)
    for name, board in boards.items():
        text = json.dumps(board.to_json(), indent=2) + "\n"
        (OUT / name).write_text(text)
        print(f"wrote {OUT / name} ({len(board.panels)} panels)")
        # Same JSON as a ConfigMap picked up by the kube-prometheus-stack Grafana sidecar (label grafana_dashboard=1).
        cm = {
            "apiVersion": "v1", "kind": "ConfigMap",
            "metadata": {"name": "dashboard-" + name.removesuffix(".json"), "namespace": "observability",
                         "labels": {"grafana_dashboard": "1", "app.kubernetes.io/part-of": "afrotransact"},
                         "annotations": {"grafana_folder": "AfroTransact"}},
            "data": {name: text},
        }
        (K8S / (name.removesuffix(".json") + ".yaml")).write_text(
            "# GENERATED by infra/observability/scripts/generate_dashboards.py — do not edit by hand.\n" + json.dumps(cm, indent=1) + "\n")


if __name__ == "__main__":
    main()
