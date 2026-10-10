package upstreams

import (
	"context"
	"net/http"
	"net/url"
	"slices"
	"strings"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/adapter/httpclient"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

// ---------------------------------------------------------------- offers (contracts/openapi/offers.yaml, public reads)

type storefrontOffersImpl struct {
	c       httpclient.Client
	cache   port.Cache
	ceiling time.Duration
}

var _ port.StorefrontOffersClient = (*storefrontOffersImpl)(nil)

// maxBuyBoxBatch is offers' batch limit (OfferListingService.MAX_BATCH).
const maxBuyBoxBatch = 50

// NewStorefrontOffers returns the offers client for the storefront (anonymous reads). Buy boxes are public and cached
// for offers' max-age (15 s) capped by ceiling; single offers for add-to-cart are always read live.
func NewStorefrontOffers(c httpclient.Client, cache port.Cache, ceiling time.Duration) port.StorefrontOffersClient {
	return &storefrontOffersImpl{c: c, cache: cache, ceiling: ceiling}
}

func (o *storefrontOffersImpl) ttl(h http.Header) time.Duration { return ttlFor(h, o.ceiling, 0) }

func (o *storefrontOffersImpl) BuyBox(ctx context.Context, atin string) (port.OfferBuyBox, error) {
	return cached(o.cache, "offers:buybox:"+atin, func() (port.OfferBuyBox, http.Header, error) {
		var out port.OfferBuyBox
		res, err := call(ctx, o.c, httpclient.Request{Path: "/v1/public/atins/" + url.PathEscape(atin) + "/offers"}, &out)
		if err != nil {
			return out, nil, err
		}
		return out, res.Header, nil
	}, o.ttl)
}

func (o *storefrontOffersImpl) ProductSummaries(ctx context.Context, atins []string) (map[string]port.OfferBuyBox, error) {
	out := map[string]port.OfferBuyBox{}
	unique := slices.Compact(slices.Sorted(slices.Values(atins)))
	for chunk := range slices.Chunk(unique, maxBuyBoxBatch) {
		key := "offers:summaries:" + strings.Join(chunk, ",")
		page, err := cached(o.cache, key, func() ([]port.OfferBuyBox, http.Header, error) {
			var body struct {
				Items []port.OfferBuyBox `json:"items"`
			}
			res, err := call(ctx, o.c, httpclient.Request{Path: "/v1/public/buy-boxes",
				Query: url.Values{"atins": {strings.Join(chunk, ",")}, "by": {"product"}}}, &body)
			if err != nil {
				return nil, nil, err
			}
			return body.Items, res.Header, nil
		}, o.ttl)
		if err != nil {
			return nil, err
		}
		for _, b := range page {
			out[b.ATIN] = b
		}
	}
	return out, nil
}

func (o *storefrontOffersImpl) Offer(ctx context.Context, offerID string) (port.OfferDetail, error) {
	var out port.OfferDetail
	_, err := call(ctx, o.c, httpclient.Request{Path: "/v1/public/offers/" + url.PathEscape(offerID)}, &out)
	return out, err
}
