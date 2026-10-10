package postgres

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"strconv"
	"strings"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/billionairedev24/diaspora-commerce/go/pkg/events"
	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/port"
)

type notificationStoreImpl struct{ pool *pgxpool.Pool }

type inboxImpl struct{ pool *pgxpool.Pool }

var (
	_ port.NotificationStore = (*notificationStoreImpl)(nil)
	_ events.Inbox           = (*inboxImpl)(nil)
)

// NewNotificationStore returns the Postgres NotificationStore.
func NewNotificationStore(pool *pgxpool.Pool) port.NotificationStore {
	return &notificationStoreImpl{pool: pool}
}

// NewInbox returns the Postgres events.Inbox (consumer, event id) de-duplicator.
func NewInbox(pool *pgxpool.Pool) events.Inbox { return &inboxImpl{pool: pool} }

func (i *inboxImpl) FirstDelivery(ctx context.Context, consumer, eventID string) (bool, error) {
	tag, err := i.pool.Exec(ctx, `INSERT INTO inbox (consumer, event_id) VALUES ($1, $2) ON CONFLICT DO NOTHING`, consumer, eventID)
	if err != nil {
		return false, fmt.Errorf("inbox: %w", err)
	}
	return tag.RowsAffected() == 1, nil
}

// columnList is the scan order of a notification row; coalesced columns are nullable.
var columnList = []struct {
	name     string
	nullable bool
}{
	{"id", false}, {"idempotency_key", false}, {"event_id", true}, {"template_key", false},
	{"template_version", false}, {"locale", false}, {"channel", false}, {"category", false}, {"market", false},
	{"recipient_id", true}, {"address", false}, {"time_zone", true}, {"subject", true}, {"body_html", true},
	{"body_text", true}, {"headers", false}, {"status", false}, {"status_detail", true}, {"attempts", false},
	{"next_attempt_at", false}, {"last_error", true}, {"provider", true}, {"provider_message_id", true},
	{"created_at", false}, {"updated_at", false}, {"sent_at", false},
}

// cols renders the select list, qualified with alias when given (UPDATE … RETURNING).
func cols(alias string) string {
	parts := make([]string, 0, len(columnList))
	for _, c := range columnList {
		name := c.name
		if alias != "" {
			name = alias + "." + name
		}
		if c.nullable {
			name = "coalesce(" + name + ",'')"
		}
		parts = append(parts, name)
	}
	return strings.Join(parts, ", ")
}

var columns = cols("")

func scan(row pgx.Row) (domain.Notification, error) {
	var n domain.Notification
	var id uuid.UUID
	var headers []byte
	var ch, cat, st string
	err := row.Scan(&id, &n.IdempotencyKey, &n.EventID, &n.TemplateKey, &n.TemplateVersion, &n.Locale, &ch, &cat,
		&n.Market, &n.RecipientID, &n.Address, &n.TimeZone, &n.Subject, &n.BodyHTML, &n.BodyText, &headers, &st,
		&n.StatusDetail, &n.Attempts, &n.NextAttemptAt, &n.LastError, &n.Provider, &n.ProviderMessageID,
		&n.CreatedAt, &n.UpdatedAt, &n.SentAt)
	if errors.Is(err, pgx.ErrNoRows) {
		return n, domain.ErrNotFound
	}
	if err != nil {
		return n, fmt.Errorf("scan notification: %w", err)
	}
	n.ID, n.Channel, n.Category, n.Status = id.String(), domain.Channel(ch), domain.Category(cat), domain.Status(st)
	if len(headers) > 0 {
		if err := json.Unmarshal(headers, &n.Headers); err != nil {
			return n, fmt.Errorf("decode headers: %w", err)
		}
	}
	return n, nil
}

func collect(rows pgx.Rows) ([]domain.Notification, error) {
	defer rows.Close()
	out := []domain.Notification{}
	for rows.Next() {
		n, err := scan(rows)
		if err != nil {
			return nil, err
		}
		out = append(out, n)
	}
	if err := rows.Err(); err != nil {
		return nil, fmt.Errorf("rows: %w", err)
	}
	return out, nil
}

func nullable(s string) *string {
	if s == "" {
		return nil
	}
	return &s
}

