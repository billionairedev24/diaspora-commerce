// Package pgstore implements carrier-gateway's PostgreSQL persistence: shipment_label, tracked_shipment,
// tracking_event, the transactional outbox (relayed with FOR UPDATE SKIP LOCKED), carrier pickups and pickup
// locations.
package pgstore

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io/fs"
	"sort"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/billionairedev24/diaspora-commerce/go/pkg/events"
	"github.com/billionairedev24/diaspora-commerce/go/services/carrier-gateway/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/carrier-gateway/internal/port"
)

// Store bundles every port this adapter implements.
type Store interface {
	port.LabelRepository
	port.TrackingStore
	port.Outbox
	port.PickupRepository
}

type storeImpl struct {
	pool *pgxpool.Pool
	now  func() time.Time
}

var _ Store = (*storeImpl)(nil)

// New returns the Postgres store.
func New(pool *pgxpool.Pool) Store { return &storeImpl{pool: pool, now: time.Now} }

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

func (s *storeImpl) Ping(ctx context.Context) error {
	if err := s.pool.Ping(ctx); err != nil {
		return fmt.Errorf("ping: %w", err)
	}
	return nil
}

func inTx(ctx context.Context, pool *pgxpool.Pool, fn func(tx pgx.Tx) error) error {
	if err := pgx.BeginFunc(ctx, pool, fn); err != nil {
		return fmt.Errorf("tx: %w", err)
	}
	return nil
}

// ---- labels ----

const labelColumns = `l.id::text, l.idempotency_key, l.request_hash, l.reference, l.origin_node, l.carrier, l.service,
	coalesce(l.tracking_number, ''), coalesce(l.carrier_shipment_id, ''), l.cost_minor, l.currency, l.label_format,
	coalesce(l.label_key, ''), l.status, coalesce(t.state, ''), l.created_at, l.voided_at`

func scanLabel(row pgx.Row) (domain.LabelRecord, error) {
	var r domain.LabelRecord
	var carrier, service, format, status, state string
	err := row.Scan(&r.ID, &r.IdempotencyKey, &r.RequestHash, &r.Reference, &r.OriginNode, &carrier, &service,
		&r.TrackingNumber, &r.CarrierShipmentID, &r.CostMinor, &r.Currency, &format, &r.LabelKey, &status, &state,
		&r.CreatedAt, &r.VoidedAt)
	if errors.Is(err, pgx.ErrNoRows) {
		return domain.LabelRecord{}, domain.ErrNotFound
	}
	if err != nil {
		return domain.LabelRecord{}, fmt.Errorf("scan label: %w", err)
	}
	r.Carrier, r.Service, r.Format = domain.CarrierCode(carrier), domain.ServiceCode(service), domain.LabelFormat(format)
	r.Status, r.TrackingState = domain.LabelStatus(status), domain.ShipmentState(state)
	return r, nil
}

func (s *storeImpl) Reserve(ctx context.Context, rec domain.LabelRecord) (domain.LabelRecord, bool, error) {
	tag, err := s.pool.Exec(ctx, `insert into shipment_label
		(id, idempotency_key, request_hash, reference, origin_node, carrier, service, status, created_at)
		values ($1, $2, $3, $4, $5, $6, $7, $8, $9) on conflict (idempotency_key) do nothing`,
		rec.ID, rec.IdempotencyKey, rec.RequestHash, rec.Reference, rec.OriginNode, string(rec.Carrier), string(rec.Service),
		string(domain.LabelPurchasing), rec.CreatedAt)
	if err != nil {
		return domain.LabelRecord{}, false, fmt.Errorf("reserve label: %w", err)
	}
	if tag.RowsAffected() == 1 {
		return rec, true, nil
	}
	existing, err := scanLabel(s.pool.QueryRow(ctx, `select `+labelColumns+` from shipment_label l
		left join tracked_shipment t on t.label_id = l.id where l.idempotency_key = $1`, rec.IdempotencyKey))
	if err != nil {
		return domain.LabelRecord{}, false, err
	}
	return existing, false, nil
}

