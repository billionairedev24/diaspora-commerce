package upstreams

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"net/url"
	"strings"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/adapter/httpclient"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

// preordersImpl calls delivery-runs' public pre-order routes (contracts/openapi/delivery-runs.yaml, tag Public
// pre-orders). Resolutions and the open list are public data, cached briefly (countdowns are computed by the client).
type preordersImpl struct {
	c     httpclient.Client
	cache port.Cache
	ttl   time.Duration
}

var _ port.PreorderClient = (*preordersImpl)(nil)

// NewPreorders returns the delivery-runs public client; ttl ≤ 0 disables caching.
func NewPreorders(c httpclient.Client, cache port.Cache, ttl time.Duration) port.PreorderClient {
	return &preordersImpl{c: c, cache: cache, ttl: ttl}
}

func (p *preordersImpl) cached(ctx context.Context, key, path string, q url.Values) (json.RawMessage, error) {
	if p.cache != nil && p.ttl > 0 {
		if b, ok := p.cache.Get(key); ok {
			return json.RawMessage(b), nil
		}
	}
	res, err := call(ctx, p.c, httpclient.Request{Path: path, Query: q}, nil)
	if err != nil {
		return nil, err
	}
	body := json.RawMessage(res.Body)
	if !json.Valid(body) {
		body = json.RawMessage("{}")
	}
	if p.cache != nil {
		if ttl := ttlFor(res.Header, p.ttl, p.ttl); ttl > 0 {
			p.cache.Set(key, body, ttl)
		}
	}
	return body, nil
}

func (p *preordersImpl) Resolve(ctx context.Context, a port.PreorderArea) (json.RawMessage, error) {
	q := url.Values{"country": {a.Country}, "region": {a.Region}}
	if a.City != "" {
		q.Set("city", a.City)
	}
	key := "delivery-runs:preorders:resolve:" + strings.ToLower(a.Country+"/"+a.Region+"/"+a.City)
	return p.cached(ctx, key, "/v1/public/preorders/resolve", q)
}

func (p *preordersImpl) Open(ctx context.Context) (json.RawMessage, error) {
	return p.cached(ctx, "delivery-runs:preorders:open", "/v1/public/preorders/open", nil)
}

func (p *preordersImpl) Waitlist(ctx context.Context, w port.PreorderWaitlist) (json.RawMessage, error) {
	var out json.RawMessage
	_, err := call(ctx, p.c, httpclient.Request{Method: http.MethodPost, Path: "/v1/public/subscriptions", JSON: w}, &out)
	return out, err
}

func (p *preordersImpl) LeaveWaitlist(ctx context.Context, token string) error {
	_, err := call(ctx, p.c, httpclient.Request{Method: http.MethodPost, Path: "/v1/public/preorders/waitlist/unsubscribe",
		JSON: map[string]string{"token": token}}, nil)
	return err
}

// staffStreamTimeout bounds one relayed export (large CSVs stream for a while).
const staffStreamTimeout = 2 * time.Minute

var _ port.StaffStreamer = (*staffJSONImpl)(nil)

func (s *staffJSONImpl) Stream(ctx context.Context, bearer string, sc port.StaffCall) (port.StaffStream, error) {
	method := sc.Method
	if method == "" {
		method = http.MethodGet
	}
	res, err := s.c.Stream(ctx, httpclient.Request{Method: method, Path: sc.Path, Query: sc.Query, Bearer: bearer}, "text/csv", staffStreamTimeout)
	if err != nil {
		return port.StaffStream{}, fmt.Errorf("%s %s: %w", method, sc.Path, err)
	}
	return port.StaffStream{ContentType: res.Header.Get("Content-Type"), ContentDisposition: res.Header.Get("Content-Disposition"), Body: res.Body}, nil
}
