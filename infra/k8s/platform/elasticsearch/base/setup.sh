#!/bin/sh
# Index templates + ILM for the ECK cluster. Files come from the search-es-setup ConfigMap.
set -eu
put() {
  echo "PUT $1"
  curl -fsS --cacert /ca/ca.crt -u "elastic:${ES_PASSWORD}" -X PUT "${ES_URL}$1" \
    -H 'Content-Type: application/json' --data-binary "@/setup/$2"
  echo
}
put /_ilm/policy/afrotransact-logs ilm-logs.json
put /_index_template/afrotransact-logs template-logs.json
put /_index_template/afrotransact-products template-products.json
