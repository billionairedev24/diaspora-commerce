package app

import (
	"context"
	"strings"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

// SellerBusinessPageService lets the team change how the organisation's plain public page looks (description, logo,
// brand colour) at any time, also after approval. Reading needs Team view; changing needs Team edit (the seller
// service further limits changes to the owner and admins).
type SellerBusinessPageService interface {
	Get(ctx context.Context, c *domain.Caller) (domain.BusinessPageSettings, error)
	Update(ctx context.Context, c *domain.Caller, in domain.BusinessPageSettingsInput) (domain.BusinessPageSettings, error)
}

type sellerBusinessPageImpl struct{ r *orgResolver }

var _ SellerBusinessPageService = (*sellerBusinessPageImpl)(nil)

// NewSellerBusinessPageService returns the SellerBusinessPageService.
func NewSellerBusinessPageService(d SellerDeps) SellerBusinessPageService {
	return &sellerBusinessPageImpl{r: newResolver(d)}
}

func businessPageSettingsOf(p port.UpBusinessPageSettings, canEdit bool) domain.BusinessPageSettings {
	return domain.BusinessPageSettings{Name: p.Name, Slug: strPtr(p.Slug), Description: strPtr(p.Description),
		BrandColor: strPtr(p.BrandColor), LogoURL: strPtr(p.LogoURL), ContactEmail: strPtr(p.ContactEmail), CanEdit: canEdit}
}

func (s *sellerBusinessPageImpl) Get(ctx context.Context, c *domain.Caller) (domain.BusinessPageSettings, error) {
	sc, err := s.r.resolve(ctx, c)
	if err != nil {
		return domain.BusinessPageSettings{}, err
	}
	if !sc.access.Allows("team", domain.LevelView) {
		return domain.BusinessPageSettings{}, domain.AccessDenied(sc.org.Role, domain.LevelView)
	}
	p, err := s.r.d.Sellers.BusinessPageSettings(ctx, c.BearerToken, sc.org.ID)
	if err != nil {
		return domain.BusinessPageSettings{}, mapErr("seller", err)
	}
	return businessPageSettingsOf(p, domain.AtLeast(sc.access.Level("team"), domain.LevelEdit)), nil
}

func (s *sellerBusinessPageImpl) Update(ctx context.Context, c *domain.Caller, in domain.BusinessPageSettingsInput) (domain.BusinessPageSettings, error) {
	sc, err := s.r.resolve(ctx, c)
	if err != nil {
		return domain.BusinessPageSettings{}, err
	}
	if !sc.access.Allows("team", domain.LevelEdit) {
		return domain.BusinessPageSettings{}, domain.AccessDenied(sc.org.Role, domain.LevelEdit)
	}
	t := strings.TrimSpace
	p, err := s.r.d.Sellers.UpdateBusinessPageSettings(ctx, c.BearerToken, sc.org.ID,
		port.BusinessPageSettingsInput{Description: t(in.Description), BrandColor: t(in.BrandColor), LogoURL: t(in.LogoURL)})
	if err != nil {
		return domain.BusinessPageSettings{}, mapErr("seller", err)
	}
	return businessPageSettingsOf(p, true), nil
}
