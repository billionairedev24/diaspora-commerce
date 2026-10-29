package app

import (
	"context"
	"fmt"
	"net/http"
	"net/url"
	"strings"
	"sync"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
)

// StaffSupportService is the customer support console: customer lookup, the customer 360 and the support actions
// (refund within the role's limit, resend a notification, cancel a shipment). Every action goes through the upstream
// that owns it, which records it in the trust-ops audit log through its events.
type StaffSupportService interface {
	Search(ctx context.Context, c *domain.Caller, q string) ([]domain.CustomerHit, error)
	Customer(ctx context.Context, c *domain.Caller, id string) (domain.Customer360, error)
	Refund(ctx context.Context, c *domain.Caller, req SupportRefund, idem string) (domain.SupportActionResult, error)
	Resend(ctx context.Context, c *domain.Caller, notificationID, customerID, idem string) (domain.SupportActionResult, error)
	CancelShipment(ctx context.Context, c *domain.Caller, shipmentID string, req SupportCancel, idem string) (domain.SupportActionResult, error)
}

// SupportRefund is POST /v1/staff/support/refunds.
type SupportRefund struct {
	CustomerID  string `json:"customerId"`
	OrderNumber string `json:"orderNumber"`
	AmountMinor int64  `json:"amountMinor"`
	Reason      string `json:"reason"`
	Via         string `json:"via"`
	ClaimID     string `json:"claimId"`
	CaseID      string `json:"caseId"`
}

// SupportCancel is POST /v1/staff/support/shipments/{id}/cancel.
type SupportCancel struct {
	CustomerID  string `json:"customerId"`
	OrderNumber string `json:"orderNumber"`
	Reason      string `json:"reason"`
	CaseID      string `json:"caseId"`
}

type staffSupportImpl struct{ r *staffResolver }

var _ StaffSupportService = (*staffSupportImpl)(nil)

// NewStaffSupportService returns the support console use case.
func NewStaffSupportService(d StaffDeps) StaffSupportService {
	return &staffSupportImpl{r: newStaffResolver(d)}
}

// supportOrderLimit caps how many recent orders the customer 360 reads claims and returns for.
const supportOrderLimit = 10

func (s *staffSupportImpl) Search(ctx context.Context, c *domain.Caller, q string) ([]domain.CustomerHit, error) {
	q = strings.TrimSpace(q)
	if len([]rune(q)) < 2 || len(q) > 120 {
		return nil, domain.ValidationProblem(map[string]string{"q": "Type 2 to 120 characters"})
	}
	sc, err := s.r.require(ctx, c, "support", domain.LevelView)
	if err != nil {
		return nil, err
	}
	var page jobj
	if err := sc.get(ctx, sc.up.Identity, "/v1/admin/users", url.Values{"q": {q}, "size": {"20"}}, &page); err != nil {
		return nil, err
	}
	out := []domain.CustomerHit{}
	for _, u := range objs(page, "items") {
		out = append(out, domain.CustomerHit{ID: jstr(u, "id"), Name: jstr(u, "name"), Email: jstr(u, "email"), Enabled: boolean(u, "enabled"),
			PrincipalType: firstNonEmpty(jstr(u, "principalType"), "CUSTOMER")})
	}
	return out, nil
}

func supportPermissions(a domain.StaffAccess) domain.SupportPermissions {
	edit := a.Allows("support", domain.LevelEdit)
	return domain.SupportPermissions{
		Refund:         edit && a.CanAny("refunds:issue", "disputes:manage") && (domain.RefundLimit(a.Roles) == nil || *domain.RefundLimit(a.Roles) > 0),
		Resend:         edit && a.Can("notifications:manage"),
		CancelShipment: edit && a.CanAny("orders:manage", "orders:update-status"),
	}
}

