package testkit

import (
	"os"
	"testing"
	"time"
)

// TestServeFakes serves the fake upstreams for manual runs (curl / storefront against a real BFF):
//
//	BFF_SERVE_FAKES=/tmp/fakes.url go test -run TestServeFakes -timeout 0 ./internal/testkit/
//
// It writes the base URL to the file and stops when the file is removed. Skipped unless the variable is set.
func TestServeFakes(t *testing.T) {
	file := os.Getenv("BFF_SERVE_FAKES")
	if file == "" {
		t.Skip("set BFF_SERVE_FAKES to serve the fake upstreams")
	}
	u := NewUpstreams(Options{Payments: true})
	defer u.Close()
	if err := os.WriteFile(file, []byte(u.URL()+"\n"+u.Secret+"\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	for {
		time.Sleep(500 * time.Millisecond)
		if _, err := os.Stat(file); err != nil {
			return
		}
	}
}
