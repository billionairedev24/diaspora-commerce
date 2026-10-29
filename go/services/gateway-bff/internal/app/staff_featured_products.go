package app

import (
	"context"
	"net/http"
	"net/url"
	"strings"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
)

// maxFeaturedHits is how many products the picker lists at once.
const maxFeaturedHits = 8

// SearchFeaturedProducts finds products by name for the advertorial editor, from the search index the shop front uses
// (so only products shoppers can find are offered). Staff pick by name; the card keeps the product code, the name and
// the picture, never a price.
func (s *staffPreordersImpl) SearchFeaturedProducts(ctx context.Context, c *domain.Caller, q string) (any, error) {
	q = strings.TrimSpace(q)
	if n := len([]rune(q)); n < 2 || n > 120 {
		return nil, domain.ValidationProblem(map[string]string{"q": "Type 2 to 120 characters"})
	}
	sc, _, err := s.need(ctx, c, poView)
	if err != nil {
		return nil, err
	}
	if sc.up.Search == nil {
		return nil, domain.NewProblem(http.StatusServiceUnavailable, domain.CodeUpstreamUnavailable, "Product search is not available", "Try again in a moment.")
	}
	var res struct {
		Hits []jobj `json:"hits"`
	}
	if err := sc.get(ctx, sc.up.Search, "/v1/search", url.Values{"q": {q}, "size": {"8"}}, &res); err != nil {
		return nil, err
	}
	items := make([]any, 0, len(res.Hits))
	for _, h := range res.Hits {
		atin, title := jstr(h, "atin"), jstr(h, "title")
		if atin == "" || title == "" {
			continue
		}
		item := map[string]any{"atin": atin, "title": title}
		if img := jstr(h, "imageUrl"); strings.HasPrefix(img, "https://") {
			item["imageUrl"] = img
		}
		if seller := jstr(h, "sellerName"); seller != "" {
			item["sellerName"] = seller
		}
		items = append(items, item)
		if len(items) >= maxFeaturedHits {
			break
		}
	}
	return map[string]any{"items": items}, nil
}
