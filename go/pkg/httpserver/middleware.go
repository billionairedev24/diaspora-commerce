package httpserver

import (
	"crypto/rand"
	"encoding/hex"
	"log/slog"
	"net/http"
	"strconv"
	"time"

	"github.com/prometheus/client_golang/prometheus"

	"github.com/billionairedev24/diaspora-commerce/go/pkg/problem"
)

// CorrelationHeader carries the request correlation id across services and into logs.
const CorrelationHeader = "X-Correlation-Id"

type statusRecorder struct {
	http.ResponseWriter
	status int
}

func (s *statusRecorder) WriteHeader(code int) {
	s.status = code
	s.ResponseWriter.WriteHeader(code)
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
		next.ServeHTTP(w, r)
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
