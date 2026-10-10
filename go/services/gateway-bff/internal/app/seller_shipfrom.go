package app

import (
	"context"
	"strings"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

// SellerShipFromService manages the organisation's ship-from addresses in the seller service (the label form
// preselects the default). Reading needs Orders view; changing needs Orders edit.
type SellerShipFromService interface {
	List(ctx context.Context, c *domain.Caller) ([]domain.ShipFromAddress, error)
	Create(ctx context.Context, c *domain.Caller, in domain.ShipFromInput) (domain.ShipFromAddress, error)
	Update(ctx context.Context, c *domain.Caller, id string, in domain.ShipFromInput) (domain.ShipFromAddress, error)
	MakeDefault(ctx context.Context, c *domain.Caller, id string) ([]domain.ShipFromAddress, error)
	Delete(ctx context.Context, c *domain.Caller, id string) ([]domain.ShipFromAddress, error)
}

type sellerShipFromImpl struct{ r *orgResolver }

var _ SellerShipFromService = (*sellerShipFromImpl)(nil)

// NewSellerShipFromService returns the SellerShipFromService.
func NewSellerShipFromService(d SellerDeps) SellerShipFromService {
	return &sellerShipFromImpl{r: newResolver(d)}
}

func shipFromOf(a port.ShipFromAddress) domain.ShipFromAddress {
	return domain.ShipFromAddress{ID: a.ID, Label: a.Label, Name: a.Name, Company: a.Company, Phone: a.Phone, Line1: a.Line1,
		Line2: a.Line2, City: a.City, State: a.State, PostalCode: a.PostalCode, Country: firstNonEmpty(a.Country, "US"),
		IsDefault: a.IsDefault, Version: a.Version}
}

func shipFromList(in []port.ShipFromAddress) []domain.ShipFromAddress {
	out := make([]domain.ShipFromAddress, 0, len(in))
	for _, a := range in {
		out = append(out, shipFromOf(a))
	}
	return out
}

func shipFromInput(in domain.ShipFromInput) port.ShipFromInput {
	t := strings.TrimSpace
	return port.ShipFromInput{Label: t(in.Label), Name: t(in.Name), Company: t(in.Company), Phone: t(in.Phone), Line1: t(in.Line1),
		Line2: t(in.Line2), City: t(in.City), State: strings.ToUpper(t(in.State)), PostalCode: t(in.PostalCode),
		Country: strings.ToUpper(firstNonEmpty(t(in.Country), "US")), MakeDefault: in.MakeDefault, Version: in.Version}
}

func (s *sellerShipFromImpl) List(ctx context.Context, c *domain.Caller) ([]domain.ShipFromAddress, error) {
	sc, err := s.r.require(ctx, c, domain.OrgKindSeller, "orders", domain.LevelView)
	if err != nil {
		return nil, err
	}
	list, err := s.r.d.Sellers.ShipFrom(ctx, c.BearerToken, sc.org.ID)
	if err != nil {
		return nil, mapErr("seller", err)
	}
	return shipFromList(list), nil
}

func (s *sellerShipFromImpl) Create(ctx context.Context, c *domain.Caller, in domain.ShipFromInput) (domain.ShipFromAddress, error) {
	if errs := in.Validate(); len(errs) > 0 {
		return domain.ShipFromAddress{}, domain.ValidationProblem(errs)
	}
	sc, err := s.r.require(ctx, c, domain.OrgKindSeller, "orders", domain.LevelEdit)
	if err != nil {
		return domain.ShipFromAddress{}, err
	}
	a, err := s.r.d.Sellers.CreateShipFrom(ctx, c.BearerToken, sc.org.ID, shipFromInput(in))
	if err != nil {
		return domain.ShipFromAddress{}, mapErr("seller", err)
	}
	return shipFromOf(a), nil
}

func (s *sellerShipFromImpl) Update(ctx context.Context, c *domain.Caller, id string, in domain.ShipFromInput) (domain.ShipFromAddress, error) {
	if errs := in.Validate(); len(errs) > 0 {
		return domain.ShipFromAddress{}, domain.ValidationProblem(errs)
	}
	sc, err := s.r.require(ctx, c, domain.OrgKindSeller, "orders", domain.LevelEdit)
	if err != nil {
		return domain.ShipFromAddress{}, err
	}
	a, err := s.r.d.Sellers.UpdateShipFrom(ctx, c.BearerToken, sc.org.ID, id, shipFromInput(in))
	if err != nil {
		return domain.ShipFromAddress{}, mapErr("seller", err)
	}
	return shipFromOf(a), nil
}

func (s *sellerShipFromImpl) MakeDefault(ctx context.Context, c *domain.Caller, id string) ([]domain.ShipFromAddress, error) {
	sc, err := s.r.require(ctx, c, domain.OrgKindSeller, "orders", domain.LevelEdit)
	if err != nil {
		return nil, err
	}
	list, err := s.r.d.Sellers.DefaultShipFrom(ctx, c.BearerToken, sc.org.ID, id)
	if err != nil {
		return nil, mapErr("seller", err)
	}
	return shipFromList(list), nil
}

func (s *sellerShipFromImpl) Delete(ctx context.Context, c *domain.Caller, id string) ([]domain.ShipFromAddress, error) {
	sc, err := s.r.require(ctx, c, domain.OrgKindSeller, "orders", domain.LevelEdit)
	if err != nil {
		return nil, err
	}
	list, err := s.r.d.Sellers.DeleteShipFrom(ctx, c.BearerToken, sc.org.ID, id)
	if err != nil {
		return nil, mapErr("seller", err)
	}
	return shipFromList(list), nil
}
