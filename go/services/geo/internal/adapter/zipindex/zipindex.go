// Package zipindex implements port.ZipIndex from an embedded table of US ZIP centroids (us_zips.tsv.gz, one
// "zip<TAB>city<TAB>state<TAB>lat<TAB>lon" line per ZIP; rebuilt with tools/geo/build_zip_index.py).
package zipindex

import (
	"bufio"
	"bytes"
	"compress/gzip"
	"embed"
	"fmt"
	"math"
	"sort"
	"strconv"
	"strings"

	"github.com/billionairedev24/diaspora-commerce/go/services/geo/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/geo/internal/port"
)

//go:embed us_zips.tsv.gz
var files embed.FS

type zipIndexImpl struct {
	byZip  map[string]domain.ZipPlace
	byCity map[string][]domain.ZipPlace
	all    []domain.ZipPlace
}

var _ port.ZipIndex = (*zipIndexImpl)(nil)

// New parses the embedded table.
func New() (port.ZipIndex, error) {
	data, err := files.ReadFile("us_zips.tsv.gz")
	if err != nil {
		return nil, fmt.Errorf("zipindex: embedded data: %w", err)
	}
	return Parse(data)
}

// Parse reads a gzip-compressed table; exported for tests with small fixtures.
func Parse(data []byte) (port.ZipIndex, error) {
	zr, err := gzip.NewReader(bytes.NewReader(data))
	if err != nil {
		return nil, fmt.Errorf("zipindex: open: %w", err)
	}
	defer func() { _ = zr.Close() }()
	ix := &zipIndexImpl{byZip: map[string]domain.ZipPlace{}, byCity: map[string][]domain.ZipPlace{}}
	sc := bufio.NewScanner(zr)
	for sc.Scan() {
		f := strings.Split(sc.Text(), "\t")
		if len(f) != 5 {
			continue
		}
		lat, e1 := strconv.ParseFloat(f[3], 64)
		lon, e2 := strconv.ParseFloat(f[4], 64)
		if e1 != nil || e2 != nil {
			continue
		}
		p := domain.ZipPlace{Zip: f[0], City: f[1], State: f[2], Lat: lat, Lon: lon}
		ix.byZip[p.Zip] = p
		ix.all = append(ix.all, p)
		key := cityKey(p.City, p.State)
		ix.byCity[key] = append(ix.byCity[key], p)
	}
	if err := sc.Err(); err != nil {
		return nil, fmt.Errorf("zipindex: read: %w", err)
	}
	return ix, nil
}

func (ix *zipIndexImpl) Lookup(zip string) (domain.ZipPlace, bool) {
	p, ok := ix.byZip[strings.TrimSpace(zip)]
	return p, ok
}

func (ix *zipIndexImpl) Nearby(lat, lon, miles float64, limit int) []domain.NearbyCity {
	// A degree of latitude is ~69 miles; the box keeps the scan cheap before the exact distance.
	dLat := miles / 69.0
	dLon := miles / (69.0 * math.Max(0.2, math.Cos(lat*math.Pi/180)))
	cities := map[string]*domain.NearbyCity{}
	for _, p := range ix.all {
		if math.Abs(p.Lat-lat) > dLat || math.Abs(p.Lon-lon) > dLon {
			continue
		}
		d := domain.HaversineMiles(lat, lon, p.Lat, p.Lon)
		if d > miles {
			continue
		}
		key := p.City + "|" + p.State
		c := cities[key]
		if c == nil {
			c = &domain.NearbyCity{City: p.City, State: p.State, Lat: p.Lat, Lon: p.Lon, DistanceMiles: d}
			cities[key] = c
		}
		c.Zips = append(c.Zips, p.Zip)
		if d < c.DistanceMiles {
			c.DistanceMiles, c.Lat, c.Lon = d, p.Lat, p.Lon
		}
	}
	out := make([]domain.NearbyCity, 0, len(cities))
	for _, c := range cities {
		c.DistanceMiles = math.Round(c.DistanceMiles*10) / 10
		sort.Strings(c.Zips)
		out = append(out, *c)
	}
	sort.Slice(out, func(i, j int) bool {
		if out[i].DistanceMiles != out[j].DistanceMiles {
			return out[i].DistanceMiles < out[j].DistanceMiles
		}
		return out[i].City < out[j].City
	})
	if limit > 0 && len(out) > limit {
		out = out[:limit]
	}
	return out
}

func cityKey(city, state string) string {
	return strings.ToUpper(strings.Join(strings.Fields(city), " ")) + "|" + strings.ToUpper(strings.TrimSpace(state))
}

func (ix *zipIndexImpl) ByCity(city, state string) (domain.CityZips, bool) {
	places := ix.byCity[cityKey(city, state)]
	if len(places) == 0 {
		return domain.CityZips{}, false
	}
	var lat, lon float64
	for _, p := range places {
		lat, lon = lat+p.Lat, lon+p.Lon
	}
	lat, lon = lat/float64(len(places)), lon/float64(len(places))
	out := domain.CityZips{City: places[0].City, State: places[0].State, Zips: make([]string, 0, len(places))}
	best := math.MaxFloat64
	for _, p := range places {
		out.Zips = append(out.Zips, p.Zip)
		d := domain.HaversineMiles(lat, lon, p.Lat, p.Lon)
		if d < best || (d == best && p.Zip < out.Zip) {
			best, out.Zip = d, p.Zip
		}
	}
	sort.Strings(out.Zips)
	return out, true
}
