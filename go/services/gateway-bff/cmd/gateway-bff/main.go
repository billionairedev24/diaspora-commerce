// Command gateway-bff serves the screen-shaped API for the storefront and customer app: it composes catalog,
// inventory, search, cart, geo, identity, content, payments, promise and orders, and mediates OIDC tokens for
// browsers (HttpOnly session cookie; tokens stay in Valkey). The service itself is bffmodule.New (it cannot live in the
// root package: internal/app's in-package test imports the root for Files, which would be an import cycle).
package main

import (
	"github.com/billionairedev24/diaspora-commerce/go/pkg/module"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/bffmodule"
)

func main() { module.Main(bffmodule.Name, bffmodule.New) }
