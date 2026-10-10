package sellers

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strconv"
	"strings"

	"golang.org/x/oauth2"
	"golang.org/x/oauth2/clientcredentials"

	"github.com/billionairedev24/diaspora-commerce/go/services/indexer/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/indexer/internal/port"
)

// ClientConfig configures the seller-service client.
type ClientConfig struct {
	BaseURL      string // e.g. http://seller
	TokenURL     string // <issuer>/oauth2/token; empty = no token (local profile, seller auth off)
	ClientID     string // indexer-service
	ClientSecret string
	Scopes       []string // sellers:directory
}

type clientImpl struct {
	base   string
	client *http.Client
}

var _ port.SellerSource = (*clientImpl)(nil)

// NewClient reads seller-service's organisation directory (GET /v1/orgs/directory, scope sellers:directory) and
// public profiles (GET /v1/orgs/{id}/public). The client-credentials token is cached and refreshed before expiry.
func NewClient(cfg ClientConfig, httpClient *http.Client) port.SellerSource {
	client := httpClient
	if cfg.TokenURL != "" && cfg.ClientSecret != "" {
		cc := clientcredentials.Config{ClientID: cfg.ClientID, ClientSecret: cfg.ClientSecret, TokenURL: cfg.TokenURL,
			Scopes: cfg.Scopes, AuthStyle: oauth2.AuthStyleInHeader}
		client = cc.Client(context.WithValue(context.Background(), oauth2.HTTPClient, httpClient))
		client.Timeout = httpClient.Timeout
	}
	return &clientImpl{base: strings.TrimRight(cfg.BaseURL, "/"), client: client}
}

func (c *clientImpl) Directory(ctx context.Context, page, size int) (domain.SellerDirectoryPage, error) {
	var out domain.SellerDirectoryPage
	path := "/v1/orgs/directory?page=" + strconv.Itoa(page) + "&size=" + strconv.Itoa(size)
	found, err := c.get(ctx, path, &out)
	if err != nil {
		return domain.SellerDirectoryPage{}, err
	}
	if !found {
		return domain.SellerDirectoryPage{}, fmt.Errorf("seller %s: not found", path)
	}
	return out, nil
}

func (c *clientImpl) Org(ctx context.Context, orgID string) (domain.SellerOrg, bool, error) {
	var out domain.SellerOrg
	found, err := c.get(ctx, "/v1/orgs/"+url.PathEscape(orgID)+"/public", &out)
	return out, found, err
}

func (c *clientImpl) get(ctx context.Context, path string, out any) (bool, error) {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, c.base+path, nil)
	if err != nil {
		return false, fmt.Errorf("seller request: %w", err)
	}
	req.Header.Set("Accept", "application/json")
	res, err := c.client.Do(req)
	if err != nil {
		return false, fmt.Errorf("seller %s: %w", path, err)
	}
	defer func() { _ = res.Body.Close() }()
	switch {
	case res.StatusCode == http.StatusNotFound:
		_, _ = io.Copy(io.Discard, res.Body)
		return false, nil
	case res.StatusCode != http.StatusOK:
		body, _ := io.ReadAll(io.LimitReader(res.Body, 512))
		return false, fmt.Errorf("seller %s: status %d: %s", path, res.StatusCode, strings.TrimSpace(string(body)))
	}
	if err := json.NewDecoder(io.LimitReader(res.Body, 16<<20)).Decode(out); err != nil {
		return false, fmt.Errorf("seller %s: decode: %w", path, err)
	}
	return true, nil
}
