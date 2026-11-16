#!/usr/bin/env bash
# make smoke: does the running local stack work, end to end? One PASS / FAIL line per check, the failing URL and status on a
# FAIL, a summary, and a non-zero exit when anything failed. bash + curl + jq; read-only except an add-to-cart and (unless
# SMOKE_CHECKOUT=0) one checkout that stops at the payment step, which leaves an unpaid "awaiting payment" order of the test
# customer. Never prints a secret. Run it after `make up` and `make seed`. Docs: docs/runbooks/release-1-verification.md.
#
# Where things are (override any of them; the defaults are what `make up` starts):
#   STACK=monolith (default) core :8120 + afrotransact-go :8200      STACK=services the old per-service processes
#   BFF_URL (gateway-bff / afrotransact-go)  CORE_URL  IDENTITY_URL  ORDERS_URL  CATALOG_URL  STOREFRONT_URL  SELLER_CENTER_URL
#   STAFF_CONSOLE_URL  MAILPIT_URL  MEILISEARCH_URL  PROMETHEUS_URL        (web ports follow .local/web-ports when set there)
#   SMOKE_CHECKOUT=0 skips the checkout check · SMOKE_MIN_PRODUCTS (40) · SMOKE_DEPT_SKIP ("Services": departments whose
#   items are not products in search) · SMOKE_CUSTOMER / SMOKE_PASSWORD (customer.houston@afrotransact.test, the seeded one)
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
STACK="${STACK:-monolith}"
for tool in curl jq; do command -v "$tool" >/dev/null || { echo "smoke: $tool is required" >&2; exit 2; }; done

# Web apps may have moved off their default ports (.local/web-ports: STOREFRONT_PORT=3000 ...).
web_port() { # VAR default
  local v="${!1:-}"
  if [[ -z "$v" && -f "$ROOT/.local/web-ports" ]]; then v=$(sed -n "s/^$1=//p" "$ROOT/.local/web-ports" | tail -1); fi
  echo "${v:-$2}"
}
BFF_URL="${BFF_URL:-http://localhost:${AFROTRANSACT_GO_PORT:-8200}}"
BFF_URL="${BFF_URL%/}"
CORE_URL="${CORE_URL:-http://localhost:8120}"
CORE_URL="${CORE_URL%/}"
# STACK=monolith: every Java module through core's single entry, <CORE_URL>/modules/<module> (the legacy ports 8100-8115 are
# compatibility only and may be off); STACK=services: each service's own port. Any of them can be set.
if [[ "$STACK" == services ]]; then
  IDENTITY_URL="${IDENTITY_URL:-http://localhost:8100}"; ORDERS_URL="${ORDERS_URL:-http://localhost:8104}"
  CATALOG_URL="${CATALOG_URL:-http://localhost:8101}"
else
  IDENTITY_URL="${IDENTITY_URL:-$CORE_URL/modules/identity}"; ORDERS_URL="${ORDERS_URL:-$CORE_URL/modules/orders}"
  CATALOG_URL="${CATALOG_URL:-$CORE_URL/modules/catalog}"
fi
STOREFRONT_URL="${STOREFRONT_URL:-http://localhost:$(web_port STOREFRONT_PORT 3000)}"
SELLER_CENTER_URL="${SELLER_CENTER_URL:-http://localhost:$(web_port SELLER_CENTER_PORT 3010)}"
STAFF_CONSOLE_URL="${STAFF_CONSOLE_URL:-http://localhost:$(web_port STAFF_CONSOLE_PORT 3002)}"
MAILPIT_URL="${MAILPIT_URL:-http://localhost:8025}"
MEILISEARCH_URL="${MEILISEARCH_URL:-http://localhost:7700}"
PROMETHEUS_URL="${PROMETHEUS_URL:-http://localhost:9090}"
MIN_PRODUCTS="${SMOKE_MIN_PRODUCTS:-40}"
DEPT_SKIP="${SMOKE_DEPT_SKIP:-Services}"
CUSTOMER="${SMOKE_CUSTOMER:-customer.houston@afrotransact.test}"
PASSWORD="${SMOKE_PASSWORD:-Test1234!Local}"   # the documented local test password (tools/seed/README.md), not a secret

TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
JAR="$TMP/jar"
PASS=0; FAIL=0; SKIP=0
CODE=""; BODY="$TMP/body"

pass() { PASS=$((PASS + 1)); printf 'PASS  %s\n' "$1"; }
fail() { FAIL=$((FAIL + 1)); printf 'FAIL  %s  ->  %s\n' "$1" "$2"; }
skip() { SKIP=$((SKIP + 1)); printf 'SKIP  %s  (%s)\n' "$1" "$2"; }

# req METHOD URL [curl args...]: the answer's status in CODE, its body in $BODY (CODE 000 when nothing answered).
req() {
  local method="$1" url="$2"; shift 2
  CODE=$(curl -sS -m 25 -X "$method" -o "$BODY" -w '%{http_code}' "$@" "$url" 2>/dev/null) || CODE="000"
  [[ -s "$BODY" ]] || : >"$BODY"
}
ok2xx() { [[ "$CODE" =~ ^2 ]]; }
j() { jq -r "$1" "$BODY" 2>/dev/null; }          # a value of the last body ("" when it is not JSON)
num() { local v; v=$(j "$1"); [[ "$v" =~ ^[0-9]+$ ]] && echo "$v" || echo 0; }
bff() { # METHOD PATH [curl args]: gateway-bff with the guest cookie jar
  local m="$1" p="$2"; shift 2
  req "$m" "$BFF_URL$p" -b "$JAR" -c "$JAR" -H 'Accept: application/json' "$@"
}

echo "AfroTransact release 1 smoke (STACK=$STACK): BFF $BFF_URL, core $CORE_URL, storefront $STOREFRONT_URL"

# ------------------------------------------------------------------------------------------------ backends
if [[ "$STACK" == services ]]; then
  down=""
  for p in 8100 8101 8102 8103 8104 8105 8106 8107 8108 8109 8110 8111 8112 8115; do
    req GET "http://localhost:$p/actuator/health"
    [[ "$CODE" == 200 && "$(j .status)" == UP ]] || down="$down :$p($CODE)"
  done
  if [[ -z "$down" ]]; then pass "every Java service is UP (:8100-8115)"; else fail "every Java service is UP" "DOWN:$down"; fi
else
  req GET "$CORE_URL/actuator/health/readiness"
  if [[ "$CODE" == 200 && "$(j .status)" == UP ]]; then pass "core readiness"; else fail "core readiness" "$CORE_URL/actuator/health/readiness status $CODE"; fi
  req GET "$CORE_URL/actuator/health"
  bad=$(jq -r '[.components // {} | to_entries[] | select(.value.status == "DOWN" or .value.status == "OUT_OF_SERVICE") | .key] | join(", ")' "$BODY" 2>/dev/null)
  n_up=$(jq -r '[.components // {} | to_entries[] | select(.value.status == "UP")] | length' "$BODY" 2>/dev/null || echo 0)
  if [[ "$CODE" == 200 && -z "$bad" && "${n_up:-0}" -gt 0 ]]; then pass "every core module is UP ($n_up components)"
  else fail "every core module is UP" "$CORE_URL/actuator/health status $CODE, not UP: ${bad:-none listed}"; fi
fi
req GET "$BFF_URL/readyz"
if [[ "$CODE" == 200 ]]; then pass "afrotransact-go /readyz"; else fail "afrotransact-go /readyz" "$BFF_URL/readyz status $CODE"; fi

# ------------------------------------------------------------------------------------------------ catalogue and search
bff GET "/v1/search?size=60&ads=false"
total=$(num .total)
if ok2xx && [[ "$total" -ge "$MIN_PRODUCTS" ]]; then pass "products exist ($total >= $MIN_PRODUCTS)"
else fail "products exist (>= $MIN_PRODUCTS)" "$BFF_URL/v1/search status $CODE, total $total (run make seed; make reindex-search)"; fi
HITS="$TMP/hits.json"; cp "$BODY" "$HITS"

