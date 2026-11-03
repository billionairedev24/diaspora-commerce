// Package sources implements the reindex ports over the HTTP APIs of the services that own the indexed data:
// catalog-service (products, ratings), offers-service (buy boxes) and inventory-service (stock).
package sources

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"strings"
)

// maxBody bounds a decoded response.
const maxBody = 32 << 20

// getJSON GETs base+path and decodes a 200 response into out.
func getJSON(ctx context.Context, client *http.Client, base, path string, out any) error {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, base+path, nil)
	if err != nil {
		return fmt.Errorf("request %s: %w", path, err)
	}
	req.Header.Set("Accept", "application/json")
	res, err := client.Do(req)
	if err != nil {
		return fmt.Errorf("get %s: %w", path, err)
	}
	defer func() { _ = res.Body.Close() }()
	if res.StatusCode != http.StatusOK {
		body, _ := io.ReadAll(io.LimitReader(res.Body, 512))
		return fmt.Errorf("get %s: status %d: %s", path, res.StatusCode, strings.TrimSpace(string(body)))
	}
	if err := json.NewDecoder(io.LimitReader(res.Body, maxBody)).Decode(out); err != nil {
		return fmt.Errorf("get %s: decode: %w", path, err)
	}
	return nil
}
