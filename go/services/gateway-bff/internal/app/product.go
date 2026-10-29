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
	"sync"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

// ProductService builds the product page: catalog public product + inventory availability per variant + the buy box
// and "Other sellers on AfroTransact" from the offers service (catalog's single seller when offers is unavailable) +
// delivery promise (promise service when available, estimate otherwise) + low-stock flags. It also manages the
// shopper's back-in-stock alerts (notifications).
type ProductService interface {
	Detail(ctx context.Context, c *domain.Caller, atin string) (domain.ProductDetail, error)
	StockAlert(ctx context.Context, c *domain.Caller, atin, email string) error
	StockAlerts(ctx context.Context, c *domain.Caller) ([]domain.StockAlertItem, error)
	RemoveStockAlert(ctx context.Context, c *domain.Caller, atin string) error
}

type productServiceImpl struct {
	catalog       port.CatalogClient
	inventory     port.InventoryClient
	promise       port.PromiseClient
	notifications port.NotificationsClient
	offers        port.StorefrontOffersClient
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
	// Offers is optional: without it the page shows the catalog's single seller.
	Offers    port.StorefrontOffersClient
	Location  LocationService
	Estimator domain.Estimator
	Sellers   SellerDirectory
	Clock     Clock
	Log       *slog.Logger
}

