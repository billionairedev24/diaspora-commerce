// Package host runs Go modules in one process (ADR 0011): it builds the enabled modules in order, wires their
// in-process calls, mounts them on one HTTP surface, merges their health and metrics and starts and stops them together.
package host

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"net/http"
	"os"
	"strings"
	"sync"

	"github.com/prometheus/client_golang/prometheus"

	"github.com/billionairedev24/diaspora-commerce/go/apps/afrotransact-go/internal/inproc"
	"github.com/billionairedev24/diaspora-commerce/go/apps/afrotransact-go/internal/modcfg"
	"github.com/billionairedev24/diaspora-commerce/go/pkg/module"
)

// Mount says where a module's own routes are reachable on the shared listener.
type Mount string

const (
	// MountFlat serves the module's routes at their existing paths (and under /modules/<name>/).
	MountFlat Mount = "flat"
	// MountPrefixed serves them only under /modules/<name>/. Used where the existing paths collide with another module
	// (cart and search with gateway-bff's facade of the same paths), so the module is reached by in-process calls and
	// by callers that use http://<host>/modules/<name> as its base URL.
	MountPrefixed Mount = "prefixed"
	// MountInternal serves nothing on the listener: in-process callers only.
	MountInternal Mount = "internal"
)

// Spec describes one Go module the host can run.
type Spec struct {
	Name string
	// Default is whether the module runs when AT_MODULES_<NAME>_ENABLED is unset.
	Default bool
	// Edge marks the module that owns the unprefixed standard pages (/openapi.yaml, /docs, /redoc) and the fallback for
	// requests no other module claims (gateway-bff).
	Edge bool
	// Mount is the default mount (flat when empty); AT_MODULES_<NAME>_MOUNT overrides it.
	Mount Mount
	// PeerVars are the configuration names other modules read the URL of this module from (SEARCH_URL ...). While the
	// module runs in-process they resolve to http://<name>.inproc.
	PeerVars []string
	// Schema is the module's schema in the shared Go database (AT_GO_DB_URL); empty for modules without PostgreSQL.
	Schema string
	New    module.Constructor
}

// Config configures New.
type Config struct {
	// Env is APP_ENV.
	Env string
	// Getenv resolves process environment (os.LookupEnv in main).
	Getenv modcfg.Lookup
	Logger *slog.Logger
	// MetricsPublic serves /metrics on the public handler (local and dev without a management listener).
	MetricsPublic bool
	// Transport is the in-process transport; New creates one when nil.
	Transport inproc.Transport
}

// Info is a module's state for diagnostics.
type Info struct {
	Name    string
	Enabled bool
	Mode    string
	Mount   Mount
}

// Host is the running set of modules.
type Host interface {
	// Modules lists every spec with its resolved switches, in build order.
	Modules() []Info
	// Handler is the public listener: flat routes, /modules/<name>/..., probes and (when allowed) metrics.
	Handler() http.Handler
	// Management is the private listener: probes and metrics only.
	Management() http.Handler
	// Transport reaches in-process modules; install it as http.DefaultTransport (main) or give it to clients (tests).
	Transport() http.RoundTripper
	// Telemetry is the tracing configuration of the first enabled module that has an endpoint.
	Telemetry() module.Telemetry
	// Gatherer merges every module's registry with module and service labels.
	Gatherer() prometheus.Gatherer
	// Start starts the modules in build order.
	Start(ctx context.Context) error
	// Fatal delivers a background failure of any module: the process should stop.
	Fatal() <-chan error
	// Close closes the modules in reverse build order.
	Close(ctx context.Context) error
}

type running struct {
	spec  Spec
	mod   module.Module
	mount Mount
}

type hostImpl struct {
	cfg       Config
	infos     []Info
	mods      []*running
	byName    map[string]*running
	edge      *running
	transport inproc.Transport
	gatherer  prometheus.Gatherer
	fatal     chan error
}

var _ Host = (*hostImpl)(nil)

// EnvName is the upper-case form used in AT_MODULES_<NAME>_*: carrier-gateway becomes CARRIER_GATEWAY.
func EnvName(module string) string { return strings.ToUpper(strings.ReplaceAll(module, "-", "_")) }

