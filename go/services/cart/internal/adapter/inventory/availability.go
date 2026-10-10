// Package inventory implements port.AvailabilityReader over inventory-service's public availability endpoint.
package inventory

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"net/url"
	"strings"

	"github.com/billionairedev24/diaspora-commerce/go/services/cart/internal/port"
)

type readerImpl struct {
	base   string
	client *http.Client
}

var _ port.AvailabilityReader = (*readerImpl)(nil)

// New returns an AvailabilityReader.
func New(base string, client *http.Client) port.AvailabilityReader {
	return &readerImpl{base: strings.TrimSuffix(base, "/"), client: client}
}

func (r *readerImpl) Available(ctx context.Context, atins []string) (map[string]int, error) {
	out := make(map[string]int, len(atins))
	if len(atins) == 0 {
		return out, nil
	}
	for start := 0; start < len(atins); start += 100 {
		end := min(start+100, len(atins))
		u := r.base + "/v1/availability?skus=" + url.QueryEscape(strings.Join(atins[start:end], ","))
		req, err := http.NewRequestWithContext(ctx, http.MethodGet, u, nil)
		if err != nil {
			return nil, fmt.Errorf("request: %w", err)
		}
		res, err := r.client.Do(req)
		if err != nil {
			return nil, fmt.Errorf("inventory: %w", err)
		}
		var rows []struct {
			SKU       string `json:"sku"`
			Available int    `json:"available"`
		}
		err = json.NewDecoder(res.Body).Decode(&rows)
		_ = res.Body.Close()
		if res.StatusCode != http.StatusOK {
			return nil, fmt.Errorf("inventory: status %d", res.StatusCode)
		}
		if err != nil {
			return nil, fmt.Errorf("inventory decode: %w", err)
		}
		for _, row := range rows {
			out[row.SKU] = row.Available
		}
	}
	return out, nil
}