no_photo=$(jq -r '[.hits // [] | .[] | select((.imageUrl // "") == "")] | length' "$HITS" 2>/dev/null || echo 0)
n_hits=$(jq -r '.hits // [] | length' "$HITS" 2>/dev/null || echo 0)
if [[ "${n_hits:-0}" -gt 0 && "${no_photo:-1}" -eq 0 ]]; then pass "every product in search has a photo ($n_hits checked)"
else fail "every product in search has a photo" "$BFF_URL/v1/search: $no_photo of $n_hits hits without imageUrl (make seed re-uploads them)"; fi

bad_img=""
for url in $(jq -r '.hits // [] | .[0:3][] | .imageUrl // empty' "$HITS" 2>/dev/null); do
  code=$(curl -sS -m 15 -o /dev/null -I -w '%{http_code}' "$url" 2>/dev/null || echo 000)
  [[ "$code" == 200 ]] || bad_img="$bad_img $url($code)"
done
if [[ -z "$bad_img" && "${n_hits:-0}" -gt 0 ]]; then pass "photo files are served (object store)"; else fail "photo files are served" "${bad_img:-no hits}"; fi

bff GET "/v1/home"
home_total=$(jq -r '[.. | objects | select(has("atin") and has("imageUrl"))] | length' "$BODY" 2>/dev/null || echo 0)
home_missing=$(jq -r '[.. | objects | select(has("atin") and has("imageUrl")) | select((.imageUrl // "") == "")] | length' "$BODY" 2>/dev/null || echo 0)
if ok2xx && [[ "${home_total:-0}" -gt 0 && "${home_missing:-1}" -eq 0 ]]; then pass "storefront home rails: every product has a photo ($home_total)"
else fail "storefront home rails have photos" "$BFF_URL/v1/home status $CODE, $home_total products, $home_missing without a photo"; fi

for q in iru rice; do
  bff GET "/v1/search?q=$q&size=3&ads=false"
  if ok2xx && [[ "$(num .total)" -gt 0 ]]; then pass "search '$q' returns results ($(num .total))"
  else fail "search '$q' returns results" "$BFF_URL/v1/search?q=$q status $CODE, total $(num .total)"; fi
done

req GET "$CATALOG_URL/v1/categories"
depts=$(jq -r '.[]? | select(.level == "DEPARTMENT") | .name' "$BODY" 2>/dev/null)
if [[ -z "$depts" ]]; then
  fail "one product per top-level department" "category tree not readable (status $CODE)"
else
  empty=""; checked=0
  while IFS= read -r d; do
    [[ -z "$d" ]] && continue
    if [[ ",$DEPT_SKIP," == *",$d,"* ]]; then continue; fi
    checked=$((checked + 1))
    req GET "$BFF_URL/v1/search" -G --data-urlencode "department=$d" -d "size=1" -d "ads=false" -H 'Accept: application/json'
    [[ "$(num .total)" -gt 0 ]] || empty="$empty [$d]"
  done <<<"$depts"
  if [[ -z "$empty" ]]; then pass "at least one product in each of $checked departments"
  else fail "at least one product per top-level department" "no products in:$empty (skipped: $DEPT_SKIP)"; fi
fi

# ------------------------------------------------------------------------------------------------ product page, store page
atin=$(jq -r '.hits[0].atin // empty' "$HITS" 2>/dev/null)
offer=$(jq -r '.hits[0].offerId // empty' "$HITS" 2>/dev/null)
if [[ -n "$atin" ]]; then
  bff GET "/v1/products/$atin"
  price=$(j '.buyBox.priceMinor // empty'); photo=$(j '.product.images[0].url // empty'); seller=$(j '.buyBox.seller.name // .soldBy // empty')
  if ok2xx && [[ -n "$price" && -n "$photo" && -n "$seller" ]]; then pass "product page data: price, photo, seller ($atin)"
  else fail "product page has price, photo and seller" "$BFF_URL/v1/products/$atin status $CODE, price='${price}' photo='${photo:+set}' seller='${seller}'"; fi
  req GET "$STOREFRONT_URL/p/$atin" -L
  if [[ "$CODE" == 200 ]]; then pass "storefront product page /p/$atin renders"; else fail "storefront product page renders" "$STOREFRONT_URL/p/$atin status $CODE"; fi
