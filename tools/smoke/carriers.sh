#!/usr/bin/env bash
# Carrier sandbox smoke test (S204): drives carrier-gateway's public API against the carrier sandboxes it is
# configured for (config/<env>.yaml: USPS TEM, UPS CIE, FedEx sandbox, MyDHL test) and checks, per carrier:
#   rates            POST /v1/rates (carriers=[c])                    -> status ok, at least one quote
#   address          POST /v1/addresses/validate                     -> 200 (carrier-side validation)
#   label            POST /v1/labels (Idempotency-Key)               -> 201, tracking number, document
#   label (replay)   same key + body                                  -> same label id
#   tracking         GET  /v1/tracking/{carrier}/{trackingNumber}     -> 200 (sandboxes rarely have scans yet)
#   void             POST /v1/labels/{id}/void                        -> status voided (DHL: local void)
#   webhook          POST /v1/webhooks/{fedex|dhl} signed with the shared secret -> 200; wrong signature -> 401
#
# Runbook: docs/runbooks/carrier-sandboxes.md. CI: .github/workflows/carrier-sandboxes.yml (manual).
#
# Usage
#   tools/smoke/carriers.sh                                  # every carrier against http://localhost:8209 (auth off)
#   tools/smoke/carriers.sh --carriers "ups fedex" --rates-only
#   CARRIER_GATEWAY_URL=https://api.dev.<domain>/carrier-gateway \
#   AUTH_TOKEN_URL=https://auth.dev.<domain>/oauth2/token SMOKE_CLIENT_ID=... SMOKE_CLIENT_SECRET=... \
#     tools/smoke/carriers.sh
#   tools/smoke/carriers.sh --dry-run                        # print the requests, call nothing
#
# Environment
#   CARRIER_GATEWAY_URL   base URL of the service (default http://localhost:8209)
#   SMOKE_TOKEN           bearer token with shipping:rate shipping:label shipping:void; or obtain one with
#   AUTH_TOKEN_URL, SMOKE_CLIENT_ID, SMOKE_CLIENT_SECRET   (client credentials, scope from SMOKE_SCOPE)
#   SMOKE_ORIGIN_NODE     originNode used for carrier accounts (default FC-HOU-1)
#   FEDEX_WEBHOOK_SECRET, DHL_WEBHOOK_SECRET   enable the signed webhook checks (same values the service uses)
#   SMOKE_REPORT          also write the results as JSON lines to this file
#
# Exit status: 0 all checks passed (skips allowed), 1 a check failed, 2 usage / missing tools.
set -euo pipefail

BASE="${CARRIER_GATEWAY_URL:-http://localhost:8209}"
BASE="${BASE%/}"
CARRIERS="usps ups fedex dhl"
RATES_ONLY=0
DRY_RUN=0
TIMEOUT="${SMOKE_TIMEOUT:-30}"
ORIGIN="${SMOKE_ORIGIN_NODE:-FC-HOU-1}"
REPORT="${SMOKE_REPORT:-}"

usage() { sed -n '2,32p' "$0" | sed 's/^# \{0,1\}//'; }

while [[ $# -gt 0 ]]; do
  case "$1" in
    --carriers) CARRIERS="${2:?--carriers needs a list}"; shift ;;
    --rates-only) RATES_ONLY=1 ;;
    --dry-run) DRY_RUN=1 ;;
    --url) BASE="${2:?--url needs a value}"; BASE="${BASE%/}"; shift ;;
    -h|--help) usage; exit 0 ;;
    *) echo "unknown argument: $1" >&2; usage >&2; exit 2 ;;
  esac
  shift
done

for tool in curl jq openssl; do
  command -v "$tool" >/dev/null || { echo "carriers.sh needs $tool on PATH" >&2; exit 2; }
done
for c in $CARRIERS; do
  case "$c" in usps|ups|fedex|dhl) ;; *) echo "unknown carrier: $c (usps ups fedex dhl)" >&2; exit 2 ;; esac
done

WORK="$(mktemp -d)"; trap 'rm -rf "$WORK"' EXIT
PASS=0; FAIL=0; SKIP=0
RESULTS=()

record() { # carrier check status detail
  local line
  line="$(printf '%-6s %-15s %-4s %s' "$1" "$2" "$3" "$4")"
  RESULTS+=("$line")
  case "$3" in ok) PASS=$((PASS + 1)) ;; FAIL) FAIL=$((FAIL + 1)) ;; *) SKIP=$((SKIP + 1)) ;; esac
  echo "$line"
  if [[ -n "$REPORT" ]]; then
    jq -cn --arg carrier "$1" --arg check "$2" --arg status "$3" --arg detail "$4" '$ARGS.named' >> "$REPORT"
  fi
}

