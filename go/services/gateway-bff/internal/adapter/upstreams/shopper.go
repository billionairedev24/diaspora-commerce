package upstreams

import (
	"context"
	"fmt"
	"net/http"
	"net/url"
	"strconv"
	"strings"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/adapter/httpclient"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

func esc(s string) string { return url.PathEscape(s) }

// ---------------------------------------------------------------- payments wallet

type walletImpl struct{ c httpclient.Client }

var _ port.WalletClient = (*walletImpl)(nil)

// NewWallet returns the payments client for the shopper's saved methods (/v1/customers/me/...).
func NewWallet(c httpclient.Client) port.WalletClient { return &walletImpl{c: c} }

func (w *walletImpl) List(ctx context.Context, bearer string) ([]port.WalletMethod, error) {
	res, err := call(ctx, w.c, httpclient.Request{Path: "/v1/customers/me/payment-methods", Bearer: bearer}, nil)
	if err != nil {
		return nil, err
	}
	items, _, err := itemsOf[port.WalletMethod](res.Body)
	return items, err
}

func (w *walletImpl) SetupIntent(ctx context.Context, bearer string, types []string) (port.WalletSetupIntent, error) {
	var out port.WalletSetupIntent
	_, err := call(ctx, w.c, httpclient.Request{Method: http.MethodPost, Path: "/v1/customers/me/setup-intents", Bearer: bearer,
		JSON: map[string]any{"types": types}}, &out)
	return out, err
}

func (w *walletImpl) MakeDefault(ctx context.Context, bearer, id string) (port.WalletMethod, error) {
	var out port.WalletMethod
	_, err := call(ctx, w.c, httpclient.Request{Method: http.MethodPost, Path: "/v1/customers/me/payment-methods/" + esc(id) + "/default", Bearer: bearer}, &out)
	return out, err
}

func (w *walletImpl) Remove(ctx context.Context, bearer, id string) error {
	_, err := call(ctx, w.c, httpclient.Request{Method: http.MethodDelete, Path: "/v1/customers/me/payment-methods/" + esc(id), Bearer: bearer}, nil)
	return err
}

func (w *walletImpl) EphemeralKey(ctx context.Context, bearer string) (port.WalletEphemeralKey, error) {
	var out port.WalletEphemeralKey
	_, err := call(ctx, w.c, httpclient.Request{Method: http.MethodPost, Path: "/v1/customers/me/ephemeral-keys", Bearer: bearer,
		JSON: map[string]any{}}, &out)
	return out, err
}

// ---------------------------------------------------------------- orders (customer)

type customerOrdersImpl struct{ c httpclient.Client }

var _ port.CustomerOrdersClient = (*customerOrdersImpl)(nil)

// NewCustomerOrders returns the orders client for the customer's own orders and the checkout saga.
func NewCustomerOrders(c httpclient.Client) port.CustomerOrdersClient {
	return &customerOrdersImpl{c: c}
}

func (o *customerOrdersImpl) List(ctx context.Context, bearer, cursor string, limit int) ([]port.UpOrder, *string, error) {
	q := url.Values{"customer": {"me"}, "limit": {strconv.Itoa(limit)}}
	if cursor != "" {
		q.Set("cursor", cursor)
	}
	res, err := call(ctx, o.c, httpclient.Request{Path: "/v1/orders", Query: q, Bearer: bearer}, nil)
	if err != nil {
		return nil, nil, err
	}
	items, next, err := itemsOf[port.UpOrder](res.Body)
	if next != nil && *next == "" {
		next = nil
	}
	return items, next, err
}

func (o *customerOrdersImpl) Get(ctx context.Context, bearer, number string) (port.UpOrder, error) {
	var out port.UpOrder
	_, err := call(ctx, o.c, httpclient.Request{Path: "/v1/orders/" + esc(number), Bearer: bearer}, &out)
	return out, err
}

func (o *customerOrdersImpl) Cancel(ctx context.Context, bearer, number, shipmentID, reason string) (port.UpOrder, error) {
	var out port.UpOrder
	_, err := call(ctx, o.c, httpclient.Request{Method: http.MethodPost, Bearer: bearer, JSON: map[string]string{"reason": reason},
		Path: "/v1/orders/" + esc(number) + "/shipments/" + esc(shipmentID) + "/cancel"}, &out)
	return out, err
}

func (o *customerOrdersImpl) Checkout(ctx context.Context, bearer, key string, req port.UpCheckoutRequest) (port.UpCheckout, int, error) {
	var out port.UpCheckout
	res, err := call(ctx, o.c, httpclient.Request{Method: http.MethodPost, Path: "/v1/checkout", Bearer: bearer, JSON: req,
		Header: idem(key), RetryUnsafe: true}, &out)
	if err != nil {
		return out, 0, err
	}
	return out, res.Status, nil
}

func (o *customerOrdersImpl) CheckoutStatus(ctx context.Context, bearer, number string) (port.UpCheckout, error) {
	var out port.UpCheckout
	_, err := call(ctx, o.c, httpclient.Request{Path: "/v1/checkout/" + esc(number), Bearer: bearer}, &out)
	return out, err
}

func (o *customerOrdersImpl) ConfirmPayment(ctx context.Context, bearer, number string) (port.UpCheckout, error) {
	var out port.UpCheckout
	_, err := call(ctx, o.c, httpclient.Request{Method: http.MethodPost, Path: "/v1/checkout/" + esc(number) + "/confirm", Bearer: bearer,
		RetryUnsafe: true}, &out)
	return out, err
}

// ---------------------------------------------------------------- tax-returns

type returnsImpl struct{ c httpclient.Client }

var _ port.ReturnsClient = (*returnsImpl)(nil)

// NewReturns returns the tax-returns client (customer returns: eligibility, RMAs, refund quotes).
func NewReturns(c httpclient.Client) port.ReturnsClient { return &returnsImpl{c: c} }

func (r *returnsImpl) Eligibility(ctx context.Context, bearer, orderID string) (port.UpReturnEligibility, error) {
	var out port.UpReturnEligibility
	_, err := call(ctx, r.c, httpclient.Request{Path: "/v1/returns/eligibility", Query: url.Values{"orderId": {orderID}}, Bearer: bearer}, &out)
	return out, err
}

func (r *returnsImpl) Create(ctx context.Context, bearer, key string, req port.UpCreateReturn) (port.UpRma, error) {
	var out port.UpRma
	_, err := call(ctx, r.c, httpclient.Request{Method: http.MethodPost, Path: "/v1/returns", Bearer: bearer, JSON: req,
		Header: idem(key), RetryUnsafe: true}, &out)
	return out, err
}

func (r *returnsImpl) List(ctx context.Context, bearer, orderID string) ([]port.UpRma, error) {
	q := url.Values{"limit": {"50"}}
	if orderID != "" {
		q.Set("orderId", orderID)
	}
	res, err := call(ctx, r.c, httpclient.Request{Path: "/v1/returns", Query: q, Bearer: bearer}, nil)
	if err != nil {
		return nil, err
	}
	items, _, err := itemsOf[port.UpRma](res.Body)
	return items, err
}

func (r *returnsImpl) ByNumber(ctx context.Context, bearer, number string) (port.UpRma, error) {
	var out port.UpRma
	_, err := call(ctx, r.c, httpclient.Request{Path: "/v1/returns/number/" + esc(number), Bearer: bearer}, &out)
	return out, err
}

func (r *returnsImpl) Get(ctx context.Context, bearer, id string) (port.UpRma, error) {
	var out port.UpRma
	_, err := call(ctx, r.c, httpclient.Request{Path: "/v1/returns/" + esc(id), Bearer: bearer}, &out)
	return out, err
}

func (r *returnsImpl) Cancel(ctx context.Context, bearer, id string) (port.UpRma, error) {
	var out port.UpRma
	_, err := call(ctx, r.c, httpclient.Request{Method: http.MethodPost, Path: "/v1/returns/" + esc(id) + "/cancel", Bearer: bearer}, &out)
	return out, err
}

func (r *returnsImpl) Quote(ctx context.Context, bearer, id string) (domain.RefundQuote, error) {
	var out domain.RefundQuote
	_, err := call(ctx, r.c, httpclient.Request{Path: "/v1/returns/" + esc(id) + "/refund-quote", Bearer: bearer}, &out)
	return out, err
}

// ---------------------------------------------------------------- identity security

type securityImpl struct{ c httpclient.Client }

var _ port.SecurityClient = (*securityImpl)(nil)

// NewSecurity returns the identity client for /v1/me sign-in methods, sessions, devices and notification switches.
func NewSecurity(c httpclient.Client) port.SecurityClient { return &securityImpl{c: c} }

func (s *securityImpl) get(ctx context.Context, bearer, path string, out any) error {
	_, err := call(ctx, s.c, httpclient.Request{Path: path, Bearer: bearer}, out)
	return err
}

func (s *securityImpl) send(ctx context.Context, bearer, method, path string, body, out any) error {
	_, err := call(ctx, s.c, httpclient.Request{Method: method, Path: path, Bearer: bearer, JSON: body}, out)
	return err
}

func (s *securityImpl) MFA(ctx context.Context, bearer string) (port.UpMfa, error) {
	var out port.UpMfa
	return out, s.get(ctx, bearer, "/v1/me/mfa", &out)
}

func (s *securityImpl) Passkeys(ctx context.Context, bearer string) ([]port.UpPasskey, error) {
	var out []port.UpPasskey
	return out, s.get(ctx, bearer, "/v1/me/passkeys", &out)
}

func (s *securityImpl) Sessions(ctx context.Context, bearer string) ([]port.UpSession, error) {
	var out []port.UpSession
	return out, s.get(ctx, bearer, "/v1/me/sessions", &out)
}

func (s *securityImpl) Devices(ctx context.Context, bearer string) ([]port.UpDevice, error) {
	var out []port.UpDevice
	return out, s.get(ctx, bearer, "/v1/me/devices", &out)
}

func (s *securityImpl) Linked(ctx context.Context, bearer string) (port.UpLinkedAccounts, error) {
	var out port.UpLinkedAccounts
	return out, s.get(ctx, bearer, "/v1/me/linked-accounts", &out)
}

func (s *securityImpl) Unlink(ctx context.Context, bearer, provider string) (port.UpLinkedAccounts, error) {
	var out port.UpLinkedAccounts
	return out, s.send(ctx, bearer, http.MethodDelete, "/v1/me/linked-accounts/"+esc(provider), nil, &out)
}

func (s *securityImpl) ChangePassword(ctx context.Context, bearer string, in domain.PasswordChange) error {
	return s.send(ctx, bearer, http.MethodPut, "/v1/me/password", map[string]string{"currentPassword": in.CurrentPassword,
		"newPassword": in.NewPassword, "confirmPassword": in.NewPassword}, nil)
}

func (s *securityImpl) StartTOTP(ctx context.Context, bearer string) (domain.TotpSetup, error) {
	var out domain.TotpSetup
	return out, s.send(ctx, bearer, http.MethodPost, "/v1/me/mfa/totp", nil, &out)
}

func (s *securityImpl) ConfirmTOTP(ctx context.Context, bearer, code string) (domain.RecoveryCodes, error) {
	var out domain.RecoveryCodes
	return out, s.send(ctx, bearer, http.MethodPost, "/v1/me/mfa/totp/confirm", map[string]string{"code": code}, &out)
}

func (s *securityImpl) DisableTOTP(ctx context.Context, bearer string) error {
	return s.send(ctx, bearer, http.MethodDelete, "/v1/me/mfa/totp", nil, nil)
}

func (s *securityImpl) StartSMS(ctx context.Context, bearer string) (string, error) {
	var out struct {
		CodeExpiresAt string `json:"codeExpiresAt"`
	}
	return out.CodeExpiresAt, s.send(ctx, bearer, http.MethodPost, "/v1/me/mfa/sms", nil, &out)
}

func (s *securityImpl) ConfirmSMS(ctx context.Context, bearer, code string) (domain.RecoveryCodes, error) {
	var out domain.RecoveryCodes
	return out, s.send(ctx, bearer, http.MethodPost, "/v1/me/mfa/sms/confirm", map[string]string{"code": code}, &out)
}

func (s *securityImpl) DisableSMS(ctx context.Context, bearer string) error {
	return s.send(ctx, bearer, http.MethodDelete, "/v1/me/mfa/sms", nil, nil)
}

func (s *securityImpl) RecoveryCodes(ctx context.Context, bearer string) (domain.RecoveryCodes, error) {
	var out domain.RecoveryCodes
	return out, s.send(ctx, bearer, http.MethodPost, "/v1/me/mfa/recovery-codes", nil, &out)
}

func (s *securityImpl) RemovePasskey(ctx context.Context, bearer, id string) error {
	return s.send(ctx, bearer, http.MethodDelete, "/v1/me/passkeys/"+esc(id), nil, nil)
}

func (s *securityImpl) RevokeSession(ctx context.Context, bearer, id string) error {
	return s.send(ctx, bearer, http.MethodDelete, "/v1/me/sessions/"+esc(id), nil, nil)
}

func (s *securityImpl) RevokeOtherSessions(ctx context.Context, bearer string) (int, error) {
	var out struct {
		Revoked int `json:"revoked"`
	}
	return out.Revoked, s.send(ctx, bearer, http.MethodPost, "/v1/me/sessions/revoke-others", nil, &out)
}

func (s *securityImpl) RemoveDevice(ctx context.Context, bearer, id string) error {
	return s.send(ctx, bearer, http.MethodDelete, "/v1/me/devices/"+esc(id), nil, nil)
}

func (s *securityImpl) Preferences(ctx context.Context, bearer string) (domain.NotificationPreferences, error) {
	var out domain.NotificationPreferences
	return out, s.get(ctx, bearer, "/v1/me/notification-preferences", &out)
}

func (s *securityImpl) SavePreferences(ctx context.Context, bearer string, p domain.NotificationPreferences) (domain.NotificationPreferences, error) {
	var out domain.NotificationPreferences
	return out, s.send(ctx, bearer, http.MethodPut, "/v1/me/notification-preferences", p, &out)
}

// ---------------------------------------------------------------- bookings (public directory + customer)

type customerBookingsImpl struct{ c httpclient.Client }

var _ port.CustomerBookingsClient = (*customerBookingsImpl)(nil)

// NewCustomerBookings returns the bookings client for the public directory and the customer's bookings.
func NewCustomerBookings(c httpclient.Client) port.CustomerBookingsClient {
	return &customerBookingsImpl{c: c}
}

func slotQuery(q port.SlotQuery) url.Values {
	v := url.Values{}
	set := func(k, s string) {
		if s != "" {
			v.Set(k, s)
		}
	}
	set("from", q.From)
	set("date", q.Date)
	set("memberId", q.MemberID)
	set("mode", q.Mode)
	if q.Days > 0 {
		v.Set("days", strconv.Itoa(q.Days))
	}
	if len(q.AddOnIDs) > 0 {
		v.Set("addOnIds", strings.Join(q.AddOnIDs, ","))
	}
	return v
}

func (b *customerBookingsImpl) Search(ctx context.Context, q, category, zip string) ([]port.UpBkListing, error) {
	v := url.Values{"size": {"40"}}
	for k, s := range map[string]string{"q": q, "category": category, "zip": zip} {
		if s != "" {
			v.Set(k, s)
		}
	}
	var out struct {
		Services []port.UpBkListing `json:"services"`
	}
	_, err := call(ctx, b.c, httpclient.Request{Path: "/v1/public/services/search", Query: v}, &out)
	return out.Services, err
}

func (b *customerBookingsImpl) Service(ctx context.Context, id string) (port.UpBkService, error) {
	var out port.UpBkService
	_, err := call(ctx, b.c, httpclient.Request{Path: "/v1/public/services/" + esc(id)}, &out)
	return out, err
}

func (b *customerBookingsImpl) Provider(ctx context.Context, orgID string) (port.UpBkProvider, error) {
	var out port.UpBkProvider
	_, err := call(ctx, b.c, httpclient.Request{Path: "/v1/public/providers/" + esc(orgID)}, &out)
	return out, err
}

func (b *customerBookingsImpl) Days(ctx context.Context, serviceID string, q port.SlotQuery) ([]domain.AvailableDay, error) {
	var out []domain.AvailableDay
	_, err := call(ctx, b.c, httpclient.Request{Path: "/v1/public/services/" + esc(serviceID) + "/days", Query: slotQuery(q)}, &out)
	return out, err
}

func (b *customerBookingsImpl) Slots(ctx context.Context, serviceID string, q port.SlotQuery) ([]domain.AvailableSlot, error) {
	var out []domain.AvailableSlot
	_, err := call(ctx, b.c, httpclient.Request{Path: "/v1/public/services/" + esc(serviceID) + "/slots", Query: slotQuery(q)}, &out)
	return out, err
}

func (b *customerBookingsImpl) Summary(ctx context.Context, serviceID string, q port.SlotQuery) (domain.BookingQuote, error) {
	var out domain.BookingQuote
	_, err := call(ctx, b.c, httpclient.Request{Path: "/v1/public/services/" + esc(serviceID) + "/summary", Query: slotQuery(q)}, &out)
	return out, err
}

func (b *customerBookingsImpl) Create(ctx context.Context, bearer, key string, req port.UpBkCreate) (port.UpBkBooking, error) {
	var out struct {
		Booking port.UpBkBooking `json:"booking"`
	}
	_, err := call(ctx, b.c, httpclient.Request{Method: http.MethodPost, Path: "/v1/bookings", Bearer: bearer, JSON: req,
		Header: idem(key), RetryUnsafe: true}, &out)
	return out.Booking, err
}

func (b *customerBookingsImpl) Mine(ctx context.Context, bearer string) ([]port.UpBkBooking, error) {
	res, err := call(ctx, b.c, httpclient.Request{Path: "/v1/bookings/mine", Bearer: bearer}, nil)
	if err != nil {
		return nil, err
	}
	items, _, err := itemsOf[port.UpBkBooking](res.Body)
	return items, err
}

func (b *customerBookingsImpl) Get(ctx context.Context, bearer, id string) (port.UpBkBooking, error) {
	var out port.UpBkBooking
	_, err := call(ctx, b.c, httpclient.Request{Path: "/v1/bookings/" + esc(id), Bearer: bearer}, &out)
	return out, err
}

func (b *customerBookingsImpl) Quote(ctx context.Context, bearer, id string) (port.UpBkQuote, error) {
	var out port.UpBkQuote
	_, err := call(ctx, b.c, httpclient.Request{Path: "/v1/bookings/" + esc(id) + "/cancellation-quote", Bearer: bearer}, &out)
	return out, err
}

func (b *customerBookingsImpl) Cancel(ctx context.Context, bearer, key, id, reason string) (port.UpBkBooking, port.UpBkQuote, error) {
	var out struct {
		Booking port.UpBkBooking `json:"booking"`
		Applied port.UpBkQuote   `json:"applied"`
	}
	_, err := call(ctx, b.c, httpclient.Request{Method: http.MethodPost, Path: "/v1/bookings/" + esc(id) + "/cancel", Bearer: bearer,
		JSON: map[string]string{"reason": reason}, Header: idem(key), RetryUnsafe: true}, &out)
	return out.Booking, out.Applied, err
}

func (b *customerBookingsImpl) Reschedule(ctx context.Context, bearer, key, id, startAt string) (port.UpBkBooking, error) {
	var out port.UpBkBooking
	_, err := call(ctx, b.c, httpclient.Request{Method: http.MethodPost, Path: "/v1/bookings/" + esc(id) + "/reschedule", Bearer: bearer,
		JSON: map[string]string{"startAt": startAt}, Header: idem(key), RetryUnsafe: true}, &out)
	return out, err
}

func (b *customerBookingsImpl) NewTime(ctx context.Context, bearer, key, id string, accept bool) (port.UpBkBooking, error) {
	action := "decline"
	if accept {
		action = "accept"
	}
	var out port.UpBkBooking
	_, err := call(ctx, b.c, httpclient.Request{Method: http.MethodPost, Path: "/v1/bookings/" + esc(id) + "/new-time/" + action, Bearer: bearer,
		Header: idem(key), RetryUnsafe: true}, &out)
	return out, err
}

func (b *customerBookingsImpl) Review(ctx context.Context, bearer, id string, rating int, text string) (domain.BookingReview, error) {
	var out domain.BookingReview
	_, err := call(ctx, b.c, httpclient.Request{Method: http.MethodPost, Path: "/v1/bookings/" + esc(id) + "/review", Bearer: bearer,
		JSON: map[string]any{"rating": rating, "text": text}}, &out)
	return out, err
}

func (b *customerBookingsImpl) SyncPayment(ctx context.Context, bearer, id string) (port.UpBkBooking, error) {
	var out port.UpBkBooking
	_, err := call(ctx, b.c, httpclient.Request{Method: http.MethodPost, Path: "/v1/bookings/" + esc(id) + "/payment/sync", Bearer: bearer}, &out)
	return out, err
}

// ---------------------------------------------------------------- storefront published pages

type publishedPagesImpl struct{ c httpclient.Client }

var _ port.PublishedPagesClient = (*publishedPagesImpl)(nil)

// NewPublishedPages returns the storefront client for published pages (GET /v1/storefronts/{slug}).
func NewPublishedPages(c httpclient.Client) port.PublishedPagesClient {
	return &publishedPagesImpl{c: c}
}

func (p *publishedPagesImpl) Published(ctx context.Context, slug string) (port.UpPublishedPage, error) {
	var out port.UpPublishedPage
	_, err := call(ctx, p.c, httpclient.Request{Path: "/v1/storefronts/" + esc(slug)}, &out)
	return out, err
}

// ---------------------------------------------------------------- delivery facts (promise, carrier-gateway, geo)

type deliveryFactsImpl struct {
	promise, carrier, geo httpclient.Client
	tokens                port.ServiceTokens
}

var _ port.DeliveryFactsClient = (*deliveryFactsImpl)(nil)

// NewDeliveryFacts returns the checkout/tracking client for promise dates, pickup locations, delivery zones and
// carrier scans (carrier tracking with a gateway-bff service token, scope shipping:rate).
func NewDeliveryFacts(promise, carrier, geo httpclient.Client, tokens port.ServiceTokens) port.DeliveryFactsClient {
	return &deliveryFactsImpl{promise: promise, carrier: carrier, geo: geo, tokens: tokens}
}

func (d *deliveryFactsImpl) Promises(ctx context.Context, zip string, offers []port.PromiseOffer) ([]port.PromiseQuote, error) {
	var out struct {
		Promises []port.PromiseQuote `json:"promises"`
	}
	_, err := call(ctx, d.promise, httpclient.Request{Method: http.MethodPost, Path: "/v1/promises", RetryUnsafe: true,
		JSON: map[string]any{"destinationZip": zip, "offers": offers}}, &out)
	return out.Promises, err
}

func (d *deliveryFactsImpl) PickupLocations(ctx context.Context, ownerOrg, postalPrefix string) ([]port.PickupLocation, error) {
	q := url.Values{}
	if ownerOrg != "" {
		q.Set("ownerOrg", ownerOrg)
	}
	if postalPrefix != "" {
		q.Set("postalPrefix", postalPrefix)
	}
	var out struct {
		Items []port.PickupLocation `json:"items"`
	}
	_, err := call(ctx, d.carrier, httpclient.Request{Path: "/v1/pickup-locations", Query: q}, &out)
	return out.Items, err
}

func (d *deliveryFactsImpl) ZonesCovering(ctx context.Context, lat, lon float64, zoneType string) ([]port.DeliveryZone, error) {
	q := url.Values{"lat": {strconv.FormatFloat(lat, 'f', 6, 64)}, "lon": {strconv.FormatFloat(lon, 'f', 6, 64)}, "type": {zoneType}}
	var out struct {
		Zones []port.DeliveryZone `json:"zones"`
	}
	_, err := call(ctx, d.geo, httpclient.Request{Path: "/v1/zones/covering", Query: q}, &out)
	return out.Zones, err
}

func (d *deliveryFactsImpl) Tracking(ctx context.Context, _, carrier, number string) ([]port.CarrierScan, error) {
	token, err := d.tokens.Token(ctx, "shipping:rate")
	if err != nil {
		return nil, fmt.Errorf("carrier tracking token: %w", err)
	}
	var out struct {
		Events []port.CarrierScan `json:"events"`
	}
	_, err = call(ctx, d.carrier, httpclient.Request{Path: "/v1/tracking/" + esc(strings.ToLower(carrier)) + "/" + esc(number), Bearer: token}, &out)
	return out.Events, err
}
