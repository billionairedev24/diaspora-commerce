package app

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"log/slog"
	"net/http"
	"regexp"
	"strings"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

// ShopperDeps are the upstreams of the storefront's account, checkout, services and Lola use cases.
type ShopperDeps struct {
	Wallet  port.WalletClient
	Orders  port.CustomerOrdersClient
	Returns port.ReturnsClient
	// Payments reads an order's charged and refunded amounts (optional).
	Payments port.OrderPaymentsClient
	Security port.SecurityClient
	Bookings port.CustomerBookingsClient
	Pages    port.PublishedPagesClient
	Lola     port.LolaClient
	Delivery port.DeliveryFactsClient
	// ProofLinks signs the links to a delivery's proof photo and signature (media-service; optional: no pictures without it).
	ProofLinks port.ProofLinks
	// LocalDelivery and Offers price AfroTransact Delivery from the seller's store on the checkout page (S255; optional).
	LocalDelivery port.LocalDeliveryClient
	Offers        port.StorefrontOffersClient
	// Coupons validates checkout coupon codes (offers-service; optional).
	Coupons  port.CheckoutCouponsClient
	Identity port.IdentityClient
	Checkout CheckoutService
	Carts    CartService
	Location LocationService
	// PublishableKey is the Stripe publishable key handed to Stripe.js / PaymentSheet.
	PublishableKey string
	// IdentityPublicURL is where the browser reaches identity (hosted passkey registration page).
	IdentityPublicURL string
	Clock             Clock
	Log               *slog.Logger
}

func (d ShopperDeps) log() *slog.Logger {
	if d.Log == nil {
		return slog.Default()
	}
	return d.Log
}

// derivedKey makes an Idempotency-Key for a customer action when the client sent none: retries of the same action
// by the same customer within the same minute replay instead of repeating it.
func derivedKey(c *domain.Caller, now time.Time, parts ...string) string {
	sum := sha256.Sum256([]byte(c.Subject + "|" + strings.Join(parts, "|") + "|" + now.UTC().Format("200601021504")))
	return "bff-" + hex.EncodeToString(sum[:16])
}

func optStr(s string) *string { return strPtr(strings.TrimSpace(s)) }

// ---------------------------------------------------------------- wallet (payments)

// WalletService manages saved Stripe payment methods (S148) and the PaymentSheet keys of the customer app (S238).
type WalletService interface {
	List(ctx context.Context, c *domain.Caller) (domain.Page[domain.PaymentMethod], error)
	SetupIntent(ctx context.Context, c *domain.Caller, types []string) (domain.SetupIntent, error)
	MakeDefault(ctx context.Context, c *domain.Caller, id string) (domain.PaymentMethod, error)
	Remove(ctx context.Context, c *domain.Caller, id string) error
	PaymentSheet(ctx context.Context, c *domain.Caller) (domain.PaymentSheetKeys, error)
}

type walletServiceImpl struct{ d ShopperDeps }

var _ WalletService = (*walletServiceImpl)(nil)

// NewWalletService returns the WalletService.
func NewWalletService(d ShopperDeps) WalletService { return &walletServiceImpl{d: d} }

var walletTypes = map[string]bool{"card": true, "us_bank_account": true, "cashapp": true, "link": true}

func brandName(b string) string {
	switch strings.ToLower(b) {
	case "visa":
		return "Visa"
	case "mastercard":
		return "Mastercard"
	case "amex", "american_express":
		return "American Express"
	case "discover":
		return "Discover"
	case "":
		return "Card"
	}
	return strings.ToUpper(b[:1]) + b[1:]
}

