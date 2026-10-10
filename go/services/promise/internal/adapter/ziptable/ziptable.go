// Package ziptable implements port.ZipDirectory and port.TransitTable from embedded CSV data: ZIP3 regions with
// centroid coordinates and time zones, and a ZIP3-to-ZIP3 ground transit table for the launch FCs. When a pair is
// not in the table (sellers ship from anywhere) transit is derived from the centroid distance. Data, not code:
// replace the CSVs with carrier zone charts or Census ZCTA centroids without touching the logic.
package ziptable

import (
	"context"
	"encoding/csv"
	"errors"
	"fmt"
	"io"
	"io/fs"
	"math"
	"strconv"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/services/promise/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/promise/internal/port"
)

// Band maps a distance to a ground transit range.
type Band struct {
	MaxMiles         float64
	MinDays, MaxDays int
}

// DefaultBands approximate ground carrier zone bands.
var DefaultBands = []Band{
	{150, 1, 1}, {300, 1, 2}, {600, 2, 2}, {1000, 2, 3}, {1400, 3, 4}, {2000, 4, 5}, {math.Inf(1), 5, 5},
}

// nonContiguous regions always take 5–7 ground days to or from the mainland.
var nonContiguous = map[string]bool{"AK": true, "HI": true, "PR": true}

type region struct {
	from, to int
	state    string
	lat, lon float64
	loc      *time.Location
}

type lane struct {
	origin           int
	from, to         int
	minDays, maxDays int
}

// Table is both the ZIP directory and the transit table.
type Table interface {
	port.ZipDirectory
	port.TransitTable
}

type tableImpl struct {
	regions []region
	lanes   []lane
	bands   []Band
}

var _ Table = (*tableImpl)(nil)

// Load reads regionsPath and transitPath (CSV, '#' comment lines allowed) from fsys.
func Load(fsys fs.FS, regionsPath, transitPath string) (Table, error) {
	t := &tableImpl{bands: DefaultBands}
	rows, err := readCSV(fsys, regionsPath)
	if err != nil {
		return nil, err
	}
	for i, r := range rows {
		if len(r) != 6 {
			return nil, fmt.Errorf("%s row %d: want 6 columns", regionsPath, i+1)
		}
		from, err1 := strconv.Atoi(r[0])
		to, err2 := strconv.Atoi(r[1])
		lat, err3 := strconv.ParseFloat(r[3], 64)
		lon, err4 := strconv.ParseFloat(r[4], 64)
		loc, err5 := time.LoadLocation(r[5])
		if err := errors.Join(err1, err2, err3, err4, err5); err != nil {
			return nil, fmt.Errorf("%s row %d: %w", regionsPath, i+1, err)
		}
		t.regions = append(t.regions, region{from: from, to: to, state: r[2], lat: lat, lon: lon, loc: loc})
	}
	rows, err = readCSV(fsys, transitPath)
	if err != nil {
		return nil, err
	}
	for i, r := range rows {
		if len(r) != 5 {
			return nil, fmt.Errorf("%s row %d: want 5 columns", transitPath, i+1)
		}
		vals := make([]int, 5)
		for j := range r {
			if vals[j], err = strconv.Atoi(r[j]); err != nil {
				return nil, fmt.Errorf("%s row %d: %w", transitPath, i+1, err)
			}
		}
		t.lanes = append(t.lanes, lane{origin: vals[0], from: vals[1], to: vals[2], minDays: vals[3], maxDays: vals[4]})
	}
	return t, nil
}

func readCSV(fsys fs.FS, path string) ([][]string, error) {
	f, err := fsys.Open(path)
	if err != nil {
		return nil, fmt.Errorf("open %s: %w", path, err)
	}
	defer func() { _ = f.Close() }()
	r := csv.NewReader(f)
	r.Comment = '#'
	r.TrimLeadingSpace = true
	var rows [][]string
	header := true
	for {
		rec, err := r.Read()
		if errors.Is(err, io.EOF) {
			break
		}
		if err != nil {
			return nil, fmt.Errorf("read %s: %w", path, err)
		}
		if header {
			header = false
			continue
		}
		rows = append(rows, rec)
	}
	return rows, nil
}

func (t *tableImpl) region(zip3 int) (region, bool) {
	for _, r := range t.regions {
		if zip3 >= r.from && zip3 <= r.to {
			return r, true
		}
	}
	return region{}, false
}

func (t *tableImpl) Lookup(zip string) (domain.ZipInfo, error) {
	z, err := domain.NormalizeZIP(zip)
	if err != nil {
		return domain.ZipInfo{}, fmt.Errorf("zip: %w", err)
	}
	n, _ := strconv.Atoi(z[:3])
	r, ok := t.region(n)
	if !ok {
		return domain.ZipInfo{}, fmt.Errorf("%w: ZIP %s is not in a served region", domain.ErrInvalid, z)
	}
	return domain.ZipInfo{ZIP: z, ZIP3: z[:3], State: r.state, Lat: r.lat, Lon: r.lon, Location: r.loc}, nil
}

func (t *tableImpl) Transit(_ context.Context, originZIP, destZIP string) (domain.Transit, error) {
	o, err := t.Lookup(originZIP)
	if err != nil {
		return domain.Transit{}, fmt.Errorf("origin: %w", err)
	}
	d, err := t.Lookup(destZIP)
	if err != nil {
		return domain.Transit{}, fmt.Errorf("destination: %w", err)
	}
	oz, _ := strconv.Atoi(o.ZIP3)
	dz, _ := strconv.Atoi(d.ZIP3)
	for _, l := range t.lanes {
		if l.origin == oz && dz >= l.from && dz <= l.to {
			return domain.Transit{MinDays: l.minDays, MaxDays: l.maxDays, Source: "table"}, nil
		}
	}
	if o.State != d.State && (nonContiguous[o.State] || nonContiguous[d.State]) {
		return domain.Transit{MinDays: 5, MaxDays: 7, Source: "distance"}, nil
	}
	miles := Haversine(o.Lat, o.Lon, d.Lat, d.Lon)
	for _, b := range t.bands {
		if miles <= b.MaxMiles {
			return domain.Transit{MinDays: b.MinDays, MaxDays: b.MaxDays, Source: "distance"}, nil
		}
	}
	return domain.Transit{MinDays: 5, MaxDays: 7, Source: "distance"}, nil
}

// Haversine returns the great-circle distance in miles.
func Haversine(lat1, lon1, lat2, lon2 float64) float64 {
	const earthMiles = 3958.8
	rad := func(d float64) float64 { return d * math.Pi / 180 }
	dLat, dLon := rad(lat2-lat1), rad(lon2-lon1)
	a := math.Sin(dLat/2)*math.Sin(dLat/2) + math.Cos(rad(lat1))*math.Cos(rad(lat2))*math.Sin(dLon/2)*math.Sin(dLon/2)
	return 2 * earthMiles * math.Asin(math.Sqrt(a))
}
