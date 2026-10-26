package app

import (
	"context"
	"net/http"
	"net/url"
	"strings"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
)

// Delivery runs from the staff console (S260): schedule, change, announce, open, extend, confirm and cancel a run.
// delivery-runs owns the lifecycle and its rules; the BFF checks the module level first (View to read, EDIT with
// preorders:manage to change), the shape of every body (field-level answers) and that a decision carries its reason.
// Extensions, confirmations and cancellations are logged by delivery-runs with the staff member's id, the time and the
// reason; the BFF turns the id into a name when the log is read.

// runLocation is a run's location with its area, from delivery-runs, or nil when it cannot be read: the run itself is
// still worth showing.
func (s *staffPreordersImpl) runLocation(ctx context.Context, sc staffCtx, id string) map[string]any {
	if id == "" {
		return nil
	}
	var loc any
	if err := sc.get(ctx, sc.up.DeliveryRuns, locationsPath+"/"+url.PathEscape(id), nil, &loc); err != nil {
		return nil
	}
	l, _ := loc.(map[string]any)
	return l
}

// runDetail completes a run read from delivery-runs: its location (name and area), what its status permits and what this
// staff member may do.
func (s *staffPreordersImpl) runDetail(ctx context.Context, sc staffCtx, can domain.PreorderCan, out any) any {
	run, ok := out.(map[string]any)
	if !ok {
		return out
	}
	run["allowed"] = domain.RunActions(jstr(run, "status"))
	run["can"] = can
	if l := s.runLocation(ctx, sc, jstr(run, "locationId")); l != nil {
		run["location"] = l
		run["locationName"] = jstr(l, "name")
	}
	return run
}

func runPath(id string, rest ...string) string {
	return runsPath + "/" + url.PathEscape(id) + strings.Join(rest, "")
}

// Run is one run with its location, break-even and what staff may do with it.
func (s *staffPreordersImpl) Run(ctx context.Context, c *domain.Caller, id string) (any, error) {
	if p := domain.ValidUUID(id); p != nil {
		return nil, p
	}
	sc, can, err := s.need(ctx, c, poView)
	if err != nil {
		return nil, err
	}
	var out any
	if err := sc.get(ctx, sc.up.DeliveryRuns, runPath(id), nil, &out); err != nil {
		return nil, err
	}
	return s.runDetail(ctx, sc, can, out), nil
}

// CreateRun schedules a run (a draft) for a location.
func (s *staffPreordersImpl) CreateRun(ctx context.Context, c *domain.Caller, body map[string]any) (any, error) {
	sent, err := domain.RunScheduleBody(body, true)
	if err != nil {
		return nil, err
	}
	sc, can, err := s.need(ctx, c, poManage)
	if err != nil {
		return nil, err
	}
	out, err := sc.send(ctx, http.MethodPost, runsPath, "", sent)
	if err != nil {
		return nil, err
	}
	return s.runDetail(ctx, sc, can, out), nil
}

// UpdateRun changes a run before it opens (an open run keeps its fee, cutoff and drop mode).
func (s *staffPreordersImpl) UpdateRun(ctx context.Context, c *domain.Caller, id string, body map[string]any) (any, error) {
	if p := domain.ValidUUID(id); p != nil {
		return nil, p
	}
	sent, err := domain.RunScheduleBody(body, false)
	if err != nil {
		return nil, err
	}
	sc, can, err := s.need(ctx, c, poManage)
	if err != nil {
		return nil, err
	}
	out, err := sc.send(ctx, http.MethodPut, runPath(id), "", sent)
	if err != nil {
		return nil, err
	}
	return s.runDetail(ctx, sc, can, out), nil
}

// DeleteRun deletes a draft.
func (s *staffPreordersImpl) DeleteRun(ctx context.Context, c *domain.Caller, id string) error {
	if p := domain.ValidUUID(id); p != nil {
		return p
	}
	sc, _, err := s.need(ctx, c, poManage)
	if err != nil {
		return err
	}
	_, err = sc.send(ctx, http.MethodDelete, runPath(id), "", nil)
	return err
}

// ActRun announces a draft or opens a draft or announced run.
func (s *staffPreordersImpl) ActRun(ctx context.Context, c *domain.Caller, id, action string) (any, error) {
	if p := domain.ValidUUID(id); p != nil {
		return nil, p
	}
	if action != "announce" && action != "open" {
		return nil, domain.NewProblem(http.StatusNotFound, domain.CodeNotFound, "Not found", "No such action.")
	}
	sc, can, err := s.need(ctx, c, poManage)
	if err != nil {
		return nil, err
	}
	out, err := sc.send(ctx, http.MethodPost, runPath(id, "/", action), "", nil)
	if err != nil {
		return nil, err
	}
	return s.runDetail(ctx, sc, can, out), nil
}

// ExtendRun moves the cutoff later; the reason is required and logged with who and when.
func (s *staffPreordersImpl) ExtendRun(ctx context.Context, c *domain.Caller, id string, body map[string]any) (any, error) {
	if p := domain.ValidUUID(id); p != nil {
		return nil, p
	}
	sent, err := domain.RunExtendBody(body)
	if err != nil {
		return nil, err
	}
	sc, can, err := s.need(ctx, c, poManage)
	if err != nil {
		return nil, err
	}
	out, err := sc.send(ctx, http.MethodPost, runPath(id, "/extend"), "", sent)
	if err != nil {
		return nil, err
	}
	return s.runDetail(ctx, sc, can, out), nil
}