func (s *storeImpl) Complete(ctx context.Context, rec domain.LabelRecord) error {
	return inTx(ctx, s.pool, func(tx pgx.Tx) error {
		tag, err := tx.Exec(ctx, `update shipment_label set tracking_number = $2, carrier_shipment_id = $3, cost_minor = $4,
			currency = $5, label_format = $6, label_key = $7, status = 'purchased', updated_at = now()
			where id = $1 and status = 'purchasing'`,
			rec.ID, rec.TrackingNumber, rec.CarrierShipmentID, rec.CostMinor, rec.Currency, string(rec.Format), rec.LabelKey)
		if err != nil {
			return fmt.Errorf("complete label: %w", err)
		}
		if tag.RowsAffected() != 1 {
			return fmt.Errorf("%w: label %s is not purchasing", domain.ErrConflict, rec.ID)
		}
		_, err = tx.Exec(ctx, `insert into tracked_shipment (carrier, tracking_number, label_id, reference, state, active)
			values ($1, $2, $3, $4, 'label_created', true)
			on conflict (carrier, tracking_number) do update set label_id = excluded.label_id,
				reference = excluded.reference, updated_at = now()`,
			string(rec.Carrier), rec.TrackingNumber, rec.ID, rec.Reference)
		if err != nil {
			return fmt.Errorf("track label: %w", err)
		}
		return nil
	})
}

func (s *storeImpl) Release(ctx context.Context, id string) error {
	if _, err := s.pool.Exec(ctx, `delete from shipment_label where id = $1 and status = 'purchasing'`, id); err != nil {
		return fmt.Errorf("release label: %w", err)
	}
	return nil
}

func (s *storeImpl) Get(ctx context.Context, id string) (domain.LabelRecord, error) {
	return scanLabel(s.pool.QueryRow(ctx, `select `+labelColumns+` from shipment_label l
		left join tracked_shipment t on t.label_id = l.id where l.id::text = $1`, id))
}

func (s *storeImpl) MarkVoided(ctx context.Context, id string, at time.Time) error {
	return inTx(ctx, s.pool, func(tx pgx.Tx) error {
		tag, err := tx.Exec(ctx, `update shipment_label set status = 'voided', voided_at = $2, updated_at = now()
			where id::text = $1 and status = 'purchased'`, id, at)
		if err != nil {
			return fmt.Errorf("void label: %w", err)
		}
		if tag.RowsAffected() != 1 {
			return fmt.Errorf("%w: label is not purchased", domain.ErrConflict)
		}
		if _, err := tx.Exec(ctx, `update tracked_shipment set active = false, updated_at = now() where label_id::text = $1`, id); err != nil {
			return fmt.Errorf("stop tracking: %w", err)
		}
		return nil
	})
}

// ---- tracking ----

type querier interface {
	QueryRow(ctx context.Context, sql string, args ...any) pgx.Row
}

const shipmentColumns = `carrier, tracking_number, coalesce(label_id::text, ''), reference, state,
	coalesce(last_event_at, 'epoch'::timestamptz), last_sequence, active`

func scanShipment(row pgx.Row) (domain.TrackedShipment, error) {
	var t domain.TrackedShipment
	var carrier, state string
	err := row.Scan(&carrier, &t.TrackingNumber, &t.LabelID, &t.Reference, &state, &t.LastEventAt, &t.LastSequence, &t.Active)
	if errors.Is(err, pgx.ErrNoRows) {
		return domain.TrackedShipment{}, domain.ErrNotFound
	}
	if err != nil {
		return domain.TrackedShipment{}, fmt.Errorf("scan shipment: %w", err)
	}
	if t.LastEventAt.Unix() == 0 {
		t.LastEventAt = time.Time{}
	}
	t.Carrier, t.State = domain.CarrierCode(carrier), domain.ShipmentState(state)
	return t, nil
}

func getShipment(ctx context.Context, q querier, carrier domain.CarrierCode, tn string, lock bool) (domain.TrackedShipment, error) {
	sql := `select ` + shipmentColumns + ` from tracked_shipment where carrier = $1 and tracking_number = $2`
	if lock {
		sql += ` for update`
	}
	return scanShipment(q.QueryRow(ctx, sql, string(carrier), tn))
}

func (s *storeImpl) GetShipment(ctx context.Context, carrier domain.CarrierCode, tn string) (domain.TrackedShipment, error) {
	return getShipment(ctx, s.pool, carrier, tn, false)
}

type trackingTx struct{ tx pgx.Tx }

var _ port.TrackingTx = (*trackingTx)(nil)