else
  fail "product page has price, photo and seller" "no product to open (search returned none)"
fi

store_ok=""; tried=""
while IFS= read -r name; do
  [[ -z "$name" ]] && continue
  slug=$(printf '%s' "$name" | tr '[:upper:]' '[:lower:]' | sed -E 's/[^a-z0-9]+/-/g; s/^-+|-+$//g')
  tried="$tried /b/$slug"
  req GET "$STOREFRONT_URL/b/$slug" -L
  if [[ "$CODE" == 200 ]] && grep -q '/p/' "$BODY"; then store_ok="/b/$slug"; break; fi
done < <(jq -r '[.hits // [] | .[] | select(.sellerKind != "PLATFORM") | .sellerName] | unique | .[]' "$HITS" 2>/dev/null)
if [[ -n "$store_ok" ]]; then pass "seller store page $store_ok returns 200 with products"
else fail "a seller store page /b/<slug> returns 200 with products" "$STOREFRONT_URL tried:${tried:- none} (a store page needs a published store)"; fi

# ------------------------------------------------------------------------------------------------ cart, tax, checkout
if [[ -n "$offer" ]]; then
  bff POST "/v1/cart/items" -H 'Content-Type: application/json' -d "{\"offerId\":\"$offer\",\"quantity\":1}"
  if ok2xx; then pass "cart add (guest)"; else fail "cart add" "$BFF_URL/v1/cart/items status $CODE $(j '.code // .title // empty')"; fi
  bff GET "/v1/cart"
  lines=$(jq -r '[.packages // [] | .[] | .lines // [] | .[]] | length' "$BODY" 2>/dev/null || echo 0)
  if ok2xx && [[ "${lines:-0}" -ge 1 ]]; then pass "cart read ($lines line)"; else fail "cart read" "$BFF_URL/v1/cart status $CODE, $lines lines"; fi
  bff GET "/v1/checkout/tax-estimate?zip=77042"
  status=$(j .taxEstimateStatus)
  if ok2xx && [[ "$status" == ESTIMATED ]]; then pass "estimated tax for ZIP 77042 ($(j .taxRateLabel), $(j .taxJurisdictionLabel))"
  else fail "estimated tax shows a value for ZIP 77042" "$BFF_URL/v1/checkout/tax-estimate?zip=77042 status $CODE, taxEstimateStatus '$status' $(j '.taxUnavailableReason // empty')"; fi
else
  fail "cart add / read / tax estimate" "no buyable offer in search results"
fi

