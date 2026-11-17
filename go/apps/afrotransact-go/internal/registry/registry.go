// Package registry lists the Go modules afrotransact-go can run, in build order (release-1 architecture, section 7):
// cart, promise, geo, search and indexer, notifications, media, carrier-gateway, authz-sync, and gateway-bff last because it
// is the edge and the biggest consumer of the others.
package registry

import (
	"github.com/billionairedev24/diaspora-commerce/go/apps/afrotransact-go/internal/host"
	authzsync "github.com/billionairedev24/diaspora-commerce/go/services/authz-sync"
	carriergateway "github.com/billionairedev24/diaspora-commerce/go/services/carrier-gateway"
	"github.com/billionairedev24/diaspora-commerce/go/services/cart"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/bffmodule"
	"github.com/billionairedev24/diaspora-commerce/go/services/geo"
	"github.com/billionairedev24/diaspora-commerce/go/services/indexer"
	"github.com/billionairedev24/diaspora-commerce/go/services/media"
	"github.com/billionairedev24/diaspora-commerce/go/services/notifications"
	"github.com/billionairedev24/diaspora-commerce/go/services/promise"
	"github.com/billionairedev24/diaspora-commerce/go/services/search"
)

// Specs returns the catalogue. Defaults follow the launch set: promise, carrier-gateway and authz-sync are off unless
// AT_MODULES_<NAME>_ENABLED=true.
func Specs() []host.Spec {
	return []host.Spec{
		// cart and search document the same paths as gateway-bff's facade; the BFF reaches them in-process, so they are
		// mounted under /modules/<name>/ on the shared listener.
		{Name: cart.Name, Default: true, Mount: host.MountPrefixed, PeerVars: []string{"CART_URL"}, Schema: "cart", New: cart.New},
		{Name: promise.Name, PeerVars: []string{"PROMISE_URL"}, New: promise.New},
		{Name: geo.Name, Default: true, PeerVars: []string{"GEO_URL"}, Schema: "geo,public", New: geo.New},
		{Name: search.Name, Default: true, Mount: host.MountPrefixed, PeerVars: []string{"SEARCH_URL"}, New: search.New},
		{Name: indexer.Name, Default: true, New: indexer.New},
		{Name: notifications.Name, Default: true, PeerVars: []string{"NOTIFICATIONS_URL"}, Schema: "notifications", New: notifications.New},
		{Name: media.Name, Default: true, PeerVars: []string{"MEDIA_URL"}, Schema: "media", New: media.New},
		{Name: carriergateway.Name, Mount: host.MountPrefixed, PeerVars: []string{"CARRIER_URL", "CARRIER_GATEWAY_URL"}, Schema: "carriers", New: carriergateway.New},
		{Name: authzsync.Name, New: authzsync.New},
		{Name: bffmodule.Name, Default: true, Edge: true, New: bffmodule.New},
	}
}
