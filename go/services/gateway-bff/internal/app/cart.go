package app

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"fmt"
	"log/slog"
	"net/http"
	"regexp"
	"slices"
	"strconv"
	"strings"
	"sync"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

// CartService maps the contract's cart (lineId/savedId, selection for checkout, stock-capped quantities with
// STOCK_LIMIT problems) onto the cart service. Line and saved ids are variant ATINs; a line's offerId is the
// offers-service offer the shopper chose (validated through offers before the cart service is called). The cart service has no line
// selection, so the BFF keeps the deselected lines in Valkey keyed by the cart owner (new lines start selected).
type CartService interface {
	Get(ctx context.Context, c *domain.Caller) (domain.Cart, error)
	Add(ctx context.Context, c *domain.Caller, offerID string, qty int) (domain.Cart, error)
	UpdateLine(ctx context.Context, c *domain.Caller, lineID string, qty *int, selected *bool) (domain.Cart, error)
	RemoveLine(ctx context.Context, c *domain.Caller, lineID string) (domain.Cart, error)
	SelectAll(ctx context.Context, c *domain.Caller, selected bool) (domain.Cart, error)
	SaveForLater(ctx context.Context, c *domain.Caller, lineID string) (domain.Cart, error)
	MoveToCart(ctx context.Context, c *domain.Caller, savedID string) (domain.Cart, error)
	RemoveSaved(ctx context.Context, c *domain.Caller, savedID string) (domain.Cart, error)
	// Snapshot returns the presented cart with the raw upstream cart (checkout).
	Snapshot(ctx context.Context, c *domain.Caller) (domain.Cart, port.UpstreamCart, error)
}

// CartDeps are the CartService's collaborators.
type CartDeps struct {
	Cart      port.CartClient
	Catalog   port.CatalogClient
	Inventory port.InventoryClient
	// Offers validates offer ids (buyable) for add-to-cart; optional (without it offer ids are ATINs).
	Offers    port.StorefrontOffersClient
	Selection port.SelectionStore
	Sessions  SessionService
	Estimator domain.Estimator
	Clock     Clock
	Log       *slog.Logger
}

type cartServiceImpl struct{ d CartDeps }

var _ CartService = (*cartServiceImpl)(nil)

// NewCartService returns the CartService.
func NewCartService(d CartDeps) CartService {
	if d.Log == nil {
		d.Log = slog.Default()
	}
	return &cartServiceImpl{d: d}
}

func owner(c *domain.Caller) port.CartOwner {
	return port.CartOwner{BearerToken: c.BearerToken, GuestID: c.GuestCartID()}
}

func selectionOwner(c *domain.Caller) string {
	if c.Authenticated() {
		return "user:" + c.Subject
	}
	if g := c.GuestCartID(); g != "" {
		return "guest:" + g
	}
	return ""
}

func emptyCart() domain.Cart {
	return domain.Cart{CartID: "cart_new", Currency: domain.Currency, Packages: []domain.CartPackage{}, Saved: []domain.SavedItem{}}
}

// remember stores a guest cart id the cart service issued (web: in the session; app: echoed as X-Guest-Cart).
func (s *cartServiceImpl) remember(ctx context.Context, c *domain.Caller, up port.UpstreamCart) error {
	if up.GuestID == "" || up.GuestID == c.GuestCartID() {
		return nil
	}
	if c.App {
		c.IssuedGuestCartID = up.GuestID
		return nil
	}
	s.d.Sessions.Ensure(c)
	c.Session.GuestCartID = up.GuestID
	if err := s.d.Sessions.Save(ctx, c); err != nil {
		return fmt.Errorf("remember guest cart: %w", err)
	}
	return nil
}