sign_in_customer() { # prints an access token, or nothing
  local secret="${GATEWAY_BFF_CLIENT_SECRET:-${BFF_CLIENT_SECRET:-local-bff-secret}}"
  local ctoken ticket verifier challenge loc code redirect="http://localhost:3000/api/bff/v1/auth/oidc/callback"
  ctoken=$(curl -sS -m 20 -u "gateway-bff:$secret" -d grant_type=client_credentials -d scope=auth:first-party "$IDENTITY_URL/oauth2/token" 2>/dev/null | jq -r '.access_token // empty')
  [[ -n "$ctoken" ]] || return 1
  ticket=$(curl -sS -m 20 -X POST "$IDENTITY_URL/v1/auth/password" -H "Authorization: Bearer $ctoken" -H 'Content-Type: application/json' \
    -d "$(jq -nc --arg e "$CUSTOMER" --arg p "$PASSWORD" '{email:$e,password:$p,clientIp:"127.0.0.1",userAgent:"AfroTransactSmoke"}')" 2>/dev/null | jq -r '.loginTicket // empty')
  [[ -n "$ticket" ]] || return 1
  verifier=$(head -c 48 /dev/urandom | base64 | tr -d '=+/\n' | cut -c1-64)
  challenge=$(printf '%s' "$verifier" | openssl dgst -sha256 -binary | openssl base64 -A | tr '+/' '-_' | tr -d '=')
  loc=$(curl -sS -m 20 -o /dev/null -D - -G "$IDENTITY_URL/oauth2/authorize" --data-urlencode response_type=code --data-urlencode client_id=gateway-bff \
    --data-urlencode "redirect_uri=$redirect" --data-urlencode scope=openid --data-urlencode state=smoke --data-urlencode "code_challenge=$challenge" \
    --data-urlencode code_challenge_method=S256 --data-urlencode "login_ticket=$ticket" 2>/dev/null | tr -d '\r' | sed -n 's/^[Ll]ocation: //p')
  code=$(printf '%s' "$loc" | sed -n 's/.*[?&]code=\([^&]*\).*/\1/p')
  [[ -n "$code" ]] || return 1
  curl -sS -m 20 -u "gateway-bff:$secret" -d grant_type=authorization_code -d "code=$code" --data-urlencode "redirect_uri=$redirect" \
    -d "code_verifier=$verifier" "$IDENTITY_URL/oauth2/token" 2>/dev/null | jq -r '.access_token // empty'
}

if [[ "${SMOKE_CHECKOUT:-1}" == 0 ]]; then
  skip "checkout reaches the payment-intent step" "SMOKE_CHECKOUT=0"
elif [[ -z "$offer" ]]; then
  fail "checkout reaches the payment-intent step" "no buyable offer in search results"
else
  token=$(sign_in_customer || true)
  if [[ -z "$token" ]]; then
    fail "checkout reaches the payment-intent step" "$IDENTITY_URL sign-in of $CUSTOMER failed (first-party client token or password; the core fix for identity.first-party-client-required must be running)"
  else
    auth=(-H "Authorization: Bearer $token" -H 'Accept: application/json')
    req POST "$BFF_URL/v1/cart/items" "${auth[@]}" -H 'Content-Type: application/json' -d "{\"offerId\":\"$offer\",\"quantity\":1}"
    req GET "$BFF_URL/v1/checkout/summary" "${auth[@]}"
    address=$(j '.addresses[0].id // empty'); quote=$(j '.taxQuoteId // empty')
    selections=$(jq -c '[.packages // [] | .[] | {packageId: .packageId, option: ([.deliveryOptions[]?.key | select(. == "afrotransact_delivery" or . == "pickup")] | first // "afrotransact_delivery")}]' "$BODY" 2>/dev/null)
    if [[ -z "$address" ]]; then
      fail "checkout reaches the payment-intent step" "$BFF_URL/v1/checkout/summary status $CODE: $CUSTOMER has no saved address"
    else
      key=$(head -c 16 /dev/urandom | od -An -tx1 | tr -d ' \n')
      req POST "$BFF_URL/v1/checkout/orders" "${auth[@]}" -H 'Content-Type: application/json' -H "Idempotency-Key: $key" \
        -d "$(jq -nc --arg a "$address" --arg q "$quote" --argjson s "${selections:-[]}" '{addressId:$a, deliverySelections:$s, newPaymentMethod:true} + (if $q != "" then {taxQuoteId:$q} else {} end)')"
      secret_present=$(j '(.paymentClientSecret // "") | if . == "" then "no" else "yes" end')
      if [[ "$CODE" =~ ^(201|202)$ && "$secret_present" == yes ]]; then
        pass "checkout reaches the payment-intent step (order $(j .orderNumber), $(j .status); local Stripe stand-in)"
      else
        fail "checkout reaches the payment-intent step" "$BFF_URL/v1/checkout/orders status $CODE $(j '.code // .title // empty') (paymentClientSecret: $secret_present)"
      fi
    fi
  fi
