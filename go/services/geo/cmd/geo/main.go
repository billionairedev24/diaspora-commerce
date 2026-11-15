// Command geo serves address autocomplete, validation, ZIP lookup and delivery-zone queries.
package main

import (
	"github.com/billionairedev24/diaspora-commerce/go/pkg/module"
	"github.com/billionairedev24/diaspora-commerce/go/services/geo"
)

func main() { module.Main(geo.Name, geo.New) }
