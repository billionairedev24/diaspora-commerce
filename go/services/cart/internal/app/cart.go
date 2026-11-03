// Package app holds the cart use cases.
package app

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"strings"
	"sync"
	"time"

	"golang.org/x/sync/errgroup"

	"github.com/billionairedev24/diaspora-commerce/go/services/cart/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/cart/internal/port"
)

// CartService is used by the storefront and app (via the BFF) and by checkout.
type CartService interface {
	View(ctx context.Context, owner domain.Owner) (domain.View, error)
	// Add puts qty of a variant in the cart. With offerID the line is that offers-service offer (validated: it must
	// exist, be buyable and match variantATIN when both are given); without it the catalog's seller applies.
	Add(ctx context.Context, owner domain.Owner, variantATIN, offerID string, qty int) (domain.View, error)
	SetQuantity(ctx context.Context, owner domain.Owner, variantATIN string, qty int) (domain.View, error)
	Remove(ctx context.Context, owner domain.Owner, variantATIN string) (domain.View, error)
	SaveForLater(ctx context.Context, owner domain.Owner, variantATIN string) (domain.View, error)
	MoveToCart(ctx context.Context, owner domain.Owner, variantATIN string) (domain.View, error)
	// MergeGuest folds a guest cart into the signed-in customer's cart and deletes the guest cart.
	MergeGuest(ctx context.Context, user domain.Owner, guestID string) (domain.View, error)
	// RemoveOrdered clears lines that were checked out (called when an order is placed).
	RemoveOrdered(ctx context.Context, owner domain.Owner, variantATINs []string) error
	// SetPreorderFulfilment records the door / pickup choice for a pre-order window (S272).
	SetPreorderFulfilment(ctx context.Context, owner domain.Owner, windowID, fulfilment string) (domain.View, error)
}

// Option configures the cart service.
type Option func(*cartServiceImpl)

// WithRuns offers the next open delivery run staff scheduled (S261) to AfroTransact-held lines before pre-order windows.
func WithRuns(r port.DeliveryRuns) Option {
	return func(s *cartServiceImpl) { s.runs = r }
}

// WithPreorders groups pre-order lines into window packages when the request names a delivery location (S272).
func WithPreorders(p port.PreorderEligibility) Option {
	return func(s *cartServiceImpl) { s.preorders = p }
}

type locationKey struct{}

// WithLocation puts the shopper's delivery location (from the BFF) on the request context; cart views then show the
// pre-order packages that apply there.
func WithLocation(ctx context.Context, loc domain.Location) context.Context {
	return context.WithValue(ctx, locationKey{}, loc)
}

func locationOf(ctx context.Context) domain.Location {
	loc, _ := ctx.Value(locationKey{}).(domain.Location)
	return loc
}

// Errors of the cart use cases.
var (
	// ErrNotSellable is returned when a variant (or the named offer) has no live offer.
	ErrNotSellable = errors.New("product is not available for sale")
	// ErrNotBuyable is returned when the named offer exists but cannot be bought now (paused, out of stock,
	// product not live, seller suppressed).
	ErrNotBuyable = errors.New("this offer cannot be bought right now")
	// ErrOfferMismatch is returned when offerId and variantAtin name different variants.
	ErrOfferMismatch = errors.New("the offer is for another variant")
)

type cartServiceImpl struct {
	guests port.CartRepository
	users  port.CartRepository
	offers port.OfferReader // catalog: product facts (title, image, options) and the legacy single seller
	stock  port.AvailabilityReader
	lookup port.OfferLookup // offers service: lines that carry an offerId
	// preorders is delivery-runs eligibility (nil: no pre-orders, every line ships now).
	preorders port.PreorderEligibility
	// runs lists the open delivery runs for a ZIP (nil: no runs are offered).
	runs    port.DeliveryRuns
	retries int
	now     func() time.Time
}

var _ CartService = (*cartServiceImpl)(nil)

// NewCartService stores guest carts and customer carts in separate repositories (Redis TTL vs durable Postgres).
// Lines with an offerId are priced, stocked and validated by lookup (offers service); the others by the catalog
// reader and inventory, as before offers existed.
func NewCartService(guests, users port.CartRepository, offers port.OfferReader, stock port.AvailabilityReader, lookup port.OfferLookup, opts ...Option) CartService {
	s := &cartServiceImpl{guests: guests, users: users, offers: offers, stock: stock, lookup: lookup, retries: 3, now: time.Now}
	for _, o := range opts {
		o(s)
	}
	return s
}