// toPaymentMethod maps payments' display data onto the contract (label and MM/YY expiry are composed here).
func toPaymentMethod(m port.WalletMethod) domain.PaymentMethod {
	pm := domain.PaymentMethod{ID: m.ID, Type: m.Type, Brand: strPtr(m.Brand), Last4: strPtr(m.Last4), IsDefault: m.IsDefault}
	switch m.Type {
	case "us_bank_account":
		pm.Label = strings.TrimSpace(firstNonEmpty(m.BankName, "Bank account") + " " + m.AccountType + " ending in " + m.Last4)
		if m.Verified != nil && !*m.Verified {
			pm.Label += " (verification pending)"
		}
	case "cashapp":
		pm.Label = "Cash App Pay"
	case "link":
		pm.Label = "Link"
	case "amazon_pay":
		pm.Label = "Amazon Pay"
	default:
		pm.Type = "card"
		pm.Label = brandName(m.Brand) + " ending in " + m.Last4
	}
	if m.ExpMonth > 0 && m.ExpYear > 0 {
		pm.Expires = ptr(fmt.Sprintf("%02d/%02d", m.ExpMonth, m.ExpYear%100))
	}
	return pm
}

func walletErr(err error) error {
	if p, ok := port.AsUpstreamProblem(err); ok {
		switch {
		case p.Code == "payments.default-method-required":
			return domain.NewProblem(http.StatusConflict, domain.CodeConflict, "Choose another default first",
				firstNonEmpty(p.Detail, "Your default payment method can't be removed. Make another one the default first.")).WithCause(err)
		case p.Status == http.StatusNotFound:
			return domain.NewProblem(http.StatusNotFound, domain.CodePaymentNotFound, "Payment method not found", "That payment method no longer exists.").WithCause(err)
		}
	}
	return mapErr("payments", err)
}

func (s *walletServiceImpl) List(ctx context.Context, c *domain.Caller) (domain.Page[domain.PaymentMethod], error) {
	if err := requireCustomer(c); err != nil {
		return domain.Page[domain.PaymentMethod]{}, err
	}
	wallet, err := s.d.Wallet.List(ctx, c.BearerToken)
	if err != nil {
		return domain.Page[domain.PaymentMethod]{}, walletErr(err)
	}
	out := make([]domain.PaymentMethod, 0, len(wallet.Items))
	for _, m := range wallet.Items {
		out = append(out, toPaymentMethod(m))
	}
	return domain.Page[domain.PaymentMethod]{Items: out}, nil
}

func (s *walletServiceImpl) SetupIntent(ctx context.Context, c *domain.Caller, types []string) (domain.SetupIntent, error) {
	if err := requireCustomer(c); err != nil {
		return domain.SetupIntent{}, err
	}
	if len(types) == 0 {
		types = []string{"card", "us_bank_account"}
	}
	for _, t := range types {
		if !walletTypes[t] {
			return domain.SetupIntent{}, domain.ValidationProblem(map[string]string{"types": "Unsupported payment method type " + t})
		}
	}
	si, err := s.d.Wallet.SetupIntent(ctx, c.BearerToken, types)
	if err != nil {
		return domain.SetupIntent{}, walletErr(err)
	}
	return domain.SetupIntent{ClientSecret: si.ClientSecret, PublishableKey: s.d.PublishableKey}, nil
}

func (s *walletServiceImpl) MakeDefault(ctx context.Context, c *domain.Caller, id string) (domain.PaymentMethod, error) {
	if err := requireCustomer(c); err != nil {
		return domain.PaymentMethod{}, err
	}
	m, err := s.d.Wallet.MakeDefault(ctx, c.BearerToken, id)
	if err != nil {
		return domain.PaymentMethod{}, walletErr(err)
	}
	m.IsDefault = true
	return toPaymentMethod(m), nil
}

func (s *walletServiceImpl) Remove(ctx context.Context, c *domain.Caller, id string) error {
	if err := requireCustomer(c); err != nil {
		return err
	}
	if err := s.d.Wallet.Remove(ctx, c.BearerToken, id); err != nil {
		return walletErr(err)
	}
	return nil
}

func (s *walletServiceImpl) PaymentSheet(ctx context.Context, c *domain.Caller) (domain.PaymentSheetKeys, error) {
	if err := requireCustomer(c); err != nil {
		return domain.PaymentSheetKeys{}, err
	}
	k, err := s.d.Wallet.EphemeralKey(ctx, c.BearerToken)
	if err != nil {
		return domain.PaymentSheetKeys{}, walletErr(err)
	}
	return domain.PaymentSheetKeys{CustomerID: k.CustomerID, EphemeralKey: k.Secret, PublishableKey: s.d.PublishableKey}, nil
}

