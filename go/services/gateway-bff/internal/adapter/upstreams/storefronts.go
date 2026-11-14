package upstreams

import (
	"context"
	"encoding/json"
	"net/http"
	"net/url"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/adapter/httpclient"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

type storefrontsImpl struct{ c httpclient.Client }

var _ port.StorefrontsClient = (*storefrontsImpl)(nil)

// NewStorefronts returns the seller-service client for published storefronts (public, no token).
func NewStorefronts(c httpclient.Client) port.StorefrontsClient { return &storefrontsImpl{c: c} }

func (s *storefrontsImpl) Storefront(ctx context.Context, slug string) (port.UpStorefront, error) {
	var out port.UpStorefront
	_, err := call(ctx, s.c, httpclient.Request{Path: "/v1/public/storefronts/" + url.PathEscape(slug)}, &out)
	return out, err
}

// ---------------------------------------------------------------- seller: store page settings (member token)

func storePagePath(orgID, tail string) string {
	return "/v1/orgs/" + url.PathEscape(orgID) + "/store-page" + tail
}

func (s *sellerServiceImpl) StorePage(ctx context.Context, bearer, orgID string) (port.UpStorePage, error) {
	var out port.UpStorePage
	_, err := call(ctx, s.c, httpclient.Request{Path: storePagePath(orgID, ""), Bearer: bearer, UserBearer: true}, &out)
	return out, err
}

func (s *sellerServiceImpl) SaveStorePage(ctx context.Context, bearer, orgID string, config json.RawMessage, version int64) (port.UpStorePage, error) {
	var out port.UpStorePage
	body := struct {
		Config  json.RawMessage `json:"config"`
		Version int64           `json:"version"`
	}{config, version}
	_, err := call(ctx, s.c, httpclient.Request{Method: http.MethodPut, Path: storePagePath(orgID, ""), Bearer: bearer, UserBearer: true, JSON: body}, &out)
	return out, err
}

func (s *sellerServiceImpl) PublishStorePage(ctx context.Context, bearer, orgID string, version int64) (port.UpStorePage, error) {
	var out port.UpStorePage
	_, err := call(ctx, s.c, httpclient.Request{Method: http.MethodPost, Path: storePagePath(orgID, "/publish"), Bearer: bearer, UserBearer: true,
		JSON: map[string]int64{"version": version}}, &out)
	return out, err
}

func (s *sellerServiceImpl) UnpublishStorePage(ctx context.Context, bearer, orgID string) (port.UpStorePage, error) {
	var out port.UpStorePage
	_, err := call(ctx, s.c, httpclient.Request{Method: http.MethodPost, Path: storePagePath(orgID, "/unpublish"), Bearer: bearer, UserBearer: true}, &out)
	return out, err
}

func (s *sellerServiceImpl) StorePageAudit(ctx context.Context, bearer, orgID string) ([]port.UpStorePageAuditEntry, error) {
	var out []port.UpStorePageAuditEntry
	_, err := call(ctx, s.c, httpclient.Request{Path: storePagePath(orgID, "/audit"), Bearer: bearer, UserBearer: true}, &out)
	return out, err
}
