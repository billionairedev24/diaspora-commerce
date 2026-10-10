// Package gatewaybff embeds the gateway BFF's environment configs and OpenAPI document.
package gatewaybff

import "embed"

// Files holds config/*.yaml and api/openapi.yaml (an identical copy of contracts/openapi/gateway-bff.yaml).
//
//go:embed config/*.yaml api/openapi.yaml
var Files embed.FS
