// Package module is the contract between a Go service and its hosts (ADR 0011, docs/engineering/release-1-architecture.md
// section 7). Every service's root package exports New(Deps) (Module, error). Two hosts call it: the service's own
// cmd/<svc>/main.go (through Main) and the afrotransact-go deployable, which mounts all launch modules in one process.
//
// New builds the service but never touches process-wide state: no slog.SetDefault, no tracer provider, no listener,
// no goroutine. Start launches background work (consumers, schedulers, syncs) and returns; Close releases what New opened.
package module

import (
	"context"
	"errors"
	"fmt"
	"io/fs"
	"log/slog"
	"net/http"
	"os"
	"strings"
	"sync"
	"time"

	"github.com/prometheus/client_golang/prometheus"

	"github.com/billionairedev24/diaspora-commerce/go/pkg/config"
	"github.com/billionairedev24/diaspora-commerce/go/pkg/httpserver"
	"github.com/billionairedev24/diaspora-commerce/go/pkg/logging"
)

// Deps is what a host gives a module. The zero value is the standalone process: environment variables, APP_ENV, a logger built
// from the module name.
type Deps struct {
	// Env is the resolved APP_ENV (local, dev, uat, prod). Empty means read APP_ENV.
	Env string
	// Lookup resolves configuration names (PORT, DB_URL ...). Nil means os.LookupEnv. afrotransact-go passes a lookup that
	// tries <MODULE>_<NAME> first and then <NAME>.
	Lookup func(string) (string, bool)
	// Logger carries the host's attributes (a module attribute inside afrotransact-go). Nil means logging.New(name, env).
	Logger *slog.Logger
}

// Telemetry is the module's tracing configuration, read from its config so the host sets the tracer provider once.
type Telemetry struct {
	OTLPEndpoint string
	SampleRatio  float64
	// BestEffort logs a tracing setup failure instead of stopping the process.
	BestEffort bool
}

// Module is a constructed service.
type Module struct {
	Name string
	// Handler serves the whole API with the platform middleware (recover, correlation id, metrics, tracing), the standard
	// endpoints (/healthz, /readyz, /openapi.yaml, /docs, /redoc) and the module's own routes.
	Handler http.Handler
	// Router answers which pattern a request matches (the module's ServeMux). afrotransact-go uses it to route and to
	// detect two modules claiming the same route.
	Router interface {
		Handler(r *http.Request) (http.Handler, string)
	}
	// Specs are the module's OpenAPI documents, used for the startup duplicate-route check.
	Specs [][]byte
	// Gatherer is the module's own Prometheus registry.
	Gatherer prometheus.Gatherer
	Checks   []httpserver.ReadinessCheck
	// Start launches background work and returns at once. It may be nil.
	Start func(ctx context.Context) error
	// Close releases connections and stops what Start launched. It may be nil.
	Close func(ctx context.Context) error
	// Fatal delivers an unrecoverable failure of background work. Nil when the module has none.
	Fatal <-chan error
	// Server is the standalone listener (Main). Nil when the host owns the listener.
	Server    httpserver.Server
	Logger    *slog.Logger
	Telemetry Telemetry
	Env       string
}

// lookup is the configured lookup or os.LookupEnv.
func (d Deps) lookup() func(string) (string, bool) {
	if d.Lookup != nil {
		return d.Lookup
	}
	return os.LookupEnv
}

// Loader returns the config loader for the module's embedded files, honouring the host's lookup and environment.
func (d Deps) Loader(fsys fs.FS) config.Loader {
	if d.Env == "" && d.Lookup == nil {
		return config.NewLoader(fsys)
	}
	env := d.Env
	if env == "" {
		v, _ := d.lookup()("APP_ENV")
		env = strings.ToLower(strings.TrimSpace(v))
	}
	if env == "" {
		env = config.EnvLocal
	}
	return config.NewLoaderFor(fsys, env, d.lookup())
}

// Log returns Deps.Logger or a logger tagged with the module name.
func (d Deps) Log(name, env string) *slog.Logger {
	if d.Logger != nil {
		return d.Logger
	}
	return logging.New(name, env)
}

// Getenv is the lookup as the os.Getenv shape.
func (d Deps) Getenv(name string) string {
	v, _ := d.lookup()(name)
	return v
}

