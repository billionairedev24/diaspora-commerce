// Package notifications embeds the service's environment configs and OpenAPI document.
package notifications

import "embed"

// Files holds config/*.yaml and api/openapi.yaml.
//
//go:embed config/*.yaml api/openapi.yaml tenant_isolation_matrix.txt
var Files embed.FS
