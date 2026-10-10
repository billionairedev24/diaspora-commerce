package postgres

import (
	"context"
	"errors"
	"fmt"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/port"
)

type templateStoreImpl struct{ pool *pgxpool.Pool }

var _ port.TemplateStore = (*templateStoreImpl)(nil)

// NewTemplateStore returns the Postgres TemplateStore.
func NewTemplateStore(pool *pgxpool.Pool) port.TemplateStore { return &templateStoreImpl{pool: pool} }

const templateColumns = `key, locale, channel, version, category, subject, body_html, body_text, created_by, created_at`

func scanTemplate(row pgx.Row) (domain.Template, error) {
	var t domain.Template
	var ch, cat string
	err := row.Scan(&t.Key, &t.Locale, &ch, &t.Version, &cat, &t.Subject, &t.BodyHTML, &t.BodyText, &t.CreatedBy, &t.CreatedAt)
	if errors.Is(err, pgx.ErrNoRows) {
		return t, domain.ErrTemplateNotFound
	}
	if err != nil {
		return t, fmt.Errorf("scan template: %w", err)
	}
	t.Channel, t.Category = domain.Channel(ch), domain.Category(cat)
	return t, nil
}

func (s *templateStoreImpl) Find(ctx context.Context, key string, locales []string, channel domain.Channel, version int) (domain.Template, error) {
	return scanTemplate(s.pool.QueryRow(ctx, `SELECT `+templateColumns+` FROM template
		WHERE key = $1 AND channel = $2 AND locale = ANY($3) AND ($4 = 0 OR version = $4)
		ORDER BY array_position($3, locale), version DESC LIMIT 1`, key, string(channel), locales, version))
}

func (s *templateStoreImpl) Create(ctx context.Context, t domain.Template) (domain.Template, error) {
	var out domain.Template
	err := pgx.BeginFunc(ctx, s.pool, func(tx pgx.Tx) error {
		// Serialise version numbering per key/locale/channel.
		if _, err := tx.Exec(ctx, `SELECT pg_advisory_xact_lock(hashtext($1))`, t.Key+"|"+t.Locale+"|"+string(t.Channel)); err != nil {
			return fmt.Errorf("lock: %w", err)
		}
		var err error
		out, err = scanTemplate(tx.QueryRow(ctx, `INSERT INTO template (key, locale, channel, version, category, subject,
				body_html, body_text, created_by)
			SELECT $1, $2, $3, coalesce(max(version), 0) + 1, $4, $5, $6, $7, $8
			FROM template WHERE key = $1 AND locale = $2 AND channel = $3
			RETURNING `+templateColumns,
			t.Key, t.Locale, string(t.Channel), string(t.Category), t.Subject, t.BodyHTML, t.BodyText, t.CreatedBy))
		return err
	})
	if err != nil {
		return domain.Template{}, fmt.Errorf("create template: %w", err)
	}
	return out, nil
}

func (s *templateStoreImpl) List(ctx context.Context, key string) ([]domain.Template, error) {
	var rows pgx.Rows
	var err error
	if key == "" {
		rows, err = s.pool.Query(ctx, `SELECT DISTINCT ON (key, locale, channel) `+templateColumns+` FROM template
			ORDER BY key, locale, channel, version DESC`)
	} else {
		rows, err = s.pool.Query(ctx, `SELECT `+templateColumns+` FROM template WHERE key = $1
			ORDER BY locale, channel, version DESC`, key)
	}
	if err != nil {
		return nil, fmt.Errorf("list templates: %w", err)
	}
	defer rows.Close()
	out := []domain.Template{}
	for rows.Next() {
		t, err := scanTemplate(rows)
		if err != nil {
			return nil, err
		}
		out = append(out, t)
	}
	if err := rows.Err(); err != nil {
		return nil, fmt.Errorf("rows: %w", err)
	}
	return out, nil
}
