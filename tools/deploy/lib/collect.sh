#!/usr/bin/env bash
# Interactive collection of the catalogue's values (phases 2, 3 and 4 of tools/deploy/guide.sh): one value at a time, each with
# what it is, why, how to get it, validation on entry and a live check where one is cheap and safe. Sourced after catalogue.sh.
# Secrets are read with read -s and go to GitHub through stdin; they are never printed, stored in a file or put in the state.

OVH_AK='' OVH_AS='' OVH_CK=''   # the OVHcloud API values typed in THIS run (memory only), for the live check, project list and state keys

wrap() { # wrap LABEL TEXT: aligned, wrapped
  local label=$1 text=$2 w=$((UI_W - 16))
  printf '%s' "$text" | fold -s -w "$w" | awk -v l="$label" -v b="$C_BOLD" -v r="$C_RESET" 'NR == 1 { printf "  %s%-12s%s %s\n", b, l, r, $0; next } { printf "  %-12s %s\n", "", $0 }'
}

show_card() { # show_card NAME (after cat_load)
  local k=$C_KIND need=$C_NEED
  blank
  printf '%s%s%s  %s(%s, %s)%s\n' "$C_BOLD$C_CYAN" "$C_NAME" "$C_RESET" "$C_DIM" "$k" "$need" "$C_RESET"
  wrap "What it is" "$C_WHAT"
  wrap "Why" "$C_WHY"
  if [ -n "$C_HOWTO" ]; then
    printf '  %sHow to get it%s\n' "$C_BOLD" "$C_RESET"
    printf '%s\n' "$C_HOWTO" | tr '~' '\n' | fold -s -w $((UI_W - 6)) | sed 's/^/    /'
  fi
  [ -z "$C_DEFAULT" ] || hint "default: $C_DEFAULT (press Enter to use it)"
  case "$need" in (optional) hint "optional: type s (or just Enter) to skip it" ;; esac
  hint "type ? to see this again, q to quit"
}

skip_note() { warn "skipped $C_NAME. If skipped: $C_WHY"; }

# collect_one NAME: returns 0 when set or knowingly skipped, 1 on a storage error.
collect_one() {
  local name=$1 val=''
  cat_load "$name" || { bad "unknown value $name"; return 1; }
  show_card
  if is_set "$name"; then
    if [ "$C_KIND" = secret ]; then ok "already set in GitHub (value hidden)"; else ok "already set: $(gh_var "$name")"; fi
    if confirm_yn "Keep it?" y; then return 0; fi
  fi
  while :; do
    local pr="Value"
    [ -z "$C_DEFAULT" ] || pr="Value [$C_DEFAULT]"
    [ "$C_NEED" != generated ] || pr="Value (Enter = generate)"
    if [ "$C_KIND" = secret ]; then read_secret "$pr (hidden):"; else read_in "$pr:"; fi
    val=$REPLY
    case "$val" in
      ('?') show_card; continue ;;
      (s | S) if [ "$C_NEED" = optional ] || [ "$C_NEED" = external ]; then skip_note; return 0; fi
        bad "$name is required; it cannot be skipped"; continue ;;
      ('')
        if [ -n "$C_DEFAULT" ]; then val=$C_DEFAULT
        elif [ "$C_NEED" = generated ]; then val=$(openssl rand -hex 16); GENERATED_SHOWN="$GENERATED_SHOWN $name=$val"
        elif [ "$C_NEED" = optional ]; then skip_note; return 0
        else bad "$name is required"; continue; fi ;;
    esac
    if [ "$C_VAL" != - ] && ! validate "$C_VAL" "$val"; then bad "not accepted: $V_MSG"; continue; fi
    if [ "$C_LIVE" != - ] && [ "$C_LIVE" != ovh ] && [ "$C_LIVE" != ovh_project ]; then
      if ! live_check "$C_LIVE" "$val" "$name"; then
        read_in "Enter to type it again, or type  anyway  to store it as it is:"
        [ "$REPLY" = anyway ] || continue
      fi
    fi
    if gh_put "$C_KIND" "$name" "$val"; then ok "stored in the GitHub Environment $ENVN$([ "$C_KIND" = secret ] && echo ' (secret, hidden)')"
    else bad "GitHub refused to store $name (admin rights on $REPO? is the environment there?)"; return 1; fi
    return 0
  done
}

GENERATED_SHOWN=''
show_generated() { # once, in a panel, for the generated admin password / backup passphrase
  [ -n "$GENERATED_SHOWN" ] || return 0
  local kv
  { echo "These were generated for you and are stored in GitHub. This is the only time they are shown. Copy them into a password manager NOW."
    echo
    for kv in $GENERATED_SHOWN; do echo "${kv%%=*}: ${kv#*=}"; done; } | panel "SHOWN ONCE" yellow
  GENERATED_SHOWN=''
}