func (s *staffSupportImpl) Customer(ctx context.Context, c *domain.Caller, id string) (domain.Customer360, error) {
	sc, err := s.r.require(ctx, c, "support", domain.LevelView)
	if err != nil {
		return domain.Customer360{}, err
	}
	var u jobj
	if err := sc.get(ctx, sc.up.Identity, "/v1/admin/users/"+url.PathEscape(id), nil, &u); err != nil {
		return domain.Customer360{}, err
	}
	out := domain.Customer360{
		Customer: domain.SupportCustomer{ID: jstr(u, "id"), Name: jstr(u, "name"), Email: jstr(u, "email"), Enabled: boolean(u, "enabled"),
			EmailVerified: boolean(u, "emailVerified"), Roles: strs(u, "roles")},
		Orders: []domain.SupportOrder{}, Bookings: []domain.SupportBooking{}, Claims: []domain.SupportClaim{}, Returns: []domain.SupportReturn{},
		Notifications: []domain.SupportNotification{}, Cases: []domain.SupportCaseSummary{}, Audit: []domain.AuditLine{},
		RefundLimitMinor: domain.RefundLimit(sc.access.Roles), Can: supportPermissions(sc.access),
		// Bookings has no staff lookup of a customer's bookings yet (GET /v1/bookings?customerId=).
		Unavailable: []string{"bookings"},
	}
	var mu sync.Mutex
	var wg sync.WaitGroup
	miss := func(name string) {
		mu.Lock()
		defer mu.Unlock()
		out.Unavailable = append(out.Unavailable, name)
	}
	run := func(f func()) {
		wg.Add(1)
		go func() { defer wg.Done(); f() }()
	}
	var orders []jobj
	run(func() {
		// What the membership gives: payments answers for any customer; a customer without one is "not a member".
		var e jobj
		if err := sc.get(ctx, sc.up.Payments, "/v1/memberships/customers/"+url.PathEscape(id)+"/entitlements", nil, &e); err != nil {
			miss("membership")
			return
		}
		if boolean(e, "member") {
			mu.Lock()
			defer mu.Unlock()
			out.Membership = &domain.SupportMembership{TierName: firstNonEmpty(jstr(e, "tierName"), jstr(e, "tierCode")), Status: jstr(e, "status"),
				PrioritySupport: boolean(e, "prioritySupport")}
		}
	})
	run(func() {
		var page jobj
		if err := sc.get(ctx, sc.up.Orders, "/v1/admin/orders", url.Values{"customer": {id}, "limit": {"20"}}, &page); err != nil {
			miss("orders")
			return
		}
		orders = objs(page, "items")
	})
	run(func() {
		var page jobj
		if err := sc.get(ctx, sc.up.Notifications, "/v1/notifications", url.Values{"recipient": {id}, "limit": {"20"}}, &page); err != nil {
			miss("notifications")
			return
		}
		for _, n := range objs(page, "items") {
			out.Notifications = append(out.Notifications, domain.SupportNotification{ID: jstr(n, "id"), Channel: jstr(n, "channel"), TemplateKey: jstr(n, "templateKey"),
				Subject: strPtr(jstr(n, "subject")), Status: strings.ToUpper(jstr(n, "status")), Address: jstr(n, "address"), CreatedAt: timeOf(n, "createdAt")})
		}
	})
	run(func() {
		// trust-ops filters cases by status only: read the open queue and keep this customer's cases.
		var page jobj
		if err := sc.get(ctx, sc.up.TrustOps, "/v1/support-cases", url.Values{"size": {"200"}}, &page); err != nil {
			miss("support cases")
			return
		}
		for _, k := range objs(page, "items") {
			if jstr(k, "customerId") == id {
				out.Cases = append(out.Cases, domain.SupportCaseSummary{ID: jstr(k, "id"), Reference: jstr(k, "reference"), Reason: jstr(k, "reason"),
					Summary: jstr(k, "summary"), Status: jstr(k, "status"), OpenedAt: timeOf(k, "openedAt")})
			}
		}
	})
	run(func() {
		var page jobj
		if err := sc.get(ctx, sc.up.TrustOps, "/v1/audit/entries", url.Values{"entityId": {id}, "size": {"20"}}, &page); err != nil {
			miss("audit log")
			return
		}
		entries := objs(page, "items")
		nameAll(ctx, sc, entries, personRef("actorId", "Actor"))
		for _, e := range entries {
			out.Audit = append(out.Audit, domain.AuditLine{At: timeOf(e, "occurredAt"), Actor: jstr(e, "actorName"), Action: jstr(e, "action"), Summary: jstr(e, "summary")})
		}
	})
	wg.Wait()

	for i, o := range orders {
		so := domain.SupportOrder{ID: jstr(o, "id"), OrderNumber: jstr(o, "orderNumber"), Status: jstr(o, "status"), PlacedAt: timeOf(o, "placedAt"),
			TotalMinor: int64(num(o, "totalMinor")), Currency: firstNonEmpty(jstr(o, "currency"), "USD"), Shipments: []domain.SupportShipment{}}
		for _, sh := range objs(o, "shipments") {
			so.Shipments = append(so.Shipments, domain.SupportShipment{ID: firstNonEmpty(jstr(sh, "shipmentId"), jstr(sh, "id")), Label: jstr(sh, "label"),
				Status: jstr(sh, "status"), SellerName: jstr(sh, "sellerName"), Carrier: strPtr(jstr(sh, "carrier")), TrackingNumber: strPtr(jstr(sh, "trackingNumber")),
				Cancellable: boolean(sh, "cancellable"), TotalMinor: int64(num(sh, "totalMinor"))})
		}
		out.Orders = append(out.Orders, so)
		if i >= supportOrderLimit {
			continue
		}
		run(func() {
			var page jobj
			if err := sc.get(ctx, sc.up.TrustOps, "/v1/claims", url.Values{"orderId": {so.ID}, "size": {"20"}}, &page); err != nil {
				miss("claims " + so.OrderNumber)
				return
			}
			mu.Lock()
			defer mu.Unlock()
			for _, k := range objs(page, "items") {
				st := jstr(k, "status")
				var refund *int64
				if v, ok := k["refundMinor"].(float64); ok {
					refund = ptr(int64(v))
				}
				out.Claims = append(out.Claims, domain.SupportClaim{ID: jstr(k, "id"), Reference: jstr(k, "reference"), Type: jstr(k, "type"), Status: st,
					OrderNumber: firstNonEmpty(jstr(k, "orderNumber"), so.OrderNumber), AmountMinor: int64(num(k, "amountMinor")), RefundMinor: refund,
					Open: !strings.HasPrefix(st, "RESOLVED") && st != "WITHDRAWN"})
			}
		})
		run(func() {
			var list []jobj
			if err := sc.get(ctx, sc.up.TaxReturns, "/v1/returns", url.Values{"orderId": {so.ID}, "limit": {"20"}}, &list); err != nil {
				miss("returns " + so.OrderNumber)
				return
			}
			mu.Lock()
			defer mu.Unlock()
			for _, r := range list {
				out.Returns = append(out.Returns, domain.SupportReturn{ID: jstr(r, "id"), Number: jstr(r, "number"), Status: jstr(r, "status"),
					OrderNumber: firstNonEmpty(jstr(r, "orderNumber"), so.OrderNumber), RefundState: strPtr(jstr(r, "refundState")), EstimateMinor: int64(num(r, "estimateMinor"))})
			}
		})
	}
	wg.Wait()
	out.Unavailable = compactMisses(out.Unavailable)
	return out, nil
}

