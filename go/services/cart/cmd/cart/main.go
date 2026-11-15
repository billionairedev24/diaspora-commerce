// Command cart serves shopping carts: guest carts in Redis/Valkey, customer carts in PostgreSQL.
package main

import (
	"github.com/billionairedev24/diaspora-commerce/go/pkg/module"
	"github.com/billionairedev24/diaspora-commerce/go/services/cart"
)

func main() { module.Main(cart.Name, cart.New) }