// New builds the enabled modules in the order of specs. A module that fails to build stops the host and names the module;
// two modules claiming the same flat route fail the build naming both.
func New(cfg Config, specs []Spec) (Host, error) {
	if cfg.Getenv == nil {
		cfg.Getenv = os.LookupEnv
	}
	if cfg.Logger == nil {
		cfg.Logger = slog.Default()
	}
	h := &hostImpl{cfg: cfg, byName: map[string]*running{}, fatal: make(chan error, 8)}
	h.transport = cfg.Transport
	if h.transport == nil {
		h.transport = inproc.New(nil)
	}

	enabled := map[string]bool{}
	modes := map[string]string{}
	mounts := map[string]Mount{}
	for _, s := range specs {
		// MODULE_<NAME>_ENABLED is the name the Helm chart (go/apps/afrotransact-go/deploy/helm) writes; AT_MODULES_ wins.
		en, err := parseBool(cfg.Getenv, "MODULE_"+EnvName(s.Name)+"_ENABLED", s.Default)
		if err == nil {
			en, err = parseBool(cfg.Getenv, "AT_MODULES_"+EnvName(s.Name)+"_ENABLED", en)
		}
		if err != nil {
			return nil, err
		}
		mode, err := parseChoice(cfg.Getenv, "AT_MODULES_"+EnvName(s.Name)+"_MODE", "in-process", "in-process", "http")
		if err != nil {
			return nil, err
		}
		mount, err := parseChoice(cfg.Getenv, "AT_MODULES_"+EnvName(s.Name)+"_MOUNT", string(orFlat(s.Mount)),
			string(MountFlat), string(MountPrefixed), string(MountInternal))
		if err != nil {
			return nil, err
		}
		enabled[s.Name], modes[s.Name], mounts[s.Name] = en, mode, Mount(mount)
		h.infos = append(h.infos, Info{Name: s.Name, Enabled: en, Mode: mode, Mount: Mount(mount)})
	}

	// Peer URLs that point at a module running in this process resolve in-process (unless the mode is http or the
	// operator set <CALLER>_<NAME> for one edge).
	inprocURLs := map[string]string{}
	for _, s := range specs {
		if enabled[s.Name] && modes[s.Name] == "in-process" {
			for _, v := range s.PeerVars {
				inprocURLs[v] = inproc.URL(s.Name)
			}
		}
	}
	sharedDB, _ := cfg.Getenv("AT_GO_DB_URL")

	for _, s := range specs {
		if !enabled[s.Name] {
			cfg.Logger.Info("module disabled", "module", s.Name)
			continue
		}
		deps := module.Deps{
			Env: cfg.Env,
			Lookup: modcfg.New(modcfg.Options{Module: s.Name, Base: cfg.Getenv, InProcess: inprocURLs,
				SharedDBURL: sharedDB, Schema: s.Schema}),
			Logger: cfg.Logger.With("module", s.Name),
		}
		m, err := s.New(deps)
		if err != nil {
			_ = h.Close(context.Background())
			return nil, fmt.Errorf("module %s: %w", s.Name, err)
		}
		r := &running{spec: s, mod: m, mount: mounts[s.Name]}
		h.mods = append(h.mods, r)
		h.byName[s.Name] = r
		if s.Edge {
			h.edge = r
		}
		h.transport.Register(s.Name, m.Handler)
		cfg.Logger.Info("module built", "module", s.Name, "mount", string(r.mount), "mode", modes[s.Name])
	}
	if err := h.checkRoutes(); err != nil {
		_ = h.Close(context.Background())
		return nil, err
	}
	h.gatherer = newMergedGatherer(h.mods)
	return h, nil
}

func orFlat(m Mount) Mount {
	if m == "" {
		return MountFlat
	}
	return m
}

func parseBool(get modcfg.Lookup, name string, def bool) (bool, error) {
	v, ok := get(name)
	if !ok || strings.TrimSpace(v) == "" {
		return def, nil
	}
	switch strings.ToLower(strings.TrimSpace(v)) {
	case "true", "1", "yes", "on":
		return true, nil
	case "false", "0", "no", "off":
		return false, nil
	}
	return false, fmt.Errorf("host: %s=%q is not a boolean", name, v)
}

func parseChoice(get modcfg.Lookup, name, def string, allowed ...string) (string, error) {
	v, ok := get(name)
	v = strings.ToLower(strings.TrimSpace(v))
	if !ok || v == "" {
		return def, nil
	}
	for _, a := range allowed {
		if v == a {
			return v, nil
		}
	}
	return "", fmt.Errorf("host: %s=%q must be one of %s", name, v, strings.Join(allowed, ", "))
}

func (h *hostImpl) Modules() []Info               { return append([]Info(nil), h.infos...) }
func (h *hostImpl) Transport() http.RoundTripper  { return h.transport }
func (h *hostImpl) Gatherer() prometheus.Gatherer { return h.gatherer }
func (h *hostImpl) Fatal() <-chan error           { return h.fatal }

func (h *hostImpl) Telemetry() module.Telemetry {
	for _, r := range h.mods {
		if r.mod.Telemetry.OTLPEndpoint != "" {
			return r.mod.Telemetry
		}
	}
	return module.Telemetry{BestEffort: true}
}

func (h *hostImpl) Start(ctx context.Context) error {
	for _, r := range h.mods {
		if r.mod.Start != nil {
			if err := r.mod.Start(ctx); err != nil {
				return fmt.Errorf("start module %s: %w", r.spec.Name, err)
			}
		}
		if r.mod.Fatal != nil {
			go func() {
				select {
				case err := <-r.mod.Fatal:
					select {
					case h.fatal <- fmt.Errorf("module %s: %w", r.spec.Name, err):
					default:
					}
				case <-ctx.Done():
				}
			}()
		}
	}
	return nil
}

func (h *hostImpl) Close(ctx context.Context) error {
	var (
		mu   sync.Mutex
		errs []error
	)
	for i := len(h.mods) - 1; i >= 0; i-- {
		r := h.mods[i]
		if r.mod.Close == nil {
			continue
		}
		if err := r.mod.Close(ctx); err != nil {
			mu.Lock()
			errs = append(errs, fmt.Errorf("close module %s: %w", r.spec.Name, err))
			mu.Unlock()
		}
	}
	return errors.Join(errs...)
}
