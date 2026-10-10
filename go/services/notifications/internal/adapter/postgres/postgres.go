// Package postgres implements the notification, template and consent ports on PostgreSQL (pgx/v5), with
// embedded SQL migrations applied at startup.
package postgres

import (
	"context"
	"embed"
	"fmt"
	"io/fs"
	"sort"
	"strings"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
)

//go:embed migrations/*.sql
var migrations embed.FS

// Open connects a pool to dsn and verifies connectivity.
func Open(ctx context.Context, dsn string) (*pgxpool.Pool, error) {
	pool, err := pgxpool.New(ctx, dsn)
	if err != nil {
		return nil, fmt.Errorf("postgres pool: %w", err)
	}
	if err := pool.Ping(ctx); err != nil {
		pool.Close()
		return nil, fmt.Errorf("postgres ping: %w", err)
	}
	return pool, nil
}

// migrationLock is the advisory lock key serialising migrations across replicas.
const migrationLock = 820_8001

// Migrate applies embedded migrations in file-name order, each in its own transaction, recording them in
// schema_migrations. Concurrent replicas serialise on an advisory lock.
func Migrate(ctx context.Context, pool *pgxpool.Pool) error {
	conn, err := pool.Acquire(ctx)
	if err != nil {
		return fmt.Errorf("migrate: acquire: %w", err)
	}
	defer conn.Release()
	if _, err := conn.Exec(ctx, "SELECT pg_advisory_lock($1)", migrationLock); err != nil {
		return fmt.Errorf("migrate: lock: %w", err)
	}
	defer func() { _, _ = conn.Exec(context.WithoutCancel(ctx), "SELECT pg_advisory_unlock($1)", migrationLock) }()
	if _, err := conn.Exec(ctx, `CREATE TABLE IF NOT EXISTS schema_migrations (
		version text PRIMARY KEY, applied_at timestamptz NOT NULL DEFAULT now())`); err != nil {
		return fmt.Errorf("migrate: bookkeeping table: %w", err)
	}
	files, err := fs.Glob(migrations, "migrations/*.sql")
	if err != nil {
		return fmt.Errorf("migrate: list: %w", err)
	}
	sort.Strings(files)
	for _, f := range files {
		version := strings.TrimSuffix(strings.TrimPrefix(f, "migrations/"), ".sql")
		var exists bool
		if err := conn.QueryRow(ctx, "SELECT EXISTS (SELECT 1 FROM schema_migrations WHERE version = $1)", version).Scan(&exists); err != nil {
			return fmt.Errorf("migrate: check %s: %w", version, err)
		}
		if exists {
			continue
		}
		sql, err := migrations.ReadFile(f)
		if err != nil {
			return fmt.Errorf("migrate: read %s: %w", f, err)
		}
		if err := pgx.BeginFunc(ctx, conn, func(tx pgx.Tx) error {
			if _, err := tx.Exec(ctx, string(sql)); err != nil {
				return fmt.Errorf("apply: %w", err)
			}
			if _, err := tx.Exec(ctx, "INSERT INTO schema_migrations (version) VALUES ($1)", version); err != nil {
				return fmt.Errorf("record: %w", err)
			}
			return nil
		}); err != nil {
			return fmt.Errorf("migrate %s: %w", version, err)
		}
	}
	return nil
}
