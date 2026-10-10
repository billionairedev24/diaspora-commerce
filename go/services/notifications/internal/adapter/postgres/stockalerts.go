package postgres

import (
	"context"
	"errors"
	"fmt"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/port"
)

type stockAlertStoreImpl struct{ pool *pgxpool.Pool }

var _ port.StockAlertStore = (*stockAlertStoreImpl)(nil)

// NewStockAlertStore returns the Postgres StockAlertStore.
func NewStockAlertStore(pool *pgxpool.Pool) port.StockAlertStore {
	return &stockAlertStoreImpl{pool: pool}
}

const stockAlertColumns = `id, user_id, atin, coalesce(email,''), market, locale, coalesce(product_title,''), created_at, notified_at`

func scanStockAlert(row pgx.Row) (domain.StockAlert, error) {
	var a domain.StockAlert
	var id uuid.UUID
	var notified *time.Time
	err := row.Scan(&id, &a.UserID, &a.Atin, &a.Email, &a.Market, &a.Locale, &a.ProductTitle, &a.CreatedAt, &notified)
	if errors.Is(err, pgx.ErrNoRows) {
		return a, domain.ErrNotFound
	}
	if err != nil {
		return a, fmt.Errorf("scan stock alert: %w", err)
	}
	a.ID, a.NotifiedAt, a.Status = id.String(), notified, domain.StockAlertActive
	if notified != nil {
		a.Status = domain.StockAlertNotified
	}
	return a, nil
}

func collectStockAlerts(rows pgx.Rows) ([]domain.StockAlert, error) {
	defer rows.Close()
	out := []domain.StockAlert{}
	for rows.Next() {
		a, err := scanStockAlert(rows)
		if err != nil {
			return nil, err
		}
		out = append(out, a)
	}
	if err := rows.Err(); err != nil {
		return nil, fmt.Errorf("rows: %w", err)
	}
	return out, nil
}

func (s *stockAlertStoreImpl) Subscribe(ctx context.Context, a domain.StockAlert) (domain.StockAlert, bool, error) {
	var out domain.StockAlert
	created := false
	err := pgx.BeginFunc(ctx, s.pool, func(tx pgx.Tx) error {
		row := tx.QueryRow(ctx, `INSERT INTO stock_alert (id, user_id, atin, email, market, locale, product_title)
			VALUES ($1, $2, $3, $4, $5, $6, $7) ON CONFLICT (user_id, atin) DO NOTHING RETURNING `+stockAlertColumns,
			uuid.New(), a.UserID, a.Atin, nullable(a.Email), a.Market, a.Locale, nullable(a.ProductTitle))
		inserted, err := scanStockAlert(row)
		if err == nil {
			out, created = inserted, true
			return nil
		}
		if !errors.Is(err, domain.ErrNotFound) {
			return err
		}
		existing, err := scanStockAlert(tx.QueryRow(ctx, `SELECT `+stockAlertColumns+` FROM stock_alert
			WHERE user_id = $1 AND atin = $2 FOR UPDATE`, a.UserID, a.Atin))
		if err != nil {
			return err
		}
		if existing.Status == domain.StockAlertActive {
			out = existing
			return nil
		}
		// Notified before: re-arm it with the latest contact details.
		out, err = scanStockAlert(tx.QueryRow(ctx, `UPDATE stock_alert SET email = coalesce($3, email), market = $4,
			locale = $5, product_title = coalesce($6, product_title), notified_at = NULL, notified_event_id = NULL,
			created_at = now(), updated_at = now()
			WHERE user_id = $1 AND atin = $2 RETURNING `+stockAlertColumns,
			a.UserID, a.Atin, nullable(a.Email), a.Market, a.Locale, nullable(a.ProductTitle)))
		created = err == nil
		return err
	})
	if err != nil {
		return out, false, fmt.Errorf("subscribe stock alert: %w", err)
	}
	return out, created, nil
}

func (s *stockAlertStoreImpl) Unsubscribe(ctx context.Context, userID, atin string) error {
	tag, err := s.pool.Exec(ctx, `DELETE FROM stock_alert WHERE user_id = $1 AND atin = $2`, userID, atin)
	if err != nil {
		return fmt.Errorf("delete stock alert: %w", err)
	}
	if tag.RowsAffected() == 0 {
		return domain.ErrNotFound
	}
	return nil
}

func (s *stockAlertStoreImpl) ListByUser(ctx context.Context, userID string) ([]domain.StockAlert, error) {
	rows, err := s.pool.Query(ctx, `SELECT `+stockAlertColumns+` FROM stock_alert WHERE user_id = $1
		ORDER BY created_at DESC LIMIT 200`, userID)
	if err != nil {
		return nil, fmt.Errorf("list stock alerts: %w", err)
	}
	return collectStockAlerts(rows)
}

func (s *stockAlertStoreImpl) ClaimForRestock(ctx context.Context, atin, eventID string, limit int) ([]domain.StockAlert, error) {
	rows, err := s.pool.Query(ctx, `WITH due AS (
			SELECT id FROM stock_alert
			WHERE atin = $1 AND (notified_at IS NULL OR notified_event_id = $2)
			ORDER BY created_at
			LIMIT $3
			FOR UPDATE SKIP LOCKED)
		UPDATE stock_alert a SET notified_at = coalesce(a.notified_at, now()), notified_event_id = $2, updated_at = now()
		FROM due WHERE a.id = due.id
		RETURNING a.id, a.user_id, a.atin, coalesce(a.email,''), a.market, a.locale, coalesce(a.product_title,''),
			a.created_at, a.notified_at`, atin, eventID, limit)
	if err != nil {
		return nil, fmt.Errorf("claim stock alerts: %w", err)
	}
	return collectStockAlerts(rows)
}
