// Package content implements port.CampaignSource over content-service's ad serving endpoint
// (GET /v1/ads/serving?market=, client-credentials token of client search-service with scope ads:serve).
package content

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strings"
	"sync"
	"time"

	"golang.org/x/oauth2"
	"golang.org/x/oauth2/clientcredentials"

	"github.com/billionairedev24/diaspora-commerce/go/services/search/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/search/internal/port"
)

// Config configures the content client.
type Config struct {
	BaseURL      string // e.g. http://content
	TokenURL     string // <issuer>/oauth2/token; empty = no token (local profile, content auth off)
	ClientID     string
	ClientSecret string
	Scopes       []string
	// CacheTTL is how long eligible campaigns are reused; StaleTTL how long a stale list may serve while content is
	// unavailable.
	CacheTTL, StaleTTL time.Duration
}

type entry struct {
	campaigns []domain.Campaign
	fetched   time.Time
}

type campaignsImpl struct {
	base   string
	client *http.Client
	cfg    Config
	now    func() time.Time

	mu    sync.Mutex
	cache map[string]entry
	// fetching serialises refreshes per market so a burst of searches makes one request.
	fetching map[string]*sync.Mutex
}

var _ port.CampaignSource = (*campaignsImpl)(nil)

// New returns the cached content campaign source. httpClient carries the timeout of token and API calls.
func New(cfg Config, httpClient *http.Client) port.CampaignSource {
	client := httpClient
	if cfg.TokenURL != "" && cfg.ClientSecret != "" {
		cc := clientcredentials.Config{ClientID: cfg.ClientID, ClientSecret: cfg.ClientSecret, TokenURL: cfg.TokenURL,
			Scopes: cfg.Scopes, AuthStyle: oauth2.AuthStyleInHeader}
		client = cc.Client(context.WithValue(context.Background(), oauth2.HTTPClient, httpClient))
		client.Timeout = httpClient.Timeout
	}
	if cfg.CacheTTL <= 0 {
		cfg.CacheTTL = 15 * time.Second
	}
	if cfg.StaleTTL < cfg.CacheTTL {
		cfg.StaleTTL = 5 * time.Minute
	}
	return &campaignsImpl{base: strings.TrimRight(cfg.BaseURL, "/"), client: client, cfg: cfg, now: time.Now,
		cache: map[string]entry{}, fetching: map[string]*sync.Mutex{}}
}

func (c *campaignsImpl) Eligible(ctx context.Context, market string) ([]domain.Campaign, error) {
	market = strings.ToUpper(market)
	if e, ok := c.fresh(market); ok {
		return e.campaigns, nil
	}
	lock := c.lockFor(market)
	lock.Lock()
	defer lock.Unlock()
	if e, ok := c.fresh(market); ok {
		return e.campaigns, nil
	}
	camps, err := c.fetch(ctx, market)
	if err != nil {
		c.mu.Lock()
		e, ok := c.cache[market]
		c.mu.Unlock()
		if ok && c.now().Sub(e.fetched) < c.cfg.StaleTTL {
			return e.campaigns, nil
		}
		return nil, err
	}
	c.mu.Lock()
	c.cache[market] = entry{campaigns: camps, fetched: c.now()}
	c.mu.Unlock()
	return camps, nil
}

func (c *campaignsImpl) fresh(market string) (entry, bool) {
	c.mu.Lock()
	defer c.mu.Unlock()
	e, ok := c.cache[market]
	return e, ok && c.now().Sub(e.fetched) < c.cfg.CacheTTL
}

func (c *campaignsImpl) lockFor(market string) *sync.Mutex {
	c.mu.Lock()
	defer c.mu.Unlock()
	l, ok := c.fetching[market]
	if !ok {
		l = &sync.Mutex{}
		c.fetching[market] = l
	}
	return l
}

type servingResponse struct {
	Market    string            `json:"market"`
	Campaigns []domain.Campaign `json:"campaigns"`
}

func (c *campaignsImpl) fetch(ctx context.Context, market string) ([]domain.Campaign, error) {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet,
		c.base+"/v1/ads/serving?market="+url.QueryEscape(market), nil)
	if err != nil {
		return nil, fmt.Errorf("ads serving request: %w", err)
	}
	req.Header.Set("Accept", "application/json")
	res, err := c.client.Do(req)
	if err != nil {
		return nil, fmt.Errorf("ads serving: %w", err)
	}
	defer func() { _ = res.Body.Close() }()
	if res.StatusCode != http.StatusOK {
		body, _ := io.ReadAll(io.LimitReader(res.Body, 512))
		return nil, fmt.Errorf("ads serving: status %d: %s", res.StatusCode, strings.TrimSpace(string(body)))
	}
	var out servingResponse
	if err := json.NewDecoder(io.LimitReader(res.Body, 8<<20)).Decode(&out); err != nil {
		return nil, fmt.Errorf("ads serving: decode: %w", err)
	}
	if out.Campaigns == nil {
		out.Campaigns = []domain.Campaign{}
	}
	return out.Campaigns, nil
}
