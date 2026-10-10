// Package httpserver is the shared HTTP runtime for Go services: health and readiness probes, Prometheus
// metrics, OpenTelemetry, correlation ids, panic recovery, graceful shutdown and API reference pages
// (/openapi.yaml, /docs with Scalar, /redoc with Redoc).
package httpserver

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"net/http"
	"time"

	"github.com/prometheus/client_golang/prometheus"
	"github.com/prometheus/client_golang/prometheus/collectors"
	"github.com/prometheus/client_golang/prometheus/promhttp"
	"go.opentelemetry.io/contrib/instrumentation/net/http/otelhttp"
)

// ReadinessCheck reports whether a dependency (database, Elasticsearch, Kafka…) is reachable.
type ReadinessCheck interface {
	Name() string
	Check(ctx context.Context) error
}

// Server runs the service's HTTP API until the context is cancelled.
type Server interface {
	// Mux exposes the router so adapters can register "METHOD /path" handlers.
	Mux() *http.ServeMux
	// Run serves until ctx is done, then shuts down gracefully.
	Run(ctx context.Context) error
}

// DefaultWriteTimeout bounds how long an ordinary response may take to write.
const DefaultWriteTimeout = 30 * time.Second

// Options configure a Server.
type Options struct {
	Name     string
	Addr     string
	OpenAPI  []byte
	DocsOn   bool
	Checks   []ReadinessCheck
	Logger   *slog.Logger
	Registry *prometheus.Registry
	// WriteTimeout bounds every response that is not a stream (default DefaultWriteTimeout).
	WriteTimeout time.Duration
	// StreamRoutes are mux patterns exactly as registered (e.g. "POST /v1/lola/chat") whose responses are
	// long-lived (Server-Sent Events, chunked streams). They are exempt from WriteTimeout. Handlers registered
	// elsewhere can opt in per route with Stream instead.
	StreamRoutes []string
	// StreamMaxDuration caps a stream's total write time instead of WriteTimeout (0 = no cap; the handler ends
	// the stream, or the client disconnects).
	StreamMaxDuration time.Duration
}

type serverImpl struct {
	opts Options
	mux  *http.ServeMux
}

var _ Server = (*serverImpl)(nil)

// New builds a Server with the platform's standard endpoints already registered.
func New(opts Options) Server {
	if opts.Registry == nil {
		opts.Registry = prometheus.NewRegistry()
		opts.Registry.MustRegister(collectors.NewGoCollector(), collectors.NewProcessCollector(collectors.ProcessCollectorOpts{}))
	}
	if opts.WriteTimeout <= 0 {
		opts.WriteTimeout = DefaultWriteTimeout
	}
	if opts.Logger == nil {
		opts.Logger = slog.Default()
	}
	s := &serverImpl{opts: opts, mux: http.NewServeMux()}
	s.mux.HandleFunc("GET /healthz", func(w http.ResponseWriter, _ *http.Request) { w.WriteHeader(http.StatusOK) })
	s.mux.HandleFunc("GET /readyz", s.ready)
	s.mux.Handle("GET /metrics", promhttp.HandlerFor(opts.Registry, promhttp.HandlerOpts{}))
	if len(opts.OpenAPI) > 0 {
		s.mux.HandleFunc("GET /openapi.yaml", func(w http.ResponseWriter, _ *http.Request) {
			w.Header().Set("Content-Type", "application/yaml")
			_, _ = w.Write(opts.OpenAPI)
		})
		if opts.DocsOn {
			for _, p := range []DocsPage{NewScalarPage(), NewRedocPage()} {
				s.mux.HandleFunc("GET "+p.Path(), docsHandler(p, opts.Name))
			}
		}
	}
	return s
}

func (s *serverImpl) Mux() *http.ServeMux { return s.mux }

func (s *serverImpl) ready(w http.ResponseWriter, r *http.Request) {
	ctx, cancel := context.WithTimeout(r.Context(), 2*time.Second)
	defer cancel()
	for _, c := range s.opts.Checks {
		if err := c.Check(ctx); err != nil {
			s.opts.Logger.WarnContext(ctx, "readiness failed", "check", c.Name(), "error", err)
			http.Error(w, c.Name()+" not ready", http.StatusServiceUnavailable)
			return
		}
	}
	w.WriteHeader(http.StatusOK)
}

// handler assembles the middleware chain around the mux and registers the HTTP metrics.
func (s *serverImpl) handler() http.Handler {
	reqs := prometheus.NewCounterVec(prometheus.CounterOpts{
		Name: "http_server_requests_total", Help: "HTTP requests by method, route and status.",
	}, []string{"method", "route", "status"})
	dur := prometheus.NewHistogramVec(prometheus.HistogramOpts{
		Name: "http_server_request_duration_seconds", Help: "HTTP request latency.",
		Buckets: []float64{.005, .01, .025, .05, .1, .15, .25, .5, 1, 2.5, 5},
	}, []string{"method", "route"})
	s.opts.Registry.MustRegister(reqs, dur)

	var h http.Handler = s.mux
	h = streamRoutes(s.mux, s.opts.StreamRoutes, s.opts.StreamMaxDuration, s.opts.Logger)(h)
	h = instrument(s.opts.Logger, reqs, dur)(h)
	h = recoverer(s.opts.Logger)(h)
	h = correlation(h)
	return otelhttp.NewHandler(h, s.opts.Name)
}

// httpServer is the net/http server with the platform's timeouts. Stream routes lift WriteTimeout per request.
func (s *serverImpl) httpServer(h http.Handler) *http.Server {
	return &http.Server{
		Addr:              s.opts.Addr,
		Handler:           h,
		ReadHeaderTimeout: 5 * time.Second,
		ReadTimeout:       15 * time.Second,
		WriteTimeout:      s.opts.WriteTimeout,
		IdleTimeout:       120 * time.Second,
	}
}

func (s *serverImpl) Run(ctx context.Context) error {
	srv := s.httpServer(s.handler())
	errCh := make(chan error, 1)
	go func() {
		s.opts.Logger.Info("listening", "addr", s.opts.Addr)
		errCh <- srv.ListenAndServe()
	}()
	select {
	case err := <-errCh:
		if errors.Is(err, http.ErrServerClosed) {
			return nil
		}
		return fmt.Errorf("http server: %w", err)
	case <-ctx.Done():
		shutdownCtx, cancel := context.WithTimeout(context.WithoutCancel(ctx), 20*time.Second)
		defer cancel()
		if err := srv.Shutdown(shutdownCtx); err != nil {
			return fmt.Errorf("shutdown: %w", err)
		}
		return nil
	}
}
