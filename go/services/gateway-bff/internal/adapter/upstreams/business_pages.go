package upstreams

import (
	"context"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/adapter/httpclient"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

type businessPagesImpl struct{ c httpclient.Client }

var _ port.BusinessPagesClient = (*businessPagesImpl)(nil)

// NewBusinessPages returns the seller-service client for the public business pages.
func NewBusinessPages(c httpclient.Client) port.BusinessPagesClient { return &businessPagesImpl{c: c} }

func (b *businessPagesImpl) Page(ctx context.Context, slug string) (port.UpBusinessPage, error) {
	var out port.UpBusinessPage
	_, err := call(ctx, b.c, httpclient.Request{Path: "/v1/public/business-pages/" + esc(slug)}, &out)
	return out, err
}