// ---------------------------------------------------------------- orders

// CustomerOrdersService is Your orders: list, detail with tracking, the customer app's tracking view and cancel.
type CustomerOrdersService interface {
	List(ctx context.Context, c *domain.Caller, cursor string, limit int) (domain.Page[domain.OrderSummary], error)
	Detail(ctx context.Context, c *domain.Caller, number string) (domain.OrderDetail, error)
	Tracking(ctx context.Context, c *domain.Caller, number string) (domain.OrderTracking, error)
	Cancel(ctx context.Context, c *domain.Caller, number, shipmentID, reason string) (domain.OrderSummary, error)
}

type customerOrdersServiceImpl struct{ d ShopperDeps }

var _ CustomerOrdersService = (*customerOrdersServiceImpl)(nil)

// NewCustomerOrdersService returns the CustomerOrdersService.
func NewCustomerOrdersService(d ShopperDeps) CustomerOrdersService {
	return &customerOrdersServiceImpl{d: d}
}

var statusLabels = map[string]string{
	"pending_payment": "Waiting for payment", "placed": "Order placed", "confirmed": "Confirmed by the seller", "packed": "Packed",
	"ready": "Ready", "picked_up": "Picked up by the carrier", "in_transit": "In transit", "out_for_delivery": "Out for delivery",
	"delivered": "Delivered", "delivery_failed": "Delivery attempt failed", "returned": "Returned to sender", "cancelled": "Cancelled",
}

func statusLabel(s string) string {
	if l, ok := statusLabels[strings.ToLower(s)]; ok {
		return l
	}
	return strings.ReplaceAll(s, "_", " ")
}

func methodKey(deliveryMethod, serviceLevel string) string {
	switch strings.ToUpper(deliveryMethod) {
	case "AFROTRANSACT_DELIVERY":
		return "afrotransact_delivery"
	case "PICKUP":
		return "pickup"
	}
	if lv := strings.ToLower(serviceLevel); lv == "express" || lv == "overnight" || lv == "two_day" {
		return "express"
	}
	return "carrier"
}

func orderAddress(a *port.UpShipTo) *domain.OrderAddress {
	if a == nil {
		return nil
	}
	return &domain.OrderAddress{Name: a.FullName, Line1: a.Line1, Line2: a.Line2, City: a.City, State: a.State, ZIP: a.PostalCode}
}

func toOrderSummary(o port.UpOrder) domain.OrderSummary {
	out := domain.OrderSummary{OrderNumber: o.OrderNumber, PlacedAt: firstNonEmpty(o.PlacedAt, o.CreatedAt), TotalMinor: o.TotalMinor,
		Currency: firstNonEmpty(o.Currency, domain.Currency), ShipTo: o.ShipTo, Status: strings.ToLower(o.Status), Shipments: []domain.OrderShipment{}}
	if out.ShipTo == "" && o.ShippingAddress != nil {
		out.ShipTo = strings.TrimSpace(o.ShippingAddress.Line1 + ", " + o.ShippingAddress.City)
	}
	for _, s := range o.Shipments {
		out.Shipments = append(out.Shipments, domain.OrderShipment{ShipmentID: firstNonEmpty(s.ShipmentID, s.ID), Label: s.Label,
			Status: statusLabel(s.Status), Items: s.Items, Cancellable: s.Cancellable, Carrier: optStr(s.Carrier),
			TrackingNumber: optStr(s.TrackingNumber), PromisedBy: optStr(s.PromisedBy), DeliveredAt: optStr(s.DeliveredAt),
			DeliveryCode: optStr(s.HandoverCode), Returnable: s.Returnable, ReturnWindowEndsAt: optStr(s.ReturnWindowEndsAt),
			ProblemReportOnly: s.ProblemReportOnly})
	}
	return out
}

