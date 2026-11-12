package app

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"strings"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

// CheckoutService composes the checkout page from the selected cart lines, the address book and saved payment
// methods, and places orders through the orders saga (Idempotency-Key relayed).
type CheckoutService interface {
	Summary(ctx context.Context, c *domain.Caller, addressID string) (domain.CheckoutSummary, error)
	PlaceOrder(ctx context.Context, c *domain.Caller, idempotencyKey string, req domain.PlaceOrderRequest) (port.PlaceOrderResult, error)
}

type checkoutServiceImpl struct {
	carts    CartService
	identity port.IdentityClient
	payments port.PaymentsClient
	orders   port.OrdersClient
}

var _ CheckoutService = (*checkoutServiceImpl)(nil)

// NewCheckoutService returns the CheckoutService.
func NewCheckoutService(carts CartService, identity port.IdentityClient, payments port.PaymentsClient,
	orders port.OrdersClient) CheckoutService {
	return &checkoutServiceImpl{carts: carts, identity: identity, payments: payments, orders: orders}
}

// selected keeps only selected lines (and packages that still have some); a line whose quantity exceeds live
// stock fails the checkout with the stock problem the cart page understands.
func selected(cart domain.Cart) ([]domain.CheckoutPackage, error) {
	out := []domain.CheckoutPackage{}
	for _, p := range cart.Packages {
		var lines []domain.CartLine
		for _, l := range p.Lines {
			if !l.Selected {
				continue
			}
			if l.Quantity > l.MaxQuantity {
				return nil, domain.StockProblem(l.Title, l.AvailableQty, l.Quantity, l.Quantity)
			}
			lines = append(lines, l)
		}
		if len(lines) == 0 {
			continue
		}
		p.Lines = lines
		p.ItemCount = 0
		for _, l := range lines {
			p.ItemCount += l.Quantity
		}
		out = append(out, domain.CheckoutPackage{CartPackage: p, PickupPoints: []domain.PickupPoint{}})
	}
	return out, nil
}

func (s *checkoutServiceImpl) Summary(ctx context.Context, c *domain.Caller, _ string) (domain.CheckoutSummary, error) {
	if err := requireCustomer(c); err != nil {
		return domain.CheckoutSummary{}, err
	}
	cart, err := s.carts.Get(ctx, c)
	if err != nil {
		return domain.CheckoutSummary{}, fmt.Errorf("checkout cart: %w", err)
	}
	pkgs, err := selected(cart)
	if err != nil {
		return domain.CheckoutSummary{}, err
	}
	addrs, err := s.identity.Addresses(ctx, c.BearerToken)
	if err != nil {
		return domain.CheckoutSummary{}, mapErr("identity", err)
	}
	wallet, err := s.payments.PaymentMethods(ctx, c.BearerToken)
	if err != nil {
		return domain.CheckoutSummary{}, mapErr("payments", err)
	}
	out := domain.CheckoutSummary{Packages: pkgs, Addresses: []domain.Address{},
		PaymentMethods: make([]domain.PaymentMethod, 0, len(wallet.Items)), StripeCustomerID: wallet.StripeCustomerID,
		Currency: cart.Currency, TaxRateLabel: taxLabelNeedsAddress, TaxEstimateStatus: domain.TaxNeedsAddress}
	for _, m := range wallet.Items {
		out.PaymentMethods = append(out.PaymentMethods, toPaymentMethod(m))
	}
	for _, a := range addrs {
		out.Addresses = append(out.Addresses, toAddress(a))
	}
	sellers := map[string]bool{}
	for _, p := range pkgs {
		out.SubtotalMinor += p.SubtotalMinor
		if len(p.DeliveryOptions) > 0 {
			out.ShippingMinor += p.DeliveryOptions[0].FeeMinor
		}
		sellers[p.Seller.ID] = true
	}
	out.SellerCount = len(sellers)
	out.TotalMinor = out.SubtotalMinor + out.ShippingMinor + out.TaxMinor
	return out, nil
}

func (s *checkoutServiceImpl) PlaceOrder(ctx context.Context, c *domain.Caller, key string, req domain.PlaceOrderRequest) (port.PlaceOrderResult, error) {
	if err := requireCustomer(c); err != nil {
		return port.PlaceOrderResult{}, err
	}
	errs := map[string]string{}
	if l := len(key); l < 16 || l > 64 {
		errs["Idempotency-Key"] = "Send a 16–64 character Idempotency-Key header"
	}
	if strings.TrimSpace(req.AddressID) == "" {
		errs["addressId"] = "Choose a delivery address"
	}
	if len(req.DeliverySelections) == 0 {
		errs["deliverySelections"] = "Choose delivery options"
	}
	for _, raw := range req.DeliverySelections {
		var sel struct {
			Option string `json:"option"`
		}
		if json.Unmarshal(raw, &sel) == nil && strings.EqualFold(strings.TrimSpace(sel.Option), domain.OptionQuoteRequired) {
			// Orders refuses it too; saying it here keeps the shopper on the page with a way forward.
			errs["deliverySelections"] = quoteRequiredMessage
		}
	}
	if req.PaymentMethodID == "" && req.Wallet == "" {
		errs["paymentMethodId"] = "Choose a payment method"
	}
	if len(strings.TrimSpace(req.CouponCode)) > 20 {
		errs["couponCode"] = "Coupon codes have at most 20 characters"
	}
	if len(errs) > 0 {
		return port.PlaceOrderResult{}, domain.ValidationProblem(errs)
	}
	cart, err := s.carts.Get(ctx, c)
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
	res, err := s.orders.Place(ctx, c.BearerToken, key, req, lines)
	if err != nil {
		return res, orderErr(err)
	}
	return res, nil
}

