package app

import (
	"context"
	"net/http"
	"net/url"
	"strings"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
)

// Delivery runs in the Dispatch module (S264). fulfilment plans a confirmed run into routes (seller pickups before drops,
// split by capacity) and follows it through the driver flow; delivery-runs moves the run itself (collecting, out for
// delivery, completed) from the drivers' shipment events. This console shows both: fulfilment's routes and progress with
// the run's lifecycle and its orders per stage from delivery-runs. Reading needs VIEW on dispatch; planning a run and
// moving it by hand need EDIT and delivery:dispatch (and delivery-runs:manage upstream for the move).

const (
	runsPath       = "/v1/dispatch/runs"
	runsLimit      = "50"
	deliveryRuns   = "/v1/runs/"
	runListStates  = "CONFIRMED,COLLECTING,OUT_FOR_DELIVERY,COMPLETED,CANCELLED"
	runIDMaxLength = 64
)

// runAdvanceSteps maps the step staff may force to delivery-runs' transition.
var runAdvanceSteps = map[string]string{
	"COLLECTING":       "start-collecting",
	"OUT_FOR_DELIVERY": "out-for-delivery",
	"COMPLETED":        "complete",
}

func validRunID(id string) error {
	if id == "" || len(id) > runIDMaxLength || strings.ContainsAny(id, "/?#") {
		return domain.NewProblem(http.StatusNotFound, domain.CodeNotFound, "Not found", "No such run.")
	}
	return nil
}

func (s *staffDispatchImpl) Runs(ctx context.Context, c *domain.Caller, status string) (domain.RunDispatchList, error) {
	if status != "" && status != "PENDING" && status != "PLANNED" && status != "CANCELLED" {
		return domain.RunDispatchList{}, domain.ValidationProblem(map[string]string{"status": "Unknown status"})
	}
	sc, err := s.r.require(ctx, c, dispatchModule, domain.LevelView)
	if err != nil {
		return domain.RunDispatchList{}, err
	}
	q := url.Values{"limit": {runsLimit}}
	if status != "" {
		q.Set("status", status)
	}
	var up []jobj
	if err := sc.get(ctx, sc.up.Fulfilment, runsPath, q, &up); err != nil {
		return domain.RunDispatchList{}, err
	}
	states := s.runStates(ctx, sc)
	can := canManageRuns(sc.access)
	out := domain.RunDispatchList{Items: []domain.RunDispatch{}, CanManage: can}
	for _, r := range up {
		run := runDispatchOf(r, can)
		if st, ok := states[run.RunID]; ok {
			run.RunStatus = strPtr(st)
		}
		out.Items = append(out.Items, run)
	}
	return out, nil
}

func (s *staffDispatchImpl) Run(ctx context.Context, c *domain.Caller, runID string) (domain.RunDispatch, error) {
	if err := validRunID(runID); err != nil {
		return domain.RunDispatch{}, err
	}
	sc, err := s.r.require(ctx, c, dispatchModule, domain.LevelView)
	if err != nil {
		return domain.RunDispatch{}, err
	}
	return s.runDetail(ctx, sc, runID)
}

func (s *staffDispatchImpl) PlanRun(ctx context.Context, c *domain.Caller, runID string) (domain.RunDispatch, error) {
	if err := validRunID(runID); err != nil {
		return domain.RunDispatch{}, err
	}
	sc, err := s.r.require(ctx, c, dispatchModule, domain.LevelEdit)
	if err != nil {
		return domain.RunDispatch{}, err
	}
	if !sc.access.Can(deliveryDispatch) {
		return domain.RunDispatch{}, needsPermission(deliveryDispatch)
	}
	var up jobj
	if err := sc.post(ctx, sc.up.Fulfilment, runsPath+"/"+url.PathEscape(runID)+"/plan", nil, &up); err != nil {
		return domain.RunDispatch{}, err
	}
	return s.withLifecycle(ctx, sc, runDispatchOf(up, true), runID), nil
}

