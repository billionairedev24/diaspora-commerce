// Package app holds the cart use cases.
package app

import (
	"context"
	"errors"
	"fmt"
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
	guests  port.CartRepository
	users   port.CartRepository
	offers  port.OfferReader // catalog: product facts (title, image, options) and the legacy single seller
	stock   port.AvailabilityReader
	lookup  port.OfferLookup // offers service: lines that carry an offerId
	retries int
	now     func() time.Time
}

var _ CartService = (*cartServiceImpl)(nil)

// NewCartService stores guest carts and customer carts in separate repositories (Redis TTL vs durable Postgres).
// Lines with an offerId are priced, stocked and validated by lookup (offers service); the others by the catalog
// reader and inventory, as before offers existed.
func NewCartService(guests, users port.CartRepository, offers port.OfferReader, stock port.AvailabilityReader, lookup port.OfferLookup) CartService {
	return &cartServiceImpl{guests: guests, users: users, offers: offers, stock: stock, lookup: lookup, retries: 3, now: time.Now}
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
	return c
}

func (s *cartServiceImpl) present(ctx context.Context, c domain.Cart) (domain.View, error) {
	live, avail, err := s.live(ctx, append(append([]domain.Line{}, c.Lines...), c.Saved...))
	if err != nil {
		return domain.View{}, err
	}
	return c.Present(live, avail), nil
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