func ordersErr(err error) error {
	if p, ok := port.AsUpstreamProblem(err); ok {
		switch {
		case p.Status == http.StatusNotFound || p.Status == http.StatusForbidden:
			return domain.NewProblem(http.StatusNotFound, domain.CodeOrderNotFound, "Order not found", "We couldn't find that order.").WithCause(err)
		case p.Code == "orders.cancel-window-closed" || p.Code == "orders.invalid-transition":
			return domain.NewProblem(http.StatusConflict, domain.CodeConflict, "Can't cancel now",
				firstNonEmpty(p.Detail, "This shipment is already packed or on its way, so it can't be cancelled.")).WithCause(err)
		}
	}
	return mapErr("orders", err)
}

func (s *customerOrdersServiceImpl) List(ctx context.Context, c *domain.Caller, cursor string, limit int) (domain.Page[domain.OrderSummary], error) {
	if err := requireCustomer(c); err != nil {
		return domain.Page[domain.OrderSummary]{}, err
	}
	list, next, err := s.d.Orders.List(ctx, c.BearerToken, cursor, limit)
	if err != nil {
		return domain.Page[domain.OrderSummary]{}, ordersErr(err)
	}
	out := domain.Page[domain.OrderSummary]{Items: make([]domain.OrderSummary, 0, len(list)), NextCursor: next}
	for _, o := range list {
		out.Items = append(out.Items, toOrderSummary(o))
	}
	return out, nil
}

func timeline(entries []port.UpTimelineEntry) []domain.TimelineEvent {
	out := make([]domain.TimelineEvent, 0, len(entries))
	for _, e := range entries {
		out = append(out, domain.TimelineEvent{Status: strings.ToLower(e.To), Label: statusLabel(e.To), At: e.At})
	}
	return out
}

func (s *customerOrdersServiceImpl) Detail(ctx context.Context, c *domain.Caller, number string) (domain.OrderDetail, error) {
	if err := requireCustomer(c); err != nil {
		return domain.OrderDetail{}, err
	}
	o, err := s.d.Orders.Get(ctx, c.BearerToken, number)
	if err != nil {
		return domain.OrderDetail{}, ordersErr(err)
	}
	sum := toOrderSummary(o)
	out := domain.OrderDetail{OrderNumber: o.OrderNumber, PlacedAt: sum.PlacedAt, Status: sum.Status, Currency: sum.Currency,
		ItemsMinor: o.ItemsMinor, ShippingMinor: o.ShippingMinor, TaxMinor: o.TaxMinor, DiscountMinor: o.DiscountMinor,
		TotalMinor: o.TotalMinor, ShipTo: sum.ShipTo, ShippingAddress: orderAddress(o.ShippingAddress), Shipments: []domain.ShipmentDetail{}}
	for _, sh := range o.Shipments {
		d := domain.ShipmentDetail{ShipmentID: firstNonEmpty(sh.ShipmentID, sh.ID), Label: sh.Label, Status: strings.ToLower(sh.Status),
			StatusLabel: statusLabel(sh.Status), Items: sh.Items, SellerName: sh.SellerName, DeliveryMethod: methodKey(sh.DeliveryMethod, sh.ServiceLevel),
			Cancellable: sh.Cancellable, CancellableUntil: optStr(sh.CancellableUntil), Carrier: optStr(sh.Carrier), TrackingNumber: optStr(sh.TrackingNumber), PromisedFrom: optStr(sh.PromisedFrom),
			PromisedBy: optStr(sh.PromisedBy), DeliveredAt: optStr(sh.DeliveredAt), DeliveryCode: optStr(sh.HandoverCode),
			Lines: []domain.OrderLineView{}, Timeline: timeline(sh.Timeline), Returnable: sh.Returnable,
			ReturnWindowEndsAt: optStr(sh.ReturnWindowEndsAt), ProblemReportOnly: sh.ProblemReportOnly}
		if ld := sh.LocalDelivery; ld != nil {
			d.LocalDelivery = &domain.OrderLocalDelivery{RoadMiles: ld.RoadMiles, DurationMinutes: ld.DurationMinutes, ChargeMinor: ld.CustomerChargeMinor}
		}
		d.Pickup = s.pickupOf(ctx, sh)
		entries := make([]proofEntry, 0, len(sh.Timeline))
		for _, t := range sh.Timeline {
			entries = append(entries, proofEntry{to: t.To, at: t.At, proof: t.Proof})
		}
		d.Proof = deliveredProof(entries)
		for _, l := range sh.Lines {
			d.Lines = append(d.Lines, domain.OrderLineView{LineID: firstNonEmpty(l.ID, l.VariantATIN), ATIN: l.VariantATIN, Title: l.Title,
				ImageURL: optStr(l.ImageURL), Quantity: l.Quantity, UnitPriceMinor: l.UnitPriceMinor, ItemsMinor: l.ItemsMinor})
		}
		out.Shipments = append(out.Shipments, d)
	}
	proofs := make([]*domain.DeliveryProof, 0, len(out.Shipments))
	for _, sh := range out.Shipments {
		proofs = append(proofs, sh.Proof)
	}
	attachProofLinks(ctx, s.d.ProofLinks, proofs)
	s.withPayment(ctx, c, o.ID, &out)
	return out, nil
}

