// Package cdn implements port.CachePurger for Cloudflare (purge by URL) and a no-op for local.
package cdn

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"net/http"

	"github.com/billionairedev24/diaspora-commerce/go/services/storefront/internal/port"
)

type cloudflareImpl struct {
	zoneID, token, base string
	client              *http.Client
}

type noopImpl struct{}

var (
	_ port.CachePurger = (*cloudflareImpl)(nil)
	_ port.CachePurger = noopImpl{}
)

// NewCloudflare purges URLs from the Cloudflare cache of zoneID. base may be empty for the public API.
func NewCloudflare(zoneID, token, base string, client *http.Client) port.CachePurger {
	if base == "" {
		base = "https://api.cloudflare.com/client/v4"
	}
	return &cloudflareImpl{zoneID: zoneID, token: token, base: base, client: client}
}

// NewNoop does nothing (local and tests).
func NewNoop() port.CachePurger { return noopImpl{} }

func (noopImpl) Purge(context.Context, []string) error { return nil }

func (c *cloudflareImpl) Purge(ctx context.Context, urls []string) error {
	body, err := json.Marshal(map[string][]string{"files": urls})
	if err != nil {
		return fmt.Errorf("encode: %w", err)
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, c.base+"/zones/"+c.zoneID+"/purge_cache", bytes.NewReader(body))
	if err != nil {
		return fmt.Errorf("request: %w", err)
	}
	req.Header.Set("Authorization", "Bearer "+c.token)
	req.Header.Set("Content-Type", "application/json")
	res, err := c.client.Do(req)
	if err != nil {
		return fmt.Errorf("cloudflare purge: %w", err)
	}
	defer func() { _ = res.Body.Close() }()
	if res.StatusCode != http.StatusOK {
		return fmt.Errorf("cloudflare purge: status %d", res.StatusCode)
	}
	return nil
}
