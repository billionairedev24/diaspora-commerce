package app

import (
	"context"
	"strings"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

// SellerCouponsService lists and manages seller-funded coupons (offers-service).
type SellerCouponsService interface {
	List(ctx context.Context, c *domain.Caller) ([]domain.SellerCoupon, error)
	Create(ctx context.Context, c *domain.Caller, in domain.CreateSellerCoupon) (domain.SellerCoupon, error)
	SetStatus(ctx context.Context, c *domain.Caller, id, status string) (domain.SellerCoupon, error)
}

type sellerCouponsImpl struct{ r *orgResolver }

var _ SellerCouponsService = (*sellerCouponsImpl)(nil)

// NewSellerCouponsService returns SellerCouponsService.
func NewSellerCouponsService(d SellerDeps) SellerCouponsService {
	return &sellerCouponsImpl{r: newResolver(d)}
}

func sellerCouponOf(c port.SellerCouponUp, editable bool) domain.SellerCoupon {
	return domain.SellerCoupon{
		ID: c.ID, Code: c.Code, Description: c.Description, Type: strings.ToUpper(c.Type), Value: c.Value,
		Currency: strings.ToUpper(c.Currency), Scope: strings.ToUpper(c.Scope), ScopeRef: c.ScopeRef,
		MinSpendMinor: c.MinSpendMinor, Status: strings.ToUpper(c.Status), Funding: strings.ToUpper(c.Funding),
		Editable: editable, Version: c.Version,
	}
}

func (s *sellerCouponsImpl) List(ctx context.Context, c *domain.Caller) ([]domain.SellerCoupon, error) {
	if _, err := s.r.require(ctx, c, domain.OrgKindSeller, "coupons", domain.LevelView); err != nil {
		return nil, err
	}
	if s.r.d.Offers == nil {
		return nil, domain.UpstreamUnavailable("offers")
	}
	raw, err := s.r.d.Offers.ListCoupons(ctx, c.BearerToken)
	if err != nil {
		return nil, err
	}
	out := make([]domain.SellerCoupon, 0, len(raw))
	for _, row := range raw {
		out = append(out, sellerCouponOf(row, strings.EqualFold(row.Funding, "SELLER")))
	}
	return out, nil
}

func (s *sellerCouponsImpl) Create(ctx context.Context, c *domain.Caller, in domain.CreateSellerCoupon) (domain.SellerCoupon, error) {
	if _, err := s.r.require(ctx, c, domain.OrgKindSeller, "coupons", domain.LevelEdit); err != nil {
		return domain.SellerCoupon{}, err
	}
	if s.r.d.Offers == nil {
		return domain.SellerCoupon{}, domain.UpstreamUnavailable("offers")
	}
	body := map[string]any{
		"code": in.Code, "description": in.Description, "type": in.Type, "value": in.Value, "currency": firstNonEmpty(in.Currency, domain.Currency),
		"scope": in.Scope, "scopeRef": in.ScopeRef, "minSpendMinor": in.MinSpendMinor, "totalLimit": in.TotalLimit,
		"perCustomerLimit": in.PerCustomerLimit, "clipRequired": false, "funding": "SELLER",
	}
	row, err := s.r.d.Offers.CreateCoupon(ctx, c.BearerToken, body)
	if err != nil {
		return domain.SellerCoupon{}, err
	}
	return sellerCouponOf(row, true), nil
}

func (s *sellerCouponsImpl) SetStatus(ctx context.Context, c *domain.Caller, id, status string) (domain.SellerCoupon, error) {
	if _, err := s.r.require(ctx, c, domain.OrgKindSeller, "coupons", domain.LevelEdit); err != nil {
		return domain.SellerCoupon{}, err
	}
	if s.r.d.Offers == nil {
		return domain.SellerCoupon{}, domain.UpstreamUnavailable("offers")
	}
	row, err := s.r.d.Offers.SetCouponStatus(ctx, c.BearerToken, id, strings.ToUpper(status))
	if err != nil {
		return domain.SellerCoupon{}, err
	}
	return sellerCouponOf(row, true), nil
}
