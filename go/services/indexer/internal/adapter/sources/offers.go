package sources

import (
	"context"
	"fmt"
	"net/http"
	"net/url"
	"strings"

	"github.com/billionairedev24/diaspora-commerce/go/services/indexer/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/indexer/internal/port"
)

// MaxBuyBoxATINs is how many variant ATINs offers-service answers for in one call.
const MaxBuyBoxATINs = 50

type offersImpl struct {
	base   string
	client *http.Client
}

var _ port.BuyBoxSource = (*offersImpl)(nil)

// NewOffers reads offers-service's public buy boxes (GET /v1/public/buy-boxes, anonymous: the storefront reads it too).
func NewOffers(baseURL string, httpClient *http.Client) port.BuyBoxSource {
	return &offersImpl{base: strings.TrimRight(baseURL, "/"), client: httpClient}
}

type buyBoxList struct {
	Items []struct {
		ATIN   string `json:"atin"`
		BuyBox *struct {
			OfferID      string `json:"offerId"`
			AvailableQty int    `json:"availableQty"`
		} `json:"buyBox"`
	} `json:"items"`
}

func (o *offersImpl) BuyBoxes(ctx context.Context, variantATINs []string) ([]domain.VariantBuyBox, error) {
	if len(variantATINs) == 0 {
		return nil, nil
	}
	if len(variantATINs) > MaxBuyBoxATINs {
		return nil, fmt.Errorf("buy boxes: %d ATINs, at most %d per call", len(variantATINs), MaxBuyBoxATINs)
	}
	var list buyBoxList
	path := "/v1/public/buy-boxes?by=variant&atins=" + url.QueryEscape(strings.Join(variantATINs, ","))
	if err := getJSON(ctx, o.client, o.base, path, &list); err != nil {
		return nil, fmt.Errorf("offers: %w", err)
	}
	out := make([]domain.VariantBuyBox, 0, len(list.Items))
	for _, it := range list.Items {
		bb := domain.VariantBuyBox{ATIN: it.ATIN}
		if it.BuyBox != nil {
			bb.WinnerOfferID, bb.AvailableQty = it.BuyBox.OfferID, it.BuyBox.AvailableQty
		}
		out = append(out, bb)
	}
	return out, nil
}