func (s *staffDispatchImpl) AdvanceRun(ctx context.Context, c *domain.Caller, runID string, req domain.RunAdvanceRequest) (domain.RunDispatch, error) {
	if err := validRunID(runID); err != nil {
		return domain.RunDispatch{}, err
	}
	step, ok := runAdvanceSteps[strings.ToUpper(strings.TrimSpace(req.To))]
	if !ok {
		return domain.RunDispatch{}, domain.ValidationProblem(map[string]string{"to": "Choose collecting, out for delivery or completed"})
	}
	sc, err := s.r.require(ctx, c, dispatchModule, domain.LevelEdit)
	if err != nil {
		return domain.RunDispatch{}, err
	}
	if !sc.access.Can(deliveryDispatch) {
		return domain.RunDispatch{}, needsPermission(deliveryDispatch)
	}
	if sc.up.DeliveryRuns == nil {
		return domain.RunDispatch{}, domain.UpstreamUnavailable("delivery-runs")
	}
	if err := sc.post(ctx, sc.up.DeliveryRuns, deliveryRuns+url.PathEscape(runID)+"/"+step, nil, nil); err != nil {
		if p, ok := domain.AsProblem(err); ok && p.Status == http.StatusConflict {
			return domain.RunDispatch{}, domain.NewProblem(http.StatusConflict, domain.CodeConflict, "Can't move this run",
				"That step does not follow where the run is now.").WithCause(err)
		}
		return domain.RunDispatch{}, err
	}
	return s.runDetail(ctx, sc, runID)
}

// runDetail is fulfilment's run (routes with every stop) with the run's lifecycle and orders per stage from
// delivery-runs, which are left out when it cannot be read.
func (s *staffDispatchImpl) runDetail(ctx context.Context, sc staffCtx, runID string) (domain.RunDispatch, error) {
	var up jobj
	if err := sc.get(ctx, sc.up.Fulfilment, runsPath+"/"+url.PathEscape(runID), nil, &up); err != nil {
		return domain.RunDispatch{}, err
	}
	return s.withLifecycle(ctx, sc, runDispatchOf(up, canManageRuns(sc.access)), runID), nil
}

func (s *staffDispatchImpl) withLifecycle(ctx context.Context, sc staffCtx, run domain.RunDispatch, runID string) domain.RunDispatch {
	if sc.up.DeliveryRuns == nil {
		return run
	}
	var r jobj
	if err := sc.get(ctx, sc.up.DeliveryRuns, deliveryRuns+url.PathEscape(runID), nil, &r); err == nil && jstr(r, "status") != "" {
		run.RunStatus = strPtr(strings.ToUpper(jstr(r, "status")))
	} else if err != nil {
		s.r.d.Log.WarnContext(ctx, "run lifecycle unavailable", "run", runID, "error", err)
	}
	var p jobj
	if err := sc.get(ctx, sc.up.DeliveryRuns, deliveryRuns+url.PathEscape(runID)+"/progress", nil, &p); err == nil && p != nil {
		run.Orders = &domain.RunOrderProgress{Orders: int(num(p, "orders")), Pending: int(num(p, "pending")),
			Collected: int(num(p, "collected")), OutForDelivery: int(num(p, "outForDelivery")), Failed: int(num(p, "failed")),
			Delivered: int(num(p, "delivered")), Returned: int(num(p, "returned")), LastDeliveredAt: optTime(jstr(p, "lastDeliveredAt"))}
	}
	return run
}

// runStates is the lifecycle status of every run delivery-runs holds (one call for the list; empty when unreadable).
func (s *staffDispatchImpl) runStates(ctx context.Context, sc staffCtx) map[string]string {
	out := map[string]string{}
	if sc.up.DeliveryRuns == nil {
		return out
	}
	var runs []jobj
	q := url.Values{"limit": {"500"}, "status": strings.Split(runListStates, ",")}
	if err := sc.get(ctx, sc.up.DeliveryRuns, "/v1/runs", q, &runs); err != nil {
		s.r.d.Log.WarnContext(ctx, "run lifecycles unavailable", "error", err)
		return out
	}
	for _, r := range runs {
		if id := jstr(r, "id"); id != "" {
			out[id] = strings.ToUpper(jstr(r, "status"))
		}
	}
	return out
}

