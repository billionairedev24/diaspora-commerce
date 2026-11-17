package host

import (
	"context"
	"encoding/json"
	"net/http"
	"strings"
	"time"

	"github.com/prometheus/client_golang/prometheus/promhttp"

	"github.com/billionairedev24/diaspora-commerce/go/pkg/module"
)

// standard endpoints every module registers on its own mux. In the shared process the module's copies live under
// /modules/<name>/; the unprefixed ones are the host's (probes, metrics) or the edge module's (API reference pages).
var standardPaths = map[string]bool{
	"/healthz": true, "/readyz": true, "/metrics": true, "/openapi.yaml": true, "/docs": true, "/redoc": true,
}

var probePaths = map[string]bool{"/healthz": true, "/readyz": true, "/metrics": true}

// patternPath returns the path part of a ServeMux pattern ("GET /v1/x" gives "/v1/x").
func patternPath(p string) string {
	if i := strings.LastIndexByte(p, ' '); i >= 0 {
		return p[i+1:]
	}
	return p
}

func (h *hostImpl) Handler() http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { h.serve(w, r, h.cfg.MetricsPublic) })
}

func (h *hostImpl) Management() http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if !h.probe(w, r, "", true) {
			http.NotFound(w, r)
		}
	})
}

func (h *hostImpl) serve(w http.ResponseWriter, r *http.Request, metrics bool) {
	path := r.URL.Path
	if probePaths[path] {
		if h.probe(w, r, "", metrics) {
			return
		}
	}
	if rest, ok := strings.CutPrefix(path, "/modules/"); ok {
		name, sub, _ := strings.Cut(rest, "/")
		m := h.byName[name]
		if m == nil || m.mount == MountInternal {
			http.NotFound(w, r)
			return
		}
		if sub == "healthz" || sub == "readyz" || sub == "metrics" {
			if h.probe(w, r, name, metrics) {
				return
			}
		}
		http.StripPrefix("/modules/"+name, m.mod.Handler).ServeHTTP(w, r.WithContext(module.WithName(r.Context(), name)))
		return
	}
	if m := h.route(r); m != nil {
		m.mod.Handler.ServeHTTP(w, r.WithContext(module.WithName(r.Context(), m.spec.Name)))
		return
	}
	if h.edge != nil {
		h.edge.mod.Handler.ServeHTTP(w, r.WithContext(module.WithName(r.Context(), h.edge.spec.Name)))
		return
	}
	http.NotFound(w, r)
}

// route finds the flat-mounted module whose own mux claims the request. Duplicate claims were refused at startup.
func (h *hostImpl) route(r *http.Request) *running {
	for _, m := range h.mods {
		if m.mount != MountFlat || m.mod.Router == nil {
			continue
		}
		_, pattern := m.mod.Router.Handler(r)
		if pattern == "" {
			continue
		}
		if standardPaths[patternPath(pattern)] && (m != h.edge || probePaths[patternPath(pattern)]) {
			continue
		}
		return m
	}
	return nil
}

// probe serves /healthz, /readyz and /metrics for the whole process (module == "") or one module. It reports whether the
// request was one of those.
func (h *hostImpl) probe(w http.ResponseWriter, r *http.Request, name string, metrics bool) bool {
	if r.Method != http.MethodGet && r.Method != http.MethodHead {
		return false
	}
	which := strings.TrimPrefix(r.URL.Path, "/")
	if name != "" {
		which = strings.TrimPrefix(r.URL.Path, "/modules/"+name+"/")
	}
	switch which {
	case "healthz":
		h.healthz(w, name)
	case "readyz":
		h.readyz(w, r.Context(), name)
	case "metrics":
		if !metrics {
			return false
		}
		g := h.gatherer
		if name != "" {
			g = newMergedGatherer([]*running{h.byName[name]})
		}
		promhttp.HandlerFor(g, promhttp.HandlerOpts{ErrorHandling: promhttp.ContinueOnError}).ServeHTTP(w, r)
	default:
		return false
	}
	return true
}

func (h *hostImpl) names(name string) []*running {
	if name == "" {
		return h.mods
	}
	return []*running{h.byName[name]}
}

func (h *hostImpl) healthz(w http.ResponseWriter, name string) {
	mods := map[string]string{}
	for _, m := range h.names(name) {
		mods[m.spec.Name] = "up"
	}
	writeJSON(w, http.StatusOK, map[string]any{"status": "up", "modules": mods})
}

func (h *hostImpl) readyz(w http.ResponseWriter, parent context.Context, name string) {
	ctx, cancel := context.WithTimeout(parent, 2*time.Second)
	defer cancel()
	status, mods := http.StatusOK, map[string]string{}
	for _, m := range h.names(name) {
		mods[m.spec.Name] = "up"
		for _, c := range m.mod.Checks {
			if err := c.Check(ctx); err != nil {
				h.cfg.Logger.WarnContext(ctx, "readiness failed", "module", m.spec.Name, "check", c.Name(), "error", err)
				mods[m.spec.Name], status = c.Name()+" not ready", http.StatusServiceUnavailable
				break
			}
		}
	}
	state := "ready"
	if status != http.StatusOK {
		state = "not ready"
	}
	writeJSON(w, status, map[string]any{"status": state, "modules": mods})
}

func writeJSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(v)
}
