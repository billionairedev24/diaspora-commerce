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

type alertStoreImpl struct{ pool *pgxpool.Pool }

var _ port.AlertPreferenceStore = (*alertStoreImpl)(nil)

// NewAlertPreferenceStore returns the Postgres AlertPreferenceStore.
func NewAlertPreferenceStore(pool *pgxpool.Pool) port.AlertPreferenceStore {
	return &alertStoreImpl{pool: pool}
}

func (s *alertStoreImpl) AlertPreferences(ctx context.Context, key string) ([]domain.AlertPreference, domain.PersonalQuietHours, error) {
	rows, err := s.pool.Query(ctx, `SELECT topic, channel, enabled FROM alert_preference WHERE recipient_key = $1
		ORDER BY topic, channel`, key)
	if err != nil {
		return nil, domain.PersonalQuietHours{}, fmt.Errorf("query alert preferences: %w", err)
	}
	defer rows.Close()
	out := []domain.AlertPreference{}
	for rows.Next() {
		var p domain.AlertPreference
		var topic, channel string
		if err := rows.Scan(&topic, &channel, &p.Enabled); err != nil {
			return nil, domain.PersonalQuietHours{}, fmt.Errorf("scan alert preference: %w", err)
		}
		p.Topic, p.Channel = domain.AlertTopic(topic), domain.Channel(channel)
		out = append(out, p)
	}
	if err := rows.Err(); err != nil {
		return nil, domain.PersonalQuietHours{}, fmt.Errorf("rows: %w", err)
	}
	quiet, err := quietHours(ctx, s.pool, key)
	if err != nil {
		return nil, domain.PersonalQuietHours{}, err
	}
	return out, quiet, nil
}

func quietHours(ctx context.Context, q interface {
	QueryRow(ctx context.Context, sql string, args ...any) pgx.Row
}, key string) (domain.PersonalQuietHours, error) {
	var out domain.PersonalQuietHours
	err := q.QueryRow(ctx, `SELECT enabled, start_minute, end_minute FROM personal_quiet_hours WHERE recipient_key = $1`, key).
		Scan(&out.Enabled, &out.StartMinute, &out.EndMinute)
	if errors.Is(err, pgx.ErrNoRows) {
		return domain.DefaultPersonalQuietHours, nil
	}
	if err != nil {
		return domain.PersonalQuietHours{}, fmt.Errorf("quiet hours: %w", err)
	}
	return out, nil
}

func (s *alertStoreImpl) SetAlertPreferences(ctx context.Context, key string, prefs []domain.AlertPreference, quiet *domain.PersonalQuietHours) error {
	err := pgx.BeginFunc(ctx, s.pool, func(tx pgx.Tx) error {
		for _, p := range prefs {
			if _, err := tx.Exec(ctx, `INSERT INTO alert_preference (recipient_key, topic, channel, enabled) VALUES ($1, $2, $3, $4)
				ON CONFLICT (recipient_key, topic, channel) DO UPDATE SET enabled = EXCLUDED.enabled, updated_at = now()`,
				key, string(p.Topic), string(p.Channel), p.Enabled); err != nil {
				return fmt.Errorf("upsert alert preference: %w", err)
			}
		}
		if quiet != nil {
			if _, err := tx.Exec(ctx, `INSERT INTO personal_quiet_hours (recipient_key, enabled, start_minute, end_minute)
				VALUES ($1, $2, $3, $4) ON CONFLICT (recipient_key) DO UPDATE SET enabled = EXCLUDED.enabled,
				start_minute = EXCLUDED.start_minute, end_minute = EXCLUDED.end_minute, updated_at = now()`,
				key, quiet.Enabled, quiet.StartMinute, quiet.EndMinute); err != nil {
				return fmt.Errorf("upsert quiet hours: %w", err)
			}
		}
		return nil
	})
	if err != nil {
		return fmt.Errorf("set alert preferences: %w", err)
	}
	return nil
}
