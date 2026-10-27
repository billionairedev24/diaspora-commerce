package app

import (
	"context"
	"net/http"
	"net/url"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
)

// Delivery-run locations from the staff console (S262): create, edit, switch off and back on. delivery-runs owns the
// locations; the BFF checks the module level first (EDIT with preorders:manage to change) and the shape of every body.
// Switching a location off needs a reason, which delivery-runs audits with the staff member's id, the time and the
// reason (event delivery-runs.location.changed). An edit never switches a location.

func locationPath(id string, rest ...string) string {
	p := locationsPath + "/" + url.PathEscape(id)
	for _, r := range rest {
		p += r
	}
	return p
}

// CreateRunLocation adds a town delivery runs serve.
func (s *staffPreordersImpl) CreateRunLocation(ctx context.Context, c *domain.Caller, body map[string]any) (any, error) {
	sent, err := domain.RunLocationBody(body, true)
	if err != nil {
		return nil, err
	}
	sc, can, err := s.need(ctx, c, poManage)
	if err != nil {
		return nil, err
	}
	out, err := sc.send(ctx, http.MethodPost, locationsPath, "", sent)
	if err != nil {
		return nil, err
	}
	return withCan(out, can), nil
}

// UpdateRunLocation replaces a location's details.
func (s *staffPreordersImpl) UpdateRunLocation(ctx context.Context, c *domain.Caller, id string, body map[string]any) (any, error) {
	if p := domain.ValidUUID(id); p != nil {
		return nil, p
	}
	sent, err := domain.RunLocationBody(body, false)
	if err != nil {
		return nil, err
	}
	sc, can, err := s.need(ctx, c, poManage)
	if err != nil {
		return nil, err
	}
	out, err := sc.send(ctx, http.MethodPut, locationPath(id), "", sent)
	if err != nil {
		return nil, err
	}
	return withCan(out, can), nil
}

// SwitchRunLocation switches a location off (a reason is required) or back on (the reason is optional).
func (s *staffPreordersImpl) SwitchRunLocation(ctx context.Context, c *domain.Caller, id string, on bool, body map[string]any) (any, error) {
	if p := domain.ValidUUID(id); p != nil {
		return nil, p
	}
	sent, err := domain.RunLocationSwitchBody(body, !on)
	if err != nil {
		return nil, err
	}
	sc, can, err := s.need(ctx, c, poManage)
	if err != nil {
		return nil, err
	}
	action := "/deactivate"
	if on {
		action = "/activate"
	}
	out, err := sc.send(ctx, http.MethodPost, locationPath(id, action), "", sent)
	if err != nil {
		return nil, err
	}
	return withCan(out, can), nil
}

// RunLocationAttribution reads the first-party UTM counters of a location, or of one of its runs (?runId=&days=). The
// counters keep only the three tags: no cookie, address or visitor id.
func (s *staffPreordersImpl) RunLocationAttribution(ctx context.Context, c *domain.Caller, id string, q url.Values) (any, error) {
	if p := domain.ValidUUID(id); p != nil {
		return nil, p
	}
	pq, err := passQuery(q, "runId", "days")
	if err != nil {
		return nil, err
	}
	if v := pq.Get("runId"); v != "" && domain.ValidUUID(v) != nil {
		return nil, domain.ValidationProblem(map[string]string{"runId": "Choose one of the runs"})
	}
	if v := pq.Get("days"); v != "" && !wholeBetween(v, 1, 366) {
		return nil, domain.ValidationProblem(map[string]string{"days": "Between 1 and 366"})
	}
	sc, _, err := s.need(ctx, c, poView)
	if err != nil {
		return nil, err
	}
	var out any
	if err := sc.get(ctx, sc.up.DeliveryRuns, locationPath(id, "/attribution"), pq, &out); err != nil {
		return nil, err
	}
	return out, nil
}
