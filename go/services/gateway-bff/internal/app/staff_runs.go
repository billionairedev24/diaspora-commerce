package app

import (
	"context"
	"errors"
	"net/http"
	"net/url"
	"strings"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
)

// Run economics (S265) and location advertorials (S263) are part of the staff console's Pre-orders module: delivery-runs
// owns them and the BFF relays them with the staff member's token, checking the module level first (View to read, Edit
// with preorders:manage to change) and the shape of every body so the console gets field-level answers. Changing the
// driver cost model needs a reason (logged with who and when) and the version the staff member saw (If-Match).
const (
	economicsSettingsPath = "/v1/economics-settings"
	locationsPath         = "/v1/locations"
	runsPath              = "/v1/runs"
	advertorialsPath      = "/v1/advertorials"
)

// EconomicsSettings is the driver cost model in force (version 0: the environment's defaults).
func (s *staffPreordersImpl) EconomicsSettings(ctx context.Context, c *domain.Caller) (any, error) {
	sc, can, err := s.need(ctx, c, poView)
	if err != nil {
		return nil, err
	}
	var out any
	if err := sc.get(ctx, sc.up.DeliveryRuns, economicsSettingsPath, nil, &out); err != nil {
		return nil, err
	}
	return withCan(out, can), nil
}

// UpdateEconomicsSettings saves new numbers as the next version ({driverCostPerHourMinor, driverCostPerMileMinor,
// collectionMinutes, perStopMinutes, pickupPointStopMinutes, handoverMinutesPerOrder, roundTrip, reason}).
func (s *staffPreordersImpl) UpdateEconomicsSettings(ctx context.Context, c *domain.Caller, ifMatch string, body map[string]any) (any, error) {
	sent, err := domain.EconomicsSettingsBody(body)
	reason, reasonErr := reasonOf(body)
	if err != nil || reasonErr != nil {
		// A missing reason is reported with the numbers' problems, so the form shows everything at once.
		return nil, domain.ValidationProblem(fieldProblems(err, reasonErr))
	}
	sent["reason"] = reason
	sc, can, err := s.need(ctx, c, poManage)
	if err != nil {
		return nil, err
	}
	out, err := sc.send(ctx, http.MethodPut, economicsSettingsPath, ifMatch, sent)
	if err != nil {
		return nil, err
	}
	return withCan(out, can), nil
}

// fieldProblems merges the field messages of validation problems.
func fieldProblems(errs ...error) map[string]string {
	fields := map[string]string{}
	for _, e := range errs {
		var p *domain.Problem
		if errors.As(e, &p) {
			for k, v := range p.Errors {
				fields[k] = v
			}
		}
	}
	return fields
}

// EconomicsChanges is the audit trail of the cost model, newest first.
func (s *staffPreordersImpl) EconomicsChanges(ctx context.Context, c *domain.Caller, q url.Values) (any, error) {
	pq, err := passQuery(q, "limit")
	if err != nil {
		return nil, err
	}
	if v := pq.Get("limit"); v != "" && !wholeBetween(v, 1, 200) {
		return nil, domain.ValidationProblem(map[string]string{"limit": "Between 1 and 200"})
	}
	sc, _, err := s.need(ctx, c, poView)
	if err != nil {
		return nil, err
	}
	var out any
	if err := sc.get(ctx, sc.up.DeliveryRuns, economicsSettingsPath+"/changes", pq, &out); err != nil {
		return nil, err
	}
	return items(out), nil
}

// WindowEconomics is a pre-order window's economics at its actual orders (a cancelled one: orders refunded).
func (s *staffPreordersImpl) WindowEconomics(ctx context.Context, c *domain.Caller, id string) (any, error) {
	if p := domain.ValidPreorderID(id); p != nil {
		return nil, p
	}
	sc, can, err := s.need(ctx, c, poView)
	if err != nil {
		return nil, err
	}
	var out any
	if err := sc.get(ctx, sc.up.DeliveryRuns, windowPath(id, "/economics"), nil, &out); err != nil {
		return nil, err
	}
	return withCan(out, can), nil
}

