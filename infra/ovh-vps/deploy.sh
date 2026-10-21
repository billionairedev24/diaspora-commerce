#!/usr/bin/env bash
# Deploys AfroTransact to the single k3s server (run as root on it, from the repository checkout copied there).
#   deploy.sh [all]       data stores, Kafka, Temporal, OpenFGA, edge (Traefik + cert-manager), every service
#   deploy.sh data        Postgres (cluster mode), Valkey, Meilisearch, Kafka, Temporal, OpenFGA
#   deploy.sh services    only the service charts (after changing IMAGE_TAG or secrets)
#   deploy.sh edge        Traefik, cert-manager, certificates and routes
#   deploy.sh observability  Prometheus + Grafana (metrics, dashboards) and Loki + Alloy (logs) at https://grafana.$DOMAIN
#   deploy.sh backup      install the nightly encrypted backup (databases + generated secrets) and run one now
#   deploy.sh status      pods, certificates and URLs
#   deploy.sh down|up     scale every service to 0 / back to 1 (the server keeps costing the same)
set -euo pipefail
. "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
[[ $EUID -eq 0 ]] || die "run as root"
load_config
K8S="$REPO_ROOT/infra/k8s/platform"

apply_stripped() { strip_kinds "$STRIP_KINDS" | kubectl apply -f - ; }

# Third-party charts are installed at the latest version the first time and that version is recorded, so every later run
# installs the same one (delete a line of /var/lib/afrotransact/chart-versions.env to upgrade that chart).
PIN_FILE="$STATE_DIR/chart-versions.env"
chart_install() { # chart_install RELEASE CHART NAMESPACE [helm args...]
  local rel="$1" chart="$2" ns="$3" ver vargs=(); shift 3
  install -d -m 700 "$STATE_DIR"; touch "$PIN_FILE"
  ver="$(grep -E "^${rel}=" "$PIN_FILE" | head -1 | cut -d= -f2- || true)"
  [[ -z "$ver" ]] || vargs=(--version "$ver")
  helm upgrade --install "$rel" "$chart" -n "$ns" --create-namespace ${vargs[@]+"${vargs[@]}"} "$@" >/dev/null
  if [[ -z "$ver" ]]; then
    ver="$(helm get metadata "$rel" -n "$ns" -o json | jq -r .version)"
    echo "${rel}=${ver}" >> "$PIN_FILE"
    echo "  $rel installed at chart version $ver (recorded in $PIN_FILE)"
  fi
}

deploy_data() {
  "$HERE/secrets.sh" platform
  log "data stores"
  if [[ "$DB_MODE" == cluster ]]; then
    kubectl apply -f "$K8S/lean/data/namespace.yaml" >/dev/null 2>&1 || true
    apply_stripped < "$K8S/lean/data/postgres-trial.yaml"
  fi
  apply_stripped < "$K8S/lean/data/valkey.yaml"
  apply_stripped < "$K8S/lean/data/meilisearch.yaml"
  if [[ "$DB_MODE" == cluster ]]; then
    kubectl -n data rollout status statefulset/postgres --timeout=300s
  fi
  "$HERE/secrets.sh" databases
  log "Kafka and Temporal"
  apply_stripped < "$K8S/lean/kafka.yaml"
  # Temporal talks TLS to its database in the lean manifest; the in-cluster Postgres has none.
  if [[ "$DB_MODE" == cluster ]]; then sed 's/POSTGRES_TLS_ENABLED, value: "true"/POSTGRES_TLS_ENABLED, value: "false"/' "$K8S/lean/temporal.yaml" | apply_stripped
  else apply_stripped < "$K8S/lean/temporal.yaml"; fi
  # notifications' dev config reaches Kafka as kafka:9092 inside its own namespace.
  kubectl -n "$NS" apply -f - >/dev/null <<YAML
apiVersion: v1
kind: Service
metadata: {name: kafka, namespace: ${NS}}
spec: {type: ExternalName, externalName: kafka.kafka.svc.cluster.local}
YAML
  log "OpenFGA"
  kubectl kustomize "$K8S/openfga/overlays/dev" | strip_kinds "$STRIP_KINDS" | kubectl -n openfga apply -f -
  kubectl -n kafka rollout status statefulset/kafka --timeout=300s || true
  kubectl -n temporal rollout status deploy/temporal --timeout=400s || true
}