func (s *cartServiceImpl) repo(o domain.Owner) port.CartRepository {
	if o.Kind == domain.OwnerUser {
		return s.users
	}
	return s.guests
}

// mutate loads, applies change and saves, retrying when another request changed the cart in between.
func (s *cartServiceImpl) mutate(ctx context.Context, owner domain.Owner, change func(*domain.Cart) error) (domain.Cart, error) {
	var last error
	for range s.retries {
		c, err := s.repo(owner).Load(ctx, owner)
		if err != nil {
			return domain.Cart{}, fmt.Errorf("load cart: %w", err)
		}
		if err := change(&c); err != nil {
			return domain.Cart{}, err
		}
		err = s.repo(owner).Save(ctx, &c)
		if err == nil {
			return c, nil
		}
		if !errors.Is(err, domain.ErrConflict) {
			return domain.Cart{}, fmt.Errorf("save cart: %w", err)
		}
		last = err
	}
	return domain.Cart{}, last
}

// live returns the live offer and sellable units of every line, keyed by variant ATIN. Lines with an offerId read
// the offers service (a missing or non-buyable offer leaves the line without a live offer, i.e. unavailable);
// legacy lines read the catalog and inventory.
func (s *cartServiceImpl) live(ctx context.Context, lines []domain.Line) (map[string]domain.Offer, map[string]int, error) {
	atins := make([]string, 0, len(lines))
	var legacy []string
	byOffer := map[string]string{}
	for _, l := range lines {
		atins = append(atins, l.VariantATIN)
		if l.OfferID == "" {
			legacy = append(legacy, l.VariantATIN)
		} else {
			byOffer[l.OfferID] = l.VariantATIN
		}
	}
	live, err := s.offers.Offers(ctx, atins)
	if err != nil {
		return nil, nil, fmt.Errorf("live offers: %w", err)
	}
	avail := map[string]int{}
	if len(legacy) > 0 {
		if avail, err = s.stock.Available(ctx, legacy); err != nil {
			return nil, nil, fmt.Errorf("availability: %w", err)
		}
	}
	if len(byOffer) == 0 {
		return live, avail, nil
	}
	if s.lookup == nil {
		return nil, nil, fmt.Errorf("offer lines: %w", errNoOffersService)
	}
	var mu sync.Mutex
	g, gctx := errgroup.WithContext(ctx)
	g.SetLimit(8)
	for id, atin := range byOffer {
		g.Go(func() error {
			lo, err := s.lookup.Offer(gctx, id)
			if err != nil && !errors.Is(err, port.ErrOfferNotFound) {
				return fmt.Errorf("offer %s: %w", id, err)
			}
			mu.Lock()
			defer mu.Unlock()
			if err != nil || !lo.Buyable || lo.VariantATIN != atin {
				delete(live, atin)
				avail[atin] = max(0, lo.Available)
				return nil
			}
			live[atin] = withOffer(live[atin], lo)
			avail[atin] = lo.Available
			return nil
		})
	}
	if err := g.Wait(); err != nil {
		return nil, nil, fmt.Errorf("live offers: %w", err)
	}
	return live, avail, nil
}

var errNoOffersService = errors.New("offers service not configured")

// withOffer overlays the offers-service terms (seller, fulfilment, price) on the catalog's product facts.
func withOffer(c domain.Offer, lo port.LiveOffer) domain.Offer {
	c.OfferID, c.VariantATIN = lo.OfferID, lo.VariantATIN
	if lo.ProductATIN != "" {
		c.ProductATIN = lo.ProductATIN
	}
	c.SellerOrgID, c.SellerKind, c.Fulfilment = lo.SellerOrgID, lo.SellerKind, lo.Fulfilment
	c.SellerName = lo.SellerName
	if c.SellerName == "" {
		c.SellerName = "Marketplace seller"
	}
	c.PriceMinor, c.Currency = lo.PriceMinor, lo.Currency
	c.ShipFromLocationID = lo.ShipFromLocationID
	return c
}

func (s *cartServiceImpl) present(ctx context.Context, c domain.Cart) (domain.View, error) {
	live, avail, err := s.live(ctx, append(append([]domain.Line{}, c.Lines...), c.Saved...))
	if err != nil {
		return domain.View{}, err
	}
	return c.PresentWithPreorders(live, avail, s.preorderLines(ctx, c.Lines, live, avail)), nil
}

