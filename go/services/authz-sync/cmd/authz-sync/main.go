// Command authz-sync keeps OpenFGA relationship tuples in sync with identity memberships and seller organisations.
package main

import (
	"github.com/billionairedev24/diaspora-commerce/go/pkg/module"
	authzsync "github.com/billionairedev24/diaspora-commerce/go/services/authz-sync"
)

func main() { module.Main(authzsync.Name, authzsync.New) }
