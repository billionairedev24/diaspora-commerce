// Command promise computes delivery promises ("Tomorrow, Oct 10") per destination ZIP from FC cutoffs, carrier
// transit, seller handling time, AfroTransact Delivery zones and pickup ready times, on the US business calendar.
package main

import (
	"github.com/billionairedev24/diaspora-commerce/go/pkg/module"
	"github.com/billionairedev24/diaspora-commerce/go/services/promise"
)

func main() { module.Main(promise.Name, promise.New) }