// cartErr maps cart-service problems (codes in port) onto contract problems.
func cartErr(err error, notFoundCode string) error {
	p, ok := port.AsUpstreamProblem(err)
	if !ok {
		return mapErr("cart", err)
	}
	switch p.Code {
	case port.CartLineNotFound:
		if notFoundCode == domain.CodeSavedNotFound {
			return domain.NewProblem(http.StatusNotFound, domain.CodeSavedNotFound, "Saved item not found", "That item is no longer saved.").WithCause(err)
		}
		return lineNotFound().WithCause(err)
	case port.CartNotSellable, port.CartOfferNotBuyable:
		return offerNotFound().WithCause(err)
	case port.CartInvalid:
		return domain.ValidationProblem(map[string]string{"quantity": firstNonEmpty(p.Detail, "Invalid quantity")}).WithCause(err)
	case port.CartConcurrentUpdate:
		return domain.NewProblem(http.StatusConflict, domain.CodeConflict, "Cart changed", "Your cart changed in another window. Please try again.").WithCause(err)
	}
	return mapErr("cart", err)
}

func lineNotFound() *domain.Problem {
	return domain.NewProblem(http.StatusNotFound, domain.CodeLineNotFound, "Cart line not found", "That item is no longer in your cart.")
}

func offerNotFound() *domain.Problem {
	return domain.NewProblem(http.StatusNotFound, domain.CodeOfferNotFound, "Offer not found", "This offer is no longer available.")
}

var onlyRe = regexp.MustCompile(`only (\d+) available`)

// stockProblem builds STOCK_LIMIT / OUT_OF_STOCK with live availability (inventory), falling back to the
// number in the cart service's message.
func (s *cartServiceImpl) stockProblem(ctx context.Context, atin, title, detail string, inCart, requested int) *domain.Problem {
	available := -1
	if atin != "" {
		if m, err := s.d.Inventory.Available(ctx, []string{atin}); err == nil {
			available = m[atin]
		}
	}
	if available < 0 {
		available = 0
		if g := onlyRe.FindStringSubmatch(detail); g != nil {
			available, _ = strconv.Atoi(g[1])
		}
	}
	if title == "" && atin != "" {
		if v, err := s.d.Catalog.Variant(ctx, atin); err == nil {
			title = v.Product.Title
		}
	}
	return domain.StockProblem(title, available, inCart, requested)
}

func isStock(err error) (*port.UpstreamProblem, bool) {
	p, ok := port.AsUpstreamProblem(err)
	return p, ok && p.Code == port.CartInsufficientStock
}

func (s *cartServiceImpl) fetch(ctx context.Context, c *domain.Caller) (port.UpstreamCart, bool, error) {
	o := owner(c)
	if o.BearerToken == "" && o.GuestID == "" {
		return port.UpstreamCart{}, false, nil
	}
	up, err := s.d.Cart.Get(ctx, o)
	if err != nil {
		return up, false, cartErr(err, domain.CodeLineNotFound)
	}
	return up, true, nil
}

func (s *cartServiceImpl) Get(ctx context.Context, c *domain.Caller) (domain.Cart, error) {
	cart, _, err := s.Snapshot(ctx, c)
	return cart, err
}

func (s *cartServiceImpl) Snapshot(ctx context.Context, c *domain.Caller) (domain.Cart, port.UpstreamCart, error) {
	up, ok, err := s.fetch(ctx, c)
	if err != nil || !ok {
		return emptyCart(), up, err
	}
	cart, err := s.present(ctx, c, up)
	return cart, up, err
}

