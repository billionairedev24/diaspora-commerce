package upstreams

import (
	"context"
	"net/http"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/adapter/httpclient"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

// staffJSONImpl is the staff console's view of one upstream: authenticated JSON calls with token relay. Paths follow
// contracts/openapi/<service>.yaml; the use cases (internal/app/staff_*.go) choose them.
type staffJSONImpl struct{ c httpclient.Client }

var _ port.StaffUpstream = (*staffJSONImpl)(nil)

// NewStaffUpstream wraps one upstream client for the staff routes.
func NewStaffUpstream(c httpclient.Client) port.StaffUpstream { return &staffJSONImpl{c: c} }

func (s *staffJSONImpl) Name() string { return s.c.Name() }

func (s *staffJSONImpl) Call(ctx context.Context, bearer string, sc port.StaffCall, out any) error {
	req := httpclient.Request{Method: sc.Method, Path: sc.Path, Query: sc.Query, JSON: sc.Body, Bearer: bearer}
	if sc.IdempotencyKey != "" || sc.IfMatch != "" {
		req.Header = http.Header{}
		if sc.IdempotencyKey != "" {
			req.Header.Set("Idempotency-Key", sc.IdempotencyKey)
			req.RetryUnsafe = true
		}
		if sc.IfMatch != "" {
			req.Header.Set("If-Match", sc.IfMatch)
		}
	}
	_, err := call(ctx, s.c, req, out)
	return err
}