func canManageRuns(a domain.StaffAccess) bool {
	return a.Can(deliveryDispatch) && a.Allows(dispatchModule, domain.LevelEdit)
}

// runDispatchOf maps fulfilment's run (stops are present on the detail only).
func runDispatchOf(o jobj, canManage bool) domain.RunDispatch {
	run := domain.RunDispatch{RunID: jstr(o, "runId"), Town: jstr(o, "town"), State: jstr(o, "state"), TimeZone: jstr(o, "timeZone"),
		ServiceDate: jstr(o, "serviceDate"), WindowStart: timeOf(o, "windowStart"), WindowEnd: timeOf(o, "windowEnd"),
		SellerPickupAt: optTime(jstr(o, "sellerPickupAt")), DropMode: strings.ToUpper(firstNonEmpty(jstr(o, "dropMode"), "DOORSTEP")),
		Status: strings.ToUpper(firstNonEmpty(jstr(o, "status"), "PENDING")), PlanError: strPtr(jstr(o, "planError")),
		PlannedAt: optTime(jstr(o, "plannedAt")), Routes: []domain.RunDispatchRoute{}, CanManage: canManage,
		Progress: domain.RunDispatchProgress{Orders: int(num(o, "progress.orders")), Bags: int(num(o, "progress.bags")),
			PickupStops: int(num(o, "progress.pickupStops")), PickupsDone: int(num(o, "progress.pickupsDone")),
			BagsCollected: int(num(o, "progress.bagsCollected")), DropStops: int(num(o, "progress.dropStops")),
			DropsDone: int(num(o, "progress.dropsDone")), DropsFailed: int(num(o, "progress.dropsFailed")),
			BagsDelivered: int(num(o, "progress.bagsDelivered")), Unrouted: int(num(o, "progress.unrouted"))}}
	if p, ok := o["pickupPoint"].(map[string]any); ok && p != nil {
		run.PickupPoint = &domain.RunPickupPoint{Name: jstr(p, "name"), Line1: jstr(p, "line1"), City: jstr(p, "city"),
			State: jstr(p, "state"), PostalCode: jstr(p, "postalCode"), Hours: jstr(p, "hours"), Notes: jstr(p, "notes")}
	}
	for _, r := range objs(o, "routes") {
		route := domain.RunDispatchRoute{ID: jstr(r, "id"), Name: jstr(r, "name"), Status: strings.ToUpper(jstr(r, "status")),
			DriverID: strPtr(jstr(r, "driverId")), VehicleID: strPtr(jstr(r, "vehicleId")), Blocked: boolean(r, "blocked"),
			Packages: int(num(r, "packages")), BagsCollected: int(num(r, "bagsCollected")), BagsDelivered: int(num(r, "bagsDelivered")),
			PickupStops: int(num(r, "pickupStops")), PickupsDone: int(num(r, "pickupsDone")), DropStops: int(num(r, "dropStops")),
			DropsDone: int(num(r, "dropsDone")), DropsFailed: int(num(r, "dropsFailed")), Stops: []domain.RunDispatchStop{}}
		for _, st := range objs(r, "stops") {
			status := strings.ToUpper(jstr(st, "status"))
			if status == "CANCELLED" || status == "REASSIGNED" {
				continue
			}
			route.Stops = append(route.Stops, domain.RunDispatchStop{ID: jstr(st, "id"), Sequence: int(num(st, "sequence")),
				Kind: strings.ToUpper(jstr(st, "kind")), Name: jstr(st, "name"), Status: status, OrderNumber: strPtr(jstr(st, "orderNumber")),
				AddressLine: strPtr(jstr(st, "addressLine")), HandoverRequired: boolean(st, "handoverRequired"), Note: strPtr(jstr(st, "note"))})
		}
		run.Routes = append(run.Routes, route)
	}
	return run
}