// resolveOffer turns an offerId into a variant ATIN. Offers are variants today (one seller per product); search
// cards carry the product ATIN, which resolves to its first in-stock variant.
func (s *cartServiceImpl) resolveOffer(ctx context.Context, offerID string) (string, string, error) {
	v, err := s.d.Catalog.Variant(ctx, offerID)
	if err == nil {
		return v.Variant.ATIN, v.Product.Title, nil
	}
	if !errors.Is(err, port.ErrNotFound) {
		return "", "", mapErr("catalog", err)
	}
	p, err := s.d.Catalog.Product(ctx, offerID)
	if err != nil {
		if errors.Is(err, port.ErrNotFound) {
			return "", "", offerNotFound().WithCause(err)
		}
		return "", "", mapErr("catalog", err)
	}
	if len(p.Variants) == 0 {
		return "", "", offerNotFound()
	}
	if len(p.Variants) == 1 {
		return p.Variants[0].ATIN, p.Title, nil
	}
	skus := make([]string, len(p.Variants))
	for i, v := range p.Variants {
		skus[i] = v.ATIN
	}
	if avail, err := s.d.Inventory.Available(ctx, skus); err == nil {
		for _, v := range p.Variants {
			if avail[v.ATIN] > 0 {
				return v.ATIN, p.Title, nil
			}
		}
	}
	return p.Variants[0].ATIN, p.Title, nil
}

var uuidRe = regexp.MustCompile(`^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$`)

// offerLine validates an offers-service offer id: it must exist and be buyable now. Out of stock answers like the
// cart's stock problems; anything else that stops the purchase is OFFER_NOT_FOUND.
func (s *cartServiceImpl) offerLine(ctx context.Context, c *domain.Caller, offerID string, qty int) (string, error) {
	d, err := s.d.Offers.Offer(ctx, offerID)
	if err != nil {
		if errors.Is(err, port.ErrNotFound) {
			return "", offerNotFound().WithCause(err)
		}
		return "", mapErr("offers", err)
	}
	if !d.Buyable {
		if slices.Contains(d.NotEligible, "OUT_OF_STOCK") {
			return "", domain.StockProblem("", 0, s.inCart(ctx, c, d.Offer.ATIN), qty)
		}
		return "", offerNotFound()
	}
	return d.Offer.ATIN, nil
}

func (s *cartServiceImpl) inCart(ctx context.Context, c *domain.Caller, atin string) int {
	if cur, ok, err := s.fetch(ctx, c); err == nil && ok {
		if l, found := cur.Line(atin); found {
			return l.Quantity
		}
	}
	return 0
}

func (s *cartServiceImpl) Add(ctx context.Context, c *domain.Caller, offerID string, qty int) (domain.Cart, error) {
	var atin, title, chosen string
	var err error
	if s.d.Offers != nil && uuidRe.MatchString(offerID) {
		chosen = strings.ToLower(offerID)
		atin, err = s.offerLine(ctx, c, chosen, qty)
	} else {
		atin, title, err = s.resolveOffer(ctx, offerID)
	}
	if err != nil {
		return domain.Cart{}, err
	}
	up, err := s.d.Cart.Add(ctx, owner(c), atin, chosen, qty)
	if err != nil {
		if p, ok := isStock(err); ok {
			inCart := s.inCart(ctx, c, atin)
			if chosen != "" {
				// Seller-declared stock: the cart service's "only N available" is the authority.
				return domain.Cart{}, s.stockProblem(ctx, "", title, p.Detail, inCart, inCart+qty).WithCause(err)
			}
			return domain.Cart{}, s.stockProblem(ctx, atin, title, p.Detail, inCart, inCart+qty).WithCause(err)
		}
		return domain.Cart{}, cartErr(err, domain.CodeLineNotFound)
	}
	if err := s.remember(ctx, c, up); err != nil {
		return domain.Cart{}, err
	}
	if so := selectionOwner(c); so != "" {
		if err := s.d.Selection.SetSelected(ctx, so, []string{atin}, true); err != nil {
			s.d.Log.WarnContext(ctx, "selection update failed", "error", err)
		}
	}
	return s.present(ctx, c, up)
}