// NewProductService returns the ProductService.
func NewProductService(d ProductDeps) ProductService {
	if d.Log == nil {
		d.Log = slog.Default()
	}
	return &productServiceImpl{catalog: d.Catalog, inventory: d.Inventory, promise: d.Promise, notifications: d.Notifications,
		offers: d.Offers, location: d.Location, estimator: d.Estimator, sellers: d.Sellers, clock: d.Clock, log: d.Log}
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

func catalogImageURL(p port.CatalogProduct) string {
	for _, im := range p.Images {
		if u := strings.TrimSpace(im.URL); u != "" {
			return u
		}
	}
	return attr(p.Attributes, "imageUrl", "image", "thumbnail", "primaryImage")
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
	// Inventory only holds warehouse stock (Fulfilled by AfroTransact, retail); a seller who ships their own orders
	// declares the quantity on the offer. The offers service's buy box is the sellable quantity of every variant.
	s.offerAvailability(ctx, skus, avail)
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
	zip := s.location.Current(ctx, c).ZIP
	qty := max(0, avail[sel.ATIN])
	currency := firstNonEmpty(strings.ToUpper(sel.Currency), domain.Currency)
	var buyBox *domain.Offer
	otherOffers := []domain.Offer{}
	shipsFrom, soldBy := seller.Name, seller.Name
	if fulfilment == domain.FulfilledByAfroTransact {
		shipsFrom = "AfroTransact"
	}
	if bb, ok := s.offerBuyBox(ctx, sel.ATIN); ok {
		// The offers service decides: winner (nil = currently unavailable) and the other listed sellers.
		if bb.BuyBox != nil {
			o := s.offerOf(*bb.BuyBox, s.promiseDays(ctx, zip, *bb.BuyBox), now)
			buyBox = &o
			shipsFrom = firstNonEmpty(bb.BuyBox.ShipsFrom, o.Seller.Name)
			soldBy = firstNonEmpty(bb.BuyBox.SoldBy, o.Seller.Name)
			seller, fulfilment = o.Seller, o.Fulfilment
		}
		for _, other := range bb.OtherOffers {
			otherOffers = append(otherOffers, s.offerOf(other, other.DeliveryDays, now))
		}
	} else if qty > 0 {
		days := 0
		if zip != "" {
			if m, err := s.promise.Promises(ctx, zip, []port.PromiseItem{{ATIN: sel.ATIN, Fulfilment: fulfilment, SellerOrgID: p.SellerOrgID}}); err == nil {
				days = m[sel.ATIN]
			} else {
				s.log.DebugContext(ctx, "promise unavailable; using the estimate", "error", err)
			}
		}
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
	return domain.ProductDetail{
		Product: domain.ProductInfo{
			ATIN: productATIN, Title: p.Title, Brand: attr(p.Attributes, "brand"),
			Community: attr(p.Attributes, "community", "communities", "cuisine"), Department: department, Unit: unit,
			Origin: attr(p.Attributes, "origin", "countryOfOrigin", "country_of_origin"), Bullets: bulletsOf(p.Attributes),
			Rating: p.Rating.Average, ReviewCount: p.Rating.Count, Images: images, Variants: variants,
		},
		BuyBox:         buyBox,
		OtherOffers:    otherOffers,
		ShipsFrom:      shipsFrom,
		SoldBy:         soldBy,
		ReturnsPolicy:  returnsPolicy(fulfilment, department),
		UnitPriceLabel: domain.Money(sel.PriceMinor) + " / " + unit,
	}, nil
}

// offerAvailability raises each variant's availability to its buy box quantity (seller-fulfilled stock lives on the
// offer, not in inventory). Variants offers cannot answer keep the inventory figure.
func (s *productServiceImpl) offerAvailability(ctx context.Context, atins []string, avail map[string]int) {
	if s.offers == nil || len(atins) == 0 {
		return
	}
	var mu sync.Mutex
	var wg sync.WaitGroup
	sem := make(chan struct{}, 8)
	for _, atin := range atins {
		wg.Add(1)
		sem <- struct{}{}
		go func() {
			defer wg.Done()
			defer func() { <-sem }()
			bb, err := s.offers.BuyBox(ctx, atin)
			if err != nil || bb.BuyBox == nil {
				return
			}
			mu.Lock()
			avail[atin] = max(avail[atin], bb.BuyBox.AvailableQty)
			mu.Unlock()
		}()
	}
	wg.Wait()
}

// offerBuyBox reads the variant's buy box from offers; ok=false when offers is not wired or cannot answer (the page
// then falls back to the catalog's single seller).
func (s *productServiceImpl) offerBuyBox(ctx context.Context, variantATIN string) (port.OfferBuyBox, bool) {
	if s.offers == nil {
		return port.OfferBuyBox{}, false
	}
	bb, err := s.offers.BuyBox(ctx, variantATIN)
	if err != nil {
		s.log.DebugContext(ctx, "offers unavailable; showing the catalog seller", "error", err)
		return port.OfferBuyBox{}, false
	}
	return bb, true
}

// promiseDays asks promise for the winning offer's delivery days (0 = use the estimate).
func (s *productServiceImpl) promiseDays(ctx context.Context, zip string, o port.PublicOffer) int {
	if zip == "" {
		return o.DeliveryDays
	}
	m, err := s.promise.Promises(ctx, zip, []port.PromiseItem{{ATIN: o.ATIN, Fulfilment: o.Fulfilment, SellerOrgID: o.Seller.ID}})
	if err != nil {
		s.log.DebugContext(ctx, "promise unavailable; using the offer's estimate", "error", err)
		return o.DeliveryDays
	}
	if d := m[o.ATIN]; d > 0 {
		return d
	}
	return o.DeliveryDays
}

// offerOf maps an offers-service offer onto the contract's Offer.
func (s *productServiceImpl) offerOf(o port.PublicOffer, days int, now time.Time) domain.Offer {
	return offerFromPublic(o, s.estimator.Promise(normFulfilment(o.Fulfilment), days, now))
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

// StockAlert arms "Email me when available" for the product's requested variant (or its first variant). The
// notifications service keys alerts by the shopper's account, so guests must sign in first.
func (s *productServiceImpl) StockAlert(ctx context.Context, c *domain.Caller, atin, email string) error {
	p, wanted, err := resolveProduct(ctx, s.catalog, atin)
	if err != nil {
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
	if !c.Authenticated() {
		return domain.NewProblem(http.StatusUnauthorized, domain.CodeUnauthenticated, "Sign in to get an alert",
			"Sign in and we'll email you when it's back in stock.")
	}
	variant := wanted
	if variant == "" && len(p.Variants) > 0 {
		variant = p.Variants[0].ATIN
	}
	in := port.StockAlertInput{ATIN: variant, Email: email, ProductTitle: p.Title}
	if err := s.notifications.SubscribeStockAlert(ctx, c.BearerToken, in); err != nil {
		return mapErr("notifications", err)
	}
	return nil
}

func (s *productServiceImpl) StockAlerts(ctx context.Context, c *domain.Caller) ([]domain.StockAlertItem, error) {
	if err := requireCustomer(c); err != nil {
		return nil, err
	}
	list, err := s.notifications.StockAlerts(ctx, c.BearerToken)
	if err != nil {
		return nil, mapErr("notifications", err)
	}
	out := make([]domain.StockAlertItem, 0, len(list))
	for _, a := range list {
		status := "notified"
		if a.Status == "active" {
			status = "active"
		}
		out = append(out, domain.StockAlertItem{ATIN: a.ATIN, ProductTitle: a.ProductTitle, Status: status, CreatedAt: a.CreatedAt,
			NotifiedAt: a.NotifiedAt})
	}
	return out, nil
}

func (s *productServiceImpl) RemoveStockAlert(ctx context.Context, c *domain.Caller, atin string) error {
	if err := requireCustomer(c); err != nil {
		return err
	}
	if err := s.notifications.RemoveStockAlert(ctx, c.BearerToken, strings.ToUpper(strings.TrimSpace(atin))); err != nil {
		if errors.Is(err, port.ErrNotFound) {
			return domain.NewProblem(http.StatusNotFound, domain.CodeNotFound, "Alert not found", "You have no alert for that item.").WithCause(err)
		}
		return mapErr("notifications", err)
	}
	return nil
}
