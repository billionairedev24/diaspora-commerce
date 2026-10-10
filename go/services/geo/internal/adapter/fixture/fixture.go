// Package fixture is an offline Autocompleter over a fixed list of addresses (local profile, tests, demos).
// Matching follows the storefront rule: every typed word must prefix-match a word of the address, and a typed
// house number is kept ("4521 Main" suggests "4521 Main St, Houston, TX 77002").
package fixture

import (
	"context"
	"fmt"
	"math"
	"sort"
	"strconv"
	"strings"

	"github.com/billionairedev24/diaspora-commerce/go/services/geo/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/geo/internal/port"
)

type fixtureImpl struct {
	streets []domain.Address
}

// Provider is the fixture list as both an autocompleter and a reverse geocoder.
type Provider interface {
	port.Autocompleter
	port.ReverseGeocoder
}

var _ Provider = (*fixtureImpl)(nil)

// New returns the fixture provider seeded with diaspora-area streets from the design.
func New() Provider { return &fixtureImpl{streets: seed} }

// reverseRadiusKm is how far the nearest fixture street may be from the point to count as its address.
const reverseRadiusKm = 25

// Reverse returns the nearest fixture street within 25 km (offline deliver-to by location).
func (f *fixtureImpl) Reverse(_ context.Context, lat, lon float64) (domain.Address, error) {
	best, bestKm := -1, math.MaxFloat64
	for i, a := range f.streets {
		if d := domain.HaversineMeters(lat, lon, a.Lat, a.Lon) / 1000; d < bestKm {
			best, bestKm = i, d
		}
	}
	if best < 0 || bestKm > reverseRadiusKm {
		return domain.Address{}, domain.ErrNoAddress
	}
	return f.streets[best], nil
}

func (f *fixtureImpl) Name() string { return "fixture" }

func (f *fixtureImpl) Suggest(_ context.Context, q domain.AutocompleteQuery) ([]domain.Suggestion, error) {
	words := strings.Fields(strings.ToLower(q.Input))
	number := ""
	if len(words) > 0 && q.Kind != domain.KindPostalCode {
		if _, err := strconv.Atoi(words[0]); err == nil && len(words[0]) < 5 {
			number, words = words[0], words[1:]
		}
	}
	type match struct {
		s    domain.Suggestion
		dist float64
	}
	var matches []match
	seen := map[string]bool{}
	for i, a := range f.streets {
		if a.Country != q.Country && q.Country != "" {
			continue
		}
		hay := strings.Fields(strings.ToLower(a.Line1 + " " + a.City + " " + a.State + " " + a.PostalCode))
		if !allPrefix(words, hay) {
			continue
		}
		s := domain.Suggestion{Provider: f.Name()}
		switch q.Kind {
		case domain.KindPostalCode:
			s.ID, s.Kind = fmt.Sprintf("f:%d:", i), domain.KindPostalCode
			s.MainText, s.SecondaryText = a.PostalCode, fmt.Sprintf("%s, %s", a.City, a.State)
			s.Text = fmt.Sprintf("%s %s, %s", a.PostalCode, a.City, a.State)
		case domain.KindLocality:
			s.ID, s.Kind = fmt.Sprintf("f:%d:", i), domain.KindLocality
			s.MainText, s.SecondaryText = a.City, a.State
			s.Text = fmt.Sprintf("%s, %s", a.City, a.State)
		case domain.KindAny, domain.KindAddress:
			line := a.Line1
			if number != "" {
				line = number + " " + stripNumber(a.Line1)
			}
			s.ID, s.Kind = fmt.Sprintf("f:%d:%s", i, number), domain.KindAddress
			s.MainText, s.SecondaryText = line, fmt.Sprintf("%s, %s %s", a.City, a.State, a.PostalCode)
			s.Text = fmt.Sprintf("%s, %s, %s %s", line, a.City, a.State, a.PostalCode)
		}
		if seen[s.Text] {
			continue
		}
		seen[s.Text] = true
		m := match{s: s}
		if q.Near != nil {
			m.dist = domain.HaversineMeters(q.Near.Lat, q.Near.Lng, a.Lat, a.Lon)
			d := m.dist
			m.s.DistanceMeters = &d
		}
		matches = append(matches, m)
	}
	if q.Near != nil {
		// The bias ranks nearby places first (the fixture list is small enough to sort).
		sort.SliceStable(matches, func(i, j int) bool { return matches[i].dist < matches[j].dist })
	}
	out := make([]domain.Suggestion, 0, min(len(matches), q.Limit))
	for _, m := range matches {
		if len(out) == q.Limit {
			break
		}
		out = append(out, m.s)
	}
	return out, nil
}