// ConfirmRun confirms a run now. A run below its minimum is confirmed only with a reason (the override is logged); the
// run is read first to know, so the answer names the field instead of failing after the fact.
func (s *staffPreordersImpl) ConfirmRun(ctx context.Context, c *domain.Caller, id string, body map[string]any) (any, error) {
	if p := domain.ValidUUID(id); p != nil {
		return nil, p
	}
	reason, tooLong := "", ""
	if _, has := body["reason"]; has && body["reason"] != nil {
		reason, tooLong = domain.RunReason(body, "")
	}
	if tooLong != "" {
		return nil, domain.ValidationProblem(map[string]string{"reason": tooLong})
	}
	sc, can, err := s.need(ctx, c, poManage)
	if err != nil {
		return nil, err
	}
	var current any
	if err := sc.get(ctx, sc.up.DeliveryRuns, runPath(id), nil, &current); err != nil {
		return nil, err
	}
	if run, ok := current.(map[string]any); ok && reason == "" {
		if met, _ := run["minimumMet"].(bool); !met {
			return nil, domain.ValidationProblem(map[string]string{"reason": "This run has not reached its minimum orders. Say why you are confirming it anyway: the reason is logged with your name"})
		}
	}
	sent := map[string]any{}
	if reason != "" {
		sent["reason"] = reason
	}
	out, err := sc.send(ctx, http.MethodPost, runPath(id, "/confirm"), "", sent)
	if err != nil {
		return nil, err
	}
	return s.runDetail(ctx, sc, can, out), nil
}

// CancelRun cancels a run; every order on it is refunded in full by orders-service and customers are told. The reason is
// required, logged with who and when, and shown to the customers.
func (s *staffPreordersImpl) CancelRun(ctx context.Context, c *domain.Caller, id string, body map[string]any) (any, error) {
	if p := domain.ValidUUID(id); p != nil {
		return nil, p
	}
	reason, problem := domain.RunReason(body, "Say why: the reason is logged with your name and shown to the customers")
	if problem != "" {
		return nil, domain.ValidationProblem(map[string]string{"reason": problem})
	}
	sc, can, err := s.need(ctx, c, poManage)
	if err != nil {
		return nil, err
	}
	out, err := sc.send(ctx, http.MethodPost, runPath(id, "/cancel"), "", map[string]any{"reason": reason})
	if err != nil {
		return nil, err
	}
	return s.runDetail(ctx, sc, can, out), nil
}

// systemActor is how delivery-runs names the cutoff job in a run's log.
const systemActor = "system"

// RunLog is what was decided about a run, oldest first, with the staff member's name (never their id).
func (s *staffPreordersImpl) RunLog(ctx context.Context, c *domain.Caller, id string) (any, error) {
	if p := domain.ValidUUID(id); p != nil {
		return nil, p
	}
	sc, _, err := s.need(ctx, c, poView)
	if err != nil {
		return nil, err
	}
	var out any
	if err := sc.get(ctx, sc.up.DeliveryRuns, runPath(id, "/log"), nil, &out); err != nil {
		return nil, err
	}
	list, _ := out.([]any)
	for _, e := range list {
		if entry, ok := e.(map[string]any); ok {
			if jstr(entry, "actor") == systemActor {
				entry["actor"], entry["bySystem"] = "System", true
			} else {
				entry["bySystem"] = false
			}
		}
	}
	return items(out), nil
}

// RunOrders are the orders on a run, by order number and customer name.
func (s *staffPreordersImpl) RunOrders(ctx context.Context, c *domain.Caller, id string, q url.Values) (any, error) {
	if p := domain.ValidUUID(id); p != nil {
		return nil, p
	}
	status, err := domain.RunOrdersQuery(strings.TrimSpace(q.Get("status")))
	if err != nil {
		return nil, err
	}
	sc, _, err := s.need(ctx, c, poView)
	if err != nil {
		return nil, err
	}
	pq := url.Values{}
	if status != "" {
		pq.Set("status", status)
	}
	var out any
	if err := sc.get(ctx, sc.up.DeliveryRuns, runPath(id, "/orders"), pq, &out); err != nil {
		return nil, err
	}
	list, _ := out.([]any)
	rows := make([]jobj, 0, len(list))
	for _, e := range list {
		if row, ok := e.(map[string]any); ok {
			// The customer's pickup-point handover code is theirs and the driver's: it never reaches this list.
			delete(row, "handoverCode")
			rows = append(rows, row)
		}
	}
	nameAll(ctx, sc, rows, customerRef("customerId"))
	return items(out), nil
}

// nameRuns puts the town on every run of a list (a run knows its location by id only).
func (s *staffPreordersImpl) nameRuns(ctx context.Context, sc staffCtx, out any) {
	list, _ := out.([]any)
	if len(list) == 0 {
		return
	}
	var locs any
	if err := sc.get(ctx, sc.up.DeliveryRuns, locationsPath, nil, &locs); err != nil {
		return
	}
	names := map[string]string{}
	all, _ := locs.([]any)
	for _, e := range all {
		if l, ok := e.(map[string]any); ok {
			names[jstr(l, "id")] = jstr(l, "name")
		}
	}
	for _, e := range list {
		if run, ok := e.(map[string]any); ok {
			if name := names[jstr(run, "locationId")]; name != "" {
				run["locationName"] = name
			}
		}
	}
}
