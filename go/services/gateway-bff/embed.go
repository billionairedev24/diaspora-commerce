// Package gatewaybff embeds the gateway BFF's environment configs and OpenAPI document.
package gatewaybff

import "embed"

// Files holds config/*.yaml, api/openapi.yaml (an identical copy of contracts/openapi/gateway-bff.yaml) and
// api/openapi-seller.yaml (an identical copy of contracts/openapi/gateway-bff-seller.yaml).
//
//go:embed config/*.yaml api/openapi.yaml api/openapi-seller.yaml
var Files embed.FS