func (s *storeImpl) InTx(ctx context.Context, fn func(ctx context.Context, tx port.TrackingTx) error) error {
	if err := pgx.BeginFunc(ctx, s.pool, func(tx pgx.Tx) error { return fn(ctx, &trackingTx{tx: tx}) }); err != nil {
		return fmt.Errorf("tracking tx: %w", err)
	}
	return nil
}

func (t *trackingTx) InsertEvent(ctx context.Context, ev domain.TrackingEvent) (bool, error) {
	tag, err := t.tx.Exec(ctx, `insert into tracking_event
		(carrier, event_id, tracking_number, code, description, state, occurred_at, location, sequence)
		values ($1, $2, $3, $4, $5, $6, $7, $8, $9) on conflict (carrier, event_id) do nothing`,
		string(ev.Carrier), ev.EventID, ev.TrackingNumber, ev.Code, ev.Description, string(ev.State), ev.OccurredAt, ev.Location, ev.Sequence)
	if err != nil {
		return false, fmt.Errorf("insert event: %w", err)
	}
	return tag.RowsAffected() == 1, nil
}

func (t *trackingTx) LockShipment(ctx context.Context, carrier domain.CarrierCode, tn string) (domain.TrackedShipment, bool, error) {
	sh, err := getShipment(ctx, t.tx, carrier, tn, true)
	if errors.Is(err, domain.ErrNotFound) {
		return domain.TrackedShipment{}, false, nil
	}
	if err != nil {
		return domain.TrackedShipment{}, false, err
	}
	return sh, true, nil
}

func nullTime(t time.Time) *time.Time {
	if t.IsZero() {
		return nil
	}
	return &t
}

func (t *trackingTx) SaveShipment(ctx context.Context, sh domain.TrackedShipment) error {
	_, err := t.tx.Exec(ctx, `insert into tracked_shipment (carrier, tracking_number, reference, state, last_event_at, last_sequence, active)
		values ($1, $2, $3, $4, $5, $6, $7)
		on conflict (carrier, tracking_number) do update set state = excluded.state, last_event_at = excluded.last_event_at,
			last_sequence = excluded.last_sequence, active = excluded.active, updated_at = now()`,
		string(sh.Carrier), sh.TrackingNumber, sh.Reference, string(sh.State), nullTime(sh.LastEventAt), sh.LastSequence, sh.Active)
	if err != nil {
		return fmt.Errorf("save shipment: %w", err)
	}
	return nil
}

func (t *trackingTx) Enqueue(ctx context.Context, e events.Envelope) error {
	return enqueue(ctx, t.tx, e)
}

func enqueue(ctx context.Context, tx pgx.Tx, e events.Envelope) error {
	_, err := tx.Exec(ctx, `insert into outbox (id, aggregate, aggregate_id, event_type, payload, occurred_at)
		values ($1, $2, $3, $4, $5, $6)`, e.ID, e.Aggregate, e.AggregateID, e.Type, e.Payload, e.OccurredAt)
	if err != nil {
		return fmt.Errorf("enqueue outbox: %w", err)
	}
	return nil
}

func (s *storeImpl) Register(ctx context.Context, t domain.TrackedShipment) error {
	_, err := s.pool.Exec(ctx, `insert into tracked_shipment (carrier, tracking_number, reference, state, active)
		values ($1, $2, $3, $4, true) on conflict (carrier, tracking_number) do nothing`,
		string(t.Carrier), t.TrackingNumber, t.Reference, string(t.State))
	if err != nil {
		return fmt.Errorf("register tracking: %w", err)
	}
	return nil
}

func (s *storeImpl) Events(ctx context.Context, carrier domain.CarrierCode, tn string) ([]domain.TrackingEvent, error) {
	rows, err := s.pool.Query(ctx, `select event_id, code, description, state, occurred_at, location, sequence
		from tracking_event where carrier = $1 and tracking_number = $2 order by occurred_at, sequence`, string(carrier), tn)
	if err != nil {
		return nil, fmt.Errorf("query events: %w", err)
	}
	defer rows.Close()
	evs := []domain.TrackingEvent{}
	for rows.Next() {
		ev := domain.TrackingEvent{Carrier: carrier, TrackingNumber: tn}
		var state string
		if err := rows.Scan(&ev.EventID, &ev.Code, &ev.Description, &state, &ev.OccurredAt, &ev.Location, &ev.Sequence); err != nil {
			return nil, fmt.Errorf("scan event: %w", err)
		}
		ev.State, ev.OccurredAt = domain.ShipmentState(state), ev.OccurredAt.UTC()
		evs = append(evs, ev)
	}
	if err := rows.Err(); err != nil {
		return nil, fmt.Errorf("events rows: %w", err)
	}
	return evs, nil
}

