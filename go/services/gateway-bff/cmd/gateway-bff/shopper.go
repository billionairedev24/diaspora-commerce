package main

import (
	"log/slog"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/adapter/httpapi"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/adapter/httpclient"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/adapter/upstreams"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/app"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/config"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

// shopperClients are the upstream clients the storefront account/checkout/services/Lola routes share with the rest
// of the BFF (one breaker per upstream); the remaining ones are created here.
type shopperClients struct {
	payments, orders, identity httpclient.Client
	identityAPI                port.IdentityClient
	serviceTokens              port.ServiceTokens
}

// shopperServices wires the storefront's account, checkout payment, services & bookings and Lola use cases.
func shopperServices(cfg config.Config, client func(string, config.Upstream) httpclient.Client, sc shopperClients,
	s httpapi.Services, log *slog.Logger) httpapi.ShopperServices {
	up := cfg.Upstreams
	ai := client("ai-gateway", up.AIGateway)
	d := app.ShopperDeps{
		Wallet: upstreams.NewWallet(sc.payments), Orders: upstreams.NewCustomerOrders(sc.orders),
		Returns: upstreams.NewReturns(client("tax-returns", up.TaxReturns)), Payments: upstreams.NewOrderPayments(sc.payments),
		Security: upstreams.NewSecurity(sc.identity), Bookings: upstreams.NewCustomerBookings(client("bookings", up.Bookings)),
		Pages: upstreams.NewPublishedPages(client("storefront", up.Storefront)),
		Lola:  upstreams.NewLola(ai, upstreams.LolaOptions{BaseURL: up.AIGateway.URL}),
		Delivery: upstreams.NewDeliveryFacts(client("promise", up.Promise), client("carrier-gateway", up.Carrier), client("geo", up.Geo),
			sc.serviceTokens),
		Identity: sc.identityAPI, Checkout: s.Checkout, Carts: s.Cart, Location: s.Location,
		PublishableKey: cfg.Stripe.PublishableKey, IdentityPublicURL: cfg.OIDC.PublicURL, Log: log,
	}
	return httpapi.ShopperServices{Wallet: app.NewWalletService(d), Orders: app.NewCustomerOrdersService(d),
		Checkout: app.NewCheckoutPaymentService(d), Returns: app.NewReturnsService(d), Security: app.NewSecurityService(d),
		Services: app.NewServicesService(d), Lola: app.NewLolaService(d),
		SearchClicks: app.NewSearchClickService(upstreams.NewSearchClicks(client("search", up.Search)), cfg.Market)}
}
