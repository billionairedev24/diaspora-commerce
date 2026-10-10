// Semgrep rule fixtures for tools/security/semgrep.yml.
package fixtures

import (
	"context"
	"crypto/tls"
	"database/sql"
	"fmt"
	"net/http"
)

const cartColumns = "id, owner, updated_at"

func clients() *http.Client {
	// ruleid: at.go.insecure-skip-verify
	cfg := &tls.Config{MinVersion: tls.VersionTLS12, InsecureSkipVerify: true}
	// ruleid: at.go.insecure-skip-verify
	cfg.InsecureSkipVerify = true
	// ok: at.go.insecure-skip-verify
	_ = &tls.Config{MinVersion: tls.VersionTLS12}
	return &http.Client{Transport: &http.Transport{TLSClientConfig: cfg}}
}

func Queries(ctx context.Context, db *sql.DB, id, col string) {
	// ruleid: at.go.sql-injection
	_, _ = db.QueryContext(ctx, fmt.Sprintf("SELECT * FROM carts WHERE id = '%s'", id))
	// ruleid: at.go.sql-injection
	_, _ = db.Exec("DELETE FROM carts WHERE id = " + id)
	// ok: at.go.sql-string-building
	_, _ = db.QueryContext(ctx, "SELECT * FROM carts WHERE id = $1", id)
	// ok: at.go.sql-string-building
	_, _ = db.QueryContext(ctx, "SELECT "+cartColumns+" FROM carts WHERE id = $1", id)
	// ok: at.go.sql-injection
	_, _ = db.QueryContext(ctx, "SELECT "+cartColumns+" FROM carts WHERE id = $1", id)
	_ = col
}