// preorderLines says which lines are not "ships now" at the request's location, and through what. Precedence (S261):
// AfroTransact-held goods that are in stock go on the next open delivery run staff scheduled for the ZIP when one has
// room; every other line is asked about at delivery-runs' pre-order windows, so a line is never offered both. The
// cart degrades to "ships now" (logged) when delivery-runs is unavailable; checkout asks again and decides.
func (s *cartServiceImpl) preorderLines(ctx context.Context, lines []domain.Line, live map[string]domain.Offer,
	avail map[string]int) map[string]domain.PreorderLine {
	loc := locationOf(ctx)
	if !loc.Known() || len(lines) == 0 {
		return nil
	}
	out := map[string]domain.PreorderLine{}
	rest := make([]string, 0, len(lines))
	run, hasRun := s.nextRun(ctx, loc)
	for _, l := range lines {
		o, ok := live[l.VariantATIN]
		if hasRun && ok && domain.HeldByAfroTransact(o) && avail[l.VariantATIN] >= l.Quantity {
			out[l.VariantATIN] = domain.RunLine(run)
			continue
		}
		rest = append(rest, l.VariantATIN)
	}
	if s.preorders != nil && len(rest) > 0 && (loc.Country != "" || loc.Region != "" || loc.City != "") {
		got, err := s.preorders.Eligibility(ctx, loc, rest)
		if err != nil {
			slog.WarnContext(ctx, "pre-order eligibility unavailable; showing those lines as ships now", "error", err)
		}
		for atin, l := range got {
			out[atin] = l
		}
	}
	return out
}

// nextRun is the first open run covering the ZIP that has room.
func (s *cartServiceImpl) nextRun(ctx context.Context, loc domain.Location) (domain.OpenRun, bool) {
	if s.runs == nil || loc.PostalCode == "" {
		return domain.OpenRun{}, false
	}
	runs, err := s.runs.OpenRuns(ctx, loc.PostalCode)
	if err != nil {
		slog.WarnContext(ctx, "open delivery runs unavailable; no run is offered", "error", err)
		return domain.OpenRun{}, false
	}
	for _, r := range runs {
		if !r.Full {
			return r, true
		}
	}
	return domain.OpenRun{}, false
}

func (s *cartServiceImpl) SetPreorderFulfilment(ctx context.Context, owner domain.Owner, windowID, fulfilment string) (domain.View, error) {
	c, err := s.mutate(ctx, owner, func(c *domain.Cart) error { return c.SetPreorderFulfilment(windowID, fulfilment) })
	if err != nil {
		return domain.View{}, err
	}
	return s.present(ctx, c)
}

func (s *cartServiceImpl) View(ctx context.Context, owner domain.Owner) (domain.View, error) {
	c, err := s.repo(owner).Load(ctx, owner)
	if err != nil {
		return domain.View{}, fmt.Errorf("load cart: %w", err)
	}
	return s.present(ctx, c)
}

func (s *cartServiceImpl) offerAndStock(ctx context.Context, atin, offerID string) (domain.Offer, int, error) {
	if offerID != "" {
		if s.lookup == nil {
			return domain.Offer{}, 0, fmt.Errorf("offer %s: %w", offerID, errNoOffersService)
		}
		lo, err := s.lookup.Offer(ctx, offerID)
		if errors.Is(err, port.ErrOfferNotFound) {
			return domain.Offer{}, 0, ErrNotSellable
		}
		if err != nil {
			return domain.Offer{}, 0, fmt.Errorf("offer: %w", err)
		}
		if atin != "" && !strings.EqualFold(atin, lo.VariantATIN) {
			return domain.Offer{}, 0, ErrOfferMismatch
		}
		if !lo.Buyable {
			return domain.Offer{}, 0, ErrNotBuyable
		}
		facts, err := s.offers.Offers(ctx, []string{lo.VariantATIN})
		if err != nil {
			return domain.Offer{}, 0, fmt.Errorf("offer: %w", err)
		}
		return withOffer(facts[lo.VariantATIN], lo), lo.Available, nil
	}
	offers, err := s.offers.Offers(ctx, []string{atin})
	if err != nil {
		return domain.Offer{}, 0, fmt.Errorf("offer: %w", err)
	}
	o, ok := offers[atin]
	if !ok {
		return domain.Offer{}, 0, ErrNotSellable
	}
	avail, err := s.stock.Available(ctx, []string{atin})
	if err != nil {
		return domain.Offer{}, 0, fmt.Errorf("availability: %w", err)
	}
	return o, avail[atin], nil
}