// PlanWindow is the break-even beside a window's minimum while the window is being created or edited.
func (s *staffPreordersImpl) PlanWindow(ctx context.Context, c *domain.Caller, body map[string]any) (any, error) {
	sent, err := domain.WindowPlanBody(body)
	if err != nil {
		return nil, err
	}
	sc, can, err := s.need(ctx, c, poView)
	if err != nil {
		return nil, err
	}
	out, err := sc.send(ctx, http.MethodPost, preordersPath+"/economics-preview", "", sent)
	if err != nil {
		return nil, err
	}
	return withCan(out, can), nil
}

// RunLocations lists the towns served by delivery runs.
func (s *staffPreordersImpl) RunLocations(ctx context.Context, c *domain.Caller) (any, error) {
	sc, can, err := s.need(ctx, c, poView)
	if err != nil {
		return nil, err
	}
	var out any
	if err := sc.get(ctx, sc.up.DeliveryRuns, locationsPath, nil, &out); err != nil {
		return nil, err
	}
	return withCan(out, can), nil
}

// RunLocationHistory is a location's runs with their orders and fees (a cancelled run: orders refunded), its
// subscriber counts and 12 months of subscriber growth.
func (s *staffPreordersImpl) RunLocationHistory(ctx context.Context, c *domain.Caller, id string) (any, error) {
	if p := domain.ValidUUID(id); p != nil {
		return nil, p
	}
	sc, _, err := s.need(ctx, c, poView)
	if err != nil {
		return nil, err
	}
	var out any
	if err := sc.get(ctx, sc.up.DeliveryRuns, locationsPath+"/"+url.PathEscape(id)+"/history", nil, &out); err != nil {
		return nil, err
	}
	return out, nil
}

// Runs lists runs, newest window first (?locationId=&status=&limit=).
func (s *staffPreordersImpl) Runs(ctx context.Context, c *domain.Caller, q url.Values) (any, error) {
	f, err := domain.RunListQuery(strings.TrimSpace(q.Get("locationId")), strings.TrimSpace(q.Get("status")), strings.TrimSpace(q.Get("limit")))
	if err != nil {
		return nil, err
	}
	sc, _, err := s.need(ctx, c, poView)
	if err != nil {
		return nil, err
	}
	pq := url.Values{}
	for k, v := range f {
		pq.Set(k, v)
	}
	var out any
	if err := sc.get(ctx, sc.up.DeliveryRuns, runsPath, pq, &out); err != nil {
		return nil, err
	}
	return items(out), nil
}

// RunPnl is a run's P&L before the run, after it, or what a cancelled run refunded.
func (s *staffPreordersImpl) RunPnl(ctx context.Context, c *domain.Caller, id string, q url.Values) (any, error) {
	if p := domain.ValidUUID(id); p != nil {
		return nil, p
	}
	pq, err := passQuery(q, "commissionShareMinor")
	if err != nil {
		return nil, err
	}
	if v := pq.Get("commissionShareMinor"); v != "" && !wholeBetween(v, 0, 1_000_000_000) {
		return nil, domain.ValidationProblem(map[string]string{"commissionShareMinor": "Enter an amount of 0 or more"})
	}
	sc, _, err := s.need(ctx, c, poView)
	if err != nil {
		return nil, err
	}
	var out any
	if err := sc.get(ctx, sc.up.DeliveryRuns, runsPath+"/"+url.PathEscape(id)+"/pnl", pq, &out); err != nil {
		return nil, err
	}
	return out, nil
}

// PlanRun is the planner for a run at a location: cost, break-even and P&L at the minimum and at the expected orders.
func (s *staffPreordersImpl) PlanRun(ctx context.Context, c *domain.Caller, body map[string]any) (any, error) {
	sent, err := domain.RunPlanBody(body)
	if err != nil {
		return nil, err
	}
	sc, _, err := s.need(ctx, c, poView)
	if err != nil {
		return nil, err
	}
	return sc.send(ctx, http.MethodPost, runsPath+"/economics-preview", "", sent)
}