func (s *cartServiceImpl) UpdateLine(ctx context.Context, c *domain.Caller, lineID string, qty *int, selected *bool) (domain.Cart, error) {
	up, ok, err := s.fetch(ctx, c)
	if err != nil {
		return domain.Cart{}, err
	}
	line, found := up.Line(lineID)
	if !ok || !found {
		return domain.Cart{}, lineNotFound()
	}
	if qty != nil && *qty != line.Quantity {
		next, err := s.d.Cart.SetQuantity(ctx, owner(c), lineID, *qty)
		if err != nil {
			if p, ok := isStock(err); ok {
				return domain.Cart{}, s.stockProblem(ctx, lineID, line.Title, p.Detail, line.Quantity, *qty).WithCause(err)
			}
			return domain.Cart{}, cartErr(err, domain.CodeLineNotFound)
		}
		up = next
	}
	if selected != nil {
		if err := s.d.Selection.SetSelected(ctx, selectionOwner(c), []string{lineID}, *selected); err != nil {
			return domain.Cart{}, domain.UpstreamUnavailable("valkey").WithCause(err)
		}
	}
	return s.present(ctx, c, up)
}

func (s *cartServiceImpl) RemoveLine(ctx context.Context, c *domain.Caller, lineID string) (domain.Cart, error) {
	up, ok, err := s.fetch(ctx, c)
	if err != nil {
		return domain.Cart{}, err
	}
	if _, found := up.Line(lineID); !ok || !found {
		return domain.Cart{}, lineNotFound()
	}
	next, err := s.d.Cart.Remove(ctx, owner(c), lineID)
	if err != nil {
		return domain.Cart{}, cartErr(err, domain.CodeLineNotFound)
	}
	return s.present(ctx, c, next)
}

func (s *cartServiceImpl) SelectAll(ctx context.Context, c *domain.Caller, selected bool) (domain.Cart, error) {
	up, ok, err := s.fetch(ctx, c)
	if err != nil || !ok {
		return emptyCart(), err
	}
	var atins []string
	for _, p := range up.Packages {
		for _, l := range p.Lines {
			atins = append(atins, l.VariantATIN)
		}
	}
	if err := s.d.Selection.SetSelected(ctx, selectionOwner(c), atins, selected); err != nil {
		return domain.Cart{}, domain.UpstreamUnavailable("valkey").WithCause(err)
	}
	return s.present(ctx, c, up)
}

func (s *cartServiceImpl) SaveForLater(ctx context.Context, c *domain.Caller, lineID string) (domain.Cart, error) {
	if owner(c) == (port.CartOwner{}) {
		return domain.Cart{}, lineNotFound()
	}
	up, err := s.d.Cart.SaveForLater(ctx, owner(c), lineID)
	if err != nil {
		return domain.Cart{}, cartErr(err, domain.CodeLineNotFound)
	}
	return s.present(ctx, c, up)
}

func (s *cartServiceImpl) MoveToCart(ctx context.Context, c *domain.Caller, savedID string) (domain.Cart, error) {
	if owner(c) == (port.CartOwner{}) {
		return domain.Cart{}, cartErr(&port.UpstreamProblem{Status: 404, Code: port.CartLineNotFound}, domain.CodeSavedNotFound)
	}
	up, err := s.d.Cart.MoveToCart(ctx, owner(c), savedID)
	if err != nil {
		if p, ok := isStock(err); ok {
			return domain.Cart{}, s.stockProblem(ctx, savedID, "", p.Detail, 0, 1).WithCause(err)
		}
		if p, ok := port.AsUpstreamProblem(err); ok && p.Code == port.CartNotSellable {
			return domain.Cart{}, s.stockProblem(ctx, savedID, "", "", 0, 1).WithCause(err)
		}
		return domain.Cart{}, cartErr(err, domain.CodeSavedNotFound)
	}
	// The contract moves the item back with quantity 1 (the cart service keeps the saved quantity).
	if l, ok := up.Line(savedID); ok && l.Quantity > 1 {
		if next, err := s.d.Cart.SetQuantity(ctx, owner(c), savedID, 1); err == nil {
			up = next
		}
	}
	if so := selectionOwner(c); so != "" {
		_ = s.d.Selection.SetSelected(ctx, so, []string{savedID}, true)
	}
	return s.present(ctx, c, up)
}

