package postgis

import (
	"context"
	"fmt"

	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/billionairedev24/diaspora-commerce/go/services/geo/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/geo/internal/port"
)

type distanceImpl struct {
	pool *pgxpool.Pool
}

var _ port.DistanceCalculator = (*distanceImpl)(nil)

// NewDistanceCalculator returns geodesic distances computed by PostGIS on the WGS84 spheroid (geography type).
func NewDistanceCalculator(pool *pgxpool.Pool) port.DistanceCalculator {
	return &distanceImpl{pool: pool}
}

// Geodesic answers one origin → many destinations in a single query; results keep the destinations' order.
func (d *distanceImpl) Geodesic(ctx context.Context, from domain.Point, to []domain.Point) ([]float64, error) {
	if len(to) == 0 {
		return []float64{}, nil
	}
	lats := make([]float64, len(to))
	lngs := make([]float64, len(to))
	for i, p := range to {
		lats[i], lngs[i] = p.Lat, p.Lng
	}
	rows, err := d.pool.Query(ctx, `
		with o as (select ST_SetSRID(ST_MakePoint($2, $1), 4326)::geography as g)
		select ST_Distance(o.g, ST_SetSRID(ST_MakePoint(t.lng, t.lat), 4326)::geography)
		from o, unnest($3::float8[], $4::float8[]) with ordinality as t(lat, lng, i)
		order by t.i`, from.Lat, from.Lng, lats, lngs)
	if err != nil {
		return nil, fmt.Errorf("geodesic distance: %w", err)
	}
	defer rows.Close()
	out := make([]float64, 0, len(to))
	for rows.Next() {
		var m float64
		if err := rows.Scan(&m); err != nil {
			return nil, fmt.Errorf("scan distance: %w", err)
		}
		out = append(out, m)
	}
	if err := rows.Err(); err != nil {
		return nil, fmt.Errorf("distance rows: %w", err)
	}
	return out, nil
}
