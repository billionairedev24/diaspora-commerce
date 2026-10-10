// Package nominatim implements port.Autocompleter with OpenStreetMap Nominatim. Respects the usage policy:
// identifying User-Agent, max 1 request per second, and results cached for an hour.
package nominatim

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"net/url"
	"strings"
	"sync"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/services/geo/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/geo/internal/port"
)

type entry struct {
	at   time.Time
	data []result
}

type nominatimImpl struct {
	base      string
	userAgent string
	client    *http.Client
	interval  time.Duration

	mu    sync.Mutex
	last  time.Time
	cache map[string]entry
}

var _ port.Autocompleter = (*nominatimImpl)(nil)

// New returns a Nominatim autocompleter. base defaults to the public instance; self-host for production volume.
func New(base, userAgent string, client *http.Client) port.Autocompleter {
	if base == "" {
		base = "https://nominatim.openstreetmap.org"
	}
	return &nominatimImpl{base: strings.TrimSuffix(base, "/"), userAgent: userAgent, client: client,
		interval: time.Second, cache: map[string]entry{}}
}

func (n *nominatimImpl) Name() string { return "osm-nominatim" }

type result struct {
	OSMType     string `json:"osm_type"`
	OSMID       int64  `json:"osm_id"`
	DisplayName string `json:"display_name"`
	Lat         string `json:"lat"`
	Lon         string `json:"lon"`
	Address     struct {
		HouseNumber string `json:"house_number"`
		Road        string `json:"road"`
		City        string `json:"city"`
		Town        string `json:"town"`
		Village     string `json:"village"`
		Hamlet      string `json:"hamlet"`
		State       string `json:"state"`
		Postcode    string `json:"postcode"`
		CountryCode string `json:"country_code"`
	} `json:"address"`
}

func (n *nominatimImpl) Suggest(ctx context.Context, input, _, country string, limit int) ([]domain.Suggestion, error) {
	q := url.Values{"q": {input}, "format": {"jsonv2"}, "addressdetails": {"1"},
		"countrycodes": {strings.ToLower(country)}, "limit": {fmt.Sprint(limit)}}
	rs, err := n.get(ctx, "/search?"+q.Encode())
	if err != nil {
		return nil, err
	}
	out := make([]domain.Suggestion, 0, len(rs))
	for _, r := range rs {
		a := toAddress(r)
		if a.Line1 == "" && a.PostalCode == "" {
			continue
		}
		out = append(out, domain.Suggestion{
			ID:            fmt.Sprintf("o:%s%d", strings.ToUpper(r.OSMType[:1]), r.OSMID),
			Text:          r.DisplayName,
			MainText:      firstNonEmpty(a.Line1, a.PostalCode),
			SecondaryText: strings.Trim(strings.Join([]string{a.City, a.State, a.PostalCode}, ", "), ", "),
			Provider:      n.Name(),
		})
	}
	return out, nil
}

func (n *nominatimImpl) Resolve(ctx context.Context, id, _ string) (domain.Address, error) {
	ref := strings.TrimPrefix(id, "o:")
	rs, err := n.get(ctx, "/lookup?"+url.Values{"osm_ids": {ref}, "format": {"jsonv2"}, "addressdetails": {"1"}}.Encode())
	if err != nil {
		return domain.Address{}, err
	}
	if len(rs) == 0 {
		return domain.Address{}, fmt.Errorf("osm place %s not found", ref)
	}
	return toAddress(rs[0]), nil
}

func (n *nominatimImpl) get(ctx context.Context, path string) ([]result, error) {
	n.mu.Lock()
	if e, ok := n.cache[path]; ok && time.Since(e.at) < time.Hour {
		n.mu.Unlock()
		return e.data, nil
	}
	if wait := n.interval - time.Since(n.last); wait > 0 {
		n.mu.Unlock()
		select {
		case <-ctx.Done():
			return nil, fmt.Errorf("nominatim: %w", ctx.Err())
		case <-time.After(wait):
		}
		n.mu.Lock()
	}
	n.last = time.Now()
	n.mu.Unlock()

	req, err := http.NewRequestWithContext(ctx, http.MethodGet, n.base+path, nil)
	if err != nil {
		return nil, fmt.Errorf("request: %w", err)
	}
	req.Header.Set("User-Agent", n.userAgent)
	res, err := n.client.Do(req)
	if err != nil {
		return nil, fmt.Errorf("nominatim: %w", err)
	}
	defer func() { _ = res.Body.Close() }()
	if res.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("nominatim: status %d", res.StatusCode)
	}
	var rs []result
	if err := json.NewDecoder(res.Body).Decode(&rs); err != nil {
		return nil, fmt.Errorf("nominatim decode: %w", err)
	}
	n.mu.Lock()
	n.cache[path] = entry{at: time.Now(), data: rs}
	n.mu.Unlock()
	return rs, nil
}

func toAddress(r result) domain.Address {
	a := domain.Address{
		Line1:      strings.TrimSpace(r.Address.HouseNumber + " " + r.Address.Road),
		City:       firstNonEmpty(r.Address.City, r.Address.Town, r.Address.Village, r.Address.Hamlet),
		PostalCode: r.Address.Postcode,
		Country:    strings.ToUpper(r.Address.CountryCode),
	}
	if code, ok := domain.StateCode(r.Address.State); ok {
		a.State = code
	} else {
		a.State = r.Address.State
	}
	_, _ = fmt.Sscan(r.Lat, &a.Lat)
	_, _ = fmt.Sscan(r.Lon, &a.Lon)
	return a
}

func firstNonEmpty(v ...string) string {
	for _, s := range v {
		if s != "" {
			return s
		}
	}
	return ""
}