func (s *storeImpl) ClaimDuePolls(ctx context.Context, carrier domain.CarrierCode, now time.Time, lease time.Duration, limit int) ([]port.PollTarget, error) {
	rows, err := s.pool.Query(ctx, `with due as (
			select carrier, tracking_number from tracked_shipment
			where carrier = $1 and active and next_poll_at <= $2
			order by next_poll_at limit $4 for update skip locked)
		update tracked_shipment t set next_poll_at = $2 + make_interval(secs => $3), updated_at = now()
		from due where t.carrier = due.carrier and t.tracking_number = due.tracking_number
		returning t.tracking_number, t.poll_failures, t.created_at`,
		string(carrier), now, lease.Seconds(), limit)
	if err != nil {
		return nil, fmt.Errorf("claim polls: %w", err)
	}
	defer rows.Close()
	var out []port.PollTarget
	for rows.Next() {
		pt := port.PollTarget{Carrier: carrier}
		if err := rows.Scan(&pt.TrackingNumber, &pt.Failures, &pt.CreatedAt); err != nil {
			return nil, fmt.Errorf("scan poll: %w", err)
		}
		out = append(out, pt)
	}
	if err := rows.Err(); err != nil {
		return nil, fmt.Errorf("poll rows: %w", err)
	}
	return out, nil
}

func (s *storeImpl) SchedulePoll(ctx context.Context, carrier domain.CarrierCode, tn string, next time.Time, failures int) error {
	_, err := s.pool.Exec(ctx, `update tracked_shipment set next_poll_at = $3, poll_failures = $4, updated_at = now()
		where carrier = $1 and tracking_number = $2`, string(carrier), tn, next, failures)
	if err != nil {
		return fmt.Errorf("schedule poll: %w", err)
	}
	return nil
}

// ---- outbox ----

func (s *storeImpl) Enqueue(ctx context.Context, e events.Envelope) error {
	return inTx(ctx, s.pool, func(tx pgx.Tx) error { return enqueue(ctx, tx, e) })
}

type outboxRow struct {
	seq      int64
	attempts int
	env      events.Envelope
}

func (s *storeImpl) Dispatch(ctx context.Context, limit int, publish func(ctx context.Context, e events.Envelope) error) (int, error) {
	published := 0
	err := pgx.BeginFunc(ctx, s.pool, func(tx pgx.Tx) error {
		rows, err := tx.Query(ctx, `select seq, id::text, aggregate, aggregate_id, event_type, payload, occurred_at, attempts
			from outbox where published_at is null and next_attempt_at <= now()
			order by seq limit $1 for update skip locked`, limit)
		if err != nil {
			return fmt.Errorf("select outbox: %w", err)
		}
		batch, err := pgx.CollectRows(rows, func(r pgx.CollectableRow) (outboxRow, error) {
			var o outboxRow
			err := r.Scan(&o.seq, &o.env.ID, &o.env.Aggregate, &o.env.AggregateID, &o.env.Type, &o.env.Payload, &o.env.OccurredAt, &o.attempts)
			if err != nil {
				return o, fmt.Errorf("scan: %w", err)
			}
			return o, nil
		})
		if err != nil {
			return fmt.Errorf("scan outbox: %w", err)
		}
		failed := map[string]bool{} // keep per-aggregate order: stop an aggregate at its first failure
		for _, o := range batch {
			if failed[o.env.AggregateID] {
				continue
			}
			if perr := publish(ctx, o.env); perr != nil {
				failed[o.env.AggregateID] = true
				backoff := min(time.Duration(1<<min(o.attempts, 8))*time.Second, 5*time.Minute)
				if _, err := tx.Exec(ctx, `update outbox set attempts = attempts + 1, last_error = $2,
					next_attempt_at = now() + make_interval(secs => $3) where seq = $1`, o.seq, perr.Error(), backoff.Seconds()); err != nil {
					return fmt.Errorf("record outbox failure: %w", err)
				}
				continue
			}
			if _, err := tx.Exec(ctx, `update outbox set published_at = now(), attempts = attempts + 1, last_error = null
				where seq = $1`, o.seq); err != nil {
				return fmt.Errorf("mark published: %w", err)
			}
			published++
		}
		return nil
	})
	if err != nil {
		return 0, fmt.Errorf("dispatch: %w", err)
	}
	return published, nil
}