# ------------------------------------------------------------------------------------------------ auth
AUTH=()
token="${SMOKE_TOKEN:-}"
if [[ -z "$token" && -n "${AUTH_TOKEN_URL:-}" && $DRY_RUN -eq 0 ]]; then
  : "${SMOKE_CLIENT_ID:?SMOKE_CLIENT_ID is required with AUTH_TOKEN_URL}"
  : "${SMOKE_CLIENT_SECRET:?SMOKE_CLIENT_SECRET is required with AUTH_TOKEN_URL}"
  token="$(curl -sS --fail-with-body --max-time "$TIMEOUT" -u "${SMOKE_CLIENT_ID}:${SMOKE_CLIENT_SECRET}" \
    -d grant_type=client_credentials \
    --data-urlencode "scope=${SMOKE_SCOPE:-shipping:rate shipping:label shipping:void}" \
    "$AUTH_TOKEN_URL" | jq -r '.access_token // empty')"
  [[ -n "$token" ]] || { echo "no access_token from $AUTH_TOKEN_URL" >&2; exit 1; }
fi
[[ -n "$token" ]] && AUTH=(-H "Authorization: Bearer ${token}")

# call METHOD PATH [BODY_FILE] [extra curl args...] -> sets HTTP_STATUS, body in $WORK/body
call() {
  local method="$1" path="$2" body="${3:-}"; shift 3 || shift $#
  local args=(-sS --max-time "$TIMEOUT" -o "$WORK/body" -w '%{http_code}' -X "$method" "${AUTH[@]}"
              -H 'Accept: application/json')
  [[ -n "$body" ]] && args+=(-H 'Content-Type: application/json' --data-binary "@$body")
  if [[ $DRY_RUN -eq 1 ]]; then
    echo "  > $method $BASE$path${body:+ $(jq -c . "$body")}" >&2
    echo '{}' > "$WORK/body"; HTTP_STATUS=000; return 0
  fi
  HTTP_STATUS="$(curl "${args[@]}" "$@" "$BASE$path" || echo 000)"
}

problem() { jq -r '[.status, .code // .type, .detail // .title] | map(tostring) | join(" ")' "$WORK/body" 2>/dev/null \
  | head -c 300 || true; }

# ------------------------------------------------------------------------------------------------ fixtures
# Sandbox-friendly addresses: USPS/UPS/FedEx domestic (Houston FC -> Atlanta), DHL Express international (US -> GB,
# customs lines with HS codes, which MyDHL requires for dutiable shipments).
shipment_json() { # carrier
  local ship_date
  ship_date="$(date -u -d '+1 day' +%F 2>/dev/null || date -u -v+1d +%F)"
  if [[ "$1" == dhl ]]; then
    jq -n --arg origin "$ORIGIN" --arg date "$ship_date" '{
      reference: ("SMOKE-" + ($date | gsub("-"; ""))), originNode: $origin, shipDate: $date,
      from: {name: "AfroTransact Smoke", company: "AfroTransact", phone: "7135550100", email: "smoke@example.com",
             line1: "1200 Smith St", city: "Houston", state: "TX", postalCode: "77002", country: "US"},
      to:   {name: "Smoke Receiver", phone: "442071234567", email: "receiver@example.com",
             line1: "10 Downing St", city: "London", postalCode: "SW1A 2AA", country: "GB"},
      parcels: [{weightLb: 2.5, lengthIn: 12, widthIn: 9, heightIn: 4}],
      customs: {currency: "USD", incoterm: "DAP", exportReason: "SALE", invoiceNumber: "SMOKE-INV-1",
                lines: [{description: "Shea butter", quantity: 2, unitValueMinor: 1500, hsCode: "1515.90",
                         originCountry: "GH", weightLb: 1.0}]}}'
  else
    jq -n --arg origin "$ORIGIN" --arg date "$ship_date" '{
      reference: ("SMOKE-" + ($date | gsub("-"; ""))), originNode: $origin, shipDate: $date,
      from: {name: "AfroTransact Smoke", company: "AfroTransact", phone: "7135550100", email: "smoke@example.com",
             line1: "1200 Smith St", city: "Houston", state: "TX", postalCode: "77002", country: "US"},
      to:   {name: "Smoke Receiver", phone: "4045550100", email: "receiver@example.com",
             line1: "55 Trinity Ave SW", city: "Atlanta", state: "GA", postalCode: "30303", country: "US",
             residential: false},
      parcels: [{weightLb: 2.5, lengthIn: 12, widthIn: 9, heightIn: 4}]}'
  fi
}

