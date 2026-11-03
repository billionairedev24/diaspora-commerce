package upstreams

import (
	"context"
	"net/http"
	"net/url"
	"strconv"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/adapter/httpclient"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

type helpRequestsImpl struct{ c httpclient.Client }

var _ port.HelpRequestsClient = (*helpRequestsImpl)(nil)

// NewHelpRequests returns the trust-ops client for the customer's own claims (open, list, read).
func NewHelpRequests(c httpclient.Client) port.HelpRequestsClient { return &helpRequestsImpl{c: c} }

func (h *helpRequestsImpl) Open(ctx context.Context, bearer string, req port.UpOpenClaim) (port.UpHelpClaim, error) {
	var out port.UpHelpClaim
	// Not retried: trust-ops would open a second claim.
	_, err := call(ctx, h.c, httpclient.Request{Method: http.MethodPost, Path: "/v1/claims", Bearer: bearer, JSON: req}, &out)
	return out, err
}

func (h *helpRequestsImpl) Mine(ctx context.Context, bearer string, page, size int) ([]port.UpHelpClaim, bool, error) {
	var out struct {
		Items         []port.UpHelpClaim `json:"items"`
		Total         int64              `json:"total"`
		Page          int                `json:"page"`
		Size          int                `json:"size"`
		TotalElements int64              `json:"totalElements"`
	}
	q := url.Values{"page": {strconv.Itoa(page)}, "size": {strconv.Itoa(size)}}
	if _, err := call(ctx, h.c, httpclient.Request{Path: "/v1/claims/mine", Query: q, Bearer: bearer}, &out); err != nil {
		return nil, false, err
	}
	total := max(out.Total, out.TotalElements)
	return out.Items, int64(page+1)*int64(size) < total, nil
}

func (h *helpRequestsImpl) Get(ctx context.Context, bearer, id string) (port.UpHelpClaim, error) {
	var out port.UpHelpClaim
	_, err := call(ctx, h.c, httpclient.Request{Path: "/v1/claims/" + esc(id), Bearer: bearer}, &out)
	return out, err
}