// ---- pickups ----

func (s *storeImpl) SavePickup(ctx context.Context, p domain.Pickup, req domain.PickupRequest) error {
	body, err := json.Marshal(req)
	if err != nil {
		return fmt.Errorf("encode pickup: %w", err)
	}
	_, err = s.pool.Exec(ctx, `insert into carrier_pickup (id, carrier, confirmation, pickup_date, origin_node, request, created_at)
		values ($1, $2, $3, $4, $5, $6, $7)`, p.ID, string(p.Carrier), p.Confirmation, p.Date, p.OriginNode, body, p.CreatedAt)
	if err != nil {
		return fmt.Errorf("insert pickup: %w", err)
	}
	return nil
}

func (s *storeImpl) SaveLocation(ctx context.Context, l domain.PickupLocation) error {
	addr, err := json.Marshal(l.Address)
	if err != nil {
		return fmt.Errorf("encode address: %w", err)
	}
	hours, err := json.Marshal(l.Hours)
	if err != nil {
		return fmt.Errorf("encode hours: %w", err)
	}
	_, err = s.pool.Exec(ctx, `insert into pickup_location
		(id, kind, owner_org, name, address, postal_code, timezone, hours, ready_hours, active, updated_at)
		values ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11)
		on conflict (id) do update set kind = excluded.kind, owner_org = excluded.owner_org, name = excluded.name,
			address = excluded.address, postal_code = excluded.postal_code, timezone = excluded.timezone,
			hours = excluded.hours, ready_hours = excluded.ready_hours, active = excluded.active, updated_at = excluded.updated_at`,
		l.ID, string(l.Kind), l.OwnerOrg, l.Name, addr, l.Address.PostalCode, l.Timezone, hours, l.ReadyHours, l.Active, l.UpdatedAt)
	if err != nil {
		return fmt.Errorf("upsert location: %w", err)
	}
	return nil
}

const locationColumns = `id::text, kind, owner_org, name, address, timezone, hours, ready_hours, active, updated_at`

func scanLocation(row pgx.Row) (domain.PickupLocation, error) {
	var l domain.PickupLocation
	var kind string
	var addr, hours []byte
	err := row.Scan(&l.ID, &kind, &l.OwnerOrg, &l.Name, &addr, &l.Timezone, &hours, &l.ReadyHours, &l.Active, &l.UpdatedAt)
	if errors.Is(err, pgx.ErrNoRows) {
		return domain.PickupLocation{}, domain.ErrNotFound
	}
	if err != nil {
		return domain.PickupLocation{}, fmt.Errorf("scan location: %w", err)
	}
	l.Kind = domain.PickupLocationKind(kind)
	if err := json.Unmarshal(addr, &l.Address); err != nil {
		return domain.PickupLocation{}, fmt.Errorf("decode address: %w", err)
	}
	if err := json.Unmarshal(hours, &l.Hours); err != nil {
		return domain.PickupLocation{}, fmt.Errorf("decode hours: %w", err)
	}
	return l, nil
}

func (s *storeImpl) GetLocation(ctx context.Context, id string) (domain.PickupLocation, error) {
	return scanLocation(s.pool.QueryRow(ctx, `select `+locationColumns+` from pickup_location where id::text = $1`, id))
}

func (s *storeImpl) ListLocations(ctx context.Context, ownerOrg, postalPrefix string, activeOnly bool) ([]domain.PickupLocation, error) {
	rows, err := s.pool.Query(ctx, `select `+locationColumns+` from pickup_location
		where ($1 = '' or owner_org = $1) and ($2 = '' or postal_code like $2 || '%') and (not $3 or active)
		order by name limit 200`, ownerOrg, postalPrefix, activeOnly)
	if err != nil {
		return nil, fmt.Errorf("query locations: %w", err)
	}
	defer rows.Close()
	out := []domain.PickupLocation{}
	for rows.Next() {
		l, err := scanLocation(rows)
		if err != nil {
			return nil, err
		}
		out = append(out, l)
	}
	if err := rows.Err(); err != nil {
		return nil, fmt.Errorf("location rows: %w", err)
	}
	return out, nil
}