// compactMisses folds "claims AT-1", "claims AT-2" into "claims".
func compactMisses(in []string) []string {
	seen := map[string]bool{}
	out := []string{}
	for _, m := range in {
		name, _, _ := strings.Cut(m, " AT-")
		if !seen[name] {
			seen[name] = true
			out = append(out, name)
		}
	}
	return out
}

func (s *staffSupportImpl) customerOrder(ctx context.Context, sc staffCtx, customerID, number string) (jobj, error) {
	var o jobj
	if err := sc.get(ctx, sc.up.Orders, "/v1/orders/"+url.PathEscape(number), nil, &o); err != nil {
		return nil, err
	}
	if jstr(o, "customerId") != customerID {
		return nil, domain.NewProblem(http.StatusNotFound, domain.CodeNotFound, "Not found", "That order isn't this customer's.")
	}
	return o, nil
}

func (s *staffSupportImpl) Refund(ctx context.Context, c *domain.Caller, req SupportRefund, idem string) (domain.SupportActionResult, error) {
	errs := map[string]string{}
	if req.CustomerID == "" {
		errs["customerId"] = "Required"
	}
	if strings.TrimSpace(req.OrderNumber) == "" {
		errs["orderNumber"] = "Choose an order"
	}
	if req.AmountMinor < 1 {
		errs["amountMinor"] = "Enter an amount above zero"
	}
	if r := len([]rune(strings.TrimSpace(req.Reason))); r < 3 || r > 500 {
		errs["reason"] = "Give a reason (3 to 500 characters)"
	}
	switch req.Via {
	case "CLAIM":
		if req.ClaimID == "" {
			errs["claimId"] = "Choose the claim to refund"
		}
	case "ORDER":
	default:
		errs["via"] = "Refund through a claim or the order"
	}
	if len(idem) < 8 {
		errs["Idempotency-Key"] = "Send an Idempotency-Key of 8 characters or more"
	}
	if len(errs) > 0 {
		return domain.SupportActionResult{}, domain.ValidationProblem(errs)
	}
	sc, err := s.r.require(ctx, c, "support", domain.LevelEdit)
	if err != nil {
		return domain.SupportActionResult{}, err
	}
	sc.idem = idem
	if limit := domain.RefundLimit(sc.access.Roles); limit != nil && req.AmountMinor > *limit {
		return domain.SupportActionResult{}, domain.RefundLimitProblem(*limit)
	}
	order, err := s.customerOrder(ctx, sc, req.CustomerID, req.OrderNumber)
	if err != nil {
		return domain.SupportActionResult{}, err
	}
	amount := moneyLabel(req.AmountMinor)
	if req.Via == "CLAIM" {
		if !sc.access.Can("disputes:manage") {
			return domain.SupportActionResult{}, needsPermission("disputes:manage")
		}
		var claim jobj
		if err := sc.get(ctx, sc.up.TrustOps, "/v1/claims/"+url.PathEscape(req.ClaimID), nil, &claim); err != nil {
			return domain.SupportActionResult{}, err
		}
		if jstr(claim, "orderId") != jstr(order, "id") && jstr(claim, "orderNumber") != req.OrderNumber {
			return domain.SupportActionResult{}, domain.ValidationProblem(map[string]string{"claimId": "That claim is about another order"})
		}
		if st := jstr(claim, "status"); strings.HasPrefix(st, "RESOLVED") || st == "WITHDRAWN" {
			return domain.SupportActionResult{}, domain.NewProblem(http.StatusConflict, domain.CodeConflict, "Already decided", "That claim is already closed.")
		}
		if claimed := int64(num(claim, "amountMinor")); claimed > 0 && req.AmountMinor != claimed {
			return domain.SupportActionResult{}, domain.ValidationProblem(map[string]string{"amountMinor": "A claim refund is for the claimed amount (" + moneyLabel(claimed) + ")"})
		}
		body := jobj{"outcome": "REFUND", "note": strings.TrimSpace(req.Reason)}
		if p := jstr(order, "paymentId"); p != "" {
			body["paymentId"] = p
		}
		if err := sc.post(ctx, sc.up.TrustOps, "/v1/claims/"+url.PathEscape(req.ClaimID)+"/decision", body, nil); err != nil {
			return domain.SupportActionResult{}, err
		}
		return domain.SupportActionResult{OK: true, Message: "Claim " + jstr(claim, "reference") + " decided with a refund of " + amount + ".", Reference: strPtr(req.ClaimID), Audited: true}, nil
	}
	if !sc.access.Can("refunds:issue") {
		return domain.SupportActionResult{}, needsPermission("refunds:issue")
	}
	lines, err := spreadRefund(order, req.AmountMinor)
	if err != nil {
		return domain.SupportActionResult{}, err
	}
	var refund jobj
	if err := sc.post(ctx, sc.up.TaxReturns, "/v1/refunds", jobj{"orderId": jstr(order, "id"), "lines": lines, "reason": strings.TrimSpace(req.Reason)}, &refund); err != nil {
		return domain.SupportActionResult{}, err
	}
	return domain.SupportActionResult{OK: true, Message: "Refund of " + amount + " issued on " + req.OrderNumber + ".", Reference: strPtr(jstr(refund, "id")), Audited: true}, nil
}

