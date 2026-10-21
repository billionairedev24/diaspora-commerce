#!/usr/bin/env bash
# Deploys AfroTransact to the single k3s server (run as root on it, from the repository checkout copied there).
#   deploy.sh [all]       data stores, Kafka, Temporal, OpenFGA, edge (Traefik + cert-manager), every service
#   deploy.sh data        Postgres (cluster mode), Valkey, Meilisearch, Kafka, Temporal, OpenFGA
#   deploy.sh services    only the service charts (after changing IMAGE_TAG or secrets)
#   deploy.sh edge        Traefik, cert-manager, certificates and routes
#   deploy.sh status      pods, certificates and URLs
#   deploy.sh down|up     scale every service to 0 / back to 1 (the server keeps costing the same)
set -euo pipefail
. "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
[[ $EUID -eq 0 ]] || die "run as root"
load_config
K8S="$REPO_ROOT/infra/k8s/platform"

apply_stripped() { strip_kinds "$STRIP_KINDS" | kubectl apply -f - ; }

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
  helm upgrade --install cert-manager jetstack/cert-manager -n cert-manager --create-namespace --set crds.enabled=true --wait --timeout 5m >/dev/null
  helm upgrade --install traefik traefik/traefik -n ingress --create-namespace --wait --timeout 5m \
    --set deployment.replicas=1 --set service.enabled=false \
    --set ports.web.hostPort=80 --set ports.websecure.hostPort=443 \
    --set ingressClass.enabled=true --set ingressClass.isDefaultClass=true \
    --set providers.kubernetesIngress.publishedService.enabled=false >/dev/null
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
  local pull=(); [[ -n "${GHCR_USER:-}" && -n "${GHCR_TOKEN:-}" ]] && pull=(--set "imagePullSecrets[0].name=ghcr")
  helm upgrade --install "$svc" "$dir" -n "$NS" \
    -f "$dir/values-dev.yaml" -f "$HERE/values-ovh-vps.yaml" \
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

scale() { services | while read -r svc _; do kubectl -n "$NS" scale "deploy/$svc" --replicas="$1" >/dev/null; done; }

case "${1:-all}" in
  all) deploy_data; deploy_edge; deploy_services; status ;;
  data) deploy_data ;;
  edge) deploy_edge ;;
  services) deploy_services; status ;;
  status) status ;;
  down) scale 0 ;;
  up) scale 1 ;;
  *) die "usage: deploy.sh [all|data|edge|services|status|down|up]" ;;
esac