fi

# ------------------------------------------------------------------------------------------------ web apps and infrastructure
req GET "$STAFF_CONSOLE_URL/" -L
if [[ "$CODE" == 200 ]]; then pass "staff console login page"; else fail "staff console login page" "$STAFF_CONSOLE_URL/ status $CODE"; fi
req GET "$SELLER_CENTER_URL/" -L
if [[ "$CODE" == 200 ]]; then pass "Seller Center login page"; else fail "Seller Center login page" "$SELLER_CENTER_URL/ status $CODE"; fi
req GET "$MAILPIT_URL/api/v1/info"
if ok2xx; then pass "Mailpit reachable"; else fail "Mailpit reachable" "$MAILPIT_URL/api/v1/info status $CODE"; fi

if [[ "${SEARCH_PROVIDER:-meilisearch}" == elasticsearch ]]; then
  es="${ES_URL:-http://localhost:9200}"
  req GET "$es/_cat/count?format=json"
  docs=$(j '.[0].count // 0')
  where="$es/_cat/count"
else
  req GET "$MEILISEARCH_URL/stats"
  docs=$(j '[.indexes // {} | .[] | .numberOfDocuments] | add // 0')
  where="$MEILISEARCH_URL/stats"
fi
if ok2xx && [[ "${docs:-0}" =~ ^[0-9]+$ && "${docs:-0}" -gt 0 ]]; then pass "search index has documents ($docs)"
else fail "search index document count > 0" "$where status $CODE, ${docs:-0} documents: run make reindex-search, then make seed"; fi

req GET "$ORDERS_URL/v1/admin/orders?limit=100"
orders_total=$(num '.total')
by_state=$(jq -r '[.items // [] | .[] | (.status // .state // "unknown")] | group_by(.) | map("\(.[0]) \(length)") | join(", ")' "$BODY" 2>/dev/null)
seeded=$(jq -r '[.items // [] | .[] | select((.attribution.src // "") == "demo-seed")] | length' "$BODY" 2>/dev/null || echo 0)
if ok2xx && [[ "$orders_total" -gt 0 ]]; then
  pass "seeded orders exist in the staff order list ($orders_total orders, $seeded seeded; by state: ${by_state:-n/a})"
else fail "seeded orders exist in the staff order list" "$ORDERS_URL/v1/admin/orders status $CODE, total $orders_total (make seed runs the ops step; its summary names each cause once)"; fi

req GET "$PROMETHEUS_URL/api/v1/targets?state=active"
if ok2xx; then
  # core is scraped at :8120 (job afrotransact-core), and through a legacy port (:8101) while those are on;
  # afrotransact-go serves :8200 (gateway-bff's port).
  core_health=$(jq -r '[.data.activeTargets[] | select(.scrapeUrl | test(":(8120|8101)/")) | .health] | if any(. == "up") then "up" else (first // "missing") end' "$BODY")
  go_health=$(jq -r '[.data.activeTargets[] | select(.scrapeUrl | test(":8200/")) | .health] | first // "missing"' "$BODY")
  if [[ "$core_health" == up ]]; then pass "Prometheus target for core (:8120) is UP"; else fail "Prometheus target for core is UP" "$PROMETHEUS_URL/api/v1/targets: core (:8120, or legacy :8101) is $core_health (restart Prometheus: make down && make up)"; fi
  if [[ "$go_health" == up ]]; then pass "Prometheus target for afrotransact-go (:8200) is UP"; else fail "Prometheus target for afrotransact-go is UP" "$PROMETHEUS_URL/api/v1/targets: :8200 is $go_health"; fi
else
  fail "Prometheus targets for core and afrotransact-go are UP" "$PROMETHEUS_URL/api/v1/targets status $CODE (OBS=0 turns Prometheus off)"
fi

echo
echo "smoke: $PASS passed, $FAIL failed, $SKIP skipped"
[[ "$FAIL" -eq 0 ]]
