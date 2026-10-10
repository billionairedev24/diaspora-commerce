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
	content_type, bytes, coalesce(width, 0), coalesce(height, 0), status, flags, created_at, moderation, phash, version`

func (r *repoImpl) Insert(ctx context.Context, a domain.Asset) error {
	flags, moderation, err := encode(a)
	if err != nil {
		return err
	}
	_, err = r.pool.Exec(ctx, `insert into asset (id, owner_org, uploaded_by, purpose, provider, object_key, url,
		file_name, content_type, bytes, status, flags, created_at, moderation, phash, version)
		values ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,$13,$14,$15,0)`,
		a.ID, a.OwnerOrg, a.UploadedBy, a.Purpose, a.Provider, a.Key, a.URL, a.FileName, a.ContentType, a.Bytes,
		a.Status, flags, a.CreatedAt, moderation, phash(a.PHash))
	if err != nil {
		return fmt.Errorf("insert asset: %w", err)
	}
	return nil
}

func encode(a domain.Asset) (flags, moderation []byte, err error) {
	flags, err = json.Marshal(a.Flags)
	if err != nil {
		return nil, nil, fmt.Errorf("encode flags: %w", err)
	}
	if a.Moderation != nil {
		moderation, err = json.Marshal(a.Moderation)
		if err != nil {
			return nil, nil, fmt.Errorf("encode moderation: %w", err)
		}
	}
	return flags, moderation, nil
}

// phash stores the unsigned 64-bit hash in a bigint (same bits).
func phash(h *uint64) *int64 {
	if h == nil {
		return nil
	}
	v := int64(*h) //nolint:gosec // bit pattern kept on purpose; compared with XOR / bit_count
	return &v
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
	flags, moderation, err := encode(a)
	if err != nil {
		return err
	}
	tag, err := r.pool.Exec(ctx, `update asset set url = $2, content_type = $3, bytes = $4, width = $5, height = $6,
		status = $7, flags = $8, provider = $9, object_key = $10, moderation = $11, phash = $12,
		version = version + 1, updated_at = now() where id = $1 and version = $13`,
		a.ID, a.URL, a.ContentType, a.Bytes, a.Width, a.Height, a.Status, flags, a.Provider, a.Key, moderation,
		phash(a.PHash), a.Version)
	if err != nil {
		return fmt.Errorf("update asset: %w", err)
	}
	if tag.RowsAffected() == 0 {
		return fmt.Errorf("update asset %s: %w", a.ID, domain.ErrConflict)
	}
	return nil
}

// SimilarImages compares 64-bit perceptual hashes by Hamming distance (bit_count of the XOR).
func (r *repoImpl) SimilarImages(ctx context.Context, purposes []domain.Purpose, hash uint64, maxDistance int,
	excludeID string, limit int,
) ([]domain.SimilarImage, error) {
	ps := make([]string, 0, len(purposes))
	for _, p := range purposes {
		ps = append(ps, string(p))
	}
	h := int64(hash) //nolint:gosec // same bit pattern as stored
	rows, err := r.pool.Query(ctx, `select id::text, owner_org, d from (
			select id, owner_org, created_at, bit_count((phash # $2)::bit(64))::int as d from asset
			where purpose = any($1) and phash is not null and status in ('READY', 'FLAGGED') and id::text <> $3
		) m where d <= $4 order by d, created_at limit $5`, ps, h, excludeID, maxDistance, limit)
	if err != nil {
		return nil, fmt.Errorf("similar images: %w", err)
	}
	defer rows.Close()
	out := []domain.SimilarImage{}
	for rows.Next() {
		var s domain.SimilarImage
		if err := rows.Scan(&s.AssetID, &s.OwnerOrg, &s.Distance); err != nil {
			return nil, fmt.Errorf("scan similar image: %w", err)
		}
		out = append(out, s)
	}
	if err := rows.Err(); err != nil {
		return nil, fmt.Errorf("rows: %w", err)
	}
	return out, nil
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
		var flags, moderation []byte
		var hash *int64
		if err := rows.Scan(&a.ID, &a.OwnerOrg, &a.UploadedBy, &a.Purpose, &a.Provider, &a.Key, &a.URL, &a.FileName,
			&a.ContentType, &a.Bytes, &a.Width, &a.Height, &a.Status, &flags, &a.CreatedAt, &moderation, &hash,
			&a.Version); err != nil {
			return nil, fmt.Errorf("scan asset: %w", err)
		}
		if err := json.Unmarshal(flags, &a.Flags); err != nil {
			return nil, fmt.Errorf("decode flags: %w", err)
		}
		if len(moderation) > 0 {
			a.Moderation = &domain.Moderation{}
			if err := json.Unmarshal(moderation, a.Moderation); err != nil {
				return nil, fmt.Errorf("decode moderation: %w", err)
			}
		}
		if hash != nil {
			h := uint64(*hash) //nolint:gosec // same bit pattern as stored
			a.PHash = &h
		}
		out = append(out, a)
	}
	if err := rows.Err(); err != nil {
		return nil, fmt.Errorf("rows: %w", err)
	}
	return out, nil
}