func (s *cartServiceImpl) RemoveSaved(ctx context.Context, c *domain.Caller, savedID string) (domain.Cart, error) {
	notFound := domain.NewProblem(http.StatusNotFound, domain.CodeSavedNotFound, "Saved item not found", "That item is no longer saved.")
	up, ok, err := s.fetch(ctx, c)
	if err != nil {
		return domain.Cart{}, err
	}
	if _, found := up.SavedLine(savedID); !ok || !found {
		return domain.Cart{}, notFound
	}
	next, err := s.d.Cart.Remove(ctx, owner(c), savedID)
	if err != nil {
		return domain.Cart{}, cartErr(err, domain.CodeSavedNotFound)
	}
	return s.present(ctx, c, next)
}

type lineInfo struct {
	department, unit string
	perishable       bool
}

// perishableProduct mirrors orders' rule (HttpProductFactsClient): product type "perishable" or attribute
// perishable=true. Perishable packages need a handover code with AfroTransact Delivery and pickup.
func perishableProduct(p port.CatalogProduct) bool {
	return strings.EqualFold(p.ProductType, "perishable") || strings.EqualFold(p.Attributes["perishable"], "true")
}

// enrich adds department and unit (catalog, cached) to cart lines; failures leave them blank.
func (s *cartServiceImpl) enrich(ctx context.Context, atins []string) map[string]lineInfo {
	out := make(map[string]lineInfo, len(atins))
	var mu sync.Mutex
	var wg sync.WaitGroup
	sem := make(chan struct{}, 8)
	for _, a := range atins {
		wg.Go(func() {
			sem <- struct{}{}
			defer func() { <-sem }()
			v, err := s.d.Catalog.Variant(ctx, a)
			if err != nil {
				return
			}
			info := lineInfo{
				department: departmentFor(ctx, s.d.Catalog, v.Product.CategoryID),
				unit:       firstNonEmpty(optionsLabel(v.Variant.Options), attr(v.Product.Attributes, "unit", "size", "netContent", "packSize")),
				perishable: perishableProduct(v.Product),
			}
			mu.Lock()
			out[a] = info
			mu.Unlock()
		})
	}
	wg.Wait()
	return out
}

func cartID(c *domain.Caller) string {
	key := selectionOwner(c)
	if key == "" {
		return "cart_new"
	}
	sum := sha256.Sum256([]byte(key))
	return "cart_" + hex.EncodeToString(sum[:8])
}

