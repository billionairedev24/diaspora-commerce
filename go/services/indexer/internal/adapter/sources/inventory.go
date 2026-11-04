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

// MaxStockSKUs is how many SKUs inventory-service answers for in one call.
const MaxStockSKUs = 100

type inventoryImpl struct {
	base   string
	client *http.Client
}

var _ port.StockSource = (*inventoryImpl)(nil)

// NewInventory reads inventory-service's public availability (GET /v1/availability, anonymous: the storefront reads
// it too).
func NewInventory(baseURL string, httpClient *http.Client) port.StockSource {
	return &inventoryImpl{base: strings.TrimRight(baseURL, "/"), client: httpClient}
}

type availability struct {
	SKU   string `json:"sku"`
	Nodes []struct {
		NodeID    string `json:"nodeId"`
		Available int    `json:"available"`
		Version   int64  `json:"version"`
	} `json:"nodes"`
}

func (i *inventoryImpl) Stock(ctx context.Context, skus []string) ([]domain.NodeStock, error) {
	if len(skus) == 0 {
		return nil, nil
	}
	if len(skus) > MaxStockSKUs {
		return nil, fmt.Errorf("stock: %d SKUs, at most %d per call", len(skus), MaxStockSKUs)
	}
	var list []availability
	path := "/v1/availability?skus=" + url.QueryEscape(strings.Join(skus, ","))
	if err := getJSON(ctx, i.client, i.base, path, &list); err != nil {
		return nil, fmt.Errorf("inventory: %w", err)
	}
	var out []domain.NodeStock
	for _, a := range list {
		for _, n := range a.Nodes {
			out = append(out, domain.NodeStock{SKU: a.SKU, Node: n.NodeID, Available: n.Available, Version: n.Version})
		}
	}
	return out, nil
}