// pickupOf reads the pickup point of a pickup shipment from carrier-gateway (best effort: nil when unreadable).
func (s *customerOrdersServiceImpl) pickupOf(ctx context.Context, sh port.UpShipment) *domain.OrderPickup {
	if !strings.EqualFold(sh.DeliveryMethod, "PICKUP") || sh.PickupLocationID == "" || s.d.Delivery == nil {
		return nil
	}
	l, err := s.d.Delivery.PickupLocation(ctx, sh.PickupLocationID)
	if err != nil {
		s.d.log().WarnContext(ctx, "pickup location unavailable", "error", err, "pickupLocationId", sh.PickupLocationID)
		return nil
	}
	pt := pickupPoint(l, nil)
	return &domain.OrderPickup{ID: pt.ID, Name: pt.Name, Address: pt.Address, Hours: pt.Hours, Ready: pt.Ready, SetBy: pt.SetBy}
}

// withPayment adds charged/refunded amounts and per-line refunds from payments (best effort). The order was read
// with the shopper's token above, which is the ownership check payments' by-order read does not do itself.
func (s *customerOrdersServiceImpl) withPayment(ctx context.Context, c *domain.Caller, orderID string, out *domain.OrderDetail) {
	if s.d.Payments == nil || orderID == "" {
		return
	}
	p, err := s.d.Payments.ByOrder(ctx, c.BearerToken, orderID)
	if err != nil {
		s.d.log().WarnContext(ctx, "order payment unavailable", "error", err)
		return
	}
	sum := domain.OrderPayment{Status: strings.ToLower(p.Status), CapturedMinor: p.CapturedMinor, RefundedMinor: p.RefundedMinor}
	refunded := map[string]int64{}
	for _, l := range p.Lines {
		sum.RefundableMinor += max(0, l.RefundableMinor)
		sum.ShippingTaxMinor += max(0, l.ShippingTaxMinor)
		refunded[l.LineID] += max(0, l.RefundedMinor)
	}
	for i := range out.Shipments {
		for j := range out.Shipments[i].Lines {
			out.Shipments[i].Lines[j].RefundedMinor = refunded[out.Shipments[i].Lines[j].LineID]
		}
	}
	out.Payment = &sum
}

