// Package offers implements port.OfferLookup over offers-service's public offer endpoint
// (GET /v1/public/offers/{offerId}), with a short cache: the cart re-reads offers on every view.
package offers

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"net/url"
	"strings"
	"sync"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/services/cart/internal/port"
)

type cached struct {
	at    time.Time
	offer port.LiveOffer
	found bool
}

type lookupImpl struct {
	base   string
	client *http.Client
	ttl    time.Duration

	mu    sync.Mutex
	cache map[string]cached
}

var _ port.OfferLookup = (*lookupImpl)(nil)

// New returns an OfferLookup against base (offers service). ttl bounds how stale price and stock may be (offers
// itself sends max-age 15 s on public reads).
func New(base string, client *http.Client, ttl time.Duration) port.OfferLookup {
	return &lookupImpl{base: strings.TrimSuffix(base, "/"), client: client, ttl: ttl, cache: map[string]cached{}}
}

type offerDetail struct {
	Offer struct {
		OfferID     string `json:"offerId"`
		ATIN        string `json:"atin"`
		ProductATIN string `json:"productAtin"`
		Seller      struct {
			ID   string `json:"id"`
			Name string `json:"name"`
			Kind string `json:"kind"`
		} `json:"seller"`
		PriceMinor   int64  `json:"priceMinor"`
		Currency     string `json:"currency"`
		Fulfilment   string `json:"fulfilment"`
		AvailableQty int    `json:"availableQty"`
	} `json:"offer"`
	Buyable bool `json:"buyable"`
}

func (l *lookupImpl) Offer(ctx context.Context, offerID string) (port.LiveOffer, error) {
	l.mu.Lock()
	if c, ok := l.cache[offerID]; ok && time.Since(c.at) < l.ttl {
		l.mu.Unlock()
		if !c.found {
			return port.LiveOffer{}, port.ErrOfferNotFound
		}
		return c.offer, nil
	}
	l.mu.Unlock()
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, l.base+"/v1/public/offers/"+url.PathEscape(offerID), nil)
	if err != nil {
		return port.LiveOffer{}, fmt.Errorf("request: %w", err)
	}
	res, err := l.client.Do(req)
	if err != nil {
		return port.LiveOffer{}, fmt.Errorf("offers: %w", err)
	}
	defer func() { _ = res.Body.Close() }()
	var entry cached
	switch res.StatusCode {
	case http.StatusOK:
		var d offerDetail
		if err := json.NewDecoder(res.Body).Decode(&d); err != nil {
			return port.LiveOffer{}, fmt.Errorf("offers decode: %w", err)
		}
		o := d.Offer
		entry = cached{found: true, offer: port.LiveOffer{OfferID: firstNonEmpty(o.OfferID, offerID), VariantATIN: o.ATIN,
			ProductATIN: o.ProductATIN, SellerOrgID: o.Seller.ID, SellerName: o.Seller.Name, SellerKind: o.Seller.Kind,
			Fulfilment: o.Fulfilment, PriceMinor: o.PriceMinor, Currency: strings.ToUpper(o.Currency),
			Available: max(0, o.AvailableQty), Buyable: d.Buyable}}
	case http.StatusNotFound, http.StatusBadRequest:
		entry = cached{found: false} // unknown, withdrawn or not an offer id
	default:
		return port.LiveOffer{}, fmt.Errorf("offers %s: status %d", offerID, res.StatusCode)
	}
	entry.at = time.Now()
	l.mu.Lock()
	l.cache[offerID] = entry
	l.mu.Unlock()
	if !entry.found {
		return port.LiveOffer{}, port.ErrOfferNotFound
	}
	return entry.offer, nil
}

func firstNonEmpty(v ...string) string {
	for _, s := range v {
		if s != "" {
			return s
		}
	}
	return ""
}
