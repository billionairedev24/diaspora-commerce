// Package upstreams implements the upstream ports over HTTP with the resilient httpclient. One file per service;
// paths follow contracts/openapi/<service>.yaml, or the planned paths for services still being built (content,
// payments, promise, orders, notifications, geo reverse geocoding).
package upstreams

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"strconv"
	"strings"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/adapter/httpclient"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

func call(ctx context.Context, c httpclient.Client, req httpclient.Request, out any) (*httpclient.Response, error) {
	res, err := c.Do(ctx, req)
	if err != nil {
		return nil, fmt.Errorf("%s %s: %w", req.Method, req.Path, err)
	}
	if out != nil {
		if err := res.Decode(out); err != nil {
			return nil, fmt.Errorf("%s %s: %w", c.Name(), req.Path, err)
		}
	}
	return res, nil
}

// maxAge reads max-age (or s-maxage) from Cache-Control; private/no-store responses are never cached.
func maxAge(h http.Header) time.Duration {
	cc := strings.ToLower(h.Get("Cache-Control"))
	if cc == "" || strings.Contains(cc, "no-store") || strings.Contains(cc, "private") || strings.Contains(cc, "no-cache") {
		return 0
	}
	var age time.Duration
	for _, part := range strings.Split(cc, ",") {
		k, v, ok := strings.Cut(strings.TrimSpace(part), "=")
		if !ok {
			continue
		}
		if k == "max-age" || k == "s-maxage" {
			if n, err := strconv.Atoi(strings.Trim(v, `"`)); err == nil && n > 0 {
				age = time.Duration(n) * time.Second
			}
		}
	}
	return age
}

// ttlFor caps the upstream's max-age by our own ceiling; without a Cache-Control the fallback applies.
func ttlFor(h http.Header, ceiling, fallback time.Duration) time.Duration {
	if strings.Contains(strings.ToLower(h.Get("Cache-Control")), "no-store") {
		return 0
	}
	if a := maxAge(h); a > 0 {
		return min(a, ceiling)
	}
	if h.Get("Cache-Control") != "" {
		return 0
	}
	return fallback
}

// cached serves key from cache or loads it, storing the JSON for ttl(header).
func cached[T any](cache port.Cache, key string, load func() (T, http.Header, error), ttl func(http.Header) time.Duration) (T, error) {
	var zero T
	if cache != nil {
		if b, ok := cache.Get(key); ok {
			var v T
			if json.Unmarshal(b, &v) == nil {
				return v, nil
			}
		}
	}
	v, h, err := load()
	if err != nil {
		return zero, err
	}
	if cache != nil {
		if d := ttl(h); d > 0 {
			if b, err := json.Marshal(v); err == nil {
				cache.Set(key, b, d)
			}
		}
	}
	return v, nil
}

// itemsOf accepts either a JSON array or an object with "items" (pending services may pick either).
func itemsOf[T any](body []byte) ([]T, *string, error) {
	trimmed := strings.TrimSpace(string(body))
	if strings.HasPrefix(trimmed, "[") {
		var items []T
		if err := json.Unmarshal(body, &items); err != nil {
			return nil, nil, fmt.Errorf("decode list: %w", err)
		}
		return items, nil, nil
	}
	var page struct {
		Items      []T     `json:"items"`
		NextCursor *string `json:"nextCursor"`
	}
	if err := json.Unmarshal(body, &page); err != nil {
		return nil, nil, fmt.Errorf("decode page: %w", err)
	}
	return page.Items, page.NextCursor, nil
}
