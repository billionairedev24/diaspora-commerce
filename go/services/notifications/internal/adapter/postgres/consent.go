package postgres

import (
	"context"
	"errors"
	"fmt"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/port"
)

type consentStoreImpl struct{ pool *pgxpool.Pool }

var _ port.ConsentStore = (*consentStoreImpl)(nil)

// NewConsentStore returns the Postgres ConsentStore.
func NewConsentStore(pool *pgxpool.Pool) port.ConsentStore { return &consentStoreImpl{pool: pool} }

func (s *consentStoreImpl) RecordSmsConsent(ctx context.Context, c domain.SmsConsent) error {
	err := pgx.BeginFunc(ctx, s.pool, func(tx pgx.Tx) error {
		if _, err := tx.Exec(ctx, `INSERT INTO sms_consent (phone, recipient_id, opted_in, source, text_version, consented_at)
			VALUES ($1, $2, true, $3, $4, $5)
			ON CONFLICT (phone) DO UPDATE SET recipient_id = coalesce(EXCLUDED.recipient_id, sms_consent.recipient_id),
				opted_in = true, source = EXCLUDED.source, text_version = EXCLUDED.text_version,
				consented_at = EXCLUDED.consented_at, revoked_at = NULL, revoked_via = NULL, updated_at = now()`,
			c.Phone, nullable(c.RecipientID), c.Source, c.TextVersion, c.ConsentedAt); err != nil {
			return fmt.Errorf("upsert: %w", err)
		}
		return audit(ctx, tx, c.Phone, "opt_in", c.Source, c.TextVersion, c.ConsentedAt)
	})
	if err != nil {
		return fmt.Errorf("record sms consent: %w", err)
	}
	return nil
}

func audit(ctx context.Context, tx pgx.Tx, phone, action, source, textVersion string, at time.Time) error {
	if _, err := tx.Exec(ctx, `INSERT INTO sms_consent_event (phone, action, source, text_version, occurred_at)
		VALUES ($1, $2, $3, $4, $5)`, phone, action, source, nullable(textVersion), at); err != nil {
		return fmt.Errorf("audit: %w", err)
	}
	return nil
}

func (s *consentStoreImpl) OptOutSms(ctx context.Context, phone, via string, at time.Time) error {
	err := pgx.BeginFunc(ctx, s.pool, func(tx pgx.Tx) error {
		// A STOP from a number we never recorded still opts it out (and stays on record).
		if _, err := tx.Exec(ctx, `INSERT INTO sms_consent (phone, opted_in, source, text_version, consented_at, revoked_at, revoked_via)
			VALUES ($1, false, 'none', 'none', $2, $2, $3)
			ON CONFLICT (phone) DO UPDATE SET opted_in = false, revoked_at = $2, revoked_via = $3, updated_at = now()`,
			phone, at, via); err != nil {
			return fmt.Errorf("opt out: %w", err)
		}
		return audit(ctx, tx, phone, "opt_out", via, "", at)
	})
	if err != nil {
		return fmt.Errorf("opt out sms: %w", err)
	}
	return nil
}

func (s *consentStoreImpl) OptInSms(ctx context.Context, phone, source, textVersion string, at time.Time) error {
	return s.RecordSmsConsent(ctx, domain.SmsConsent{Phone: phone, Source: source, TextVersion: textVersion, ConsentedAt: at})
}

func (s *consentStoreImpl) SmsConsent(ctx context.Context, phone string) (*domain.SmsConsent, error) {
	var c domain.SmsConsent
	var rid, via *string
	err := s.pool.QueryRow(ctx, `SELECT phone, recipient_id, opted_in, source, text_version, consented_at, revoked_at, revoked_via
		FROM sms_consent WHERE phone = $1`, phone).Scan(&c.Phone, &rid, &c.OptedIn, &c.Source, &c.TextVersion,
		&c.ConsentedAt, &c.RevokedAt, &via)
	if errors.Is(err, pgx.ErrNoRows) {
		return nil, nil //nolint:nilnil // absence of a consent record is a valid answer
	}
	if err != nil {
		return nil, fmt.Errorf("sms consent: %w", err)
	}
	if rid != nil {
		c.RecipientID = *rid
	}
	if via != nil {
		c.RevokedVia = *via
	}
	return &c, nil
}

func (s *consentStoreImpl) Preferences(ctx context.Context, key string) ([]domain.Preference, error) {
	rows, err := s.pool.Query(ctx, `SELECT channel, category, enabled FROM preference WHERE recipient_key = $1
		ORDER BY channel, category`, key)
	if err != nil {
		return nil, fmt.Errorf("preferences: %w", err)
	}
	defer rows.Close()
	out := []domain.Preference{}
	for rows.Next() {
		var ch, cat string
		var p domain.Preference
		if err := rows.Scan(&ch, &cat, &p.Enabled); err != nil {
			return nil, fmt.Errorf("scan preference: %w", err)
		}
		p.Channel, p.Category = domain.Channel(ch), domain.Category(cat)
		out = append(out, p)
	}
	if err := rows.Err(); err != nil {
		return nil, fmt.Errorf("rows: %w", err)
	}
	return out, nil
}

