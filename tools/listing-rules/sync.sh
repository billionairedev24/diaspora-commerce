#!/usr/bin/env bash
# Copies the listing rules (single source: tools/listing-rules/rules.json) into the catalog classpath.
# The copy is build input only; catalog's RulesDriftTest fails when it differs from the source.
set -euo pipefail
cd "$(dirname "$0")/../.."
cp tools/listing-rules/rules.json java/services/catalog/src/main/resources/listing-rules/rules.json
echo "synced listing rules into catalog"