func (s *cartServiceImpl) Add(ctx context.Context, owner domain.Owner, atin, offerID string, qty int) (domain.View, error) {
	o, avail, err := s.offerAndStock(ctx, atin, offerID)
	if err != nil {
		return domain.View{}, err
	}
	c, err := s.mutate(ctx, owner, func(c *domain.Cart) error { return c.Add(o, qty, avail, s.now().UTC()) })
	if err != nil {
		return domain.View{}, err
	}
	return s.present(ctx, c)
}

// availableFor returns the sellable units of the cart's line (or saved line) for atin, from offers or inventory.
func (s *cartServiceImpl) availableFor(ctx context.Context, owner domain.Owner, atin string) (int, error) {
	c, err := s.repo(owner).Load(ctx, owner)
	if err != nil {
		return 0, fmt.Errorf("load cart: %w", err)
	}
	line := domain.Line{Offer: domain.Offer{VariantATIN: atin}}
	for _, l := range append(append([]domain.Line{}, c.Lines...), c.Saved...) {
		if l.VariantATIN == atin {
			line = l
			break
		}
	}
	_, avail, err := s.live(ctx, []domain.Line{line})
	if err != nil {
		return 0, err
	}
	return avail[atin], nil
}

func (s *cartServiceImpl) SetQuantity(ctx context.Context, owner domain.Owner, atin string, qty int) (domain.View, error) {
	avail, err := s.availableFor(ctx, owner, atin)
	if err != nil {
		return domain.View{}, err
	}
	c, err := s.mutate(ctx, owner, func(c *domain.Cart) error { return c.SetQuantity(atin, qty, avail) })
	if err != nil {
		return domain.View{}, err
	}
	return s.present(ctx, c)
}

func (s *cartServiceImpl) Remove(ctx context.Context, owner domain.Owner, atin string) (domain.View, error) {
	c, err := s.mutate(ctx, owner, func(c *domain.Cart) error { return c.Remove(atin) })
	if err != nil {
		return domain.View{}, err
	}
	return s.present(ctx, c)
}

func (s *cartServiceImpl) SaveForLater(ctx context.Context, owner domain.Owner, atin string) (domain.View, error) {
	c, err := s.mutate(ctx, owner, func(c *domain.Cart) error { return c.SaveForLater(atin) })
	if err != nil {
		return domain.View{}, err
	}
	return s.present(ctx, c)
}

func (s *cartServiceImpl) MoveToCart(ctx context.Context, owner domain.Owner, atin string) (domain.View, error) {
	avail, err := s.availableFor(ctx, owner, atin)
	if err != nil {
		return domain.View{}, err
	}
	c, err := s.mutate(ctx, owner, func(c *domain.Cart) error { return c.MoveToCart(atin, avail) })
	if err != nil {
		return domain.View{}, err
	}
	return s.present(ctx, c)
}

func (s *cartServiceImpl) MergeGuest(ctx context.Context, user domain.Owner, guestID string) (domain.View, error) {
	guestOwner := domain.Owner{Kind: domain.OwnerGuest, ID: guestID}
	guest, err := s.guests.Load(ctx, guestOwner)
	if err != nil {
		return domain.View{}, fmt.Errorf("load guest cart: %w", err)
	}
	_, avail, err := s.live(ctx, guest.Lines)
	if err != nil {
		return domain.View{}, err
	}
	c, err := s.mutate(ctx, user, func(c *domain.Cart) error { c.Merge(guest, avail); return nil })
	if err != nil {
		return domain.View{}, err
	}
	if err := s.guests.Delete(ctx, guestOwner); err != nil {
		return domain.View{}, fmt.Errorf("delete guest cart: %w", err)
	}
	return s.present(ctx, c)
}

func (s *cartServiceImpl) RemoveOrdered(ctx context.Context, owner domain.Owner, atins []string) error {
	_, err := s.mutate(ctx, owner, func(c *domain.Cart) error { c.RemoveOrdered(atins); return nil })
	return err
}
