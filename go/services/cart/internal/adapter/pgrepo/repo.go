// Package pgrepo stores signed-in customers' carts durably in PostgreSQL with version-checked updates.
package pgrepo

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

	"github.com/billionairedev24/diaspora-commerce/go/services/cart/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/cart/internal/port"
)

type repoImpl struct{ pool *pgxpool.Pool }

var _ port.CartRepository = (*repoImpl)(nil)

// New returns a Postgres CartRepository.
func New(pool *pgxpool.Pool) port.CartRepository { return &repoImpl{pool: pool} }

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

func (r *repoImpl) Load(ctx context.Context, owner domain.Owner) (domain.Cart, error) {
	var body []byte
	err := r.pool.QueryRow(ctx, `select body from cart where owner_key = $1`, owner.Key()).Scan(&body)
	if errors.Is(err, pgx.ErrNoRows) {
		return domain.Cart{Owner: owner, Lines: []domain.Line{}, Saved: []domain.Line{}}, nil
	}
	if err != nil {
		return domain.Cart{}, fmt.Errorf("load cart: %w", err)
	}
	var c domain.Cart
	if err := json.Unmarshal(body, &c); err != nil {
		return domain.Cart{}, fmt.Errorf("decode cart: %w", err)
	}
	c.Owner = owner
	return c, nil
}

func (r *repoImpl) Save(ctx context.Context, cart *domain.Cart) error {
	next := *cart
	next.Version++
	next.UpdatedAt = time.Now().UTC()
	body, err := json.Marshal(next)
	if err != nil {
		return fmt.Errorf("encode cart: %w", err)
	}
	var tag int64
	if cart.Version == 0 {
		ct, err := r.pool.Exec(ctx, `insert into cart (owner_key, body, version) values ($1, $2, 1)
			on conflict (owner_key) do nothing`, cart.Owner.Key(), body)
		if err != nil {
			return fmt.Errorf("insert cart: %w", err)
		}
		tag = ct.RowsAffected()
	} else {
		ct, err := r.pool.Exec(ctx, `update cart set body = $2, version = version + 1, updated_at = now()
			where owner_key = $1 and version = $3`, cart.Owner.Key(), body, cart.Version)
		if err != nil {
			return fmt.Errorf("update cart: %w", err)
		}
		tag = ct.RowsAffected()
	}
	if tag == 0 {
		return domain.ErrConflict
	}
	*cart = next
	return nil
}

func (r *repoImpl) Delete(ctx context.Context, owner domain.Owner) error {
	if _, err := r.pool.Exec(ctx, `delete from cart where owner_key = $1`, owner.Key()); err != nil {
		return fmt.Errorf("delete cart: %w", err)
	}
	return nil
}

func (r *repoImpl) Ping(ctx context.Context) error {
	if err := r.pool.Ping(ctx); err != nil {
		return fmt.Errorf("ping: %w", err)
	}
	return nil
}