// Tracking adds carrier scans (carrier-gateway) to orders' shipment timelines; scans are best effort.
func (s *customerOrdersServiceImpl) Tracking(ctx context.Context, c *domain.Caller, number string) (domain.OrderTracking, error) {
	if err := requireCustomer(c); err != nil {
		return domain.OrderTracking{}, err
	}
	o, err := s.d.Orders.Get(ctx, c.BearerToken, number)
	if err != nil {
		return domain.OrderTracking{}, ordersErr(err)
	}
	out := domain.OrderTracking{OrderNumber: o.OrderNumber, PlacedAt: firstNonEmpty(o.PlacedAt, o.CreatedAt), TotalMinor: o.TotalMinor,
		Currency: firstNonEmpty(o.Currency, domain.Currency), Shipments: []domain.TrackedShipment{}}
	for _, sh := range o.Shipments {
		st := strings.ToLower(sh.Status)
		t := domain.TrackedShipment{ShipmentID: firstNonEmpty(sh.ShipmentID, sh.ID), Label: sh.Label, Status: st,
			ETA: optStr(firstNonEmpty(sh.PromisedBy, sh.PromisedFrom)), Method: methodKey(sh.DeliveryMethod, sh.ServiceLevel),
			Perishable: sh.Perishable, DeliveryCode: optStr(sh.HandoverCode), Carrier: optStr(sh.Carrier),
			TrackingNumber: optStr(sh.TrackingNumber), Items: []domain.TrackedItem{}, Timeline: timeline(sh.Timeline)}
		if sh.Preferences != nil && sh.Preferences.Handover != "" {
			t.Handover = ptr(strings.ToLower(sh.Preferences.Handover))
		}
		for _, l := range sh.Lines {
			t.Items = append(t.Items, domain.TrackedItem{ATIN: l.VariantATIN, Title: l.Title, Quantity: l.Quantity, PriceMinor: l.UnitPriceMinor,
				Returnable: st == "delivered" && !l.Perishable})
		}
		if sh.Carrier != "" && sh.TrackingNumber != "" && s.d.Delivery != nil {
			scans, err := s.d.Delivery.Tracking(ctx, c.BearerToken, sh.Carrier, sh.TrackingNumber)
			if err != nil {
				s.d.log().WarnContext(ctx, "carrier tracking unavailable; showing the orders timeline only", "error", err)
			}
			for _, sc := range scans {
				label := firstNonEmpty(sc.Description, statusLabel(sc.State))
				if sc.Location != "" {
					label += " · " + sc.Location
				}
				t.Timeline = append(t.Timeline, domain.TimelineEvent{Status: strings.ToLower(sc.State), Label: label, At: sc.OccurredAt})
			}
		}
		out.Shipments = append(out.Shipments, t)
	}
	return out, nil
}

func (s *customerOrdersServiceImpl) Cancel(ctx context.Context, c *domain.Caller, number, shipmentID, reason string) (domain.OrderSummary, error) {
	if err := requireCustomer(c); err != nil {
		return domain.OrderSummary{}, err
	}
	if len(reason) > 500 {
		return domain.OrderSummary{}, domain.ValidationProblem(map[string]string{"reason": "At most 500 characters"})
	}
	o, err := s.d.Orders.Cancel(ctx, c.BearerToken, number, shipmentID, reason)
	if err != nil {
		return domain.OrderSummary{}, ordersErr(err)
	}
	return toOrderSummary(o), nil
}

// ---------------------------------------------------------------- checkout (orders saga + delivery facts)

// CheckoutPaymentService places orders through orders' checkout saga and returns the PaymentIntent client secret
// (always), then confirms or polls the payment; the summary adds promise dates, pickup points and AfroTransact
// Delivery eligibility per package (S047/S048, S238).
type CheckoutPaymentService interface {
	Summary(ctx context.Context, c *domain.Caller, addressID string, packageDelivery map[string]string, couponCode string) (domain.CheckoutSummary, error)
	Place(ctx context.Context, c *domain.Caller, key string, req domain.PlaceOrderRequest) (port.PlaceOrderResult, error)
	Status(ctx context.Context, c *domain.Caller, number string) (domain.OrderConfirmation, error)
	Confirm(ctx context.Context, c *domain.Caller, number string) (domain.OrderConfirmation, error)
}

type checkoutPaymentServiceImpl struct {
	d     ShopperDeps
	merch CheckoutMerch
}

var _ CheckoutPaymentService = (*checkoutPaymentServiceImpl)(nil)

// CheckoutMerch resolves optional checkout merchandising (content placements).
type CheckoutMerch interface {
	CheckoutCrossSell(ctx context.Context, c *domain.Caller) (*domain.Rail, error)
}

// NewCheckoutPaymentService returns the CheckoutPaymentService.
func NewCheckoutPaymentService(d ShopperDeps, merch CheckoutMerch) CheckoutPaymentService {
	return &checkoutPaymentServiceImpl{d: d, merch: merch}
}

