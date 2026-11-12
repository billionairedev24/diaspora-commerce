// Package pgrepo implements port.AssetRepository on PostgreSQL.
package pgrepo

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io/fs"
	"sort"
	"strings"
	"time"

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

var (
	_ port.AssetRepository   = (*repoImpl)(nil)
	_ port.LibraryRepository = (*repoImpl)(nil)
	_ port.ImageRulesStore   = (*repoImpl)(nil)
)

// Repository is the asset store, the image library index and the store of the image rules override.
type Repository interface {
	port.AssetRepository
	port.LibraryRepository
	port.ImageRulesStore
}

// New returns the repository (assets and the image library).
func New(pool *pgxpool.Pool) Repository { return &repoImpl{pool: pool} }

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
	content_type, bytes, coalesce(width, 0), coalesce(height, 0), status, flags, created_at, moderation, phash, version,
	library, coalesce(library_sku, ''), coalesce(library_slot, ''), coalesce(image_slot, ''), coalesce(verdict, ''), checks,
	metrics, check_context, shadow`

func (r *repoImpl) Insert(ctx context.Context, a domain.Asset) error {
	flags, moderation, err := encode(a)
	if err != nil {
		return err
	}
	img, err := encodeImage(a)
	if err != nil {
		return err
	}
	_, err = r.pool.Exec(ctx, `insert into asset (id, owner_org, uploaded_by, purpose, provider, object_key, url,
		file_name, content_type, bytes, status, flags, created_at, moderation, phash, version, library, library_sku,
		library_slot, image_slot, verdict, checks, metrics, fill_ratio, border_ratio, check_context, shadow)
		values ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,$13,$14,$15,0,$16,nullif($17,''),nullif($18,''),
		nullif($19,''),nullif($20,''),$21,$22,$23,$24,$25,$26)`,
		a.ID, a.OwnerOrg, a.UploadedBy, a.Purpose, a.Provider, a.Key, a.URL, a.FileName, a.ContentType, a.Bytes,
		a.Status, flags, a.CreatedAt, moderation, phash(a.PHash), a.Library, a.SKU, string(a.Slot),
		string(a.ImageSlot), string(a.Verdict), img.checks, img.metrics, img.fill, img.border, img.context, a.Shadow)
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

// imageColumns are the slot-aware image check columns of an asset, encoded for the database.
type imageColumns struct {
	checks, metrics, context []byte
	fill, border             *float64
}

func encodeImage(a domain.Asset) (imageColumns, error) {
	var c imageColumns
	var err error
	if a.Checks != nil {
		if c.checks, err = json.Marshal(a.Checks); err != nil {
			return c, fmt.Errorf("encode checks: %w", err)
		}
	}
	if a.Metrics != nil {
		if c.metrics, err = json.Marshal(a.Metrics); err != nil {
			return c, fmt.Errorf("encode metrics: %w", err)
		}
		if a.Metrics.Measured {
			c.border = &a.Metrics.BorderRatio
			if a.Metrics.FillRatio >= 0 {
				c.fill = &a.Metrics.FillRatio
			}
		}
	}
	if a.Hint != (domain.ModerationHint{}) {
		if c.context, err = json.Marshal(a.Hint); err != nil {
			return c, fmt.Errorf("encode check context: %w", err)
		}
	}
	return c, nil
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
	img, err := encodeImage(a)
	if err != nil {
		return err
	}
	tag, err := r.pool.Exec(ctx, `update asset set url = $2, content_type = $3, bytes = $4, width = $5, height = $6,
		status = $7, flags = $8, provider = $9, object_key = $10, moderation = $11, phash = $12,
		file_name = $14, verdict = nullif($15, ''), checks = $16, metrics = $17, fill_ratio = $18, border_ratio = $19,
		shadow = $20, version = version + 1, updated_at = now() where id = $1 and version = $13`,
		a.ID, a.URL, a.ContentType, a.Bytes, a.Width, a.Height, a.Status, flags, a.Provider, a.Key, moderation,
		phash(a.PHash), a.Version, a.FileName, string(a.Verdict), img.checks, img.metrics, img.fill, img.border, a.Shadow)
	if err != nil {
		return fmt.Errorf("update asset: %w", err)
	}
	if tag.RowsAffected() == 0 {
		return fmt.Errorf("update asset %s: %w", a.ID, domain.ErrConflict)
	}
	return nil
}

// FindByURL returns the newest READY image with the public URL.
func (r *repoImpl) FindByURL(ctx context.Context, url string) (domain.Asset, bool, error) {
	rows, err := r.pool.Query(ctx, `select `+cols+` from asset where url = $1 and status = 'READY'
		order by created_at desc limit 1`, url)
	if err != nil {
		return domain.Asset{}, false, fmt.Errorf("find asset by url: %w", err)
	}
	as, err := collect(rows)
	if err != nil || len(as) == 0 {
		return domain.Asset{}, false, err
	}
	return as[0], true, nil
}

// UpdateChecks stores the slot, verdict, findings and measurements of a published image (a rescore).
func (r *repoImpl) UpdateChecks(ctx context.Context, a domain.Asset) error {
	img, err := encodeImage(a)
	if err != nil {
		return err
	}
	tag, err := r.pool.Exec(ctx, `update asset set width = $2, height = $3, verdict = nullif($4, ''), checks = $5,
		metrics = $6, fill_ratio = $7, border_ratio = $8, flags = $9, updated_at = now() where id = $1`,
		a.ID, a.Width, a.Height, string(a.Verdict), img.checks, img.metrics, img.fill, img.border, mustJSON(a.Flags))
	if err != nil {
		return fmt.Errorf("update checks: %w", err)
	}
	if tag.RowsAffected() == 0 {
		return ErrNotFound
	}
	return nil
}

func mustJSON(v any) []byte {
	b, err := json.Marshal(v)
	if err != nil {
		return []byte("null")
	}
	return b
}

// LoadImageRules reads the staff override of the image rules (empty when none was saved).
func (r *repoImpl) LoadImageRules(ctx context.Context) (domain.StoredImageRules, error) {
	var settings []byte
	var by *string
	var at *time.Time
	err := r.pool.QueryRow(ctx, `select settings, updated_by, updated_at from image_rules_override where id = 1`).
		Scan(&settings, &by, &at)
	if errors.Is(err, pgx.ErrNoRows) {
		return domain.StoredImageRules{}, nil
	}
	if err != nil {
		return domain.StoredImageRules{}, fmt.Errorf("load image rules: %w", err)
	}
	out := domain.StoredImageRules{UpdatedAt: at}
	if by != nil {
		out.UpdatedBy = *by
	}
	if err := json.Unmarshal(settings, &out.Override); err != nil {
		return domain.StoredImageRules{}, fmt.Errorf("decode image rules: %w", err)
	}
	return out, nil
}

// SaveImageRules replaces the staff override.
func (r *repoImpl) SaveImageRules(ctx context.Context, o domain.ImageRulesOverride, by string) error {
	b, err := json.Marshal(o)
	if err != nil {
		return fmt.Errorf("encode image rules: %w", err)
	}
	if _, err := r.pool.Exec(ctx, `insert into image_rules_override (id, settings, updated_by, updated_at)
		values (1, $1, $2, now()) on conflict (id) do update set settings = $1, updated_by = $2, updated_at = now()`,
		b, by); err != nil {
		return fmt.Errorf("save image rules: %w", err)
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

// libraryWhere filters an organisation's completed library images ($1 = org) by the query; args continue at $2.
func libraryWhere(q domain.LibraryQuery) (string, []any) {
	where := `owner_org = $1 and library and status <> 'PENDING'`
	var args []any
	if s := strings.TrimSpace(q.Q); s != "" {
		args = append(args, "%"+strings.NewReplacer(`\`, `\\`, "%", `\%`, "_", `\_`).Replace(strings.ToLower(s))+"%")
		n := len(args) + 1
		where += fmt.Sprintf(` and (lower(file_name) like $%d or lower(coalesce(library_sku, '')) like $%d)`, n, n)
	}
	switch q.Status {
	case string(domain.StatusRejected):
		where += ` and status = 'REJECTED'`
	case string(domain.StatusReady):
		// Published (quality flags do not stop that).
		where += ` and status <> 'REJECTED' and coalesce(url, '') <> ''`
	case string(domain.StatusFlagged):
		where += ` and status <> 'REJECTED' and coalesce(url, '') = ''`
	}
	if q.Matched != nil {
		if *q.Matched {
			where += ` and library_sku is not null`
		} else {
			where += ` and library_sku is null`
		}
	}
	return where, args
}

func (r *repoImpl) ListLibrary(ctx context.Context, org string, q domain.LibraryQuery) ([]domain.Asset, int, error) {
	where, args := libraryWhere(q)
	var total int
	if err := r.pool.QueryRow(ctx, `select count(*) from asset where `+where, append([]any{org}, args...)...).
		Scan(&total); err != nil {
		return nil, 0, fmt.Errorf("count library: %w", err)
	}
	n := len(args) + 2
	rows, err := r.pool.Query(ctx, `select `+cols+` from asset where `+where+
		fmt.Sprintf(` order by created_at desc, id limit $%d offset $%d`, n, n+1),
		append(append([]any{org}, args...), q.Size, q.Page*q.Size)...)
	if err != nil {
		return nil, 0, fmt.Errorf("list library: %w", err)
	}
	as, err := collect(rows)
	if err != nil {
		return nil, 0, err
	}
	return as, total, nil
}

func (r *repoImpl) LookupLibrary(ctx context.Context, org string, skus, fileNames []string) ([]domain.Asset, error) {
	lower := make([]string, 0, len(fileNames))
	for _, f := range fileNames {
		lower = append(lower, strings.ToLower(f))
	}
	if skus == nil {
		skus = []string{}
	}
	rows, err := r.pool.Query(ctx, `select `+cols+` from asset where owner_org = $1 and library
		and status in ('READY', 'FLAGGED') and (library_sku = any($2) or lower(file_name) = any($3))
		order by created_at desc, id`, org, skus, lower)
	if err != nil {
		return nil, fmt.Errorf("lookup library: %w", err)
	}
	return collect(rows)
}

func (r *repoImpl) RemoveFromLibrary(ctx context.Context, id string) error {
	tag, err := r.pool.Exec(ctx, `update asset set library = false, updated_at = now() where id = $1 and library`, id)
	if err != nil {
		return fmt.Errorf("remove from library: %w", err)
	}
	if tag.RowsAffected() == 0 {
		return ErrNotFound
	}
	return nil
}

func (r *repoImpl) RenameLibraryImage(ctx context.Context, id, newFileName string, sku, slot *string) error {
	tag, err := r.pool.Exec(ctx, `update asset set file_name = $2, library_sku = $3, library_slot = $4, updated_at = now()
		where id = $1 and library`, id, newFileName, sku, slot)
	if err != nil {
		return fmt.Errorf("rename library image: %w", err)
	}
	if tag.RowsAffected() == 0 {
		return ErrNotFound
	}
	return nil
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
		var flags, moderation, checks, metrics, hint []byte
		var hash *int64
		if err := rows.Scan(&a.ID, &a.OwnerOrg, &a.UploadedBy, &a.Purpose, &a.Provider, &a.Key, &a.URL, &a.FileName,
			&a.ContentType, &a.Bytes, &a.Width, &a.Height, &a.Status, &flags, &a.CreatedAt, &moderation, &hash,
			&a.Version, &a.Library, &a.SKU, &a.Slot, &a.ImageSlot, &a.Verdict, &checks, &metrics, &hint,
			&a.Shadow); err != nil {
			return nil, fmt.Errorf("scan asset: %w", err)
		}
		if len(checks) > 0 {
			if err := json.Unmarshal(checks, &a.Checks); err != nil {
				return nil, fmt.Errorf("decode checks: %w", err)
			}
		}
		if len(metrics) > 0 {
			a.Metrics = &domain.ImageMetrics{}
			if err := json.Unmarshal(metrics, a.Metrics); err != nil {
				return nil, fmt.Errorf("decode metrics: %w", err)
			}
		}
		if len(hint) > 0 {
			if err := json.Unmarshal(hint, &a.Hint); err != nil {
				return nil, fmt.Errorf("decode check context: %w", err)
			}
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
