#!/usr/bin/env bash
# Apply the ILM policies in modules/shared/elastic/ilm/ to an Elastic Cloud deployment and create scoped
# API keys for the search (read) and indexer (write) services. Run after `tofu apply`:
#   ES_URL=$(tofu output -raw elasticsearch_url) ES_PASSWORD=$(tofu output -raw elastic_password) \
#     scripts/elastic-ilm.sh
# Paste the printed API keys into the search / indexer secrets (ES_API_KEY) in the cloud secret store.
set -euo pipefail
: "${ES_URL:?set ES_URL}" "${ES_PASSWORD:?set ES_PASSWORD}"
ES_USER="${ES_USER:-elastic}"
dir="$(cd "$(dirname "$0")/../modules/shared/elastic/ilm" && pwd)"

for f in "$dir"/*.json; do
  name="afrotransact-$(basename "$f" .json)"
  echo "ILM policy $name"
  curl -fsS -u "$ES_USER:$ES_PASSWORD" -X PUT "$ES_URL/_ilm/policy/$name" \
    -H 'Content-Type: application/json' --data-binary "@$f" >/dev/null
done

key() { # name index-privileges
  curl -fsS -u "$ES_USER:$ES_PASSWORD" -X POST "$ES_URL/_security/api_key" -H 'Content-Type: application/json' \
    -d "{\"name\":\"$1\",\"role_descriptors\":{\"$1\":{\"cluster\":[\"manage_search_synonyms\",\"monitor\"],\"indices\":[
          {\"names\":[\"products*\",\"services*\",\"search-*\"],\"privileges\":$2},
          {\"names\":[\"search-analytics-*\"],\"privileges\":[\"create_index\",\"create_doc\",\"write\",\"manage\",\"read\"]}]}}}" \
    | python3 -c 'import json,sys; print(json.load(sys.stdin)["encoded"])'
}
# Both keys may manage product indices (aliases, synonyms reloads) and write search analytics.
echo "search  ES_API_KEY=$(key search '["read","view_index_metadata","manage"]')"
echo "indexer ES_API_KEY=$(key indexer '["create_index","index","write","manage","read"]')"
