// Package authzsync embeds the service's environment configs, OpenAPI document and the OpenFGA model it deploys.
package authzsync

import "embed"

// Files holds config/*.yaml, api/openapi.yaml and model/model.json (a copy of contracts/openfga/model.json, kept
// identical by TestEmbeddedModelMatchesContract).
//
//go:embed config/*.yaml api/openapi.yaml model/model.json
var Files embed.FS
