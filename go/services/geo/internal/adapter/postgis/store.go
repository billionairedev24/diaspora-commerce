// Package postgis implements port.ZoneStore on PostgreSQL + PostGIS (geography type, metres).
package postgis

import (
	"context"
	"fmt"
	"io/fs"
	"sort"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/billionairedev24/diaspora-commerce/go/services/geo/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/geo/internal/port"
)

const metresPerMile = 1609.344

type storeImpl struct {
	pool *pgxpool.Pool
}

var _ port.ZoneStore = (*storeImpl)(nil)

// New returns a ZoneStore backed by pool.
func New(pool *pgxpool.Pool) port.ZoneStore { return &storeImpl{pool: pool} }

// Migrate applies the embedded SQL files in name order (idempotent statements).
func Migrate(ctx context.Context, pool *pgxpool.Pool, files fs.FS) error {
	names, err := fs.Glob(files, "migrations/*.sql")
	if err != nil {
		return fmt.Errorf("list migrations: %w", err)
	}
	sort.Strings(names)
	for _, n := range names {
		sql, err := fs.ReadFile(files, n)
		if err != nil {
			return fmt.Errorf("read %s: %w", n, err)
		}
		if _, err := pool.Exec(ctx, string(sql)); err != nil {
			return fmt.Errorf("apply %s: %w", n, err)
		}
	}
	return nil
}

const cols = `id::text, type, owner_id, name, coalesce(ST_AsGeoJSON(area), ''),
	coalesce(ST_Y(center::geometry), 0), coalesce(ST_X(center::geometry), 0), coalesce(radius_m, 0), active`

func (s *storeImpl) Save(ctx context.Context, z domain.Zone) (domain.Zone, error) {
	if z.ID == "" {
		z.ID = uuid.NewString()
	}
	var err error
	if z.GeoJSON != "" {
		_, err = s.pool.Exec(ctx, `
			insert into zone (id, type, owner_id, name, area, center, radius_m, active, updated_at)
			values ($1, $2, $3, $4, ST_Multi(ST_GeomFromGeoJSON($5))::geography, null::geography, null::float8, $6, now())
			on conflict (id) do update set type = excluded.type, owner_id = excluded.owner_id, name = excluded.name,
				area = excluded.area, center = null, radius_m = null, active = excluded.active, updated_at = now()`,
			z.ID, z.Type, z.OwnerID, z.Name, z.GeoJSON, z.Active)
	} else {
		_, err = s.pool.Exec(ctx, `
			insert into zone (id, type, owner_id, name, area, center, radius_m, active, updated_at)
			values ($1, $2, $3, $4, null, ST_SetSRID(ST_MakePoint($6, $5), 4326)::geography, $7, $8, now())
			on conflict (id) do update set type = excluded.type, owner_id = excluded.owner_id, name = excluded.name,
				area = null, center = excluded.center, radius_m = excluded.radius_m, active = excluded.active,
				updated_at = now()`,
			z.ID, z.Type, z.OwnerID, z.Name, z.CenterLat, z.CenterLon, z.RadiusMiles*metresPerMile, z.Active)
	}
	if err != nil {
		return domain.Zone{}, fmt.Errorf("save zone: %w", err)
	}
	return z, nil
}

func (s *storeImpl) List(ctx context.Context, t domain.ZoneType, ownerID string) ([]domain.Zone, error) {
	rows, err := s.pool.Query(ctx, `select `+cols+` from zone
		where ($1 = '' or type = $1) and ($2 = '' or owner_id = $2) order by name`, string(t), ownerID)
	if err != nil {
		return nil, fmt.Errorf("list zones: %w", err)
	}
	return collect(rows)
}

func (s *storeImpl) Containing(ctx context.Context, lat, lon float64, t domain.ZoneType) ([]domain.Zone, error) {
	rows, err := s.pool.Query(ctx, `
		with p as (select ST_SetSRID(ST_MakePoint($2, $1), 4326)::geography as g)
		select `+cols+` from zone, p
		where active and ($3 = '' or type = $3)
		  and ((area is not null and ST_Covers(area, p.g)) or (center is not null and ST_DWithin(center, p.g, radius_m)))
		order by name`, lat, lon, string(t))
	if err != nil {
		return nil, fmt.Errorf("zones containing point: %w", err)
	}
	return collect(rows)
}

func (s *storeImpl) Within(ctx context.Context, lat, lon float64, t domain.ZoneType, metres float64) ([]domain.ZoneMatch, error) {
	rows, err := s.pool.Query(ctx, `
		with p as (select ST_SetSRID(ST_MakePoint($2, $1), 4326)::geography as g),
		     z as (select zone.*, case when area is not null then ST_Distance(area, p.g)
		                               else greatest(ST_Distance(center, p.g) - radius_m, 0) end as gap
		         from zone, p
		         where active and ($3 = '' or type = $3)
		           and ((area is not null and ST_DWithin(area, p.g, $4))
		             or (center is not null and ST_DWithin(center, p.g, radius_m + $4))))
		select `+cols+`, gap from z order by gap, name`, lat, lon, string(t), metres)
	if err != nil {
		return nil, fmt.Errorf("zones near point: %w", err)
	}
	defer rows.Close()
	out := []domain.ZoneMatch{}
	for rows.Next() {
		var z domain.Zone
		var radiusM, gap float64
		if err := rows.Scan(&z.ID, &z.Type, &z.OwnerID, &z.Name, &z.GeoJSON, &z.CenterLat, &z.CenterLon, &radiusM,
			&z.Active, &gap); err != nil {
			return nil, fmt.Errorf("scan zone: %w", err)
		}
		z.RadiusMiles = radiusM / metresPerMile
		out = append(out, domain.ZoneMatch{Zone: z, Inside: gap <= 0, DistanceMiles: domain.MetresToMiles(gap)})
	}
	if err := rows.Err(); err != nil {
		return nil, fmt.Errorf("rows: %w", err)
	}
	return out, nil
}

func (s *storeImpl) Ping(ctx context.Context) error {
	if err := s.pool.Ping(ctx); err != nil {
		return fmt.Errorf("postgres ping: %w", err)
	}
	return nil
}

func collect(rows pgx.Rows) ([]domain.Zone, error) {
	defer rows.Close()
	out := []domain.Zone{}
	for rows.Next() {
		var z domain.Zone
		var radiusM float64
		if err := rows.Scan(&z.ID, &z.Type, &z.OwnerID, &z.Name, &z.GeoJSON, &z.CenterLat, &z.CenterLon, &radiusM,
			&z.Active); err != nil {
			return nil, fmt.Errorf("scan zone: %w", err)
		}
		z.RadiusMiles = radiusM / metresPerMile
		out = append(out, z)
	}
	if err := rows.Err(); err != nil {
		return nil, fmt.Errorf("rows: %w", err)
	}
	return out, nil
}
