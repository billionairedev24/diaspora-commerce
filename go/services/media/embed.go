// Package media embeds the service's environment configs, migrations and OpenAPI document.
package media

import "embed"

// Files holds config/*.yaml, migrations/*.sql and api/openapi.yaml.
//
//go:embed config/*.yaml migrations/*.sql api/openapi.yaml
var Files embed.FS
