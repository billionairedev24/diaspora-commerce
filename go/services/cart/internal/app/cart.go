// Package app holds the cart use cases.
package app

import (
	"context"
	"errors"
	"fmt"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/services/cart/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/cart/internal/port"
)

// CartService is used by the storefront and app (via the BFF) and by checkout.
type CartService interface {
	View(ctx context.Context, owner domain.Owner) (domain.View, error)
	Add(ctx context.Context, owner domain.Owner, variantATIN string, qty int) (domain.View, error)
	SetQuantity(ctx context.Context, owner domain.Owner, variantATIN string, qty int) (domain.View, error)
	Remove(ctx context.Context, owner domain.Owner, variantATIN string) (domain.View, error)
	SaveForLater(ctx context.Context, owner domain.Owner, variantATIN string) (domain.View, error)
	MoveToCart(ctx context.Context, owner domain.Owner, variantATIN string) (domain.View, error)
	// MergeGuest folds a guest cart into the signed-in customer's cart and deletes the guest cart.
	MergeGuest(ctx context.Context, user domain.Owner, guestID string) (domain.View, error)
	// RemoveOrdered clears lines that were checked out (called when an order is placed).
	RemoveOrdered(ctx context.Context, owner domain.Owner, variantATINs []string) error
}

// ErrNotSellable is returned when a variant has no live offer.
var ErrNotSellable = errors.New("product is not available for sale")

type cartServiceImpl struct {
	guests  port.CartRepository
	users   port.CartRepository
	offers  port.OfferReader
	stock   port.AvailabilityReader
	retries int
	now     func() time.Time
}

var _ CartService = (*cartServiceImpl)(nil)

// NewCartService stores guest carts and customer carts in separate repositories (Redis TTL vs durable Postgres).
func NewCartService(guests, users port.CartRepository, offers port.OfferReader, stock port.AvailabilityReader) CartService {
	return &cartServiceImpl{guests: guests, users: users, offers: offers, stock: stock, retries: 3, now: time.Now}
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

func (s *cartServiceImpl) present(ctx context.Context, c domain.Cart) (domain.View, error) {
	atins := make([]string, 0, len(c.Lines)+len(c.Saved))
	for _, l := range append(append([]domain.Line{}, c.Lines...), c.Saved...) {
		atins = append(atins, l.VariantATIN)
	}
	live, err := s.offers.Offers(ctx, atins)
	if err != nil {
		return domain.View{}, fmt.Errorf("live offers: %w", err)
	}
	avail, err := s.stock.Available(ctx, atins)
	if err != nil {
		return domain.View{}, fmt.Errorf("availability: %w", err)
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

func (s *cartServiceImpl) offerAndStock(ctx context.Context, atin string) (domain.Offer, int, error) {
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

func (s *cartServiceImpl) Add(ctx context.Context, owner domain.Owner, atin string, qty int) (domain.View, error) {
	o, avail, err := s.offerAndStock(ctx, atin)
	if err != nil {
		return domain.View{}, err
	}
	c, err := s.mutate(ctx, owner, func(c *domain.Cart) error { return c.Add(o, qty, avail, s.now().UTC()) })
	if err != nil {
		return domain.View{}, err
	}
	return s.present(ctx, c)
}

func (s *cartServiceImpl) SetQuantity(ctx context.Context, owner domain.Owner, atin string, qty int) (domain.View, error) {
	avail, err := s.stock.Available(ctx, []string{atin})
	if err != nil {
		return domain.View{}, fmt.Errorf("availability: %w", err)
	}
	c, err := s.mutate(ctx, owner, func(c *domain.Cart) error { return c.SetQuantity(atin, qty, avail[atin]) })
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
	avail, err := s.stock.Available(ctx, []string{atin})
	if err != nil {
		return domain.View{}, fmt.Errorf("availability: %w", err)
	}
	c, err := s.mutate(ctx, owner, func(c *domain.Cart) error { return c.MoveToCart(atin, avail[atin]) })
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
	atins := make([]string, 0, len(guest.Lines))
	for _, l := range guest.Lines {
		atins = append(atins, l.VariantATIN)
	}
	avail, err := s.stock.Available(ctx, atins)
	if err != nil {
		return domain.View{}, fmt.Errorf("availability: %w", err)
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
