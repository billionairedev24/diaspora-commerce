package upstreams

import (
	"context"
	"net/http"
	"net/url"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/adapter/httpclient"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

var _ port.SellerBillingClient = (*sellerServiceImpl)(nil)

func (s *sellerServiceImpl) OrgPlans(ctx context.Context, bearer, orgID string) ([]port.SellerPlan, error) {
	res, err := call(ctx, s.c, httpclient.Request{Path: orgPath(orgID, "/billing/plans"), Bearer: bearer}, nil)
	if err != nil {
		return nil, err
	}
	items, _, err := itemsOf[port.SellerPlan](res.Body)
	return items, err
}

func (s *sellerServiceImpl) Rewards(ctx context.Context, bearer, orgID string) (port.SellerRewards, error) {
	var out port.SellerRewards
	_, err := call(ctx, s.c, httpclient.Request{Path: orgPath(orgID, "/billing/rewards"), Bearer: bearer}, &out)
	return out, err
}

func (s *sellerServiceImpl) PreviewPlanChange(ctx context.Context, bearer, orgID, planCode, cadence string) (port.SellerPlanChangePreview, error) {
	var out port.SellerPlanChangePreview
	_, err := call(ctx, s.c, httpclient.Request{Path: orgPath(orgID, "/billing/plan/preview"), Bearer: bearer,
		Query: url.Values{"planCode": {planCode}, "cadence": {cadence}}}, &out)
	return out, err
}

func (s *sellerServiceImpl) CreateSetupIntent(ctx context.Context, bearer, orgID string) (port.SellerSetupIntent, error) {
	var out port.SellerSetupIntent
	_, err := call(ctx, s.c, httpclient.Request{Method: http.MethodPost, Path: orgPath(orgID, "/billing/setup-intents"),
		Bearer: bearer}, &out)
	return out, err
}

func (s *sellerServiceImpl) AddPaymentMethod(ctx context.Context, bearer, orgID string, in port.SellerPaymentMethodInput) (port.SellerPaymentMethod, error) {
	var out port.SellerPaymentMethod
	_, err := call(ctx, s.c, httpclient.Request{Method: http.MethodPost, Path: orgPath(orgID, "/billing/payment-methods"),
		Bearer: bearer, JSON: in}, &out)
	return out, err
}