func toConfirmation(v port.UpCheckout) domain.OrderConfirmation {
	out := domain.OrderConfirmation{OrderNumber: v.OrderNumber, Status: v.Status, TotalMinor: v.TotalMinor, Currency: firstNonEmpty(v.Currency, domain.Currency),
		PaymentClientSecret: strPtr(firstNonEmpty(v.PaymentClientSecret, v.ClientSecret)), Shipments: []domain.OrderConfirmationShipment{}}
	switch v.Status {
	case "placed", "awaiting_payment", "processing":
	default:
		out.Status = "processing"
	}
	for _, s := range v.Shipments {
		out.Shipments = append(out.Shipments, domain.OrderConfirmationShipment{ShipmentID: s.ShipmentID, Label: s.Label,
			ETA: firstNonEmpty(s.ETA, s.PromisedBy), DeliveryCode: strPtr(s.DeliveryCode)})
	}
	out.Preorder = preorderBlock(v.Preorder)
	return out
}

var (
	handovers   = map[string]bool{"hand": true, "door": true, "desk": true}
	contacts    = map[string]bool{"text": true, "call": true, "none": true}
	optionKeys  = map[string]bool{"carrier": true, "express": true, "afrotransact_delivery": true, "pickup": true}
	walletKinds = map[string]bool{"apple_pay": true, "google_pay": true, "link": true}
	pmIDRe      = regexp.MustCompile(`^(pm|card|ba|src)_[A-Za-z0-9_]{1,250}$`)
)

// selections validates the per-package choices and maps them to orders' upper-case shapes.
func selections(raw []domain.PlaceOrderRequestSelection) ([]port.UpDeliverySelection, map[string]string) {
	errs := map[string]string{}
	out := make([]port.UpDeliverySelection, 0, len(raw))
	for i, s := range raw {
		f := fmt.Sprintf("deliverySelections.%d", i)
		if s.PackageID == "" {
			errs[f+".packageId"] = "Required"
		}
		if !optionKeys[s.Option] {
			errs[f+".option"] = "Choose carrier, express, afrotransact_delivery or pickup"
		}
		if s.Option == "pickup" && s.PickupPointID == "" {
			errs[f+".pickupPointId"] = "Choose a pickup location"
		}
		sel := port.UpDeliverySelection{PackageID: s.PackageID, Option: s.Option, PickupPointID: s.PickupPointID}
		if p := s.Preferences; p != nil {
			if p.Handover != "" && !handovers[p.Handover] {
				errs[f+".preferences.handover"] = "Choose hand, door or desk"
			}
			if p.Contact != "" && !contacts[p.Contact] {
				errs[f+".preferences.contact"] = "Choose text, call or none"
			}
			if len([]rune(p.Instructions)) > 240 {
				errs[f+".preferences.instructions"] = "At most 240 characters"
			}
			sel.Preferences = &port.UpPreferences{Handover: strings.ToUpper(p.Handover), SafePlace: strings.TrimSpace(p.SafePlace),
				Instructions: strings.TrimSpace(p.Instructions), Contact: strings.ToUpper(p.Contact)}
		}
		out = append(out, sel)
	}
	return out, errs
}

