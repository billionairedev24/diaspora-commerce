// Package notifications embeds the service's environment configs and OpenAPI document.
package notifications

import "embed"

// Files holds config/*.yaml and api/openapi.yaml.
//
//go:embed config/*.yaml api/openapi.yaml
var Files embed.FS
