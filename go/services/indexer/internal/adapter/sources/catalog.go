package sources

import (
	"context"
	"fmt"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"time"

	"golang.org/x/oauth2"
	"golang.org/x/oauth2/clientcredentials"

	"github.com/billionairedev24/diaspora-commerce/go/services/indexer/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/indexer/internal/port"
)

// CatalogConfig configures the catalog export client.
type CatalogConfig struct {
	BaseURL      string // http://catalog
	TokenURL     string // <issuer>/oauth2/token; empty = no token (local profile, catalog security off)
	ClientID     string // indexer-service
	ClientSecret string
	Scopes       []string // catalog:read
}

type catalogImpl struct {
	base   string
	client *http.Client
}

var _ port.ProductSource = (*catalogImpl)(nil)

// NewCatalog reads catalog-service's search export (GET /v1/search-export/products) with the client-credentials token
// of client indexer-service (scope catalog:read), cached and refreshed before expiry.
func NewCatalog(cfg CatalogConfig, httpClient *http.Client) port.ProductSource {
	client := httpClient
	if cfg.TokenURL != "" && cfg.ClientSecret != "" {
		cc := clientcredentials.Config{ClientID: cfg.ClientID, ClientSecret: cfg.ClientSecret, TokenURL: cfg.TokenURL,
			Scopes: cfg.Scopes, AuthStyle: oauth2.AuthStyleInHeader}
		client = cc.Client(context.WithValue(context.Background(), oauth2.HTTPClient, httpClient))
		client.Timeout = httpClient.Timeout
	}
	return &catalogImpl{base: strings.TrimRight(cfg.BaseURL, "/"), client: client}
}

func (c *catalogImpl) Products(ctx context.Context, q domain.ProductExportQuery) (domain.ProductExportPage, error) {
	v := url.Values{}
	if q.Size > 0 {
		v.Set("size", strconv.Itoa(q.Size))
	}
	if q.After != "" {
		v.Set("after", q.After)
	}
	if !q.Since.IsZero() {
		v.Set("since", q.Since.UTC().Format(time.RFC3339Nano))
	}
	for _, s := range q.Statuses {
		v.Add("status", s)
	}
	var page domain.ProductExportPage
	if err := getJSON(ctx, c.client, c.base, "/v1/search-export/products?"+v.Encode(), &page); err != nil {
		return domain.ProductExportPage{}, fmt.Errorf("catalog: %w", err)
	}
	return page, nil
}