# ---- special steps -----------------------------------------------------------------------------------------------------------
h_location() {
  cat_load OVH_LOCATION; show_card
  local keys cur n=0 k def=1 items=()
  keys=$(jq -r 'keys[]' "$OVH_LOCATIONS_FILE")
  cur=$(gh_var OVH_LOCATION); [ -n "$cur" ] || cur=$(state_get location); [ -n "$cur" ] || cur=us-east-va
  for k in $keys; do n=$((n + 1)); [ "$k" != "$cur" ] || def=$n; items+=("$k  $(jq -r --arg l "$k" '.[$l].label + " - account at " + .[$l].site' "$OVH_LOCATIONS_FILE")"); done
  choose "Where should the server run?" "$def" "${items[@]}"
  set -- $keys; eval "OVH_LOCATION=\${$CHOICE}"
  ovh_location "$OVH_LOCATION"
  gh_put var OVH_LOCATION "$OVH_LOCATION" && ok "OVH_LOCATION = $OVH_LOCATION (account needed at $(loc_get site))"
  state_set location "$OVH_LOCATION"
}

live_ovh() { # with the values of this run: the token works, and which Public Cloud projects it sees (>$GUIDE_TMP/projects)
  : > "$GUIDE_TMP/projects"
  (
    OVH_APPLICATION_KEY=$OVH_AK OVH_APPLICATION_SECRET=$OVH_AS OVH_CONSUMER_KEY=$OVH_CK
    ovh_location "$OVH_LOCATION"; ovh_api_init || exit 1
    ids=$(ovh_api GET /cloud/project | jq -r '.[]') || exit 1
    for id in $ids; do
      r=$(ovh_api GET "/cloud/project/$id" 2>/dev/null) || continue
      printf '%s\t%s\n' "$id" "$(printf '%s' "$r" | jq -r '(.description // "no description") + " [" + (.status // "?") + "]"')"
    done
  ) >"$GUIDE_TMP/projects" 2>"$GUIDE_TMP/ovh.err"
}

h_webhosts() { # flat web hosts: offered when OVH_DOMAIN is not the zone itself (OVH_SELLER_HOST, OVH_ADMIN_HOST, OVH_STOREFRONT_HOST=apex)
  local dom zone label cs ca
  dom=$(gh_var OVH_DOMAIN); zone=$(gh_var CLOUDFLARE_ZONE)
  [ -n "$dom" ] && [ -n "$zone" ] || return 0
  [ "$dom" != "$zone" ] || return 0   # the zone itself: www./seller./admin. are one label deep, covered by the free certificate
  cs=$(gh_var OVH_SELLER_HOST); ca=$(gh_var OVH_ADMIN_HOST)
  if [ -n "$cs$ca" ]; then
    ok "explicit web hosts already set: seller ${cs:-default}, admin ${ca:-default}, storefront-host $(gh_var OVH_STOREFRONT_HOST)"
    confirm_yn "Keep them?" y && return 0
  fi
  label=${dom%".$zone"}; label=$(printf '%s' "$label" | tr . -)
  { echo "$dom is below your Cloudflare zone ($zone). Cloudflare's free certificate covers only the zone and ONE label under it, so www.$dom, seller.$dom and admin.$dom (two labels) would get a certificate error unless you pay for an Advanced Certificate."
    echo "The flat layout keeps the web apps one label under the zone: storefront https://$dom, Seller Center https://seller-$label.$zone, staff console https://admin-$label.$zone. The backend (api., auth., grafana.) stays on $dom with Let's Encrypt."; } | panel "FREE CERTIFICATE LAYOUT" yellow
  choose "Which layout?" 1 "Flat (free certificate): storefront-host apex, seller-$label.$zone, admin-$label.$zone" "Nested (www./seller./admin.$dom; needs a paid Advanced Certificate)"
  if [ "$CHOICE" = 1 ]; then
    gh_put var OVH_STOREFRONT_HOST apex && gh_put var OVH_SELLER_HOST "seller-$label.$zone" && gh_put var OVH_ADMIN_HOST "admin-$label.$zone" \
      && ok "OVH_STOREFRONT_HOST = apex, OVH_SELLER_HOST = seller-$label.$zone, OVH_ADMIN_HOST = admin-$label.$zone"
  else warn "nested layout kept: the web hosts are two labels deep, so the browser shows a certificate error until an Advanced Certificate covers them"; fi
}

