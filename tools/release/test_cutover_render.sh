#!/usr/bin/env bash
# Render tests for the Release 1 charts (needs helm; CI job `helm` of core.yml, or locally: tools/release/test_cutover_render.sh).
#   1. a module in cutover.services is switched on in the deployable (MODULE_<NAME>_ENABLED=true), others stay false;
#   2. a service in cutover.services is scaled to 0 and its Service (same name) selects the deployable's pods, and without it nothing changes;
#   3. the connection budget guard fails the render when the pools add up to more than dbBudget.maxPerPod;
#   4. the deployables render for every environment with the platform values.
set -euo pipefail
cd "$(dirname "$0")/../.."
helm=${HELM:-helm}
core=java/apps/core/deploy/helm
go=go/apps/afrotransact-go/deploy/helm
catalog=java/services/catalog/deploy/helm
fail() { echo "FAIL: $*" >&2; exit 1; }

for c in "$core" "$go" "$catalog"; do "$helm" dependency build "$c" >/dev/null; done

# 1. module switch from the cutover map
out="$("$helm" template core "$core" -f "$core/values-prod.yaml" --set-json 'cutover.services={"catalog":"afrotransact-core"}')"
grep -A1 'name: MODULE_CATALOG_ENABLED' <<<"$out" | grep -q 'value: "true"' || fail "catalog is not enabled in core by cutover.services"
grep -A1 'name: MODULE_ORDERS_ENABLED' <<<"$out" | grep -q 'value: "false"' || fail "orders must stay off in core"
grep -q 'name: CATALOG_DB_POOL_SIZE' <<<"$out" || fail "CATALOG_DB_POOL_SIZE missing"
grep -E 'name: [A-Z_]*(PASSWORD|SECRET|KEY)$' <<<"$out" && fail "a credential name is rendered as a plain env var (secrets come from the Secret)"
# 1b. the Go deployable reads AT_MODULES_<NAME>_ENABLED; a deployable with no module on is idle (replicas 0)
out="$("$helm" template go "$go" -f "$go/values-prod.yaml" --set-json 'cutover.services={"cart":"afrotransact-go","carrier-gateway":"afrotransact-go"}')"
grep -A1 'name: AT_MODULES_CART_ENABLED' <<<"$out" | grep -q 'value: "true"' || fail "cart is not enabled in the Go deployable by cutover.services"
grep -A1 'name: AT_MODULES_CARRIER_GATEWAY_ENABLED' <<<"$out" | grep -q 'value: "true"' || fail "carrier-gateway env name (CARRIER_GATEWAY) is wrong"
grep -q 'replicas: 1' <<<"$out" || fail "a deployable with a module on must run 1 replica"
for c in "$core" "$go"; do
  "$helm" template idle "$c" -f "$c/values-prod.yaml" | grep -q 'replicas: 0' || fail "$c with no module on must be scaled to 0"
done
# 2. the old service: scaled to 0, Service routed to the deployable; unchanged without the entry
out="$("$helm" template catalog "$catalog" -f "$catalog/values-prod.yaml" --set-string cutover.movedTo=afrotransact-core)"
grep -q 'replicas: 0' <<<"$out" || fail "catalog Deployment is not scaled to 0 after the cutover"
awk '/kind: Service/{s=1} s&&/app.kubernetes.io\/name: afrotransact-core/{f=1} END{exit !f}' <<<"$out" || fail "the catalog Service does not select afrotransact-core pods"
out="$("$helm" template catalog "$catalog" -f "$catalog/values-prod.yaml")"
grep -q 'replicas: 1' <<<"$out" || fail "catalog must keep its replica without cutover"
grep -q 'app.kubernetes.io/name: afrotransact-core' <<<"$out" && fail "the catalog Service must select its own pods without cutover"
# 3. budget guard
if "$helm" template core "$core" --set modules.catalog.pool=60 >/dev/null 2>&1; then fail "pools over dbBudget.maxPerPod must fail the render"; fi
if "$helm" template go "$go" --set modules.cart.pool=60 >/dev/null 2>&1; then fail "go pools over dbBudget.maxPerPod must fail the render"; fi
# 4. every environment with its platform values
for env in dev uat prod; do
  for c in "$core" "$go"; do
    "$helm" template x "$c" -n "afrotransact-$env" -f "$c/values.yaml" -f "$c/values-$env.yaml" -f "infra/argocd/envs/$env/platform-values.yaml" >/dev/null
  done
done
echo "release-1 render tests: OK"
