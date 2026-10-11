// Package testdocker guards Testcontainers integration tests: they skip on a machine without a reachable Docker
// daemon (a laptop with Docker stopped, or none installed) and fail in CI, where Docker is required.
//
//	func TestSomethingAgainstPostgres(t *testing.T) {
//		testdocker.Require(t)
//		...
//	}
//
// Tests always skip with `go test -short` or SKIP_DOCKER_TESTS=1 (also in CI: an explicit choice), and run without
// probing Docker with REQUIRE_DOCKER_TESTS=1.
package testdocker

import (
	"context"
	"fmt"
	"net"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"
)

// TB is the subset of testing.TB used here (lets the package test itself).
type TB interface {
	Helper()
	Skip(args ...any)
	Fatalf(format string, args ...any)
}

var _ TB = (testing.TB)(nil)

var (
	once      sync.Once
	reachable error
)

// Require skips the calling test with -short or SKIP_DOCKER_TESTS=1, and when Docker is not reachable; in CI (CI set)
// an unreachable Docker fails the test instead, so integration tests never silently stop running there.
func Require(t TB) {
	t.Helper()
	decide(t, testing.Short(), os.Getenv, func() error {
		once.Do(func() { reachable = Ping(context.Background(), os.Getenv, os.UserHomeDir) })
		return reachable
	})
}

func decide(t TB, short bool, env func(string) string, ping func() error) {
	t.Helper()
	if short || env("SKIP_DOCKER_TESTS") == "1" {
		t.Skip("integration test needs Docker (skipped by -short or SKIP_DOCKER_TESTS=1)")
		return
	}
	if env("REQUIRE_DOCKER_TESTS") == "1" {
		return
	}
	required := env("CI") != ""
	if err := ping(); err != nil {
		if required {
			t.Fatalf("integration test needs Docker, which CI requires: %v", err)
			return
		}
		t.Skip("integration test needs Docker, which is not reachable here (start Docker to run it): " + err.Error())
	}
}

// Ping reports whether a Docker daemon answers on DOCKER_HOST or one of the usual local sockets (Docker Engine,
// Docker Desktop, rootless Docker, Colima, Rancher Desktop, Podman).
func Ping(ctx context.Context, env func(string) string, home func() (string, error)) error {
	endpoints := candidates(env, home)
	var tried []string
	for _, e := range endpoints {
		if err := pingOne(ctx, e); err == nil {
			return nil
		} else {
			tried = append(tried, fmt.Sprintf("%s (%v)", e, err))
		}
	}
	if len(tried) == 0 {
		return fmt.Errorf("no Docker socket found and DOCKER_HOST is not set")
	}
	return fmt.Errorf("no Docker daemon answered: %s", strings.Join(tried, "; "))
}

func candidates(env func(string) string, home func() (string, error)) []string {
	if h := env("DOCKER_HOST"); h != "" {
		return []string{h}
	}
	socks := []string{"/var/run/docker.sock"}
	if x := env("XDG_RUNTIME_DIR"); x != "" {
		socks = append(socks, filepath.Join(x, "docker.sock"), filepath.Join(x, "podman", "podman.sock"))
	}
	if h, err := home(); err == nil && h != "" {
		socks = append(socks,
			filepath.Join(h, ".docker", "run", "docker.sock"),
			filepath.Join(h, ".docker", "desktop", "docker.sock"),
			filepath.Join(h, ".colima", "default", "docker.sock"),
			filepath.Join(h, ".colima", "docker.sock"),
			filepath.Join(h, ".rd", "docker.sock"),
		)
	}
	var out []string
	for _, s := range socks {
		if _, err := os.Stat(s); err == nil {
			out = append(out, "unix://"+s)
		}
	}
	return out
}

func pingOne(ctx context.Context, endpoint string) error {
	u, err := url.Parse(endpoint)
	if err != nil {
		return fmt.Errorf("parse %q: %w", endpoint, err)
	}
	transport := &http.Transport{}
	base := ""
	switch u.Scheme {
	case "unix":
		path := u.Path
		transport.DialContext = func(ctx context.Context, _, _ string) (net.Conn, error) {
			var d net.Dialer
			return d.DialContext(ctx, "unix", path)
		}
		base = "http://docker"
	case "tcp", "http":
		base = "http://" + u.Host
	case "https":
		base = "https://" + u.Host
	default:
		return fmt.Errorf("unsupported DOCKER_HOST scheme %q", u.Scheme)
	}
	ctx, cancel := context.WithTimeout(ctx, 2*time.Second)
	defer cancel()
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, base+"/_ping", nil)
	if err != nil {
		return fmt.Errorf("build ping: %w", err)
	}
	res, err := (&http.Client{Transport: transport}).Do(req)
	if err != nil {
		return fmt.Errorf("ping: %w", err)
	}
	defer func() { _ = res.Body.Close() }()
	if res.StatusCode != http.StatusOK {
		return fmt.Errorf("ping: HTTP %d", res.StatusCode)
	}
	return nil
}
