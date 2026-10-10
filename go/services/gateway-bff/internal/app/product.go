package app

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"net/http"
	"net/mail"
	"regexp"
	"strings"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

// ProductService builds the product page: catalog public product + inventory availability per variant +
// delivery promise (promise service when available, estimate otherwise) + low-stock flags.
type ProductService interface {
	Detail(ctx context.Context, c *domain.Caller, atin string) (domain.ProductDetail, error)
	StockAlert(ctx context.Context, c *domain.Caller, atin, email string) error
}

type productServiceImpl struct {
	catalog       port.CatalogClient
	inventory     port.InventoryClient
	promise       port.PromiseClient
	notifications port.NotificationsClient
	location      LocationService
	estimator     domain.Estimator
	sellers       SellerDirectory
	clock         Clock
	log           *slog.Logger
}

var _ ProductService = (*productServiceImpl)(nil)

// ProductDeps are the ProductService's collaborators.
type ProductDeps struct {
	Catalog       port.CatalogClient
	Inventory     port.InventoryClient
	Promise       port.PromiseClient
	Notifications port.NotificationsClient
	Location      LocationService
	Estimator     domain.Estimator
	Sellers       SellerDirectory
	Clock         Clock
	Log           *slog.Logger
}

// NewProductService returns the ProductService.
func NewProductService(d ProductDeps) ProductService {
	if d.Log == nil {
		d.Log = slog.Default()
	}
	return &productServiceImpl{catalog: d.Catalog, inventory: d.Inventory, promise: d.Promise, notifications: d.Notifications,
		location: d.Location, estimator: d.Estimator, sellers: d.Sellers, clock: d.Clock, log: d.Log}
}

var atinRe = regexp.MustCompile(`^AT[0-9A-Z]{8}$`)

func productNotFound(atin string) *domain.Problem {
	return domain.NewProblem(http.StatusNotFound, domain.CodeProductNotFound, "Product not found", "No product with ATIN "+atin+".")
}

// resolve accepts a product ATIN or a variant ATIN and returns the product with the requested variant (if any).
func resolveProduct(ctx context.Context, catalog port.CatalogClient, atin string) (port.CatalogProduct, string, error) {
	atin = strings.ToUpper(strings.TrimSpace(atin))
	if !atinRe.MatchString(atin) {
		return port.CatalogProduct{}, "", productNotFound(atin)
	}
	p, err := catalog.Product(ctx, atin)
	if err == nil {
		return p, "", nil
	}
	if !errors.Is(err, port.ErrNotFound) {
		return p, "", mapErr("catalog", err)
	}
	v, err := catalog.Variant(ctx, atin)
	if err != nil {
		if errors.Is(err, port.ErrNotFound) {
			return p, "", productNotFound(atin).WithCause(err)
		}
		return p, "", mapErr("catalog", err)
	}
	return v.Product, v.Variant.ATIN, nil
}

func departmentFor(ctx context.Context, catalog port.CatalogClient, categoryID string) string {
	if categoryID == "" {
		return ""
	}
	cats, err := catalog.Categories(ctx)
	if err != nil {
		return ""
	}
	for _, c := range cats {
		if c.ID == categoryID {
			return domain.DepartmentOf(c.Path)
		}
	}
	return ""
}

func attr(attrs map[string]string, keys ...string) string {
	for _, k := range keys {
		for ak, v := range attrs {
			if strings.EqualFold(ak, k) && strings.TrimSpace(v) != "" {
				return strings.TrimSpace(v)
			}
		}
	}
	return ""
}

func bulletsOf(attrs map[string]string) []string {
	raw := attr(attrs, "bullets", "highlights", "bulletPoints", "description")
	out := []string{}
	for _, line := range strings.FieldsFunc(raw, func(r rune) bool { return r == '\n' || r == '|' || r == '•' }) {
		if l := strings.TrimSpace(strings.TrimPrefix(strings.TrimSpace(line), "-")); l != "" {
			out = append(out, l)
		}
	}
	return out
}