func insert(ctx context.Context, tx pgx.Tx, ns []domain.Notification) error {
	for _, n := range ns {
		var headers []byte
		if len(n.Headers) > 0 {
			var err error
			if headers, err = json.Marshal(n.Headers); err != nil {
				return fmt.Errorf("encode headers: %w", err)
			}
		}
		_, err := tx.Exec(ctx, `INSERT INTO notification (id, idempotency_key, event_id, template_key, template_version,
			locale, channel, category, market, recipient_id, address, time_zone, subject, body_html, body_text, headers,
			status, attempts, next_attempt_at, created_at, updated_at)
			VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,$13,$14,$15,$16,$17,0,$18,$19,$19)
			ON CONFLICT (idempotency_key) DO NOTHING`,
			n.ID, n.IdempotencyKey, nullable(n.EventID), n.TemplateKey, n.TemplateVersion, n.Locale, string(n.Channel),
			string(n.Category), n.Market, nullable(n.RecipientID), n.Address, nullable(n.TimeZone), n.Subject,
			n.BodyHTML, n.BodyText, headers, string(domain.StatusQueued), n.NextAttemptAt, n.CreatedAt)
		if err != nil {
			return fmt.Errorf("insert notification: %w", err)
		}
	}
	return nil
}

func (s *notificationStoreImpl) Enqueue(ctx context.Context, ns []domain.Notification) ([]domain.Notification, error) {
	keys := make([]string, 0, len(ns))
	for _, n := range ns {
		keys = append(keys, n.IdempotencyKey)
	}
	var out []domain.Notification
	err := pgx.BeginFunc(ctx, s.pool, func(tx pgx.Tx) error {
		if err := insert(ctx, tx, ns); err != nil {
			return err
		}
		rows, err := tx.Query(ctx, `SELECT `+columns+` FROM notification WHERE idempotency_key = ANY($1) ORDER BY created_at, channel`, keys)
		if err != nil {
			return fmt.Errorf("reload: %w", err)
		}
		out, err = collect(rows)
		return err
	})
	if err != nil {
		return nil, fmt.Errorf("enqueue: %w", err)
	}
	return out, nil
}

func (s *notificationStoreImpl) EnqueueForEvent(ctx context.Context, consumer, eventID string, ns []domain.Notification) (bool, error) {
	first := false
	err := pgx.BeginFunc(ctx, s.pool, func(tx pgx.Tx) error {
		tag, err := tx.Exec(ctx, `INSERT INTO inbox (consumer, event_id) VALUES ($1, $2) ON CONFLICT DO NOTHING`, consumer, eventID)
		if err != nil {
			return fmt.Errorf("inbox: %w", err)
		}
		if tag.RowsAffected() == 0 {
			return nil
		}
		first = true
		return insert(ctx, tx, ns)
	})
	if err != nil {
		return false, fmt.Errorf("enqueue event: %w", err)
	}
	return first, nil
}

func (s *notificationStoreImpl) Claim(ctx context.Context, limit int, lease time.Duration) ([]domain.Notification, error) {
	rows, err := s.pool.Query(ctx, `WITH due AS (
			SELECT id FROM notification
			WHERE status = 'queued' AND next_attempt_at <= now()
			ORDER BY next_attempt_at
			LIMIT $1
			FOR UPDATE SKIP LOCKED)
		UPDATE notification n SET next_attempt_at = now() + make_interval(secs => $2), updated_at = now()
		FROM due WHERE n.id = due.id
		RETURNING `+cols("n"), limit, lease.Seconds())
	if err != nil {
		return nil, fmt.Errorf("claim: %w", err)
	}
	return collect(rows)
}

func (s *notificationStoreImpl) exec(ctx context.Context, sql string, args ...any) error {
	tag, err := s.pool.Exec(ctx, sql, args...)
	if err != nil {
		return fmt.Errorf("update notification: %w", err)
	}
	if tag.RowsAffected() == 0 {
		return domain.ErrNotFound
	}
	return nil
}

