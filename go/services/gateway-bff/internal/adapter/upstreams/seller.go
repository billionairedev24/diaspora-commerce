package upstreams

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"net/url"
	"strings"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/adapter/httpclient"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

// ---------------------------------------------------------------- identity: organisations and module matrix

type orgAccessImpl struct{ c httpclient.Client }

var _ port.OrgAccessClient = (*orgAccessImpl)(nil)

// NewOrgAccess returns the identity organisation client (GET /v1/me, PUT /v1/me/active-org, module matrix, members).
func NewOrgAccess(c httpclient.Client) port.OrgAccessClient { return &orgAccessImpl{c: c} }

func (o *orgAccessImpl) Member(ctx context.Context, bearer string) (port.Member, error) {
	var m port.Member
	_, err := call(ctx, o.c, httpclient.Request{Path: "/v1/me", Bearer: bearer}, &m)
	return m, err
}

func (o *orgAccessImpl) SelectOrg(ctx context.Context, bearer, orgID string) (port.Member, error) {
	var m port.Member
	_, err := call(ctx, o.c, httpclient.Request{Method: http.MethodPut, Path: "/v1/me/active-org", Bearer: bearer,
		JSON: map[string]string{"orgId": orgID}}, &m)
	return m, err
}

func (o *orgAccessImpl) ModuleMatrix(ctx context.Context, bearer, portal string) (domain.ModuleMatrix, error) {
	var out struct {
		Modules []struct {
			Key    string            `json:"key"`
			Levels map[string]string `json:"levels"`
		} `json:"modules"`
	}
	if _, err := call(ctx, o.c, httpclient.Request{Path: "/v1/admin/module-access", Query: url.Values{"portal": {portal}}, Bearer: bearer}, &out); err != nil {
		return nil, err
	}
	m := domain.ModuleMatrix{}
	for _, row := range out.Modules {
		m[row.Key] = map[string]string{}
		for role, lv := range row.Levels {
			m[row.Key][role] = domain.NormalizeLevel(lv)
		}
	}
	return m, nil
}

func (o *orgAccessImpl) SetMemberRole(ctx context.Context, bearer, orgID, userID, roleKey string) error {
	_, err := call(ctx, o.c, httpclient.Request{Method: http.MethodPut, Bearer: bearer,
		Path: "/v1/orgs/" + url.PathEscape(orgID) + "/members/" + url.PathEscape(userID), JSON: map[string]string{"roleKey": roleKey}}, nil)
	return err
}

func (o *orgAccessImpl) Invite(ctx context.Context, bearer, orgID, email, roleKey string) (port.Invitation, error) {
	var inv port.Invitation
	_, err := call(ctx, o.c, httpclient.Request{Method: http.MethodPost, Bearer: bearer, Path: "/v1/orgs/" + url.PathEscape(orgID) + "/invitations",
		JSON: map[string]string{"email": email, "roleKey": roleKey}}, &inv)
	return inv, err
}

// ---------------------------------------------------------------- seller service (contracts/openapi/seller.yaml)

type sellerServiceImpl struct{ c httpclient.Client }

var _ port.SellerServiceClient = (*sellerServiceImpl)(nil)

// NewSellerService returns the seller-service client.
func NewSellerService(c httpclient.Client) port.SellerServiceClient { return &sellerServiceImpl{c: c} }

func orgPath(orgID, rest string) string { return "/v1/orgs/" + url.PathEscape(orgID) + rest }

func (s *sellerServiceImpl) Org(ctx context.Context, bearer, orgID string) (port.SellerOrg, error) {
	var o port.SellerOrg
	_, err := call(ctx, s.c, httpclient.Request{Path: orgPath(orgID, ""), Bearer: bearer}, &o)
	return o, err
}

func (s *sellerServiceImpl) Application(ctx context.Context, bearer string) (port.SellerApplication, error) {
	var a port.SellerApplication
	_, err := call(ctx, s.c, httpclient.Request{Path: "/v1/applications/me", Bearer: bearer}, &a)
	return a, err
}

func (s *sellerServiceImpl) AccountHealth(ctx context.Context, bearer, orgID string) (port.AccountHealth, error) {
	var h port.AccountHealth
	_, err := call(ctx, s.c, httpclient.Request{Path: orgPath(orgID, "/account-health"), Bearer: bearer}, &h)
	return h, err
}

func (s *sellerServiceImpl) Billing(ctx context.Context, bearer, orgID string) (port.SellerBilling, error) {
	var b port.SellerBilling
	_, err := call(ctx, s.c, httpclient.Request{Path: orgPath(orgID, "/billing"), Bearer: bearer}, &b)
	return b, err
}

func (s *sellerServiceImpl) Invoices(ctx context.Context, bearer, orgID string) ([]port.SellerInvoice, error) {
	res, err := call(ctx, s.c, httpclient.Request{Path: orgPath(orgID, "/billing/invoices"), Bearer: bearer}, nil)
	if err != nil {
		return nil, err
	}
	items, _, err := itemsOf[port.SellerInvoice](res.Body)
	return items, err
}

func (s *sellerServiceImpl) Plans(ctx context.Context, kind string) ([]port.SellerPlan, error) {
	res, err := call(ctx, s.c, httpclient.Request{Path: "/v1/plans", Query: url.Values{"kind": {kind}}}, nil)
	if err != nil {
		return nil, err
	}
	items, _, err := itemsOf[port.SellerPlan](res.Body)
	return items, err
}

func (s *sellerServiceImpl) ChangePlan(ctx context.Context, bearer, orgID, planCode, cadence string) (string, port.SellerBilling, error) {
	var out struct {
		Applied string             `json:"applied"`
		Billing port.SellerBilling `json:"billing"`
	}
	_, err := call(ctx, s.c, httpclient.Request{Method: http.MethodPost, Path: orgPath(orgID, "/billing/plan"), Bearer: bearer,
		JSON: map[string]string{"planCode": planCode, "cadence": cadence}}, &out)
	return out.Applied, out.Billing, err
}

func (s *sellerServiceImpl) paymentMethods(ctx context.Context, method, bearer, path string) ([]port.SellerPaymentMethod, error) {
	res, err := call(ctx, s.c, httpclient.Request{Method: method, Path: path, Bearer: bearer}, nil)
	if err != nil {
		return nil, err
	}
	var list []port.SellerPaymentMethod
	if len(strings.TrimSpace(string(res.Body))) > 0 {
		if err := json.Unmarshal(res.Body, &list); err != nil {
			return nil, fmt.Errorf("decode payment methods: %w", err)
		}
	}
	return list, nil
}

func (s *sellerServiceImpl) MakeDefaultPaymentMethod(ctx context.Context, bearer, orgID, id string) ([]port.SellerPaymentMethod, error) {
	return s.paymentMethods(ctx, http.MethodPost, bearer, orgPath(orgID, "/billing/payment-methods/"+url.PathEscape(id)+"/default"))
}

func (s *sellerServiceImpl) RemovePaymentMethod(ctx context.Context, bearer, orgID, id string) ([]port.SellerPaymentMethod, error) {
	return s.paymentMethods(ctx, http.MethodDelete, bearer, orgPath(orgID, "/billing/payment-methods/"+url.PathEscape(id)))
}
