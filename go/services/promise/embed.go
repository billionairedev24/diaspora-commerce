// Package promise embeds the service's environment configs, ZIP/transit data and OpenAPI document.
package promise

import "embed"

// Files holds config/*.yaml, data/*.csv and api/openapi.yaml.
//
//go:embed config/*.yaml data/*.csv api/openapi.yaml tenant_isolation_matrix.txt
var Files embed.FS