func (s *cartServiceImpl) present(ctx context.Context, c *domain.Caller, up port.UpstreamCart) (domain.Cart, error) {
	deselected := map[string]bool{}
	if so := selectionOwner(c); so != "" {
		d, err := s.d.Selection.Deselected(ctx, so)
		if err != nil {
			s.d.Log.WarnContext(ctx, "selection read failed; treating every line as selected", "error", err)
		} else {
			deselected = d
		}
	}
	var atins []string
	for _, p := range up.Packages {
		for _, l := range p.Lines {
			atins = append(atins, l.VariantATIN)
		}
	}
	info := s.enrich(ctx, atins)
	now := s.d.Clock.now()
	cart := domain.Cart{CartID: cartID(c), Currency: firstNonEmpty(up.Currency, domain.Currency), Packages: []domain.CartPackage{}, Saved: []domain.SavedItem{}}
	sellers := map[string]bool{}
	allSelected := true
	lineCount := 0
	for _, p := range up.Packages {
		if len(p.Lines) == 0 {
			continue
		}
		fulfilment := normFulfilment(p.Fulfilment)
		kind := p.Lines[0].SellerKind
		if kind != domain.SellerKindPlatform {
			kind = domain.SellerKindSeller
		}
		sellerID := firstNonEmpty(p.SellerOrgID, map[bool]string{true: "afrotransact", false: "seller"}[kind == domain.SellerKindPlatform])
		seller := domain.Seller{ID: sellerID, Name: firstNonEmpty(p.SellerName, sellerFallback(kind)), Kind: kind, City: ""}
		pkg := domain.CartPackage{PackageID: sellerID + ":" + fulfilment, Seller: seller, Fulfilment: fulfilment, Lines: []domain.CartLine{}}
		for _, l := range p.Lines {
			available := max(0, l.Available)
			qty := max(1, l.Quantity)
			sel := !deselected[l.VariantATIN] && !l.Unavailable
			line := domain.CartLine{
				LineID: l.VariantATIN, ATIN: firstNonEmpty(l.ProductATIN, l.VariantATIN), OfferID: firstNonEmpty(l.OfferID, l.VariantATIN), Title: l.Title,
				Unit: info[l.VariantATIN].unit, Department: info[l.VariantATIN].department, Perishable: info[l.VariantATIN].perishable, ImageURL: strPtr(l.ImageURL),
				PriceMinor: l.PriceMinor, LineTotalMinor: l.PriceMinor * int64(qty), Quantity: qty,
				MaxQuantity: domain.MaxQuantity(available), AvailableQty: available, StockStatus: domain.StockStatusFor(available), Selected: sel,
			}
			pkg.Lines = append(pkg.Lines, line)
			pkg.ItemCount += qty
			cart.ItemCount += qty
			lineCount++
			if sel {
				pkg.SubtotalMinor += line.LineTotalMinor
				cart.SelectedCount += qty
				cart.SubtotalMinor += line.LineTotalMinor
			} else {
				allSelected = false
			}
		}
		pkg.DeliveryOptions = s.d.Estimator.Options(fulfilment, 0, pkg.SubtotalMinor, now)
		pkg.Delivery = s.d.Estimator.Promise(fulfilment, 0, now)
		if len(pkg.DeliveryOptions) > 0 {
			pkg.Delivery.Label = pkg.DeliveryOptions[0].ETA
		}
		pkg.FreeShippingNudge = s.d.Estimator.Nudge(fulfilment, pkg.SubtotalMinor)
		switch fulfilment {
		case domain.FulfilledByAfroTransact:
			pkg.Heading, pkg.ShipsFrom = "Shipped by AfroTransact", "AfroTransact"
		case domain.AfroTransactDelivery:
			city := s.storeCity(ctx, p.Lines[0].OfferID)
			pkg.Seller.City = city
			pkg.Heading, pkg.ShipsFrom = domain.LocalDeliveryHeading(city), firstNonEmpty(city, seller.Name)
		default:
			pkg.Heading, pkg.ShipsFrom = "Shipped by "+seller.Name, seller.Name
		}
		sellers[seller.ID] = true
		cart.Packages = append(cart.Packages, pkg)
	}
	for _, l := range up.Saved {
		cart.Saved = append(cart.Saved, domain.SavedItem{SavedID: l.VariantATIN, ATIN: firstNonEmpty(l.ProductATIN, l.VariantATIN),
			OfferID: firstNonEmpty(l.OfferID, l.VariantATIN), Title: l.Title, ImageURL: strPtr(l.ImageURL), PriceMinor: l.PriceMinor,
			Available: l.Available > 0 && !l.Unavailable})
	}
	cart.AllSelected = lineCount > 0 && allSelected
	cart.SellerCount = len(sellers)
	return cart, nil
}

// storeCity is the city of an AfroTransact Delivery offer's store (offers' seller.city is the ship-from city); best
// effort, empty when offers can't answer.
func (s *cartServiceImpl) storeCity(ctx context.Context, offerID string) string {
	if s.d.Offers == nil || offerID == "" {
		return ""
	}
	o, err := s.d.Offers.Offer(ctx, offerID)
	if err != nil {
		return ""
	}
	return o.Offer.Seller.City
}
