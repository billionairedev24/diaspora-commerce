package app

import (
	"context"
	"net/http"
	"regexp"
	"strings"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

// BusinessPageService serves the plain public page of a business (what Stripe is given as its website): its name,
// description, logo and brand colour, and for providers the services they offer. No session is needed.
type BusinessPageService interface {
	Page(ctx context.Context, slug string) (domain.BusinessPage, error)
}

type businessPageServiceImpl struct {
	pages    port.BusinessPagesClient
	bookings port.CustomerBookingsClient
	products SearchService
}

var _ BusinessPageService = (*businessPageServiceImpl)(nil)

// NewBusinessPageService returns the business page use case; bookings (a provider's services) and products (a seller's
// live products, through search) are optional.
func NewBusinessPageService(pages port.BusinessPagesClient, bookings port.CustomerBookingsClient, products SearchService) BusinessPageService {
	return &businessPageServiceImpl{pages: pages, bookings: bookings, products: products}
}

// maxPageProducts is how many of a seller's products the page lists.
const maxPageProducts = 12

var businessSlug = regexp.MustCompile(`^[a-z0-9]+(-[a-z0-9]+)*$`)

func (s *businessPageServiceImpl) Page(ctx context.Context, slug string) (domain.BusinessPage, error) {
	slug = strings.ToLower(strings.TrimSpace(slug))
	if len(slug) > 80 || !businessSlug.MatchString(slug) {
		return domain.BusinessPage{}, businessPageNotFound(nil)
	}
	up, err := s.pages.Page(ctx, slug)
	if err != nil {
		if p, ok := port.AsUpstreamProblem(err); ok && p.Status == http.StatusNotFound {
			return domain.BusinessPage{}, businessPageNotFound(err)
		}
		return domain.BusinessPage{}, mapErr("seller", err)
	}
	out := domain.BusinessPage{Slug: up.Slug, Kind: up.Kind, Name: up.Name, Description: strPtr(up.Description),
		BrandColor: validBrandColor(up.BrandColor), LogoURL: strPtr(up.LogoURL), ContactEmail: strPtr(up.ContactEmail),
		Indexable: up.Approved, Services: []domain.BusinessPageService{}, Products: []domain.ProductSummary{}}
	if up.Kind == "PROVIDER" && s.bookings != nil {
		// Best effort: a provider without published services (or bookings down) still has its page.
		if p, err := s.bookings.Provider(ctx, up.OrgID); err == nil {
			for _, sv := range p.Services {
				out.Services = append(out.Services, domain.BusinessPageService{ServiceID: sv.ID, Name: sv.Name, DurationMinutes: sv.DurationMinutes,
					PriceMinor: sv.PriceMinor, Currency: firstNonEmpty(sv.Currency, domain.Currency)})
			}
		}
	}
	if up.Kind == "SELLER" && s.products != nil {
		// Best effort, like services: a seller's page without products (or search down) still renders.
		if page, err := s.products.Search(ctx, &domain.Caller{}, domain.SearchQuery{SellerID: up.OrgID, Size: maxPageProducts, Sort: domain.SortRelevance}); err == nil {
			out.Products = append(out.Products, page.Hits...)
		}
	}
	return out, nil
}

var hexColor = regexp.MustCompile(`^#[0-9a-f]{6}$`)

// validBrandColor is the colour when it is a well-formed #rrggbb, else nil (the page uses the platform colour).
func validBrandColor(c string) *string {
	c = strings.ToLower(strings.TrimSpace(c))
	if !hexColor.MatchString(c) {
		return nil
	}
	return &c
}

func businessPageNotFound(cause error) error {
	p := domain.NewProblem(http.StatusNotFound, domain.CodeNotFound, "Page not found", "There is no business page at this address.")
	if cause != nil {
		return p.WithCause(cause)
	}
	return p
}