func (s *productServiceImpl) Detail(ctx context.Context, c *domain.Caller, atin string) (domain.ProductDetail, error) {
	p, wanted, err := resolveProduct(ctx, s.catalog, atin)
	if err != nil {
		return domain.ProductDetail{}, err
	}
	if len(p.Variants) == 0 {
		return domain.ProductDetail{}, productNotFound(atin)
	}
	skus := make([]string, len(p.Variants))
	for i, v := range p.Variants {
		skus[i] = v.ATIN
	}
	avail, err := s.inventory.Available(ctx, skus)
	if err != nil {
		return domain.ProductDetail{}, mapErr("inventory", err)
	}
	sel := p.Variants[0]
	found := false
	for _, v := range p.Variants {
		if v.ATIN == wanted {
			sel, found = v, true
		}
	}
	if !found {
		for _, v := range p.Variants {
			if avail[v.ATIN] > 0 {
				sel = v
				break
			}
		}
	}
	seller, fulfilment := s.sellers.Describe(p.OwnerKind, p.SellerOrgID)
	now := s.clock.now()
	days := 0
	if zip := s.location.Current(ctx, c).ZIP; zip != "" {
		if m, err := s.promise.Promises(ctx, zip, []port.PromiseItem{{ATIN: sel.ATIN, Fulfilment: fulfilment, SellerOrgID: p.SellerOrgID}}); err == nil {
			days = m[sel.ATIN]
		} else {
			s.log.DebugContext(ctx, "promise unavailable; using the estimate", "error", err)
		}
	}
	qty := max(0, avail[sel.ATIN])
	currency := firstNonEmpty(strings.ToUpper(sel.Currency), domain.Currency)
	var buyBox *domain.Offer
	if qty > 0 {
		buyBox = &domain.Offer{OfferID: sel.ATIN, Seller: seller, PriceMinor: sel.PriceMinor, ListPriceMinor: nil, Currency: currency,
			Fulfilment: fulfilment, AvailableQty: qty, StockStatus: domain.StockStatusFor(qty), Delivery: s.estimator.Promise(fulfilment, days, now)}
	}
	variants := []domain.VariantOption{}
	if len(p.Variants) > 1 {
		for _, v := range p.Variants {
			variants = append(variants, domain.VariantOption{ATIN: v.ATIN, Label: firstNonEmpty(optionsLabel(v.Options), "Standard"),
				Selected: v.ATIN == sel.ATIN, Available: avail[v.ATIN] > 0, PriceMinor: v.PriceMinor})
		}
	}
	images := make([]domain.ProductImage, 0, max(1, len(p.Images)))
	for i, img := range p.Images {
		images = append(images, domain.ProductImage{URL: strPtr(img.URL), Alt: fmt.Sprintf("%s — image %d of %d", p.Title, i+1, len(p.Images))})
	}
	if len(images) == 0 {
		images = append(images, domain.ProductImage{URL: nil, Alt: p.Title})
	}
	unit := firstNonEmpty(attr(p.Attributes, "unit", "size", "netContent", "net_content", "packSize"), optionsLabel(sel.Options), "each")
	department := departmentFor(ctx, s.catalog, p.CategoryID)
	productATIN := strings.ToUpper(strings.TrimSpace(atin))
	shipsFrom := seller.Name
	if fulfilment == domain.FulfilledByAfroTransact {
		shipsFrom = "AfroTransact"
	}
	return domain.ProductDetail{
		Product: domain.ProductInfo{
			ATIN: productATIN, Title: p.Title, Brand: attr(p.Attributes, "brand"),
			Community: attr(p.Attributes, "community", "communities", "cuisine"), Department: department, Unit: unit,
			Origin: attr(p.Attributes, "origin", "countryOfOrigin", "country_of_origin"), Bullets: bulletsOf(p.Attributes),
			Rating: 0, ReviewCount: 0, Images: images, Variants: variants,
		},
		BuyBox:         buyBox,
		OtherOffers:    []domain.Offer{},
		ShipsFrom:      shipsFrom,
		SoldBy:         seller.Name,
		ReturnsPolicy:  returnsPolicy(fulfilment, department),
		UnitPriceLabel: domain.Money(sel.PriceMinor) + " / " + unit,
	}, nil
}

func returnsPolicy(fulfilment, department string) string {
	switch {
	case fulfilment == domain.FulfilledByAfroTransact:
		return "Returnable within 30 days"
	case strings.EqualFold(department, "Groceries"):
		return "Non-returnable food item — damaged-item refunds only"
	default:
		return "Seller return policy, 14 days"
	}
}

func (s *productServiceImpl) StockAlert(ctx context.Context, c *domain.Caller, atin, email string) error {
	if _, _, err := resolveProduct(ctx, s.catalog, atin); err != nil {
		return err
	}
	email = strings.TrimSpace(email)
	if email == "" && c.Session != nil && c.Session.Profile != nil {
		email = c.Session.Profile.Email
	}
	if email == "" {
		p := domain.NewProblem(http.StatusUnprocessableEntity, domain.CodeValidationFailed, "Email required", "Enter an email address so we can tell you when it’s back.")
		p.Errors = map[string]string{"email": "Enter your email"}
		return p
	}
	if _, err := mail.ParseAddress(email); err != nil {
		return domain.ValidationProblem(map[string]string{"email": "Enter a valid email address"})
	}
	if err := s.notifications.SubscribeStockAlert(ctx, strings.ToUpper(atin), email, c.BearerToken); err != nil {
		return mapErr("notifications", err)
	}
	return nil
}