// Closers collects cleanup functions in the order resources were opened and runs them in reverse.
type Closers struct {
	mu sync.Mutex
	fs []func(context.Context) error
}

// Add registers a cleanup.
func (c *Closers) Add(f func(context.Context) error) {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.fs = append(c.fs, f)
}

// AddFunc registers a cleanup that cannot fail.
func (c *Closers) AddFunc(f func()) { c.Add(func(context.Context) error { f(); return nil }) }

// Close runs every cleanup, newest first, once, and joins their errors.
func (c *Closers) Close(ctx context.Context) error {
	c.mu.Lock()
	fs := c.fs
	c.fs = nil
	c.mu.Unlock()
	var errs []error
	for i := len(fs) - 1; i >= 0; i-- {
		if err := fs[i](ctx); err != nil {
			errs = append(errs, err)
		}
	}
	return errors.Join(errs...)
}

// Background runs a module's long-lived work (consumers, schedulers, syncs). A function that returns an error while its
// context is still live is fatal: the host stops, as the standalone process did when its errgroup failed.
type Background struct {
	fns   []func(context.Context) error
	fatal chan error
	wg    sync.WaitGroup
	once  sync.Once
}

// Go registers a function; Start launches each one in its own goroutine.
func (b *Background) Go(f func(context.Context) error) { b.fns = append(b.fns, f) }

// GoQuiet registers a function that cannot fail.
func (b *Background) GoQuiet(f func(context.Context)) {
	b.fns = append(b.fns, func(ctx context.Context) error { f(ctx); return nil })
}

// Fatal delivers the first fatal error of a background function.
func (b *Background) Fatal() <-chan error {
	b.once.Do(func() { b.fatal = make(chan error, 8) })
	return b.fatal
}

// Start launches the registered functions and returns.
func (b *Background) Start(ctx context.Context) error {
	b.Fatal()
	for _, f := range b.fns {
		b.wg.Add(1)
		go func() {
			defer b.wg.Done()
			if err := f(ctx); err != nil && ctx.Err() == nil {
				select {
				case b.fatal <- err:
				default:
				}
			}
		}()
	}
	return nil
}

// Wait blocks until every function returned or ctx is done.
func (b *Background) Wait(ctx context.Context) error {
	done := make(chan struct{})
	go func() { b.wg.Wait(); close(done) }()
	select {
	case <-done:
		return nil
	case <-ctx.Done():
		return fmt.Errorf("background work still running: %w", ctx.Err())
	}
}

// Constructor is the shape every service exports as New.
type Constructor func(Deps) (Module, error)

// ErrNoServer is returned by Serve for a module without a standalone listener.
var ErrNoServer = errors.New("module: no standalone server")

// Serve runs the module as its own process: Start, listen until ctx is done, Close.
func Serve(ctx context.Context, m Module) error {
	if m.Server == nil {
		return ErrNoServer
	}
	runCtx, cancel := context.WithCancel(ctx)
	defer cancel()
	if m.Start != nil {
		if err := m.Start(runCtx); err != nil {
			return fmt.Errorf("start %s: %w", m.Name, err)
		}
	}
	fatalCh := make(chan error, 1)
	if m.Fatal != nil {
		go func() {
			select {
			case err := <-m.Fatal:
				fatalCh <- err
				cancel()
			case <-runCtx.Done():
			}
		}()
	}
	err := m.Server.Run(runCtx)
	cancel()
	if m.Close != nil {
		closeCtx, stop := context.WithTimeout(context.WithoutCancel(ctx), 20*time.Second)
		defer stop()
		if cerr := m.Close(closeCtx); cerr != nil && err == nil {
			err = fmt.Errorf("close %s: %w", m.Name, cerr)
		}
	}
	select {
	case fatal := <-fatalCh:
		if err == nil {
			err = fmt.Errorf("%s: %w", m.Name, fatal)
		}
	default:
	}
	return err
}

// FromServer describes a module served by an httpserver.Server: its handler, router, registry and checks.
func FromServer(name string, srv httpserver.Server, log *slog.Logger, env string, tel Telemetry, specs ...[]byte) Module {
	return Module{
		Name: name, Handler: httpserver.HandlerOf(srv), Router: srv.Mux(), Specs: specs,
		Gatherer: httpserver.RegistryOf(srv), Checks: httpserver.ChecksOf(srv), Server: srv, Logger: log, Env: env, Telemetry: tel,
	}
}