h_ovhcreds() {
  cat_load OVH_APPLICATION_KEY; show_card
  if is_set OVH_APPLICATION_KEY && is_set OVH_APPLICATION_SECRET && is_set OVH_CONSUMER_KEY; then
    ok "the three OVHcloud API values are already set in GitHub (hidden)"
    confirm_yn "Keep them? (No = enter new ones, for example after the 30 days expired)" y && return 0
  fi
  local rights="GET=/cloud/*&POST=/cloud/*&PUT=/cloud/*&DELETE=/cloud/*"
  info "  Token address for your account ($(loc_get site)):"
  say "    $(loc_get token_url)?$rights"
  while :; do
    read_secret "OVH_APPLICATION_KEY (hidden):"; OVH_AK=$REPLY
    read_secret "OVH_APPLICATION_SECRET (hidden):"; OVH_AS=$REPLY
    read_secret "OVH_CONSUMER_KEY (hidden):"; OVH_CK=$REPLY
    if ! validate ovhkey "$OVH_AK" || ! validate ovhkey "$OVH_AS" || ! validate ovhkey "$OVH_CK"; then bad "one of the three is not accepted: $V_MSG"; continue; fi
    if live_ovh; then verdict_pass "OVHcloud accepted the API values ($(wc -l < "$GUIDE_TMP/projects" | tr -d ' ') Public Cloud project(s) visible)"; break; fi
    verdict_fail "OVHcloud refused them: $(tail -1 "$GUIDE_TMP/ovh.err" 2>/dev/null)"
    hint "Usual causes: the token belongs to the other OVHcloud company (US vs Canada), it was copied incompletely, or its rights were changed."
    read_in "Enter to type them again, or type  anyway  to store them unchecked:"
    [ "$REPLY" != anyway ] || break
  done
  gh_put secret OVH_APPLICATION_KEY "$OVH_AK" && gh_put secret OVH_APPLICATION_SECRET "$OVH_AS" && gh_put secret OVH_CONSUMER_KEY "$OVH_CK" \
    && ok "stored in the GitHub Environment $ENVN (secrets, hidden)"
}

h_project() { # OVH_SERVICE_NAME: adopt a project the API shows, else ask
  cat_load OVH_SERVICE_NAME
  if [ -s "$GUIDE_TMP/projects" ]; then
    local items=() ids=() id d n=0
    while IFS="$(printf '\t')" read -r id d; do items+=("$d  $id"); ids+=("$id"); n=$((n + 1)); done < "$GUIDE_TMP/projects"
    items+=("another project id (type it)")
    blank; info "The API token can see these Public Cloud projects:"
    choose "Which one hosts the test server?" 1 "${items[@]}"
    if [ "$CHOICE" -le "$n" ]; then
      local pick=${ids[$((CHOICE - 1))]}
      gh_put var OVH_SERVICE_NAME "$pick" && ok "OVH_SERVICE_NAME = $pick (adopted)"; return 0
    fi
  elif [ -n "$OVH_AK" ]; then warn "the token shows no Public Cloud project: create one first (steps below)"
  fi
  collect_one OVH_SERVICE_NAME
}

h_statekeys() {
  cat_load TF_STATE_ACCESS_KEY
  local pid; pid=$(gh_var OVH_SERVICE_NAME)
  if [ -z "$OVH_AK" ]; then
    if is_set TF_STATE_ACCESS_KEY; then ok "Terraform state keys already set"; else info "  The state user and keys are made by the pipeline on its first run (tools/ovh/tf-state.sh keys)."; fi
    return 0
  fi
  [ -n "$pid" ] || { warn "no project id yet: state keys skipped"; return 0; }
  show_card
  info "  The wizard finds or creates the Public Cloud user terraform-state (role ObjectStore operator, free) and its S3 key through the API."
  confirm_yn "Do it now?" y || return 0
  local out ak sk
  out=$(
    OVH_APPLICATION_KEY=$OVH_AK OVH_APPLICATION_SECRET=$OVH_AS OVH_CONSUMER_KEY=$OVH_CK OVH_SERVICE_NAME=$pid
    ovh_location "$OVH_LOCATION"; ovh_api_init && state_keys >/dev/null 2>"$GUIDE_TMP/ovh.err" && printf '%s\n%s\n' "$STATE_ACCESS_KEY" "$STATE_SECRET_KEY"
  )
  ak=$(printf '%s\n' "$out" | sed -n 1p); sk=$(printf '%s\n' "$out" | sed -n 2p); out=''
  if [ -n "$ak" ] && [ -n "$sk" ]; then
    gh_put secret TF_STATE_ACCESS_KEY "$ak" && gh_put secret TF_STATE_SECRET_KEY "$sk" && ok "state user key stored (hidden)"
  else warn "could not make the state keys here ($(tail -1 "$GUIDE_TMP/ovh.err" 2>/dev/null)); the pipeline retries on its first run"; fi
  ak=''; sk=''
}