// spreadRefund allocates a partial refund over the order's lines, line by line up to each line's item amount.
func spreadRefund(order jobj, amount int64) ([]jobj, error) {
	lines := []jobj{}
	left := amount
	for _, sh := range objs(order, "shipments") {
		for _, l := range objs(sh, "lines") {
			if left <= 0 {
				break
			}
			take := min(left, int64(num(l, "itemsMinor")-num(l, "discountMinor")))
			if take <= 0 {
				continue
			}
			lines = append(lines, jobj{"lineId": jstr(l, "id"), "itemsMinor": take})
			left -= take
		}
	}
	if left > 0 {
		return nil, domain.ValidationProblem(map[string]string{"amountMinor": fmt.Sprintf("More than the order's items (%s)", moneyLabel(amount-left))})
	}
	return lines, nil
}

func (s *staffSupportImpl) Resend(ctx context.Context, c *domain.Caller, notificationID, customerID, idem string) (domain.SupportActionResult, error) {
	if customerID == "" {
		return domain.SupportActionResult{}, domain.ValidationProblem(map[string]string{"customerId": "Required"})
	}
	sc, err := s.r.require(ctx, c, "support", domain.LevelEdit)
	if err != nil {
		return domain.SupportActionResult{}, err
	}
	sc.idem = idem
	var n jobj
	if err := sc.get(ctx, sc.up.Notifications, "/v1/notifications/"+url.PathEscape(notificationID), nil, &n); err != nil {
		return domain.SupportActionResult{}, err
	}
	if jstr(n, "recipientId") != customerID {
		return domain.SupportActionResult{}, domain.NewProblem(http.StatusNotFound, domain.CodeNotFound, "Not found", "No such notification for this customer.")
	}
	what := domain.Humanize(jstr(n, "templateKey")) + " by " + jstr(n, "channel")
	switch strings.ToUpper(jstr(n, "status")) {
	case "FAILED", "DEAD_LETTERED", "DEAD", "UNDELIVERED", "BOUNCED":
		if !sc.access.Can("notifications:manage") {
			return domain.SupportActionResult{}, needsPermission("notifications:manage")
		}
		if err := sc.post(ctx, sc.up.Notifications, "/v1/dead-letters/"+url.PathEscape(notificationID)+"/replay", jobj{}, nil); err != nil {
			return domain.SupportActionResult{}, err
		}
	default:
		if !sc.access.Can("notifications:send") {
			return domain.SupportActionResult{}, needsPermission("notifications:send")
		}
		body := jobj{"templateKey": jstr(n, "templateKey"), "channels": []string{jstr(n, "channel")}, "recipient": jobj{"id": customerID},
			"locale": jstr(n, "locale"), "market": jstr(n, "market")}
		if err := sc.post(ctx, sc.up.Notifications, "/v1/notifications", omitEmpty(body), nil); err != nil {
			return domain.SupportActionResult{}, err
		}
	}
	return domain.SupportActionResult{OK: true, Message: what + " queued again.", Reference: strPtr(notificationID), Audited: false}, nil
}