// Advertorials lists location advertorials with their results (?source=&sourceId=&status=&limit=).
func (s *staffPreordersImpl) Advertorials(ctx context.Context, c *domain.Caller, q url.Values) (any, error) {
	f, err := domain.AdvertorialListQuery(strings.TrimSpace(q.Get("source")), strings.TrimSpace(q.Get("sourceId")),
		strings.TrimSpace(q.Get("status")), strings.TrimSpace(q.Get("limit")))
	if err != nil {
		return nil, err
	}
	sc, can, err := s.need(ctx, c, poView)
	if err != nil {
		return nil, err
	}
	pq := url.Values{}
	for k, v := range f {
		pq.Set(k, v)
	}
	var out any
	if err := sc.get(ctx, sc.up.DeliveryRuns, advertorialsPath, pq, &out); err != nil {
		return nil, err
	}
	return withCan(out, can), nil
}

// Advertorial is one advertorial with its results (ETag = its version).
func (s *staffPreordersImpl) Advertorial(ctx context.Context, c *domain.Caller, id string) (any, error) {
	if p := domain.ValidUUID(id); p != nil {
		return nil, p
	}
	sc, can, err := s.need(ctx, c, poView)
	if err != nil {
		return nil, err
	}
	var out any
	if err := sc.get(ctx, sc.up.DeliveryRuns, advertorialsPath+"/"+url.PathEscape(id), nil, &out); err != nil {
		return nil, err
	}
	return withCan(out, can), nil
}

// GenerateAdvertorial drafts the advertorial of a window or run from its data ({source, sourceId}), live at once.
func (s *staffPreordersImpl) GenerateAdvertorial(ctx context.Context, c *domain.Caller, body map[string]any) (any, error) {
	sent, err := domain.GenerateAdvertorialBody(body)
	if err != nil {
		return nil, err
	}
	sc, can, err := s.need(ctx, c, poManage)
	if err != nil {
		return nil, err
	}
	out, err := sc.send(ctx, http.MethodPost, advertorialsPath+"/generate", "", sent)
	if err != nil {
		return nil, err
	}
	return withCan(out, can), nil
}

// CreateAdvertorial writes a draft variant for a window or run.
func (s *staffPreordersImpl) CreateAdvertorial(ctx context.Context, c *domain.Caller, body map[string]any) (any, error) {
	sent, err := domain.AdvertorialBody(body, true)
	if err != nil {
		return nil, err
	}
	sc, can, err := s.need(ctx, c, poManage)
	if err != nil {
		return nil, err
	}
	out, err := sc.send(ctx, http.MethodPost, advertorialsPath, "", sent)
	if err != nil {
		return nil, err
	}
	return withCan(out, can), nil
}

// UpdateAdvertorial edits a variant (If-Match is the version seen).
func (s *staffPreordersImpl) UpdateAdvertorial(ctx context.Context, c *domain.Caller, id, ifMatch string, body map[string]any) (any, error) {
	if p := domain.ValidUUID(id); p != nil {
		return nil, p
	}
	sent, err := domain.AdvertorialBody(body, false)
	if err != nil {
		return nil, err
	}
	sc, can, err := s.need(ctx, c, poManage)
	if err != nil {
		return nil, err
	}
	out, err := sc.send(ctx, http.MethodPut, advertorialsPath+"/"+url.PathEscape(id), ifMatch, sent)
	if err != nil {
		return nil, err
	}
	return withCan(out, can), nil
}

// ActAdvertorial publishes (draft or paused to live), pauses or ends an advertorial.
func (s *staffPreordersImpl) ActAdvertorial(ctx context.Context, c *domain.Caller, id, action, ifMatch string) (any, error) {
	if p := domain.ValidUUID(id); p != nil {
		return nil, p
	}
	act, ok := domain.ValidAdvertorialAction(action)
	if !ok {
		return nil, domain.NewProblem(http.StatusNotFound, domain.CodeNotFound, "Not found", "No such action.")
	}
	sc, can, err := s.need(ctx, c, poManage)
	if err != nil {
		return nil, err
	}
	out, err := sc.send(ctx, http.MethodPost, advertorialsPath+"/"+url.PathEscape(id)+"/"+act, ifMatch, nil)
	if err != nil {
		return nil, err
	}
	return withCan(out, can), nil
}
