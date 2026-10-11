// Package gazetteer implements port.Gazetteer from an embedded world list: 250 countries, their states and
// provinces and 150,000 cities, from the countries-states-cities database (@countrystatecity/countries 1.0.9,
// ODbL-1.0, https://github.com/dr5hn/countries-states-cities-database), the data the design's pickers use.
//
// world.tsv.gz holds one record per line: "C\t<iso2>\t<name>\t<currency>", then that country's
// "S\t<iso2>\t<code>\t<name>\t<type>" lines, each followed by its "X\t<city>" lines (sorted by name).
package gazetteer

import (
	"bufio"
	"bytes"
	"compress/gzip"
	"embed"
	"fmt"
	"sort"
	"strings"

	"github.com/billionairedev24/diaspora-commerce/go/services/geo/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/geo/internal/port"
)

//go:embed world.tsv.gz
var files embed.FS

type region struct {
	code, name string
	cities     []string
	lower      []string
}

type country struct {
	code, name, currency string
	regions              []*region
}

type gazetteerImpl struct {
	countries []*country
	byCode    map[string]*country
}

var _ port.Gazetteer = (*gazetteerImpl)(nil)

// New parses the embedded world list.
func New() (port.Gazetteer, error) {
	data, err := files.ReadFile("world.tsv.gz")
	if err != nil {
		return nil, fmt.Errorf("gazetteer: embedded data: %w", err)
	}
	return parse(data)
}

func parse(data []byte) (*gazetteerImpl, error) {
	zr, err := gzip.NewReader(bytes.NewReader(data))
	if err != nil {
		return nil, fmt.Errorf("gazetteer: open: %w", err)
	}
	defer func() { _ = zr.Close() }()
	g := &gazetteerImpl{byCode: map[string]*country{}}
	var c *country
	var r *region
	sc := bufio.NewScanner(zr)
	sc.Buffer(make([]byte, 0, 64*1024), 1024*1024)
	for sc.Scan() {
		f := strings.Split(sc.Text(), "\t")
		switch {
		case f[0] == "C" && len(f) >= 3:
			c = &country{code: f[1], name: f[2]}
			if len(f) > 3 {
				c.currency = f[3]
			}
			r = nil
			g.countries = append(g.countries, c)
			g.byCode[c.code] = c
		case f[0] == "S" && len(f) >= 4 && c != nil:
			r = &region{code: strings.ToUpper(f[2]), name: f[3]}
			c.regions = append(c.regions, r)
		case f[0] == "X" && len(f) >= 2 && r != nil:
			r.cities = append(r.cities, f[1])
			r.lower = append(r.lower, strings.ToLower(f[1]))
		}
	}
	if err := sc.Err(); err != nil {
		return nil, fmt.Errorf("gazetteer: read: %w", err)
	}
	sort.Slice(g.countries, func(i, j int) bool { return g.countries[i].name < g.countries[j].name })
	for _, c := range g.countries {
		sort.SliceStable(c.regions, func(i, j int) bool { return c.regions[i].name < c.regions[j].name })
	}
	return g, nil
}

func (g *gazetteerImpl) view(c *country) domain.PlaceCountry {
	return domain.PlaceCountry{Code: c.code, Name: c.name, Currency: c.currency, RegionLabel: domain.RegionLabel(c.code),
		RegionCount: len(c.regions)}
}

func (g *gazetteerImpl) Countries() []domain.PlaceCountry {
	out := make([]domain.PlaceCountry, 0, len(g.countries))
	for _, c := range g.countries {
		out = append(out, g.view(c))
	}
	return out
}

func (g *gazetteerImpl) Regions(code string) (domain.PlaceCountry, []domain.PlaceRegion, bool) {
	c, ok := g.byCode[strings.ToUpper(strings.TrimSpace(code))]
	if !ok {
		return domain.PlaceCountry{}, nil, false
	}
	out := make([]domain.PlaceRegion, 0, len(c.regions))
	for _, r := range c.regions {
		out = append(out, domain.PlaceRegion{Code: r.code, Name: r.name, CityCount: len(r.cities)})
	}
	return g.view(c), out, true
}

func (g *gazetteerImpl) region(countryCode, regionCode string) (*country, *region, bool) {
	c, ok := g.byCode[strings.ToUpper(strings.TrimSpace(countryCode))]
	if !ok {
		return nil, nil, false
	}
	rc := strings.ToUpper(strings.TrimSpace(regionCode))
	for _, r := range c.regions {
		if r.code == rc {
			return c, r, true
		}
	}
	return c, nil, false
}

func (g *gazetteerImpl) Cities(countryCode, regionCode string) (domain.PlaceRegion, []string, bool) {
	_, r, ok := g.region(countryCode, regionCode)
	if !ok || r == nil {
		return domain.PlaceRegion{}, nil, false
	}
	return domain.PlaceRegion{Code: r.code, Name: r.name, CityCount: len(r.cities)}, append([]string(nil), r.cities...), true
}

type ranked struct {
	m    domain.PlaceMatch
	rank int
}

func (g *gazetteerImpl) Search(q, countryCode string, limit int) []domain.PlaceMatch {
	t := strings.ToLower(strings.TrimSpace(q))
	if len([]rune(t)) < 2 {
		return []domain.PlaceMatch{}
	}
	if limit < 1 || limit > 50 {
		limit = 8
	}
	only := strings.ToUpper(strings.TrimSpace(countryCode))
	var hits []ranked
	for _, c := range g.countries {
		if only != "" && c.code != only {
			continue
		}
		cn := strings.ToLower(c.name)
		if strings.Contains(cn, t) {
			hits = append(hits, ranked{domain.PlaceMatch{Key: c.code, Code: c.code, Name: c.name, Type: domain.PlaceCountryType,
				Country: c.code, CountryName: c.name}, rankOf(cn, t, 0)})
		}
		for _, r := range c.regions {
			rn := strings.ToLower(r.name)
			if strings.Contains(rn, t) || strings.ToLower(r.code) == t {
				rank := rankOf(rn, t, 0)
				if strings.ToLower(r.code) == t {
					rank = 0
				}
				hits = append(hits, ranked{domain.PlaceMatch{Key: c.code + "/" + r.code, Code: r.code, Name: r.name,
					Type: domain.PlaceRegionType, Country: c.code, CountryName: c.name, Region: r.code, RegionName: r.name}, rank})
			}
			for i, x := range r.lower {
				if strings.Contains(x, t) {
					hits = append(hits, ranked{domain.PlaceMatch{Key: c.code + "/" + r.code + "/" + r.cities[i], Code: r.cities[i],
						Name: r.cities[i], Type: domain.PlaceCityType, Country: c.code, CountryName: c.name, Region: r.code,
						RegionName: r.name}, rankOf(x, t, 1)})
				}
			}
		}
	}
	sort.SliceStable(hits, func(i, j int) bool {
		if hits[i].rank != hits[j].rank {
			return hits[i].rank < hits[j].rank
		}
		return hits[i].m.Name < hits[j].m.Name
	})
	out := make([]domain.PlaceMatch, 0, limit)
	for _, h := range hits {
		if len(out) == limit {
			break
		}
		out = append(out, h.m)
	}
	return out
}

// rankOf: prefix matches rank base, other matches base + 2 (the design ranks countries and regions 0/2, cities 1/3).
func rankOf(name, t string, base int) int {
	if strings.HasPrefix(name, t) {
		return base
	}
	return base + 2
}