func (s *consentStoreImpl) SetPreferences(ctx context.Context, key string, prefs []domain.Preference) error {
	err := pgx.BeginFunc(ctx, s.pool, func(tx pgx.Tx) error {
		for _, p := range prefs {
			if _, err := tx.Exec(ctx, `INSERT INTO preference (recipient_key, channel, category, enabled) VALUES ($1, $2, $3, $4)
				ON CONFLICT (recipient_key, channel, category) DO UPDATE SET enabled = EXCLUDED.enabled, updated_at = now()`,
				key, string(p.Channel), string(p.Category), p.Enabled); err != nil {
				return fmt.Errorf("upsert: %w", err)
			}
		}
		return nil
	})
	if err != nil {
		return fmt.Errorf("set preferences: %w", err)
	}
	return nil
}

func (s *consentStoreImpl) Suppress(ctx context.Context, address, reason string, at time.Time) error {
	// A complaint outranks an earlier bounce, so the stronger reason is kept.
	if _, err := s.pool.Exec(ctx, `INSERT INTO suppression (address, reason, created_at) VALUES ($1, $2, $3)
		ON CONFLICT (address) DO UPDATE SET reason = CASE WHEN EXCLUDED.reason = 'complained' THEN EXCLUDED.reason
			ELSE suppression.reason END`, address, reason, at); err != nil {
		return fmt.Errorf("suppress: %w", err)
	}
	return nil
}

func (s *consentStoreImpl) Unsuppress(ctx context.Context, address string) error {
	tag, err := s.pool.Exec(ctx, `DELETE FROM suppression WHERE address = $1`, address)
	if err != nil {
		return fmt.Errorf("unsuppress: %w", err)
	}
	if tag.RowsAffected() == 0 {
		return domain.ErrNotFound
	}
	return nil
}

func (s *consentStoreImpl) Suppressions(ctx context.Context, address string, limit int) ([]domain.Suppression, error) {
	if limit <= 0 || limit > 500 {
		limit = 100
	}
	rows, err := s.pool.Query(ctx, `SELECT address, reason, created_at FROM suppression
		WHERE ($1 = '' OR address = $1) ORDER BY created_at DESC LIMIT $2`, address, limit)
	if err != nil {
		return nil, fmt.Errorf("suppressions: %w", err)
	}
	defer rows.Close()
	out := []domain.Suppression{}
	for rows.Next() {
		var sp domain.Suppression
		if err := rows.Scan(&sp.Address, &sp.Reason, &sp.CreatedAt); err != nil {
			return nil, fmt.Errorf("scan suppression: %w", err)
		}
		out = append(out, sp)
	}
	if err := rows.Err(); err != nil {
		return nil, fmt.Errorf("rows: %w", err)
	}
	return out, nil
}

// ---- ConsentChecker ----

type consentCheckerImpl struct {
	pool        *pgxpool.Pool
	store       port.ConsentStore
	quiet       domain.QuietHours
	marketZones map[string]string
	now         func() time.Time
}

var _ port.ConsentChecker = (*consentCheckerImpl)(nil)

// NewConsentChecker returns the Postgres-backed ConsentChecker. marketZones maps a market to the IANA zone used
// when the recipient's own zone is unknown.
func NewConsentChecker(pool *pgxpool.Pool, quiet domain.QuietHours, marketZones map[string]string) port.ConsentChecker {
	return NewConsentCheckerWithClock(pool, quiet, marketZones, time.Now)
}

// NewConsentCheckerWithClock is NewConsentChecker with an injectable clock (tests).
func NewConsentCheckerWithClock(pool *pgxpool.Pool, quiet domain.QuietHours, marketZones map[string]string, now func() time.Time) port.ConsentChecker {
	return &consentCheckerImpl{pool: pool, store: NewConsentStore(pool), quiet: quiet, marketZones: marketZones, now: now}
}

func (c *consentCheckerImpl) location(tz, market string) *time.Location {
	for _, name := range []string{tz, c.marketZones[market]} {
		if name == "" {
			continue
		}
		if loc, err := time.LoadLocation(name); err == nil {
			return loc
		}
	}
	return time.UTC
}

func (c *consentCheckerImpl) Check(ctx context.Context, q port.ConsentQuery) (domain.Decision, error) {
	f := domain.ConsentFacts{Channel: q.Channel, Category: q.Category, Now: c.now(), Quiet: c.quiet,
		Location: c.location(q.TimeZone, q.Market)}
	if q.Category == domain.CategorySecurity {
		return domain.Decide(f), nil
	}
	err := c.pool.QueryRow(ctx, `SELECT reason FROM suppression WHERE address = $1`, q.Address).Scan(&f.SuppressWhy)
	switch {
	case err == nil:
		f.Suppressed = true
	case errors.Is(err, pgx.ErrNoRows):
	default:
		return domain.Decision{}, fmt.Errorf("suppression: %w", err)
	}
	if q.Channel == domain.ChannelSMS {
		if f.SmsConsent, err = c.store.SmsConsent(ctx, q.Address); err != nil {
			return domain.Decision{}, fmt.Errorf("consent: %w", err)
		}
	}
	var enabled bool
	err = c.pool.QueryRow(ctx, `SELECT enabled FROM preference WHERE recipient_key = $1 AND channel = $2 AND category = $3`,
		domain.Key(q.RecipientID, q.Address), string(q.Channel), string(q.Category)).Scan(&enabled)
	switch {
	case err == nil:
		f.Preference = &domain.Preference{Channel: q.Channel, Category: q.Category, Enabled: enabled}
	case errors.Is(err, pgx.ErrNoRows):
	default:
		return domain.Decision{}, fmt.Errorf("preference: %w", err)
	}
	return domain.Decide(f), nil
}
