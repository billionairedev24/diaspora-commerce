// Package nominatim implements port.Autocompleter and port.ReverseGeocoder with OpenStreetMap Nominatim. Respects
// the usage policy: identifying User-Agent, max 1 request per second, and results cached for an hour.
package nominatim

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/services/geo/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/geo/internal/port"
)

type entry struct {
	at   time.Time
	body []byte
}

// Provider is Nominatim as both an autocompleter and a reverse geocoder; one instance shares the rate limit and cache.
type Provider interface {
	port.Autocompleter
	port.ReverseGeocoder
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

var _ Provider = (*nominatimImpl)(nil)

// New returns a Nominatim provider. base defaults to the public instance; self-host for production volume.
func New(base, userAgent string, client *http.Client) Provider {
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
	var rs []result
	err := n.get(ctx, "/search?"+q.Encode(), &rs)
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
	var rs []result
	err := n.get(ctx, "/lookup?"+url.Values{"osm_ids": {ref}, "format": {"jsonv2"}, "addressdetails": {"1"}}.Encode(), &rs)
	if err != nil {
		return domain.Address{}, err
	}
	if len(rs) == 0 {
		return domain.Address{}, fmt.Errorf("osm place %s not found", ref)
	}
	return toAddress(rs[0]), nil
}

// Reverse answers GET /reverse (zoom 18 = building level); "Unable to geocode" means no address at the point.
func (n *nominatimImpl) Reverse(ctx context.Context, lat, lon float64) (domain.Address, error) {
	q := url.Values{"lat": {strconv.FormatFloat(lat, 'f', -1, 64)}, "lon": {strconv.FormatFloat(lon, 'f', -1, 64)},
		"format": {"jsonv2"}, "addressdetails": {"1"}, "zoom": {"18"}}
	var r struct {
		result
		Error string `json:"error"`
	}
	if err := n.get(ctx, "/reverse?"+q.Encode(), &r); err != nil {
		return domain.Address{}, err
	}
	if r.Error != "" || r.OSMID == 0 {
		return domain.Address{}, domain.ErrNoAddress
	}
	return toAddress(r.result), nil
}

// get fetches path (rate limited, cached for an hour) and decodes the JSON into out.
func (n *nominatimImpl) get(ctx context.Context, path string, out any) error {
	body, err := n.fetch(ctx, path)
	if err != nil {
		return err
	}
	if err := json.Unmarshal(body, out); err != nil {
		return fmt.Errorf("nominatim decode: %w", err)
	}
	return nil
}

func (n *nominatimImpl) fetch(ctx context.Context, path string) ([]byte, error) {
	n.mu.Lock()
	if e, ok := n.cache[path]; ok && time.Since(e.at) < time.Hour {
		n.mu.Unlock()
		return e.body, nil
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
	body, err := io.ReadAll(io.LimitReader(res.Body, 1<<20))
	if err != nil {
		return nil, fmt.Errorf("nominatim read: %w", err)
	}
	n.mu.Lock()
	n.cache[path] = entry{at: time.Now(), body: body}
	n.mu.Unlock()
	return body, nil
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
