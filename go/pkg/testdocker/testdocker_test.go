package testdocker

import (
	"context"
	"errors"
	"fmt"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"testing"
)

type recorder struct {
	skipped bool
	failed  bool
	msg     string
}

func (r *recorder) Helper() {}

func (r *recorder) Skip(args ...any) { r.skipped = true; r.msg = fmt.Sprint(args...) }

func (r *recorder) Fatalf(format string, args ...any) {
	r.failed = true
	r.msg = fmt.Sprintf(format, args...)
}

func envOf(m map[string]string) func(string) string { return func(k string) string { return m[k] } }

func TestDecide(t *testing.T) {
	down := func() error { return errors.New("no daemon") }
	up := func() error { return nil }
	cases := []struct {
		name       string
		short      bool
		env        map[string]string
		ping       func() error
		skip, fail bool
	}{
		{name: "docker up runs", ping: up},
		{name: "no docker on a laptop skips", ping: down, skip: true},
		{name: "no docker in CI fails", env: map[string]string{"CI": "true"}, ping: down, fail: true},
		{name: "-short skips", short: true, ping: up, skip: true},
		{name: "SKIP_DOCKER_TESTS skips", env: map[string]string{"SKIP_DOCKER_TESTS": "1"}, ping: up, skip: true},
		{name: "-short skips in CI too", short: true, env: map[string]string{"CI": "1"}, ping: down, skip: true},
		{name: "REQUIRE_DOCKER_TESTS runs without probing", env: map[string]string{"REQUIRE_DOCKER_TESTS": "1"},
			ping: func() error { t.Fatal("probed"); return nil }},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			r := &recorder{}
			decide(r, c.short, envOf(c.env), c.ping)
			if r.skipped != c.skip || r.failed != c.fail {
				t.Fatalf("skipped=%v failed=%v (%s), want skip=%v fail=%v", r.skipped, r.failed, r.msg, c.skip, c.fail)
			}
		})
	}
}

func TestPingOverUnixSocket(t *testing.T) {
	dir, err := os.MkdirTemp("", "td")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = os.RemoveAll(dir) })
	sock := filepath.Join(dir, "docker.sock")
	ln, err := (&net.ListenConfig{}).Listen(context.Background(), "unix", sock)
	if err != nil {
		t.Skipf("unix sockets unavailable: %v", err)
	}
	srv := &http.Server{Handler: http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/_ping" {
			_, _ = w.Write([]byte("OK"))
			return
		}
		http.NotFound(w, r)
	})}
	go func() { _ = srv.Serve(ln) }()
	t.Cleanup(func() { _ = srv.Close() })

	env := envOf(map[string]string{"DOCKER_HOST": "unix://" + sock})
	if err := Ping(context.Background(), env, os.UserHomeDir); err != nil {
		t.Fatalf("ping: %v", err)
	}
	missing := envOf(map[string]string{"DOCKER_HOST": "unix://" + filepath.Join(dir, "missing.sock")})
	if err := Ping(context.Background(), missing, os.UserHomeDir); err == nil {
		t.Fatal("ping of a missing socket succeeded")
	}
	none := envOf(map[string]string{})
	noHome := func() (string, error) { return "", errors.New("no home") }
	if _, err := os.Stat("/var/run/docker.sock"); err != nil {
		if err := Ping(context.Background(), none, noHome); err == nil {
			t.Fatal("ping without any socket succeeded")
		}
	}
}