deploy_edge() {
  log "Traefik and cert-manager"
  helm repo add traefik https://traefik.github.io/charts >/dev/null 2>&1 || true
  helm repo add jetstack https://charts.jetstack.io >/dev/null 2>&1 || true
  helm repo update >/dev/null
  chart_install cert-manager jetstack/cert-manager cert-manager --set crds.enabled=true --wait --timeout 5m
  chart_install traefik traefik/traefik ingress --wait --timeout 5m \
    --set deployment.replicas=1 --set service.enabled=false \
    --set ports.web.hostPort=80 --set ports.websecure.hostPort=443 \
    --set ingressClass.enabled=true --set ingressClass.isDefaultClass=true \
    --set providers.kubernetesIngress.publishedService.enabled=false
  log "certificates and routes for $DOMAIN"
  kubectl apply -f - <<YAML
apiVersion: cert-manager.io/v1
kind: ClusterIssuer
metadata: {name: letsencrypt}
spec:
  acme:
    server: https://acme-v02.api.letsencrypt.org/directory
    email: ${ACME_EMAIL:-admin@${DOMAIN}}
    privateKeySecretRef: {name: letsencrypt-account}
    solvers:
      - http01: {ingress: {ingressClassName: traefik}}
YAML
  local host
  for host in api auth mcp; do
    kubectl apply -f - <<YAML
apiVersion: cert-manager.io/v1
kind: Certificate
metadata: {name: ${host}-tls, namespace: ${NS}}
spec:
  secretName: ${host}-tls
  dnsNames: [${host}.${DOMAIN}]
  issuerRef: {name: letsencrypt, kind: ClusterIssuer}
YAML
  done
  # api.$DOMAIN: path prefixes of infra/k8s/platform/edge/overlays/dev/routes.yaml, the prefix removed before the service
  route api /bff gateway-bff "" ; route api /identity identity "" ; route api /bookings bookings "" ; route api /fulfilment fulfilment ""
  route api /media media "" ; route api /notifications notifications "" ; route api /carrier-gateway carrier-gateway ""
  route api /payments/v1/webhooks payments "/v1/webhooks"
  route auth / identity "" ; route mcp / mcp-server ""
}