func orderErr(err error) error {
	p, ok := port.AsUpstreamProblem(err)
	if !ok {
		return mapErr("orders", err)
	}
	if pr := deliveryProblem(p); pr != nil {
		return pr.WithCause(err)
	}
	if pr := quoteProblem(p); pr != nil {
		return pr.WithCause(err)
	}
	switch {
	case p.Code == "orders.invalid-delivery-selection":
		// Orders words it for the shopper ("The delivery options have changed. Reload the page and choose ...").
		return domain.FieldProblem(domain.CodeValidationFailed, "Check your delivery choice", "deliverySelections",
			firstNonEmpty(p.Detail, "Your delivery choice can't be used. Reload the page and choose Delivery by AfroTransact or pickup.")).WithCause(err)
	case p.Code == "orders.tax-line-blocked":
		// Orders names the item in plain words; the shopper removes it (the summary lists it with a Remove action).
		return domain.FieldProblem(domain.CodeCheckoutItemBlocked, "We can't sell this item yet", "cart",
			firstNonEmpty(p.Detail, blockedMessage(""))).WithCause(err)
	case p.Code == "orders.total-changed":
		// The total would be higher than the one shown (the shopper confirms the new one). Place adds a fresh lock.
		return totalChangedProblem(nil, time.Time{}, "", p, 0, err)
	case p.Code == "orders.coupon-not-applicable":
		return domain.FieldProblem(domain.CodeValidationFailed, "Coupon can't be used", "couponCode",
			firstNonEmpty(p.Detail, "This coupon can't be used on this order.")).WithCause(err)
	case p.Status == http.StatusPaymentRequired:
		return domain.NewProblem(http.StatusPaymentRequired, domain.CodePaymentDeclined, "Payment declined", firstNonEmpty(p.Detail, "Your payment was declined.")).WithCause(err)
	case p.Code == "orders.checkout-in-progress":
		// The customer's previous checkout is still being closed: retry with the SAME key in a moment.
		return domain.NewProblem(http.StatusConflict, domain.CodeCheckoutInProgress, "Checkout in progress",
			firstNonEmpty(p.Detail, "Your previous checkout is still being closed; try again in a moment.")).WithCause(err)
	case p.Code == "orders.previous-checkout-paid":
		return domain.NewProblem(http.StatusConflict, domain.CodePreviousCheckoutPaid, "Previous checkout paid",
			firstNonEmpty(p.Detail, "Your previous checkout was paid and is being placed; check your orders.")).WithCause(err)
	case p.Status == http.StatusConflict && strings.Contains(p.Code, "idempotency"):
		pr := domain.NewProblem(http.StatusConflict, domain.CodeIdempotencyConflict, "Conflicting retry", "This Idempotency-Key was used for a different order.")
		pr.Extra = map[string]any{"available": 0, "inCart": 0, "requested": 0}
		return pr.WithCause(err)
	case p.Status == http.StatusConflict:
		pr := domain.NewProblem(http.StatusConflict, domain.CodeStockChanged, "Stock changed", firstNonEmpty(p.Detail, "Some items are no longer available in the quantity you chose."))
		pr.Extra = map[string]any{"available": 0, "inCart": 0, "requested": 0}
		return pr.WithCause(err)
	}
	return mapErr("orders", err)
}

// deliveryProblem maps orders' AfroTransact Delivery refusals (S255) to the checkout's problems; the message goes on
// errors.deliverySelections so the delivery step shows it.
func deliveryProblem(p *port.UpstreamProblem) *domain.Problem {
	var code, title, detail string
	switch p.Code {
	case "orders.delivery-out-of-range":
		code, title, detail = domain.CodeDeliveryOutOfRange, "Too far for AfroTransact Delivery",
			"A seller's store is too far from this address for AfroTransact Delivery. Remove its items or choose another address."
	case "orders.delivery-unavailable":
		code, title, detail = domain.CodeDeliveryUnavailable, "AfroTransact Delivery unavailable",
			"AfroTransact Delivery from sellers' stores isn't offered right now. Remove those items to check out."
	case "orders.delivery-pickup-unavailable":
		code, title, detail = domain.CodeDeliveryPickupUnavailable, "Store pickup unavailable",
			"A seller's store isn't set up for AfroTransact Delivery right now. Remove its items to check out."
	case "orders.delivery-address-not-located":
		code, title, detail = domain.CodeDeliveryAddressNotLocated, "Address not found",
			"We couldn't locate this address for AfroTransact Delivery. Check the address or choose another one."
	default:
		return nil
	}
	return domain.FieldProblem(code, title, "deliverySelections", firstNonEmpty(p.Detail, detail))
}

// quoteProblem maps orders' delivery quote refusals (listing rules D20): a quote that is void for this cart, address or
// moment is one problem with a plain message, so the checkout asks for a new quote. Nil for any other problem.
func quoteProblem(p *port.UpstreamProblem) *domain.Problem {
	reason := map[string]string{"orders.quote-changed": "changed", "orders.quote-expired": "expired", "orders.quote-used": "used",
		"orders.quote-not-ready": "not_ready", "orders.quote-not-found": "not_found"}[p.Code]
	if reason == "" {
		return nil
	}
	pr := domain.NewProblem(http.StatusUnprocessableEntity, domain.CodeDeliveryQuoteVoid, "Delivery quote can't be used",
		firstNonEmpty(p.Detail, "This delivery quote can't be used. Ask for a new one."))
	pr.Extra = map[string]any{"reason": reason}
	return pr
}
