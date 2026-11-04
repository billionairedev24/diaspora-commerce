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

const stockAlertColumns = `id, coalesce(user_id,''), atin, coalesce(email,''), market, locale, coalesce(product_title,''), created_at, notified_at`

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

// ownerPredicate selects one shopper's alerts ($1 = the returned owner key): by account, or by address for a guest.
func ownerPredicate(a domain.StockAlert) (predicate, owner string) {
	if a.Guest() {
		return `user_id IS NULL AND email = $1`, a.Email
	}
	return `user_id = $1`, a.UserID
}

func (s *stockAlertStoreImpl) Subscribe(ctx context.Context, a domain.StockAlert, maxOpen int) (domain.StockAlert, bool, error) {
	var out domain.StockAlert
	created := false
	pred, owner := ownerPredicate(a)
	err := pgx.BeginFunc(ctx, s.pool, func(tx pgx.Tx) error {
		// One writer per owner at a time, so the cap and the (owner, atin) uniqueness hold under concurrent requests.
		if _, err := tx.Exec(ctx, `SELECT pg_advisory_xact_lock(hashtextextended($1, 0))`, "stock-alert:"+owner); err != nil {
			return fmt.Errorf("lock owner: %w", err)
		}
		existing, findErr := scanStockAlert(tx.QueryRow(ctx, `SELECT `+stockAlertColumns+` FROM stock_alert
			WHERE `+pred+` AND atin = $2 FOR UPDATE`, owner, a.Atin))
		if findErr == nil && existing.Status == domain.StockAlertActive {
			out = existing
			return nil
		}
		if findErr != nil && !errors.Is(findErr, domain.ErrNotFound) {
			return findErr
		}
		var open int
		if err := tx.QueryRow(ctx, `SELECT count(*) FROM stock_alert WHERE `+pred+` AND notified_at IS NULL`, owner).Scan(&open); err != nil {
			return fmt.Errorf("count open alerts: %w", err)
		}
		if maxOpen > 0 && open >= maxOpen {
			return domain.ErrAlertLimit
		}
		var err error
		if findErr == nil {
			// Notified before: re-arm it with the latest contact details.
			out, err = scanStockAlert(tx.QueryRow(ctx, `UPDATE stock_alert SET email = coalesce($2, email), market = $3,
				locale = $4, product_title = coalesce($5, product_title), notified_at = NULL, notified_event_id = NULL,
				created_at = now(), updated_at = now()
				WHERE id = $1 RETURNING `+stockAlertColumns,
				existing.ID, nullable(a.Email), a.Market, a.Locale, nullable(a.ProductTitle)))
			created = err == nil
			return err
		}
		out, err = scanStockAlert(tx.QueryRow(ctx, `INSERT INTO stock_alert (id, user_id, atin, email, market, locale, product_title)
			VALUES ($1, $2, $3, $4, $5, $6, $7) RETURNING `+stockAlertColumns,
			uuid.New(), nullable(a.UserID), a.Atin, nullable(a.Email), a.Market, a.Locale, nullable(a.ProductTitle)))
		created = err == nil
		return err
	})
	if errors.Is(err, domain.ErrAlertLimit) {
		return out, false, err
	}
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

func (s *stockAlertStoreImpl) DeleteByID(ctx context.Context, id string) error {
	u, err := uuid.Parse(id)
	if err != nil {
		return nil // not one of ours: nothing to delete
	}
	if _, err := s.pool.Exec(ctx, `DELETE FROM stock_alert WHERE id = $1`, u); err != nil {
		return fmt.Errorf("delete stock alert by id: %w", err)
	}
	return nil
}

func (s *stockAlertStoreImpl) Purge(ctx context.Context, openBefore, notifiedBefore time.Time) (int64, error) {
	tag, err := s.pool.Exec(ctx, `DELETE FROM stock_alert
		WHERE (notified_at IS NULL AND created_at < $1) OR (notified_at IS NOT NULL AND notified_at < $2)`,
		openBefore, notifiedBefore)
	if err != nil {
		return 0, fmt.Errorf("purge stock alerts: %w", err)
	}
	return tag.RowsAffected(), nil
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
		RETURNING a.id, coalesce(a.user_id,''), a.atin, coalesce(a.email,''), a.market, a.locale, coalesce(a.product_title,''),
			a.created_at, a.notified_at`, atin, eventID, limit)
	if err != nil {
		return nil, fmt.Errorf("claim stock alerts: %w", err)
	}
	return collectStockAlerts(rows)
}
