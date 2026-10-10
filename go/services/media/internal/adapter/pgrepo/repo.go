// Package pgrepo implements port.AssetRepository on PostgreSQL.
package pgrepo

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io/fs"
	"sort"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/billionairedev24/diaspora-commerce/go/services/media/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/media/internal/port"
)

// ErrNotFound is returned when an asset does not exist.
var ErrNotFound = errors.New("asset not found")

type repoImpl struct {
	pool *pgxpool.Pool
}

var _ port.AssetRepository = (*repoImpl)(nil)

// New returns an AssetRepository.
func New(pool *pgxpool.Pool) port.AssetRepository { return &repoImpl{pool: pool} }

// Migrate applies the embedded SQL migrations in order.
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

const cols = `id::text, owner_org, uploaded_by, purpose, provider, object_key, coalesce(url, ''), file_name,
	content_type, bytes, coalesce(width, 0), coalesce(height, 0), status, flags, created_at`

func (r *repoImpl) Insert(ctx context.Context, a domain.Asset) error {
	flags, err := json.Marshal(a.Flags)
	if err != nil {
		return fmt.Errorf("encode flags: %w", err)
	}
	_, err = r.pool.Exec(ctx, `insert into asset (id, owner_org, uploaded_by, purpose, provider, object_key, url,
		file_name, content_type, bytes, status, flags, created_at) values ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,$13)`,
		a.ID, a.OwnerOrg, a.UploadedBy, a.Purpose, a.Provider, a.Key, a.URL, a.FileName, a.ContentType, a.Bytes,
		a.Status, flags, a.CreatedAt)
	if err != nil {
		return fmt.Errorf("insert asset: %w", err)
	}
	return nil
}

func (r *repoImpl) Get(ctx context.Context, id string) (domain.Asset, error) {
	rows, err := r.pool.Query(ctx, `select `+cols+` from asset where id = $1`, id)
	if err != nil {
		return domain.Asset{}, fmt.Errorf("get asset: %w", err)
	}
	as, err := collect(rows)
	if err != nil {
		return domain.Asset{}, err
	}
	if len(as) == 0 {
		return domain.Asset{}, ErrNotFound
	}
	return as[0], nil
}

func (r *repoImpl) Update(ctx context.Context, a domain.Asset) error {
	flags, err := json.Marshal(a.Flags)
	if err != nil {
		return fmt.Errorf("encode flags: %w", err)
	}
	_, err = r.pool.Exec(ctx, `update asset set url = $2, content_type = $3, bytes = $4, width = $5, height = $6,
		status = $7, flags = $8, updated_at = now() where id = $1`,
		a.ID, a.URL, a.ContentType, a.Bytes, a.Width, a.Height, a.Status, flags)
	if err != nil {
		return fmt.Errorf("update asset: %w", err)
	}
	return nil
}

func (r *repoImpl) ListByOrg(ctx context.Context, org string, purpose domain.Purpose, limit int) ([]domain.Asset, error) {
	rows, err := r.pool.Query(ctx, `select `+cols+` from asset where owner_org = $1 and ($2 = '' or purpose = $2)
		order by created_at desc limit $3`, org, string(purpose), limit)
	if err != nil {
		return nil, fmt.Errorf("list assets: %w", err)
	}
	return collect(rows)
}

func (r *repoImpl) Ping(ctx context.Context) error {
	if err := r.pool.Ping(ctx); err != nil {
		return fmt.Errorf("ping: %w", err)
	}
	return nil
}

func collect(rows pgx.Rows) ([]domain.Asset, error) {
	defer rows.Close()
	out := []domain.Asset{}
	for rows.Next() {
		var a domain.Asset
		var flags []byte
		if err := rows.Scan(&a.ID, &a.OwnerOrg, &a.UploadedBy, &a.Purpose, &a.Provider, &a.Key, &a.URL, &a.FileName,
			&a.ContentType, &a.Bytes, &a.Width, &a.Height, &a.Status, &flags, &a.CreatedAt); err != nil {
			return nil, fmt.Errorf("scan asset: %w", err)
		}
		if err := json.Unmarshal(flags, &a.Flags); err != nil {
			return nil, fmt.Errorf("decode flags: %w", err)
		}
		out = append(out, a)
	}
	if err := rows.Err(); err != nil {
		return nil, fmt.Errorf("rows: %w", err)
	}
	return out, nil
}
