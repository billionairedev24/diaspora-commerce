package testkit

import (
	"log/slog"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/adapter/httpapi"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/adapter/httpclient"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/adapter/upstreams"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/app"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

// TestPublishableKey is the Stripe publishable key the test BFF hands out.
const TestPublishableKey = "pk_test_bff"

// shopperServices wires the storefront account/checkout/services/Lola use cases against the shopper fakes.
func shopperServices(u *Upstreams, client func(name, url string) httpclient.Client, identity port.IdentityClient, s httpapi.Services,
	clock *Clock, log *slog.Logger) httpapi.ShopperServices {
	base := u.URL() + ShopperPrefix
	tokens := upstreams.NewServiceTokens(client("identity", u.URL()), upstreams.IdentityOptions{ClientID: u.ClientID, ClientSecret: u.Secret, Now: clock.Now})
	d := app.ShopperDeps{
		Wallet: upstreams.NewWallet(client("payments", base)), Orders: upstreams.NewCustomerOrders(client("orders", base)),
		Returns: upstreams.NewReturns(client("tax-returns", base)), Payments: upstreams.NewOrderPayments(client("payments", base)), Security: upstreams.NewSecurity(client("identity", base)),
		Bookings: upstreams.NewCustomerBookings(client("bookings", base)), Pages: upstreams.NewPublishedPages(client("storefront", base)),
		Lola:     upstreams.NewLola(client("ai-gateway", base), upstreams.LolaOptions{BaseURL: base}),
		Delivery: upstreams.NewDeliveryFacts(client("promise", base), client("carrier-gateway", base), client("geo", base), tokens),
		Identity: identity, Checkout: s.Checkout, Carts: s.Cart, Location: s.Location,
		PublishableKey: TestPublishableKey, IdentityPublicURL: u.URL(), Clock: clock.Now, Log: log,
	}
	return httpapi.ShopperServices{Wallet: app.NewWalletService(d), Orders: app.NewCustomerOrdersService(d),
		Checkout: app.NewCheckoutPaymentService(d), Returns: app.NewReturnsService(d), Security: app.NewSecurityService(d),
		Settings: app.NewAccountSettingsService(app.AccountSettingsDeps{Identity: upstreams.NewAccountSettings(client("identity", base)),
			Alerts: upstreams.NewAlertPreferences(client("notifications", base))}),
		Services: app.NewServicesService(d), Lola: app.NewLolaService(d),
		SearchClicks: app.NewSearchClickService(upstreams.NewSearchClicks(client("search", u.URL())), "us")}
}