// MarkSent records the provider receipt. A delivery webhook that raced ahead of this update (stored in
// delivery_receipt) wins, so the status never moves backwards.
func (s *notificationStoreImpl) MarkSent(ctx context.Context, id string, r domain.Receipt) error {
	return s.exec(ctx, `UPDATE notification n SET
			status = (SELECT s FROM (
				SELECT 'sent' AS s
				UNION ALL SELECT n.status
				UNION ALL SELECT dr.status FROM delivery_receipt dr
					WHERE dr.provider = $2 AND dr.provider_message_id = $3 AND $3 <> '') x
				ORDER BY status_rank(s) DESC LIMIT 1),
			provider = $2, provider_message_id = $3, sent_at = $4, updated_at = now(), last_error = NULL
		WHERE id = $1`, id, r.Provider, r.ProviderMessageID, r.AcceptedAt)
}

func (s *notificationStoreImpl) Reschedule(ctx context.Context, id string, at time.Time, reason string) error {
	return s.exec(ctx, `UPDATE notification SET next_attempt_at = $2, status_detail = $3, updated_at = now()
		WHERE id = $1 AND status = 'queued'`, id, at, reason)
}

func (s *notificationStoreImpl) MarkRetry(ctx context.Context, id string, attempts int, next time.Time, lastError string) error {
	return s.exec(ctx, `UPDATE notification SET attempts = $2, next_attempt_at = $3, last_error = $4,
		status_detail = 'retry scheduled', updated_at = now() WHERE id = $1 AND status = 'queued'`, id, attempts, next, lastError)
}

func (s *notificationStoreImpl) MarkFinal(ctx context.Context, id string, status domain.Status, attempts int, reason string, deadLetter bool) error {
	err := pgx.BeginFunc(ctx, s.pool, func(tx pgx.Tx) error {
		tag, err := tx.Exec(ctx, `UPDATE notification SET status = $2, attempts = $3, status_detail = $4,
			last_error = CASE WHEN $2 = 'failed' THEN $4 ELSE last_error END, updated_at = now()
			WHERE id = $1`, id, string(status), attempts, reason)
		if err != nil {
			return fmt.Errorf("update: %w", err)
		}
		if tag.RowsAffected() == 0 {
			return domain.ErrNotFound
		}
		if deadLetter {
			if _, err := tx.Exec(ctx, `INSERT INTO dead_letter (notification_id, reason) VALUES ($1, $2)`, id, reason); err != nil {
				return fmt.Errorf("dead letter: %w", err)
			}
		}
		return nil
	})
	if err != nil {
		return fmt.Errorf("mark final: %w", err)
	}
	return nil
}

func (s *notificationStoreImpl) Get(ctx context.Context, id string) (domain.Notification, error) {
	if _, err := uuid.Parse(id); err != nil {
		return domain.Notification{}, domain.ErrNotFound
	}
	return scan(s.pool.QueryRow(ctx, `SELECT `+columns+` FROM notification WHERE id = $1`, id))
}

func (s *notificationStoreImpl) List(ctx context.Context, f domain.ListFilter) ([]domain.Notification, error) {
	sql := `SELECT ` + columns + ` FROM notification WHERE true`
	args := []any{}
	add := func(cond string, v any) {
		args = append(args, v)
		sql += " AND " + fmt.Sprintf(cond, "$"+strconv.Itoa(len(args)))
	}
	if f.Recipient != "" {
		args = append(args, f.Recipient, domain.NormalizeEmail(f.Recipient), domain.NormalizePhone(f.Recipient))
		n := len(args)
		sql += fmt.Sprintf(" AND (recipient_id = $%d OR address = $%d OR address = $%d)", n-2, n-1, n)
	}
	if f.Channel != "" {
		add("channel = %s", string(f.Channel))
	}
	if f.Status != "" {
		add("status = %s", string(f.Status))
	}
	if !f.Before.IsZero() {
		add("created_at < %s", f.Before)
	}
	args = append(args, f.Limit)
	sql += " ORDER BY created_at DESC LIMIT $" + strconv.Itoa(len(args))
	rows, err := s.pool.Query(ctx, sql, args...)
	if err != nil {
		return nil, fmt.Errorf("list: %w", err)
	}
	return collect(rows)
}

