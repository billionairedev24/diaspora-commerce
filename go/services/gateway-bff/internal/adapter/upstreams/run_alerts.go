package upstreams

import (
	"context"
	"encoding/json"
	"net/http"
	"net/url"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/adapter/httpclient"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

// runAlertsImpl calls delivery-runs' public and /v1/me routes (contracts/openapi/delivery-runs.yaml, tags Public
// subscriptions, Public runs and Public advertorials). Nothing is cached: subscriptions are personal and the
// advertorials carry the visitor's variant.
type runAlertsImpl struct{ c httpclient.Client }

var _ port.RunAlertClient = (*runAlertsImpl)(nil)

// NewRunAlerts returns the delivery-runs client for location subscriptions and advertorials.
func NewRunAlerts(c httpclient.Client) port.RunAlertClient { return &runAlertsImpl{c: c} }

func (r *runAlertsImpl) send(ctx context.Context, req httpclient.Request) (json.RawMessage, error) {
	res, err := call(ctx, r.c, req, nil)
	if err != nil {
		return nil, err
	}
	if len(res.Body) == 0 || !json.Valid(res.Body) {
		return json.RawMessage("{}"), nil
	}
	return json.RawMessage(res.Body), nil
}

func (r *runAlertsImpl) Locations(ctx context.Context) (json.RawMessage, error) {
	return r.send(ctx, httpclient.Request{Path: "/v1/public/locations"})
}

func (r *runAlertsImpl) LocationPage(ctx context.Context, slug string) (json.RawMessage, error) {
	return r.send(ctx, httpclient.Request{Path: "/v1/public/locations/" + url.PathEscape(slug)})
}

func (r *runAlertsImpl) SubscribeEmail(ctx context.Context, locationID, email, source string, utm map[string]string) (json.RawMessage, error) {
	body := map[string]string{"locationId": locationID, "email": email, "source": source}
	for k, v := range utm {
		body[k] = v
	}
	return r.send(ctx, httpclient.Request{Method: http.MethodPost, Path: "/v1/public/subscriptions", JSON: body})
}

func (r *runAlertsImpl) CountVisit(ctx context.Context, slug string, body map[string]string) error {
	_, err := r.send(ctx, httpclient.Request{Method: http.MethodPost, Path: "/v1/public/locations/" + url.PathEscape(slug) + "/visits", JSON: body})
	return err
}

func (r *runAlertsImpl) Confirm(ctx context.Context, token string) (json.RawMessage, error) {
	return r.send(ctx, httpclient.Request{Method: http.MethodPost, Path: "/v1/public/subscriptions/confirm",
		JSON: map[string]string{"token": token}})
}

func (r *runAlertsImpl) Unsubscribe(ctx context.Context, token string, channels []string) (json.RawMessage, error) {
	body := map[string]any{"token": token}
	if len(channels) > 0 {
		body["channels"] = channels
	}
	return r.send(ctx, httpclient.Request{Method: http.MethodPost, Path: "/v1/public/subscriptions/unsubscribe", JSON: body})
}

func (r *runAlertsImpl) Mine(ctx context.Context, bearer string) (json.RawMessage, error) {
	return r.send(ctx, httpclient.Request{Path: "/v1/me/run-subscriptions", Bearer: bearer})
}

func (r *runAlertsImpl) SubscribeMine(ctx context.Context, bearer, locationID string, channels []string, source string, utm map[string]string) (json.RawMessage, error) {
	body := map[string]any{"locationId": locationID, "channels": channels, "source": source}
	for k, v := range utm {
		body[k] = v
	}
	return r.send(ctx, httpclient.Request{Method: http.MethodPost, Path: "/v1/me/run-subscriptions", Bearer: bearer, JSON: body})
}

func (r *runAlertsImpl) UnsubscribeMine(ctx context.Context, bearer, locationID string, channels []string) (json.RawMessage, error) {
	q := url.Values{}
	for _, ch := range channels {
		q.Add("channel", ch)
	}
	return r.send(ctx, httpclient.Request{Method: http.MethodDelete, Path: "/v1/me/run-subscriptions/" + url.PathEscape(locationID),
		Query: q, Bearer: bearer})
}

func (r *runAlertsImpl) Advertorials(ctx context.Context, q url.Values) (json.RawMessage, error) {
	return r.send(ctx, httpclient.Request{Path: "/v1/public/advertorials", Query: q})
}

func (r *runAlertsImpl) AdvertorialEvent(ctx context.Context, id, eventType string) error {
	_, err := r.send(ctx, httpclient.Request{Method: http.MethodPost, Path: "/v1/public/advertorials/" + url.PathEscape(id) + "/events",
		JSON: map[string]string{"type": eventType}})
	return err
}
