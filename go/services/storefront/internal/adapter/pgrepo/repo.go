// Package pgrepo implements port.StorefrontRepository on PostgreSQL (jsonb draft + published snapshot).
package pgrepo

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io/fs"
	"slices"
	"sort"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgconn"
	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/billionairedev24/diaspora-commerce/go/services/storefront/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/storefront/internal/port"
)

type repoImpl struct{ pool *pgxpool.Pool }

var _ port.StorefrontRepository = (*repoImpl)(nil)

// New returns the repository.
func New(pool *pgxpool.Pool) port.StorefrontRepository { return &repoImpl{pool: pool} }

// Migrate applies embedded migrations.
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

func hasPending(s *domain.Storefront) bool {
	return slices.ContainsFunc(s.Social, func(l domain.SocialLink) bool { return l.Status == domain.LinkPending })
}

func (r *repoImpl) Draft(ctx context.Context, orgID string) (domain.Storefront, error) {
	var body []byte
	var version int64
	err := r.pool.QueryRow(ctx, `select draft, version from storefront where org_id = $1`, orgID).Scan(&body, &version)
	if errors.Is(err, pgx.ErrNoRows) {
		return domain.Storefront{}, domain.ErrNotFound
	}
	if err != nil {
		return domain.Storefront{}, fmt.Errorf("load draft: %w", err)
	}
	var s domain.Storefront
	if err := json.Unmarshal(body, &s); err != nil {
		return domain.Storefront{}, fmt.Errorf("decode draft: %w", err)
	}
	s.Version = version
	return s, nil
}

func (r *repoImpl) SaveDraft(ctx context.Context, s *domain.Storefront) error {
	return r.write(ctx, s, false)
}

func (r *repoImpl) Publish(ctx context.Context, s *domain.Storefront) error {
	return r.write(ctx, s, true)
}

func (r *repoImpl) write(ctx context.Context, s *domain.Storefront, publish bool) error {
	next := *s
	next.Version++
	body, err := json.Marshal(next)
	if err != nil {
		return fmt.Errorf("encode: %w", err)
	}
	var published []byte
	if publish {
		published = body
	}
	var ct pgconn.CommandTag
	if s.Version == 0 {
		ct, err = r.pool.Exec(ctx, `insert into storefront (org_id, kind, slug, draft, published, pending_links, version, published_at)
			values ($1, $2, $3, $4, $5, $6, 1, $7) on conflict (org_id) do nothing`,
			s.OrgID, s.Kind, s.Slug, body, published, hasPending(s), s.PublishedAt)
	} else {
		ct, err = r.pool.Exec(ctx, `update storefront set slug = $2, draft = $3,
			published = coalesce($4, published), pending_links = $5, version = version + 1,
			published_at = coalesce($6, published_at), updated_at = now()
			where org_id = $1 and version = $7`,
			s.OrgID, s.Slug, body, published, hasPending(s), s.PublishedAt, s.Version)
	}
	var pgErr *pgconn.PgError
	if errors.As(err, &pgErr) && pgErr.Code == "23505" {
		return domain.ErrSlugTaken
	}
	if err != nil {
		return fmt.Errorf("write storefront: %w", err)
	}
	if ct.RowsAffected() == 0 {
		return domain.ErrConflict
	}
	*s = next
	return nil
}

func (r *repoImpl) Published(ctx context.Context, slug string) (domain.Storefront, error) {
	var body []byte
	err := r.pool.QueryRow(ctx, `select published from storefront where slug = $1 and published is not null`, slug).Scan(&body)
	if errors.Is(err, pgx.ErrNoRows) {
		return domain.Storefront{}, domain.ErrNotFound
	}
	if err != nil {
		return domain.Storefront{}, fmt.Errorf("load published: %w", err)
	}
	var s domain.Storefront
	if err := json.Unmarshal(body, &s); err != nil {
		return domain.Storefront{}, fmt.Errorf("decode published: %w", err)
	}
	return s, nil
}

func (r *repoImpl) WithPendingLinks(ctx context.Context, limit int) ([]domain.Storefront, error) {
	rows, err := r.pool.Query(ctx, `select draft, version from storefront where pending_links order by updated_at limit $1`, limit)
	if err != nil {
		return nil, fmt.Errorf("pending: %w", err)
	}
	defer rows.Close()
	out := []domain.Storefront{}
	for rows.Next() {
		var body []byte
		var version int64
		if err := rows.Scan(&body, &version); err != nil {
			return nil, fmt.Errorf("scan: %w", err)
		}
		var s domain.Storefront
		if err := json.Unmarshal(body, &s); err != nil {
			return nil, fmt.Errorf("decode: %w", err)
		}
		s.Version = version
		out = append(out, s)
	}
	if err := rows.Err(); err != nil {
		return nil, fmt.Errorf("rows: %w", err)
	}
	return out, nil
}

func (r *repoImpl) Ping(ctx context.Context) error {
	if err := r.pool.Ping(ctx); err != nil {
		return fmt.Errorf("ping: %w", err)
	}
	return nil
}
