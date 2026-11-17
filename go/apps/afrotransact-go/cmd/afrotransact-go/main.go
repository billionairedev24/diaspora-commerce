// Command afrotransact-go is the single Go deployable of release 1 (ADR 0011): every Go service of the platform runs as a
// module of this process, mounted on one listener. Each service still builds and runs on its own from cmd/<service>.
package main

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"strings"
	"syscall"
	"time"

	sdktrace "go.opentelemetry.io/otel/sdk/trace"

	"github.com/billionairedev24/diaspora-commerce/go/apps/afrotransact-go/internal/host"
	"github.com/billionairedev24/diaspora-commerce/go/apps/afrotransact-go/internal/inproc"
	"github.com/billionairedev24/diaspora-commerce/go/apps/afrotransact-go/internal/registry"
	"github.com/billionairedev24/diaspora-commerce/go/pkg/logging"
	"github.com/billionairedev24/diaspora-commerce/go/pkg/module"
	"github.com/billionairedev24/diaspora-commerce/go/pkg/telemetry"
)

const (
	name        = "afrotransact-go"
	defaultPort = "8250"
)

func main() {
	if err := run(); err != nil {
		slog.Error(name+" exited", "error", err)
		os.Exit(1)
	}
}

func run() error {
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	env := strings.ToLower(strings.TrimSpace(os.Getenv("APP_ENV")))
	if env == "" {
		env = "local"
	}
	// One logger for the process; every module adds its own module attribute (host.New).
	log := logging.New(name, env)
	slog.SetDefault(log)

	// In-process calls: installed before any module builds its HTTP clients, which read http.DefaultTransport.
	transport := inproc.New(http.DefaultTransport)
	http.DefaultTransport = transport

	mgmtAddr := strings.TrimSpace(os.Getenv("MANAGEMENT_ADDR"))
	h, err := host.New(host.Config{Env: env, Getenv: os.LookupEnv, Logger: log, Transport: transport,
		MetricsPublic: mgmtAddr == "" && !sharedEnv(env)}, registry.Specs())
	if err != nil {
		return fmt.Errorf("build host: %w", err)
	}
	for _, m := range h.Modules() {
		log.Info("module", "name", m.Name, "enabled", m.Enabled, "mode", m.Mode, "mount", string(m.Mount))
	}

	// One tracer provider for the process; the module span processor tags spans with the module that ran them.
	tel := h.Telemetry()
	shutdownTracing, err := telemetry.SetupWith(ctx, name, env, tel.OTLPEndpoint, tel.SampleRatio,
		sdktrace.WithSpanProcessor(module.SpanProcessor()))
	if err != nil {
		log.Warn("tracing disabled", "error", err)
		shutdownTracing = func(context.Context) error { return nil }
	}
	defer func() { _ = shutdownTracing(context.WithoutCancel(ctx)) }()

	if err := h.Start(ctx); err != nil {
		_ = h.Close(context.WithoutCancel(ctx))
		return fmt.Errorf("start host: %w", err)
	}

	addr := ":" + firstNonEmpty(os.Getenv("AFROTRANSACT_GO_PORT"), os.Getenv("PORT"), defaultPort)
	srv := newServer(addr, h.Handler())
	errCh := make(chan error, 2)
	var mgmt *http.Server
	if mgmtAddr != "" {
		mgmt = newServer(mgmtAddr, h.Management())
		go func() { log.Info("management listening", "addr", mgmtAddr); errCh <- mgmt.ListenAndServe() }()
	} else if sharedEnv(env) {
		log.Warn("metrics not served: MANAGEMENT_ADDR is unset in " + env + " and /metrics is never public")
	}
	go func() { log.Info("listening", "addr", addr); errCh <- srv.ListenAndServe() }()

	var runErr error
	select {
	case err := <-errCh:
		if !errors.Is(err, http.ErrServerClosed) {
			runErr = fmt.Errorf("http server: %w", err)
		}
	case err := <-h.Fatal():
		runErr = err
	case <-ctx.Done():
	}
	shutCtx, cancel := context.WithTimeout(context.WithoutCancel(ctx), 20*time.Second)
	defer cancel()
	if mgmt != nil {
		_ = mgmt.Shutdown(shutCtx)
	}
	if err := srv.Shutdown(shutCtx); err != nil && runErr == nil {
		runErr = fmt.Errorf("shutdown: %w", err)
	}
	stop() // background work of every module sees the cancelled context before Close waits for it
	if err := h.Close(shutCtx); err != nil && runErr == nil {
		runErr = err
	}
	return runErr
}

func newServer(addr string, h http.Handler) *http.Server {
	return &http.Server{Addr: addr, Handler: h, ReadHeaderTimeout: 5 * time.Second, ReadTimeout: 15 * time.Second,
		WriteTimeout: 30 * time.Second, IdleTimeout: 120 * time.Second}
}

// sharedEnv is true for uat and prod, where /metrics is never served on the public listener.
func sharedEnv(env string) bool { return env == "uat" || env == "prod" || env == "production" }

func firstNonEmpty(vs ...string) string {
	for _, v := range vs {
		if strings.TrimSpace(v) != "" {
			return strings.TrimSpace(v)
		}
	}
	return ""
}