# ------------------------------------------------------------------------------------------------ checks
smoke_carrier() {
  local c="$1" quote service label_id tracking key
  shipment_json "$c" > "$WORK/shipment.json"

  # rates
  jq -n --arg c "$c" --slurpfile s "$WORK/shipment.json" '{carriers: [$c], shipment: $s[0]}' > "$WORK/rates.json"
  call POST /v1/rates "$WORK/rates.json"
  if [[ $DRY_RUN -eq 1 ]]; then
    service="DRY_RUN"
  elif [[ "$HTTP_STATUS" != 200 ]]; then
    record "$c" rates FAIL "HTTP $HTTP_STATUS $(problem)"; return 0
  else
    local status count
    status="$(jq -r --arg c "$c" '.carriers[]? | select(.carrier == $c) | .status' "$WORK/body")"
    count="$(jq --arg c "$c" '[.quotes[]? | select(.carrier == $c)] | length' "$WORK/body")"
    if [[ "$status" != ok || "$count" -lt 1 ]]; then
      record "$c" rates FAIL "status=${status:-missing} quotes=$count $(jq -c --arg c "$c" '.carriers[]? | select(.carrier == $c) | .error // empty' "$WORK/body")"
      return 0
    fi
    quote="$(jq -c --arg c "$c" '[.quotes[] | select(.carrier == $c)] | sort_by(.amountMinor) | first' "$WORK/body")"
    service="$(jq -r .service <<<"$quote")"
    record "$c" rates ok "$count quote(s), cheapest $service $(jq -r '"\(.amountMinor) \(.currency)"' <<<"$quote")"
  fi

  # carrier-side address validation (USPS for US by default; DHL is serviceability only)
  jq '{address: .to}' "$WORK/shipment.json" > "$WORK/address.json"
  call POST /v1/addresses/validate "$WORK/address.json"
  if [[ $DRY_RUN -eq 0 ]]; then
    case "$HTTP_STATUS" in
      200) record "$c" address ok "$(jq -c '{valid, carrier} | with_entries(select(.value != null))' "$WORK/body")" ;;
      *)   record "$c" address FAIL "HTTP $HTTP_STATUS $(problem)" ;;
    esac
  fi

  if [[ $RATES_ONLY -eq 1 ]]; then
    record "$c" label skip "--rates-only"; return 0
  fi

  # label (idempotent purchase), replay, document
  key="smoke-${c}-$(date -u +%Y%m%dT%H%M%S)-$RANDOM"
  jq -n --arg c "$c" --arg s "$service" --slurpfile sh "$WORK/shipment.json" \
    '{carrier: $c, service: $s, shipment: $sh[0], fulfilment: "FBA"}' > "$WORK/label.json"
  call POST /v1/labels "$WORK/label.json" -H "Idempotency-Key: $key"
  [[ $DRY_RUN -eq 1 ]] && { call POST "/v1/labels/{id}/void" ""; return 0; }
  if [[ "$HTTP_STATUS" != 201 ]]; then
    record "$c" label FAIL "HTTP $HTTP_STATUS $(problem)"; return 0
  fi
  label_id="$(jq -r .id "$WORK/body")"
  tracking="$(jq -r '.trackingNumber // empty' "$WORK/body")"
  if [[ -z "$tracking" ]]; then
    record "$c" label FAIL "label $label_id has no tracking number"
  else
    record "$c" label ok "id=$label_id tracking=$tracking format=$(jq -r .format "$WORK/body") cost=$(jq -r '"\(.costMinor) \(.currency)"' "$WORK/body")"
  fi

  call POST /v1/labels "$WORK/label.json" -H "Idempotency-Key: $key"
  if [[ "$HTTP_STATUS" == 201 && "$(jq -r .id "$WORK/body")" == "$label_id" ]]; then
    record "$c" label-replay ok "same label for the same Idempotency-Key"
  else
    record "$c" label-replay FAIL "HTTP $HTTP_STATUS id=$(jq -r '.id // empty' "$WORK/body")"
  fi

  local doc_status
  doc_status="$(curl -sS --max-time "$TIMEOUT" "${AUTH[@]}" -o "$WORK/label.doc" -w '%{http_code}' \
    "$BASE/v1/labels/$label_id/document" || echo 000)"
  if [[ "$doc_status" == 200 && -s "$WORK/label.doc" ]]; then
    record "$c" document ok "$(wc -c < "$WORK/label.doc" | tr -d ' ') bytes"
  elif [[ "$doc_status" == 302 || "$doc_status" == 307 ]]; then
    record "$c" document ok "redirect to signed URL"
  else
    record "$c" document FAIL "HTTP $doc_status"
  fi

  # tracking: sandboxes return canned or empty histories; 200 proves auth, path and parsing
  if [[ -n "$tracking" ]]; then
    call GET "/v1/tracking/$c/$tracking" ""
    case "$HTTP_STATUS" in
      200) record "$c" tracking ok "state=$(jq -r '.state // .trackingState // "?"' "$WORK/body") events=$(jq '.events | length? // 0' "$WORK/body")" ;;
      404) record "$c" tracking skip "not known to the sandbox yet (HTTP 404)" ;;
      *)   record "$c" tracking FAIL "HTTP $HTTP_STATUS $(problem)" ;;
    esac
  fi

  # void (before the parcel is scanned)
  call POST "/v1/labels/$label_id/void" ""
  if [[ "$HTTP_STATUS" =~ ^20[0-9]$ && "$(jq -r '.status // empty' "$WORK/body")" == voided ]]; then
    record "$c" void ok "$( [[ "$c" == dhl ]] && echo 'local void (MyDHL has no cancel)' || echo 'voided at the carrier')"
  else
    record "$c" void FAIL "HTTP $HTTP_STATUS $(problem)"
  fi
}