func (s *staffSupportImpl) CancelShipment(ctx context.Context, c *domain.Caller, shipmentID string, req SupportCancel, idem string) (domain.SupportActionResult, error) {
	errs := map[string]string{}
	if req.CustomerID == "" {
		errs["customerId"] = "Required"
	}
	if strings.TrimSpace(req.OrderNumber) == "" {
		errs["orderNumber"] = "Required"
	}
	if r := len([]rune(strings.TrimSpace(req.Reason))); r < 3 || r > 500 {
		errs["reason"] = "Give a reason (3 to 500 characters)"
	}
	if len(errs) > 0 {
		return domain.SupportActionResult{}, domain.ValidationProblem(errs)
	}
	sc, err := s.r.require(ctx, c, "support", domain.LevelEdit)
	if err != nil {
		return domain.SupportActionResult{}, err
	}
	sc.idem = idem
	if !sc.access.CanAny("orders:manage", "orders:update-status") {
		return domain.SupportActionResult{}, needsPermission("orders:manage")
	}
	order, err := s.customerOrder(ctx, sc, req.CustomerID, req.OrderNumber)
	if err != nil {
		return domain.SupportActionResult{}, err
	}
	var ship jobj
	for _, sh := range objs(order, "shipments") {
		if jstr(sh, "id") == shipmentID || jstr(sh, "shipmentId") == shipmentID {
			ship = sh
		}
	}
	if ship == nil {
		return domain.SupportActionResult{}, domain.NewProblem(http.StatusNotFound, domain.CodeNotFound, "Not found", "No such shipment on this order.")
	}
	if !boolean(ship, "cancellable") {
		return domain.SupportActionResult{}, domain.NewProblem(http.StatusConflict, domain.CodeConflict, "Too late to cancel", "This shipment can't be cancelled any more — it is already packed.")
	}
	if err := sc.post(ctx, sc.up.Orders, "/v1/shipments/"+url.PathEscape(shipmentID)+"/transitions", jobj{"to": "CANCELLED", "reason": strings.TrimSpace(req.Reason)}, nil); err != nil {
		return domain.SupportActionResult{}, err
	}
	return domain.SupportActionResult{OK: true, Message: firstNonEmpty(jstr(ship, "label"), "Shipment") + " of " + req.OrderNumber + " cancelled.", Reference: strPtr(shipmentID), Audited: true}, nil
}
