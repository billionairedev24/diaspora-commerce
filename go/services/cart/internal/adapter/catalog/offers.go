// Package catalog implements port.OfferReader over catalog-service's public variant endpoint, with a short cache
// (prices are snapshotted on the line; the live price is re-read on every cart view).
package catalog

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"net/url"
	"strings"
	"sync"
	"time"

	"golang.org/x/sync/errgroup"

	"github.com/billionairedev24/diaspora-commerce/go/services/cart/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/cart/internal/port"
)

type cached struct {
	at    time.Time
	offer *domain.Offer
}

type readerImpl struct {
	base     string
	client   *http.Client
	ttl      time.Duration
	retailID string
	names    map[string]string

	mu    sync.Mutex
	cache map[string]cached
}

var _ port.OfferReader = (*readerImpl)(nil)

// New returns an OfferReader. retailOrgID identifies AfroTransact Retail (fulfilled by AfroTransact);
// names maps seller org ids to display names until the seller service exposes them.
func New(base string, client *http.Client, ttl time.Duration, retailOrgID string, names map[string]string) port.OfferReader {
	return &readerImpl{base: strings.TrimSuffix(base, "/"), client: client, ttl: ttl, retailID: retailOrgID,
		names: names, cache: map[string]cached{}}
}

type publicVariant struct {
	Product struct {
		ATIN        string `json:"atin"`
		OwnerKind   string `json:"ownerKind"`
		SellerOrgID string `json:"sellerOrgId"`
		Title       string `json:"title"`
		Images      []struct {
			URL string `json:"url"`
		} `json:"images"`
	} `json:"product"`
	Variant struct {
		ATIN       string            `json:"atin"`
		Options    map[string]string `json:"options"`
		PriceMinor int64             `json:"priceMinor"`
		Currency   string            `json:"currency"`
	} `json:"variant"`
}

func (r *readerImpl) Offers(ctx context.Context, atins []string) (map[string]domain.Offer, error) {
	out := map[string]domain.Offer{}
	var mu sync.Mutex
	g, gctx := errgroup.WithContext(ctx)
	g.SetLimit(8)
	for _, a := range atins {
		g.Go(func() error {
			o, err := r.one(gctx, a)
			if err != nil {
				return err
			}
			if o != nil {
				mu.Lock()
				out[a] = *o
				mu.Unlock()
			}
			return nil
		})
	}
	if err := g.Wait(); err != nil {
		return nil, fmt.Errorf("offers: %w", err)
	}
	return out, nil
}

func (r *readerImpl) one(ctx context.Context, atin string) (*domain.Offer, error) {
	r.mu.Lock()
	if c, ok := r.cache[atin]; ok && time.Since(c.at) < r.ttl {
		r.mu.Unlock()
		return c.offer, nil
	}
	r.mu.Unlock()
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, r.base+"/v1/public/variants/"+url.PathEscape(atin), nil)
	if err != nil {
		return nil, fmt.Errorf("request: %w", err)
	}
	res, err := r.client.Do(req)
	if err != nil {
		return nil, fmt.Errorf("catalog: %w", err)
	}
	defer func() { _ = res.Body.Close() }()
	var offer *domain.Offer
	switch res.StatusCode {
	case http.StatusOK:
		var pv publicVariant
		if err := json.NewDecoder(res.Body).Decode(&pv); err != nil {
			return nil, fmt.Errorf("catalog decode: %w", err)
		}
		offer = r.toOffer(pv)
	case http.StatusNotFound:
		offer = nil // not live: the line shows as unavailable
	default:
		return nil, fmt.Errorf("catalog %s: status %d", atin, res.StatusCode)
	}
	r.mu.Lock()
	r.cache[atin] = cached{at: time.Now(), offer: offer}
	r.mu.Unlock()
	return offer, nil
}

func (r *readerImpl) toOffer(pv publicVariant) *domain.Offer {
	o := domain.Offer{
		VariantATIN: pv.Variant.ATIN, ProductATIN: pv.Product.ATIN, Title: pv.Product.Title,
		Options: pv.Variant.Options, SellerOrgID: pv.Product.SellerOrgID, SellerKind: pv.Product.OwnerKind,
		PriceMinor: pv.Variant.PriceMinor, Currency: strings.ToUpper(pv.Variant.Currency),
		Fulfilment: "SELLER", SellerName: r.names[pv.Product.SellerOrgID],
	}
	if len(pv.Product.Images) > 0 {
		o.ImageURL = pv.Product.Images[0].URL
	}
	if pv.Product.OwnerKind == "PLATFORM" || pv.Product.SellerOrgID == r.retailID {
		o.Fulfilment, o.SellerName = "AFROTRANSACT", "AfroTransact Retail"
	}
	if o.SellerName == "" {
		o.SellerName = "Marketplace seller"
	}
	return &o
}