func (s *checkoutPaymentServiceImpl) Place(ctx context.Context, c *domain.Caller, key string, req domain.PlaceOrderRequest) (port.PlaceOrderResult, error) {
	if err := requireCustomer(c); err != nil {
		return port.PlaceOrderResult{}, err
	}
	sels, errs := selections(req.Selections())
	if l := len(key); l < 16 || l > 64 {
		errs["Idempotency-Key"] = "Send a 16–64 character Idempotency-Key header"
	}
	if strings.TrimSpace(req.AddressID) == "" {
		errs["addressId"] = "Choose a delivery address"
	}
	if len(sels) == 0 {
		errs["deliverySelections"] = "Choose delivery options"
	}
	sources := 0
	for _, set := range []bool{req.Wallet != "", req.PaymentMethodID != "", req.NewPaymentMethod} {
		if set {
			sources++
		}
	}
	switch {
	case req.Wallet != "" && !walletKinds[req.Wallet]:
		errs["wallet"] = "Choose Apple Pay, Google Pay or Link"
	case sources == 0:
		errs["paymentMethodId"] = "Choose a payment method"
	case sources > 1:
		errs["paymentMethodId"] = "Pay with one of a saved method, a wallet or a new payment method"
	case req.PaymentMethodID != "" && !pmIDRe.MatchString(req.PaymentMethodID):
		errs["paymentMethodId"] = "Unknown payment method"
	}
	if req.SavePaymentMethod && !req.NewPaymentMethod {
		errs["savePaymentMethod"] = "Only a new payment method can be saved"
	}
	if len(strings.TrimSpace(req.CouponCode)) > 20 {
		errs["couponCode"] = "Coupon codes have at most 20 characters"
	}
	if len(errs) > 0 {
		return port.PlaceOrderResult{}, domain.ValidationProblem(errs)
	}
	cart, err := s.d.Carts.Get(ctx, c)
	if err != nil {
		return port.PlaceOrderResult{}, fmt.Errorf("checkout cart: %w", err)
	}
	pkgs, err := selected(cart)
	if err != nil {
		return port.PlaceOrderResult{}, err
	}
	var lines []port.OrderLine
	for _, p := range pkgs {
		for _, l := range p.Lines {
			lines = append(lines, port.OrderLine{VariantATIN: l.LineID, Quantity: l.Quantity, PackageID: p.PackageID})
		}
	}
	if len(lines) == 0 {
		return port.PlaceOrderResult{}, domain.ValidationProblem(map[string]string{"cart": "Select at least one item to check out"})
	}
	v, status, err := s.d.Orders.Checkout(ctx, c.BearerToken, key, port.UpCheckoutRequest{AddressID: req.AddressID, DeliverySelections: sels,
		Lines: lines, PaymentMethodID: req.PaymentMethodID, Wallet: req.Wallet, Gift: req.Gift, CouponCode: strings.TrimSpace(req.CouponCode),
		SavePaymentMethod: req.SavePaymentMethod, Attribution: req.Attribution.Clean()})
	if err != nil {
		return port.PlaceOrderResult{}, orderErr(err)
	}
	res := port.PlaceOrderResult{Status: http.StatusCreated, Confirmation: toConfirmation(v)}
	if status == http.StatusAccepted || v.Status == "processing" {
		res.Status = http.StatusAccepted
	}
	return res, nil
}

func (s *checkoutPaymentServiceImpl) Status(ctx context.Context, c *domain.Caller, number string) (domain.OrderConfirmation, error) {
	if err := requireCustomer(c); err != nil {
		return domain.OrderConfirmation{}, err
	}
	v, err := s.d.Orders.CheckoutStatus(ctx, c.BearerToken, number)
	if err != nil {
		return domain.OrderConfirmation{}, checkoutStatusErr(err)
	}
	return toConfirmation(v), nil
}

func (s *checkoutPaymentServiceImpl) Confirm(ctx context.Context, c *domain.Caller, number string) (domain.OrderConfirmation, error) {
	if err := requireCustomer(c); err != nil {
		return domain.OrderConfirmation{}, err
	}
	v, err := s.d.Orders.ConfirmPayment(ctx, c.BearerToken, number)
	if err != nil {
		return domain.OrderConfirmation{}, checkoutStatusErr(err)
	}
	return toConfirmation(v), nil
}

func checkoutStatusErr(err error) error {
	if p, ok := port.AsUpstreamProblem(err); ok {
		switch p.Status {
		case http.StatusPaymentRequired:
			return domain.NewProblem(http.StatusPaymentRequired, domain.CodePaymentDeclined, "Payment declined",
				firstNonEmpty(p.Detail, "Your payment was declined. Choose another payment method.")).WithCause(err)
		case http.StatusNotFound, http.StatusForbidden:
			return domain.NewProblem(http.StatusNotFound, domain.CodeOrderNotFound, "Order not found", "We couldn't find that order.").WithCause(err)
		}
	}
	return mapErr("orders", err)
}