h_ssh() {
  cat_load OVH_VPS_SSH_PRIVATE_KEY
  local keyfile="$GUIDE_ROOT/.ovh/ovh-vps-deploy-key"
  if is_set OVH_VPS_SSH_PRIVATE_KEY && is_set OVH_SSH_PUBLIC_KEY; then
    ok "SSH key pair already in GitHub"
    [ -f "$keyfile" ] || warn "the private half is not in .ovh/ on this machine: you will not be able to ssh in yourself (the pipeline can)"
    return 0
  fi
  show_card
  if [ ! -f "$keyfile" ]; then
    mkdir -p "$(dirname "$keyfile")"
    ssh-keygen -q -t ed25519 -N '' -C afrotransact-ovh-pipeline -f "$keyfile" && ok "new key pair $keyfile (git-ignored)"
  fi
  gh_put secret OVH_VPS_SSH_PRIVATE_KEY "$(cat "$keyfile")" && gh_put var OVH_SSH_PUBLIC_KEY "$(cat "$keyfile.pub")" && ok "stored in GitHub"
}

# collect_phase P [optional: walk|skip]: every entry of the phase, in catalogue order
collect_phase() {
  local p=$1 n opt_total=0 walk=1 skipped=''
  for n in $(cat_names_phase "$p"); do cat_load "$n"; case "$n" in (OVH_SELLER_HOST | OVH_ADMIN_HOST) continue ;; esac; { [ "$C_NEED" = optional ] && ! is_set "$n"; } && opt_total=$((opt_total + 1)); done
  if [ "$opt_total" -gt 0 ]; then
    blank
    if confirm_yn "$opt_total optional values are not set (email, AI, maps, carriers, webhooks...). Walk through them now? (No = skip them all; you can add them any time by running this phase again)" n; then walk=1; else walk=0; fi
  fi
  for n in $(cat_names_phase "$p"); do
    cat_load "$n"
    case "$n" in
      (OVH_LOCATION) h_location ;;
      (OVH_APPLICATION_KEY) h_ovhcreds ;;
      (OVH_APPLICATION_SECRET | OVH_CONSUMER_KEY | TF_STATE_SECRET_KEY | OVH_SSH_PUBLIC_KEY) ;;
      (OVH_SERVICE_NAME) h_project ;;
      (TF_STATE_ACCESS_KEY) h_statekeys ;;
      (OVH_VPS_SSH_PRIVATE_KEY) h_ssh ;;
      (DB_MODE) ;;
      (OVH_SELLER_HOST) h_webhosts ;;
      (OVH_ADMIN_HOST) ;;
      (*)
        if [ "$C_NEED" = optional ] && [ "$walk" = 0 ] && ! is_set "$n"; then skipped="$skipped $n"; continue; fi
        if [ "$C_NEED" = external ] && [ "$(db_mode_now)" != external ]; then continue; fi
        collect_one "$n" || return 1 ;;
    esac
  done
  show_generated
  if [ -n "$skipped" ]; then blank; warn "skipped optional values:$skipped"; hint "what that means: see the 'Why' of each in tools/deploy/catalogue.conf, or run  make deploy-guide ARGS='--explain'"; fi
  return 0
}

# set_one NAME: collect one value (any entry of the catalogue) with the full help; make deploy-guide ARGS='--set NAME'
set_one() {
  local rc
  cat_load "$1" || { bad "unknown value '$1' (list: make deploy-guide ARGS=--checklist)"; return 1; }
  case "$1" in
    (OVH_LOCATION) h_location ;;
    (OVH_APPLICATION_KEY | OVH_APPLICATION_SECRET | OVH_CONSUMER_KEY) h_ovhcreds ;;
    (OVH_SERVICE_NAME) h_project ;;
    (TF_STATE_ACCESS_KEY | TF_STATE_SECRET_KEY) h_statekeys ;;
    (OVH_VPS_SSH_PRIVATE_KEY | OVH_SSH_PUBLIC_KEY) h_ssh ;;
    (OVH_SELLER_HOST) if [ -n "$(gh_var CLOUDFLARE_ZONE)" ] && [ "$(gh_var OVH_DOMAIN)" != "$(gh_var CLOUDFLARE_ZONE)" ]; then h_webhosts; else collect_one "$1"; fi ;;
    (DB_MODE) bad "DB_MODE is chosen in the database phase: make deploy-guide ARGS='--only database'"; return 1 ;;
    (*) collect_one "$1" ;;
  esac
  rc=$?
  show_generated
  return $rc
}
