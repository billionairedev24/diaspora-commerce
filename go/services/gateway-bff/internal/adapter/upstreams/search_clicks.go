package upstreams

import (
	"context"
	"net/http"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/adapter/httpclient"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

type searchClicksImpl struct{ c httpclient.Client }

var _ port.SearchClicksClient = (*searchClicksImpl)(nil)

// NewSearchClicks returns the search-service click reporter (POST /v1/search/clicks, S039/S240).
func NewSearchClicks(c httpclient.Client) port.SearchClicksClient { return &searchClicksImpl{c: c} }

func (s *searchClicksImpl) RecordClick(ctx context.Context, click port.SearchClick) (port.SearchClickResult, error) {
	var out port.SearchClickResult
	_, err := call(ctx, s.c, httpclient.Request{Method: http.MethodPost, Path: "/v1/search/clicks", JSON: click}, &out)
	return out, err
}
