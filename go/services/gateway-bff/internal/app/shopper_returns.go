package app

import (
	"context"
	"net/http"
	"strconv"
	"strings"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

// ReturnsService is Returns & refunds over tax-returns: what an order can return, start a return (RMA with label,
// QR or handover code, refund estimate), list, detail with the refund quote and cancel.
type ReturnsService interface {
	Returnable(ctx context.Context, c *domain.Caller, orderNumber string) (domain.ReturnEligibility, error)
	Start(ctx context.Context, c *domain.Caller, key string, r domain.StartReturn) (domain.ReturnSummary, error)
	List(ctx context.Context, c *domain.Caller) (domain.Page[domain.ReturnSummary], error)
	Detail(ctx context.Context, c *domain.Caller, id string) (domain.ReturnDetail, error)
	Cancel(ctx context.Context, c *domain.Caller, id string) (domain.ReturnSummary, error)
}

type returnsServiceImpl struct{ d ShopperDeps }

var _ ReturnsService = (*returnsServiceImpl)(nil)

// NewReturnsService returns the ReturnsService.
func NewReturnsService(d ShopperDeps) ReturnsService { return &returnsServiceImpl{d: d} }

// ReturnReasons are tax-returns' reasons.
var ReturnReasons = map[string]bool{"DAMAGED": true, "DEFECTIVE": true, "WRONG_ITEM": true, "NOT_AS_DESCRIBED": true, "MISSING_PARTS": true,
	"EXPIRED_OR_SPOILED": true, "ARRIVED_LATE": true, "NO_LONGER_NEEDED": true, "ORDERED_BY_MISTAKE": true, "BETTER_PRICE_AVAILABLE": true}

var returnMethods = map[string]bool{"DROP_OFF": true, "DRIVER_PICKUP": true, "NO_RETURN": true}

var ineligible = map[string]string{
	"returns.window-closed": "The return window has closed", "returns.not-delivered": "Not delivered yet",
	"returns.already-returned": "Already being returned", "returns.non-returnable": "This item can't be returned",
}

func returnsErr(err error) error {
	if p, ok := port.AsUpstreamProblem(err); ok {
		switch {
		case p.Status == http.StatusNotFound || p.Status == http.StatusForbidden:
			return domain.NewProblem(http.StatusNotFound, domain.CodeNotFound, "Return not found", "We couldn't find that return.").WithCause(err)
		case p.Status == http.StatusUnprocessableEntity && len(p.Fields) == 0:
			return domain.FieldProblem(domain.CodeValidationFailed, "Can't start this return", "lines",
				firstNonEmpty(p.Detail, "These items can't be returned this way.")).WithCause(err)
		case p.Status == http.StatusConflict:
			return domain.NewProblem(http.StatusConflict, domain.CodeConflict, "Can't change this return",
				firstNonEmpty(p.Detail, "This return can no longer be changed.")).WithCause(err)
		}
	}
	return mapErr("tax-returns", err)
}

func toReturnSummary(r port.UpRma) domain.ReturnSummary {
	title := "Return"
	reason := ""
	if len(r.Lines) > 0 {
		title = r.Lines[0].Title
		reason = r.Lines[0].Reason
		if n := len(r.Lines) - 1; n > 0 {
			title += " and " + map[bool]string{true: "1 more item", false: strconv.Itoa(n) + " more items"}[n == 1]
		}
	}
	return domain.ReturnSummary{RMAID: r.ID, OrderNumber: r.OrderNumber, Title: title, Status: strings.ToLower(r.Status), RefundMinor: r.EstimateMinor,
		Number: r.Number, Method: r.Method, Reason: reason, RefundState: r.RefundState, Currency: firstNonEmpty(r.Currency, domain.Currency),
		Cancellable: r.Cancellable, CreatedAt: optStr(r.CreatedAt), LabelURL: optStr(r.LabelURL), QRPayload: optStr(r.QRPayload),
		HandoverCode: optStr(r.HandoverCode), DropOffBy: optStr(r.DropOffBy), Carrier: optStr(r.Carrier), Tracking: optStr(r.Tracking)}
}

// orderID resolves the order number to orders' id (tax-returns keys returns by order id).
func (s *returnsServiceImpl) order(ctx context.Context, c *domain.Caller, number string) (port.UpOrder, error) {
	o, err := s.d.Orders.Get(ctx, c.BearerToken, number)
	if err != nil {
		return o, ordersErr(err)
	}
	return o, nil
}

func (s *returnsServiceImpl) Returnable(ctx context.Context, c *domain.Caller, orderNumber string) (domain.ReturnEligibility, error) {
	if err := requireCustomer(c); err != nil {
		return domain.ReturnEligibility{}, err
	}
	o, err := s.order(ctx, c, orderNumber)
	if err != nil {
		return domain.ReturnEligibility{}, err
	}
	e, err := s.d.Returns.Eligibility(ctx, c.BearerToken, o.ID)
	if err != nil {
		return domain.ReturnEligibility{}, returnsErr(err)
	}
	out := domain.ReturnEligibility{OrderNumber: firstNonEmpty(e.OrderNumber, o.OrderNumber), Currency: firstNonEmpty(e.Currency, o.Currency, domain.Currency),
		Shipments: []domain.ReturnableShipment{}}
	for _, sh := range e.Shipments {
		rs := domain.ReturnableShipment{ShipmentID: sh.ShipmentID, SellerName: sh.SellerName, DeliveredAt: optStr(sh.DeliveredAt), Lines: []domain.ReturnableLine{}}
		for _, l := range sh.Lines {
			rl := domain.ReturnableLine{LineID: l.LineID, ATIN: l.ATIN, Title: l.Title, Quantity: l.Quantity, MaxQuantity: l.MaxQuantity,
				UnitPriceMinor: l.UnitPriceMinor, Eligible: l.Eligible, ReportProblemOnly: l.ReportProblemOnly, SellerFaultOnly: l.SellerFaultOnly,
				WindowEndsAt: optStr(l.WindowEndsAt), Methods: orEmpty(l.Methods)}
			if l.IneligibleCode != "" {
				rl.IneligibleReason = ptr(firstNonEmpty(ineligible[l.IneligibleCode], "Not returnable"))
			}
			rs.Lines = append(rs.Lines, rl)
		}
		out.Shipments = append(out.Shipments, rs)
	}
	return out, nil
}

// legacyLines turns the app's {orderNumber, atin, reason} into one unit of that delivered line with its first method.
func legacyLines(e port.UpReturnEligibility, r *domain.StartReturn) bool {
	for _, sh := range e.Shipments {
		for _, l := range sh.Lines {
			if strings.EqualFold(l.ATIN, r.ATIN) && l.Eligible && len(l.Methods) > 0 {
				r.ShipmentID = sh.ShipmentID
				r.Method = l.Methods[0]
				r.Lines = []domain.StartReturnLine{{LineID: l.LineID, Quantity: 1, Reason: r.Reason}}
				return true
			}
		}
	}
	return false
}

func (s *returnsServiceImpl) Start(ctx context.Context, c *domain.Caller, key string, r domain.StartReturn) (domain.ReturnSummary, error) {
	if err := requireCustomer(c); err != nil {
		return domain.ReturnSummary{}, err
	}
	if strings.TrimSpace(r.OrderNumber) == "" {
		return domain.ReturnSummary{}, domain.ValidationProblem(map[string]string{"orderNumber": "Required"})
	}
	legacy := len(r.Lines) == 0 && r.ATIN != ""
	if legacy && !ReturnReasons[r.Reason] {
		return domain.ReturnSummary{}, domain.ValidationProblem(map[string]string{"reason": "Choose a reason"})
	}
	o, err := s.order(ctx, c, r.OrderNumber)
	if err != nil {
		return domain.ReturnSummary{}, err
	}
	if legacy {
		e, err := s.d.Returns.Eligibility(ctx, c.BearerToken, o.ID)
		if err != nil {
			return domain.ReturnSummary{}, returnsErr(err)
		}
		if !legacyLines(e, &r) {
			return domain.ReturnSummary{}, domain.NewProblem(http.StatusConflict, domain.CodeConflict, "Not returnable", "This item can't be returned.")
		}
	}
	errs := map[string]string{}
	if r.ShipmentID == "" {
		errs["shipmentId"] = "Required"
	}
	if !returnMethods[r.Method] {
		errs["method"] = "Choose drop-off, driver pickup or refund without return"
	}
	if len(r.Lines) == 0 {
		errs["lines"] = "Choose at least one item"
	}
	lines := make([]port.UpCreateReturnLine, 0, len(r.Lines))
	for i, l := range r.Lines {
		if !ReturnReasons[l.Reason] {
			errs["lines."+strconv.Itoa(i)+".reason"] = "Choose a reason"
		}
		if len(l.Comment) > 500 {
			errs["lines."+strconv.Itoa(i)+".comment"] = "At most 500 characters"
		}
		lines = append(lines, port.UpCreateReturnLine{LineID: l.LineID, Quantity: max(1, l.Quantity), Reason: l.Reason, Comment: strings.TrimSpace(l.Comment)})
	}
	if len(errs) > 0 {
		return domain.ReturnSummary{}, domain.ValidationProblem(errs)
	}
	req := port.UpCreateReturn{OrderID: o.ID, ShipmentID: r.ShipmentID, Method: r.Method, Lines: lines}
	if r.Method == "DROP_OFF" && o.ShippingAddress != nil {
		a := o.ShippingAddress
		req.ReturnFrom = &port.UpReturnAddress{Name: a.FullName, Phone: a.Phone, Line1: a.Line1, Line2: a.Line2, City: a.City, State: a.State,
			PostalCode: a.PostalCode, Country: firstNonEmpty(a.Country, "US")}
	}
	if key == "" {
		key = derivedKey(c, s.d.Clock.now(), "return", o.ID, r.ShipmentID, r.Method)
	}
	rma, err := s.d.Returns.Create(ctx, c.BearerToken, key, req)
	if err != nil {
		return domain.ReturnSummary{}, returnsErr(err)
	}
	return toReturnSummary(rma), nil
}

func (s *returnsServiceImpl) List(ctx context.Context, c *domain.Caller) (domain.Page[domain.ReturnSummary], error) {
	if err := requireCustomer(c); err != nil {
		return domain.Page[domain.ReturnSummary]{}, err
	}
	list, err := s.d.Returns.List(ctx, c.BearerToken)
	if err != nil {
		return domain.Page[domain.ReturnSummary]{}, returnsErr(err)
	}
	out := domain.Page[domain.ReturnSummary]{Items: make([]domain.ReturnSummary, 0, len(list))}
	for _, r := range list {
		out.Items = append(out.Items, toReturnSummary(r))
	}
	return out, nil
}

func (s *returnsServiceImpl) Detail(ctx context.Context, c *domain.Caller, id string) (domain.ReturnDetail, error) {
	if err := requireCustomer(c); err != nil {
		return domain.ReturnDetail{}, err
	}
	r, err := s.d.Returns.Get(ctx, c.BearerToken, id)
	if err != nil {
		return domain.ReturnDetail{}, returnsErr(err)
	}
	out := domain.ReturnDetail{ReturnSummary: toReturnSummary(r), Lines: []domain.ReturnLineView{}}
	for _, l := range r.Lines {
		out.Lines = append(out.Lines, domain.ReturnLineView{LineID: l.LineID, ATIN: l.ATIN, Title: l.Title, Quantity: l.Quantity,
			UnitPriceMinor: l.UnitPriceMinor, Reason: l.Reason, Outcome: optStr(l.Outcome)})
	}
	if q, err := s.d.Returns.Quote(ctx, c.BearerToken, id); err == nil {
		out.Quote = &q
		out.RefundMinor = q.TotalMinor
	} else {
		s.d.log().WarnContext(ctx, "refund quote unavailable", "error", err)
	}
	return out, nil
}

func (s *returnsServiceImpl) Cancel(ctx context.Context, c *domain.Caller, id string) (domain.ReturnSummary, error) {
	if err := requireCustomer(c); err != nil {
		return domain.ReturnSummary{}, err
	}
	r, err := s.d.Returns.Cancel(ctx, c.BearerToken, id)
	if err != nil {
		return domain.ReturnSummary{}, returnsErr(err)
	}
	return toReturnSummary(r), nil
}
