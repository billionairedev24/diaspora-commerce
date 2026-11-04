package httpserver

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"fmt"
	"log/slog"
	"net/http"
	"strconv"
	"time"

	"github.com/prometheus/client_golang/prometheus"

	"github.com/billionairedev24/diaspora-commerce/go/pkg/problem"
)

// CorrelationHeader carries the request correlation id across services and into logs.
const CorrelationHeader = "X-Correlation-Id"

type correlationKey struct{}

// WithCorrelation stores id on ctx for outbound calls (see PropagateCorrelation).
func WithCorrelation(ctx context.Context, id string) context.Context {
	return context.WithValue(ctx, correlationKey{}, id)
}

// CorrelationFromContext returns the inbound request correlation id, if any.
func CorrelationFromContext(ctx context.Context) string {
	id, _ := ctx.Value(correlationKey{}).(string)
	return id
}

// PropagateCorrelation sets X-Correlation-Id on h when ctx carries one and the header is not already set.
func PropagateCorrelation(ctx context.Context, h http.Header) {
	if h == nil || h.Get(CorrelationHeader) != "" {
		return
	}
	if id := CorrelationFromContext(ctx); id != "" {
		h.Set(CorrelationHeader, id)
	}
}

// statusRecorder captures the response status for metrics and logs. It forwards Flush and exposes Unwrap so
// streaming handlers (SSE) can flush per event and http.ResponseController reaches the connection beneath it.
type statusRecorder struct {
	http.ResponseWriter
	status      int
	wroteHeader bool
}

var (
	_ http.Flusher = (*statusRecorder)(nil)
	_ interface {
		Unwrap() http.ResponseWriter
	} = (*statusRecorder)(nil)
)

func (s *statusRecorder) WriteHeader(code int) {
	if !s.wroteHeader {
		s.status = code
		s.wroteHeader = true
	}
	s.ResponseWriter.WriteHeader(code)
}

func (s *statusRecorder) Write(b []byte) (int, error) {
	s.wroteHeader = true
	n, err := s.ResponseWriter.Write(b)
	if err != nil {
		return n, fmt.Errorf("write response: %w", err)
	}
	return n, nil
}

// Flush implements http.Flusher; it is a no-op when nothing beneath can flush.
func (s *statusRecorder) Flush() { _ = s.FlushError() }

// FlushError is what http.ResponseController.Flush calls; it reports http.ErrNotSupported when nothing beneath
// can flush.
func (s *statusRecorder) FlushError() error {
	s.wroteHeader = true
	if err := http.NewResponseController(s.ResponseWriter).Flush(); err != nil {
		return fmt.Errorf("flush response: %w", err)
	}
	return nil
}

// Unwrap lets http.ResponseController reach the underlying writer (deadlines, hijacking, full duplex).
func (s *statusRecorder) Unwrap() http.ResponseWriter { return s.ResponseWriter }

// Stream marks a handler as long-lived (Server-Sent Events, chunked streams): the server's WriteTimeout is
// lifted for its responses. Use it when routes are registered on Mux() by an adapter; Options.StreamRoutes does
// the same by pattern.
func Stream(next http.Handler) http.Handler {
	return StreamFor(0, next)
}

// StreamFor is Stream with a cap on the stream's total write time (0 = no cap).
func StreamFor(maxDuration time.Duration, next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		liftWriteDeadline(w, r, maxDuration, nil)
		next.ServeHTTP(w, r)
	})
}

// liftWriteDeadline replaces the connection's write deadline (set by WriteTimeout when the request was read)
// with maxDuration from now, or none.
func liftWriteDeadline(w http.ResponseWriter, r *http.Request, maxDuration time.Duration, log *slog.Logger) {
	var deadline time.Time
	if maxDuration > 0 {
		deadline = time.Now().Add(maxDuration)
	}
	if err := http.NewResponseController(w).SetWriteDeadline(deadline); err != nil && log != nil {
		log.WarnContext(r.Context(), "stream: cannot lift write deadline", "error", err, "path", r.URL.Path)
	}
}

// streamRoutes lifts the write deadline for requests that the mux routes to one of patterns.
func streamRoutes(mux *http.ServeMux, patterns []string, maxDuration time.Duration, log *slog.Logger) func(http.Handler) http.Handler {
	if len(patterns) == 0 {
		return func(next http.Handler) http.Handler { return next }
	}
	set := make(map[string]struct{}, len(patterns))
	for _, p := range patterns {
		set[p] = struct{}{}
	}
	return func(next http.Handler) http.Handler {
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			if _, pattern := mux.Handler(r); pattern != "" {
				if _, ok := set[pattern]; ok {
					liftWriteDeadline(w, r, maxDuration, log)
				}
			}
			next.ServeHTTP(w, r)
		})
	}
}

func correlation(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		id := r.Header.Get(CorrelationHeader)
		if id == "" {
			b := make([]byte, 16)
			_, _ = rand.Read(b)
			id = hex.EncodeToString(b)
			r.Header.Set(CorrelationHeader, id)
		}
		w.Header().Set(CorrelationHeader, id)
		next.ServeHTTP(w, r.WithContext(WithCorrelation(r.Context(), id)))
	})
}

func recoverer(log *slog.Logger) func(http.Handler) http.Handler {
	return func(next http.Handler) http.Handler {
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			defer func() {
				if v := recover(); v != nil {
					log.ErrorContext(r.Context(), "panic", "error", v, "path", r.URL.Path)
					problem.Write(w, r, problem.New(http.StatusInternalServerError, "internal", "Unexpected error"))
				}
			}()
			next.ServeHTTP(w, r)
		})
	}
}

func instrument(log *slog.Logger, reqs *prometheus.CounterVec, dur *prometheus.HistogramVec) func(http.Handler) http.Handler {
	return func(next http.Handler) http.Handler {
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			start := time.Now()
			rec := &statusRecorder{ResponseWriter: w, status: http.StatusOK}
			next.ServeHTTP(rec, r)
			route := r.Pattern
			if route == "" {
				route = "unmatched"
			}
			elapsed := time.Since(start)
			reqs.WithLabelValues(r.Method, route, strconv.Itoa(rec.status)).Inc()
			dur.WithLabelValues(r.Method, route).Observe(elapsed.Seconds())
			if route != "GET /healthz" && route != "GET /readyz" && route != "GET /metrics" {
				log.InfoContext(r.Context(), "http", "method", r.Method, "route", route, "status", rec.status,
					"ms", elapsed.Milliseconds(), "correlation_id", r.Header.Get(CorrelationHeader))
			}
		})
	}
}