func (f *fixtureImpl) Resolve(_ context.Context, id, _ string) (domain.Address, error) {
	parts := strings.SplitN(strings.TrimPrefix(id, "f:"), ":", 2)
	i, err := strconv.Atoi(parts[0])
	if err != nil || i < 0 || i >= len(f.streets) {
		return domain.Address{}, fmt.Errorf("unknown fixture address %q", id)
	}
	a := f.streets[i]
	if len(parts) == 2 && parts[1] != "" {
		a.Line1 = parts[1] + " " + stripNumber(a.Line1)
	}
	return a, nil
}

func allPrefix(words, hay []string) bool {
	for _, w := range words {
		ok := false
		for _, h := range hay {
			if strings.HasPrefix(h, strings.Trim(w, ",.")) {
				ok = true
				break
			}
		}
		if !ok {
			return false
		}
	}
	return true
}

func stripNumber(line string) string {
	f := strings.Fields(line)
	if len(f) > 1 {
		if _, err := strconv.Atoi(f[0]); err == nil {
			return strings.Join(f[1:], " ")
		}
	}
	return line
}

var seed = []domain.Address{
	{Line1: "123 Main St", City: "Houston", State: "TX", PostalCode: "77002", Country: "US", Lat: 29.7589, Lon: -95.3677},
	{Line1: "9800 Bissonnet St", City: "Houston", State: "TX", PostalCode: "77036", Country: "US", Lat: 29.6713, Lon: -95.5446},
	{Line1: "11200 Westheimer Rd", City: "Houston", State: "TX", PostalCode: "77042", Country: "US", Lat: 29.7362, Lon: -95.5878},
	{Line1: "2500 Plaza Pkwy", City: "Sugar Land", State: "TX", PostalCode: "77479", Country: "US", Lat: 29.5941, Lon: -95.6243},
	{Line1: "1600 Oak Tree Rd", City: "Edison", State: "NJ", PostalCode: "08820", Country: "US", Lat: 40.5781, Lon: -74.3532},
	{Line1: "88 Halsey St", City: "Newark", State: "NJ", PostalCode: "07102", Country: "US", Lat: 40.7383, Lon: -74.1735},
	{Line1: "740 Flatbush Ave", City: "Brooklyn", State: "NY", PostalCode: "11226", Country: "US", Lat: 40.6527, Lon: -73.9592},
	{Line1: "2120 Grand Concourse", City: "Bronx", State: "NY", PostalCode: "10457", Country: "US", Lat: 40.8551, Lon: -73.9007},
	{Line1: "116 W 125th St", City: "New York", State: "NY", PostalCode: "10027", Country: "US", Lat: 40.8087, Lon: -73.9467},
	{Line1: "5900 Buford Hwy", City: "Doraville", State: "GA", PostalCode: "30340", Country: "US", Lat: 33.8925, Lon: -84.2806},
	{Line1: "3500 Peachtree Rd NE", City: "Atlanta", State: "GA", PostalCode: "30326", Country: "US", Lat: 33.8509, Lon: -84.3624},
	{Line1: "2200 Georgia Ave NW", City: "Washington", State: "DC", PostalCode: "20001", Country: "US", Lat: 38.9188, Lon: -77.0221},
	{Line1: "8484 Georgia Ave", City: "Silver Spring", State: "MD", PostalCode: "20910", Country: "US", Lat: 38.9954, Lon: -77.0277},
	{Line1: "6200 Columbia Pike", City: "Falls Church", State: "VA", PostalCode: "22041", Country: "US", Lat: 38.8525, Lon: -77.1283},
	{Line1: "4200 Lake Ave", City: "Minneapolis", State: "MN", PostalCode: "55407", Country: "US", Lat: 44.9283, Lon: -93.2569},
	{Line1: "1900 S Western Ave", City: "Los Angeles", State: "CA", PostalCode: "90018", Country: "US", Lat: 34.0386, Lon: -118.3087},
	{Line1: "4400 Crenshaw Blvd", City: "Los Angeles", State: "CA", PostalCode: "90043", Country: "US", Lat: 34.0055, Lon: -118.3344},
	{Line1: "2300 S King Dr", City: "Chicago", State: "IL", PostalCode: "60616", Country: "US", Lat: 41.8502, Lon: -87.6167},
	{Line1: "1500 Market St", City: "Philadelphia", State: "PA", PostalCode: "19102", Country: "US", Lat: 39.9526, Lon: -75.1652},
	{Line1: "400 Main St", City: "Dallas", State: "TX", PostalCode: "75202", Country: "US", Lat: 32.7810, Lon: -96.7970},
}