# Signed webhook round trip for the HMAC carriers: proves the secret, header name and encoding match the service.
smoke_webhook() { # carrier secret header
  local c="$1" secret="$2" header="$3" sig
  if [[ -z "$secret" ]]; then
    record "$c" webhook skip "${c^^}_WEBHOOK_SECRET not set"; return 0
  fi
  # Empty documents in each carrier's push shape: verified, parsed, nothing to apply (accepted 0).
  case "$c" in
    fedex) echo '{"output":{"completeTrackResults":[]}}' > "$WORK/hook.json" ;;
    dhl)   echo '{"shipments":[]}' > "$WORK/hook.json" ;;
  esac
  sig="$(openssl dgst -sha256 -hmac "$secret" -binary "$WORK/hook.json" | openssl base64 -A)"
  call POST "/v1/webhooks/$c" "$WORK/hook.json" -H "$header: $sig"
  [[ $DRY_RUN -eq 1 ]] && return 0
  if [[ "$HTTP_STATUS" =~ ^20[0-9]$ ]]; then
    record "$c" webhook ok "signed push accepted ($(jq -c . "$WORK/body" 2>/dev/null))"
  else
    # 401 = signature mismatch (secret, header name or encoding differ from the service's config).
    record "$c" webhook FAIL "signed push HTTP $HTTP_STATUS $(problem)"
  fi
  call POST "/v1/webhooks/$c" "$WORK/hook.json" -H "$header: AAAA"
  if [[ "$HTTP_STATUS" == 401 || "$HTTP_STATUS" == 403 ]]; then
    record "$c" webhook-bad-sig ok "rejected with HTTP $HTTP_STATUS"
  else
    record "$c" webhook-bad-sig FAIL "expected 401, got HTTP $HTTP_STATUS"
  fi
}

echo "carrier-gateway smoke: $BASE  carriers: $CARRIERS$([[ $RATES_ONLY -eq 1 ]] && echo '  (rates only)')$([[ $DRY_RUN -eq 1 ]] && echo '  (dry run)')"
if [[ $DRY_RUN -eq 0 ]]; then
  ready="$(curl -sS --max-time "$TIMEOUT" -o /dev/null -w '%{http_code}' "$BASE/readyz" || echo 000)"
  # Behind gateway-bff / the edge only /v1 is routed; a 404 on /readyz is fine there.
  [[ "$ready" == 200 || "$ready" == 404 ]] || { echo "carrier-gateway not reachable at $BASE (readyz HTTP $ready)" >&2; exit 1; }
fi

for c in $CARRIERS; do
  smoke_carrier "$c"
done
for c in $CARRIERS; do
  case "$c" in
    fedex) smoke_webhook fedex "${FEDEX_WEBHOOK_SECRET:-}" "${FEDEX_SIGNATURE_HEADER:-fdx-signature}" ;;
    dhl)   smoke_webhook dhl "${DHL_WEBHOOK_SECRET:-}" "${DHL_SIGNATURE_HEADER:-X-DHL-Signature}" ;;
  esac
done

if [[ -n "${GITHUB_STEP_SUMMARY:-}" ]]; then
  {
    echo "### Carrier sandbox smoke ($BASE)"
    echo '```'
    printf '%s\n' "${RESULTS[@]}"
    echo '```'
    echo "passed $PASS, failed $FAIL, skipped $SKIP"
  } >> "$GITHUB_STEP_SUMMARY"
fi
echo "passed $PASS, failed $FAIL, skipped $SKIP"
[[ $FAIL -eq 0 ]]