func (s *notificationStoreImpl) ApplyDeliveryEvent(ctx context.Context, e domain.DeliveryEvent) (bool, error) {
	var nid *uuid.UUID
	if id, err := uuid.Parse(e.NotificationID); err == nil {
		nid = &id
	}
	updated := false
	err := pgx.BeginFunc(ctx, s.pool, func(tx pgx.Tx) error {
		var target *uuid.UUID
		err := tx.QueryRow(ctx, `SELECT id FROM notification
			WHERE ($1::uuid IS NOT NULL AND id = $1) OR ($3 <> '' AND provider = $2 AND provider_message_id = $3)
			LIMIT 1`, nid, e.Provider, e.ProviderMessageID).Scan(&target)
		if err != nil && !errors.Is(err, pgx.ErrNoRows) {
			return fmt.Errorf("find: %w", err)
		}
		if _, err := tx.Exec(ctx, `INSERT INTO delivery_receipt (notification_id, provider, provider_message_id, status, detail, occurred_at)
			VALUES ($1, $2, $3, $4, $5, $6)`, target, e.Provider, nullable(e.ProviderMessageID), string(e.Status), nullable(e.Detail), e.OccurredAt); err != nil {
			return fmt.Errorf("receipt: %w", err)
		}
		if target == nil {
			return nil
		}
		tag, err := tx.Exec(ctx, `UPDATE notification SET status = $2, status_detail = $3, updated_at = now()
			WHERE id = $1 AND status_rank(status) < status_rank($2)`, *target, string(e.Status), nullable(e.Detail))
		if err != nil {
			return fmt.Errorf("status: %w", err)
		}
		updated = tag.RowsAffected() == 1
		return nil
	})
	if err != nil {
		return false, fmt.Errorf("apply receipt: %w", err)
	}
	return updated, nil
}

func (s *notificationStoreImpl) DeadLetters(ctx context.Context, limit int) ([]domain.DeadLetter, error) {
	rows, err := s.pool.Query(ctx, `SELECT id, notification_id, reason, created_at, replayed_at FROM dead_letter
		ORDER BY created_at DESC LIMIT $1`, limit)
	if err != nil {
		return nil, fmt.Errorf("dead letters: %w", err)
	}
	defer rows.Close()
	out := []domain.DeadLetter{}
	for rows.Next() {
		var d domain.DeadLetter
		var id, nid uuid.UUID
		if err := rows.Scan(&id, &nid, &d.Reason, &d.CreatedAt, &d.ReplayedAt); err != nil {
			return nil, fmt.Errorf("scan dead letter: %w", err)
		}
		d.ID, d.NotificationID = id.String(), nid.String()
		out = append(out, d)
	}
	if err := rows.Err(); err != nil {
		return nil, fmt.Errorf("rows: %w", err)
	}
	return out, nil
}

func (s *notificationStoreImpl) Replay(ctx context.Context, deadLetterID string) (domain.Notification, error) {
	if _, err := uuid.Parse(deadLetterID); err != nil {
		return domain.Notification{}, domain.ErrNotFound
	}
	var n domain.Notification
	err := pgx.BeginFunc(ctx, s.pool, func(tx pgx.Tx) error {
		var nid uuid.UUID
		err := tx.QueryRow(ctx, `UPDATE dead_letter SET replayed_at = now() WHERE id = $1 AND replayed_at IS NULL
			RETURNING notification_id`, deadLetterID).Scan(&nid)
		if errors.Is(err, pgx.ErrNoRows) {
			return domain.ErrNotFound
		}
		if err != nil {
			return fmt.Errorf("dead letter: %w", err)
		}
		n, err = scan(tx.QueryRow(ctx, `UPDATE notification n SET status = 'queued', attempts = 0, next_attempt_at = now(),
			last_error = NULL, status_detail = 'replayed from dead letter', updated_at = now()
			WHERE n.id = $1 RETURNING `+cols("n"), nid))
		return err
	})
	if err != nil {
		return domain.Notification{}, fmt.Errorf("replay: %w", err)
	}
	return n, nil
}

func (s *notificationStoreImpl) Ping(ctx context.Context) error {
	if err := s.pool.Ping(ctx); err != nil {
		return fmt.Errorf("ping: %w", err)
	}
	return nil
}