route() { # route HOST_PREFIX PATH SERVICE [REWRITE]
  local h="$1" p="$2" svc="$3" rewrite="${4:-}" name mw="" ann=""
  name="${h}-$(echo "${p#/}" | tr '/' '-')"; name="${name%-}"; [[ "$p" == "/" ]] && name="${h}-root"
  if [[ "$p" != "/" ]]; then
    if [[ -n "$rewrite" ]]; then
      kubectl apply -f - >/dev/null <<YAML
apiVersion: traefik.io/v1alpha1
kind: Middleware
metadata: {name: ${name}, namespace: ${NS}}
spec:
  replacePathRegex: {regex: "^${p}(.*)", replacement: "${rewrite}\$1"}
YAML
    else
      kubectl apply -f - >/dev/null <<YAML
apiVersion: traefik.io/v1alpha1
kind: Middleware
metadata: {name: ${name}, namespace: ${NS}}
spec:
  stripPrefix: {prefixes: ["${p}"]}
YAML
    fi
    ann="traefik.ingress.kubernetes.io/router.middlewares: ${NS}-${name}@kubernetescrd"
  fi
  kubectl apply -f - >/dev/null <<YAML
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: ${name}
  namespace: ${NS}
  annotations:
    traefik.ingress.kubernetes.io/router.entrypoints: websecure
    traefik.ingress.kubernetes.io/router.tls: "true"
    ${ann}
spec:
  ingressClassName: traefik
  tls: [{hosts: [${h}.${DOMAIN}], secretName: ${h}-tls}]
  rules:
    - host: ${h}.${DOMAIN}
      http:
        paths:
          - {path: ${p}, pathType: Prefix, backend: {service: {name: ${svc}, port: {number: 80}}}}
YAML
}

chart_dir() { # chart_dir SERVICE RUNTIME
  if [[ "$2" == java ]]; then echo "$REPO_ROOT/java/services/$1/deploy/helm"; else echo "$REPO_ROOT/go/services/$1/deploy/helm"; fi
}

install_service() { # install_service SERVICE RUNTIME
  local svc="$1" dir; dir="$(chart_dir "$1" "$2")"
  [[ -d "$dir" ]] || die "no chart for $svc at $dir"
  helm dependency update "$dir" >/dev/null
  local pull=() extra=()
  [[ -n "${GHCR_USER:-}" && -n "${GHCR_TOKEN:-}" ]] && pull=(--set "imagePullSecrets[0].name=ghcr")
  [[ -f "$HERE/overrides/$svc.yaml" ]] && extra+=(-f "$HERE/overrides/$svc.yaml")
  [[ "$svc" != mcp-server ]] || extra+=(--set-string "extraEnv.MCP_PUBLIC_URL=https://mcp.${DOMAIN}")
  # Prometheus Operator CRDs exist once `observability` ran: then every service is scraped.
  kubectl get crd servicemonitors.monitoring.coreos.com >/dev/null 2>&1 && extra+=(--set monitoring.serviceMonitor=true)
  helm upgrade --install "$svc" "$dir" -n "$NS" \
    -f "$dir/values-dev.yaml" -f "$HERE/values-ovh-vps.yaml" ${extra[@]+"${extra[@]}"} \
    --set "image.registry=$IMAGE_REGISTRY" --set "image.repository=$svc" --set "image.tag=$IMAGE_TAG" \
    --set "global.domain=$DOMAIN" "${pull[@]}" >/dev/null
}

deploy_services() {
  "$HERE/secrets.sh" databases
  "$HERE/secrets.sh" services
  local svc rt db wave failed=()
  for w in 1 2 3; do
    log "wave $w"
    while read -r svc rt db wave; do
      [[ "$wave" == "$w" ]] || continue
      install_service "$svc" "$rt" || failed+=("$svc")
      echo "  installed $svc"
    done < <(services)
    if [[ "$w" == 1 ]]; then kubectl -n "$NS" rollout status deploy/identity --timeout=600s || true; fi
  done
  log "waiting for rollouts (10 min at most)"
  while read -r svc _ _ _; do
    kubectl -n "$NS" rollout status "deploy/$svc" --timeout=600s >/dev/null 2>&1 || failed+=("$svc (not ready)")
  done < <(services)
  ((${#failed[@]} == 0)) || { printf 'NOT READY: %s\n' "${failed[@]}"; echo "see: kubectl -n $NS logs deploy/<service> --tail=80"; return 1; }
  echo "all services ready"
}

status() {
  kubectl get pods -A --no-headers | awk '$4!="Running" && $4!="Completed"{print}' | head -40 || true
  kubectl -n "$NS" get pods
  kubectl -n "$NS" get certificate 2>/dev/null || true
  echo "API: https://api.$DOMAIN/bff   Auth: https://auth.$DOMAIN   MCP: https://mcp.$DOMAIN"
}

deploy_observability() {
  [[ "${OBSERVABILITY:-true}" == true ]] || { echo "OBSERVABILITY=false: skipped"; return 0; }
  log "observability: Prometheus, Grafana, Loki, Alloy"
  helm repo add prometheus-community https://prometheus-community.github.io/helm-charts >/dev/null 2>&1 || true
  helm repo add grafana https://grafana.github.io/helm-charts >/dev/null 2>&1 || true
  helm repo update >/dev/null
  local gpw; gpw="$(gen GRAFANA_ADMIN_PASSWORD 18)"
  local v; v="$(mktemp)"; trap 'rm -f "$v"' RETURN
  cat > "$v" <<YAML
alertmanager: {enabled: false}
kubeEtcd: {enabled: false}
kubeControllerManager: {enabled: false}
kubeScheduler: {enabled: false}
kubeProxy: {enabled: false}
grafana:
  adminPassword: "${gpw}"
  persistence: {enabled: true, size: 2Gi}
  grafana.ini:
    server: {root_url: "https://grafana.${DOMAIN}"}
  additionalDataSources:
    - {name: Loki, type: loki, url: "http://loki.observability.svc:3100", access: proxy}
prometheus:
  prometheusSpec:
    retention: 7d
    serviceMonitorSelectorNilUsesHelmValues: false
    podMonitorSelectorNilUsesHelmValues: false
    resources: {requests: {cpu: 100m, memory: 512Mi}, limits: {memory: 1536Mi}}
    storageSpec:
      volumeClaimTemplate:
        spec:
          accessModes: [ReadWriteOnce]
          resources: {requests: {storage: 10Gi}}
YAML
  chart_install kube-prometheus-stack prometheus-community/kube-prometheus-stack observability -f "$v" --wait --timeout 10m
  cat > "$v" <<YAML
deploymentMode: SingleBinary
loki:
  auth_enabled: false
  commonConfig: {replication_factor: 1}
  schemaConfig:
    configs:
      - {from: "2024-04-01", store: tsdb, object_store: filesystem, schema: v13, index: {prefix: loki_index_, period: 24h}}
  storage: {type: filesystem}
  limits_config: {retention_period: 168h}
singleBinary:
  replicas: 1
  resources: {requests: {cpu: 50m, memory: 256Mi}, limits: {memory: 768Mi}}
  persistence: {enabled: true, size: 10Gi}
backend: {replicas: 0}
read: {replicas: 0}
write: {replicas: 0}
ingester: {replicas: 0}
querier: {replicas: 0}
queryFrontend: {replicas: 0}
queryScheduler: {replicas: 0}
distributor: {replicas: 0}
compactor: {replicas: 0}
indexGateway: {replicas: 0}
bloomCompactor: {replicas: 0}
bloomGateway: {replicas: 0}
bloomPlanner: {replicas: 0}
chunksCache: {enabled: false}
resultsCache: {enabled: false}
lokiCanary: {enabled: false}
test: {enabled: false}
gateway: {enabled: false}
minio: {enabled: false}
YAML
  chart_install loki grafana/loki observability -f "$v" --timeout 10m
  cat > "$v" <<YAML
alloy:
  configMap:
    content: |
      discovery.kubernetes "pods" { role = "pod" }
      discovery.relabel "pods" {
        targets = discovery.kubernetes.pods.targets
        rule { source_labels = ["__meta_kubernetes_namespace"] target_label = "namespace" }
        rule { source_labels = ["__meta_kubernetes_pod_name"] target_label = "pod" }
        rule { source_labels = ["__meta_kubernetes_pod_container_name"] target_label = "container" }
        rule { source_labels = ["__meta_kubernetes_pod_label_app_kubernetes_io_name"] target_label = "app" }
      }
      loki.source.kubernetes "pods" {
        targets    = discovery.relabel.pods.output
        forward_to = [loki.write.default.receiver]
      }
      loki.write "default" { endpoint { url = "http://loki.observability.svc:3100/loki/api/v1/push" } }
YAML
  chart_install alloy grafana/alloy observability -f "$v"
  # https://grafana.$DOMAIN (a service in this namespace that points at Grafana)
  kubectl -n "$NS" apply -f - >/dev/null <<YAML
apiVersion: v1
kind: Service
metadata: {name: grafana, namespace: ${NS}}
spec:
  type: ExternalName
  externalName: kube-prometheus-stack-grafana.observability.svc.cluster.local
  ports: [{port: 80}]
---
apiVersion: cert-manager.io/v1
kind: Certificate
metadata: {name: grafana-tls, namespace: ${NS}}
spec:
  secretName: grafana-tls
  dnsNames: [grafana.${DOMAIN}]
  issuerRef: {name: letsencrypt, kind: ClusterIssuer}
YAML
  route grafana / grafana ""
  echo "Grafana: https://grafana.${DOMAIN}  user admin, password in ${STATE_DIR}/generated.env (GRAFANA_ADMIN_PASSWORD)"
}

deploy_backup() {
  if [[ -z "${BACKUP_BUCKET:-}" ]]; then echo "no BACKUP_BUCKET configured: backups NOT installed"; return 0; fi
  "$HERE/backup.sh" install
  "$HERE/backup.sh" run
}

scale() { services | while read -r svc _; do kubectl -n "$NS" scale "deploy/$svc" --replicas="$1" >/dev/null; done; }

case "${1:-all}" in
  all) deploy_data; deploy_edge; deploy_services; deploy_observability || echo "observability failed (the application is deployed); retry: deploy.sh observability"; deploy_backup || echo "backup failed; retry: deploy.sh backup"; status ;;
  observability) deploy_observability ;;
  backup) deploy_backup ;;
  data) deploy_data ;;
  edge) deploy_edge ;;
  services) deploy_services; status ;;
  status) status ;;
  down) scale 0 ;;
  up) scale 1 ;;
  *) die "usage: deploy.sh [all|data|edge|services|observability|backup|status|down|up]" ;;
esac
