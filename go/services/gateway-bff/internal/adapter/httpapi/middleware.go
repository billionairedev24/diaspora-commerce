package httpapi

import (
	"context"
	"log/slog"
	"math"
	"net"
	"net/http"
	"slices"
	"strings"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/app"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

// SecurityOptions configure CORS, CSRF, rate limits and body limits.
type SecurityOptions struct {
	AllowedOrigins  []string
	CookieName      string
	CookieSecure    bool
	CookieDomain    string
	TrustForwarded  bool
	MaxBodyBytes    int64
	AuthPerMinute   int
	LookupPerMinute int
}

type callerKey struct{}

func callerFrom(ctx context.Context) *domain.Caller {
	c, _ := ctx.Value(callerKey{}).(*domain.Caller)
	if c == nil {
		return &domain.Caller{}
	}
	return c
}

func isUnsafe(method string) bool {
	switch method {
	case http.MethodGet, http.MethodHead, http.MethodOptions:
		return false
	default:
		return true
	}
}

func bearerOf(r *http.Request) string {
	t, ok := strings.CutPrefix(r.Header.Get("Authorization"), "Bearer ")
	if !ok {
		return ""
	}
	return strings.TrimSpace(t)
}

func clientIP(r *http.Request, trustForwarded bool) string {
	if trustForwarded {
		if xff := r.Header.Get("X-Forwarded-For"); xff != "" {
			first, _, _ := strings.Cut(xff, ",")
			if ip := strings.TrimSpace(first); ip != "" {
				return ip
			}
		}
	}
	host, _, err := net.SplitHostPort(r.RemoteAddr)
	if err != nil {
		return r.RemoteAddr
	}
	return host
}

// cors answers preflights and decorates responses for the configured web origins (credentials allowed).
func cors(o SecurityOptions) func(http.Handler) http.Handler {
	return func(next http.Handler) http.Handler {
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			origin := r.Header.Get("Origin")
			allowed := origin != "" && slices.Contains(o.AllowedOrigins, origin)
			if allowed {
				h := w.Header()
				h.Set("Access-Control-Allow-Origin", origin)
				h.Set("Access-Control-Allow-Credentials", "true")
				h.Add("Vary", "Origin")
				h.Set("Access-Control-Expose-Headers", "ETag, Retry-After, X-Guest-Cart, X-Correlation-Id")
			}
			if r.Method == http.MethodOptions && r.Header.Get("Access-Control-Request-Method") != "" {
				if allowed {
					w.Header().Set("Access-Control-Allow-Methods", "GET, POST, PUT, PATCH, DELETE, OPTIONS")
					w.Header().Set("Access-Control-Allow-Headers", "Accept, Accept-Language, Authorization, Content-Type, Idempotency-Key, If-None-Match, Sec-GPC, X-Guest-Cart, X-Correlation-Id")
					w.Header().Set("Access-Control-Max-Age", "600")
				}
				w.WriteHeader(http.StatusNoContent)
				return
			}
			next.ServeHTTP(w, r)
		})
	}
}

// csrf enforces the contract's Origin check: unsafe methods need an allowed Origin; a missing Origin is only
// accepted without a cookie session (native clients). Bearer-token requests are not cookie-authenticated.
func csrf(o SecurityOptions, log *slog.Logger) func(http.Handler) http.Handler {
	return func(next http.Handler) http.Handler {
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			if !isUnsafe(r.Method) || bearerOf(r) != "" {
				next.ServeHTTP(w, r)
				return
			}
			origin := r.Header.Get("Origin")
			_, cookieErr := r.Cookie(o.CookieName)
			hasCookie := cookieErr == nil
			if (origin != "" && !slices.Contains(o.AllowedOrigins, origin)) || (origin == "" && hasCookie) {
				writeProblem(w, r, log, domain.NewProblem(http.StatusForbidden, domain.CodeForbidden, "Forbidden",
					"This request did not come from an AfroTransact page."))
				return
			}
			next.ServeHTTP(w, r)
		})
	}
}

// bodyLimit caps request bodies (decode reports 413).
func bodyLimit(limit int64) func(http.Handler) http.Handler {
	return func(next http.Handler) http.Handler {
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			if r.Body != nil {
				r.Body = http.MaxBytesReader(w, r.Body, limit)
			}
			next.ServeHTTP(w, r)
		})
	}
}

// identify resolves the caller (cookie session with token refresh, or app bearer) for every /v1 request.
func identify(sessions app.SessionService, o SecurityOptions, log *slog.Logger) func(http.Handler) http.Handler {
	return func(next http.Handler) http.Handler {
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			in := app.ResolveInput{BearerToken: bearerOf(r), AppGuestCartID: r.Header.Get("X-Guest-Cart"),
				ClientIP: clientIP(r, o.TrustForwarded), Language: r.Header.Get("Accept-Language")}
			if ck, err := r.Cookie(o.CookieName); err == nil {
				in.CookieValue = ck.Value
			}
			c, err := sessions.Resolve(r.Context(), in)
			if err != nil {
				writeProblem(w, r, log, err)
				return
			}
			next.ServeHTTP(w, r.WithContext(context.WithValue(r.Context(), callerKey{}, c)))
		})
	}
}

// limiter wraps a handler with a per-client-IP fixed-window limit shared across replicas (fails open).
type limiter struct {
	rl  port.RateLimiter
	log *slog.Logger
	o   SecurityOptions
}

func (l limiter) wrap(bucket string, perMinute int, next http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if perMinute > 0 && l.rl != nil {
			ok, retry, err := l.rl.Allow(r.Context(), bucket+":"+clientIP(r, l.o.TrustForwarded), perMinute, time.Minute)
			if err != nil {
				l.log.WarnContext(r.Context(), "rate limiter unavailable; allowing", "error", err)
			} else if !ok {
				p := domain.NewProblem(http.StatusTooManyRequests, domain.CodeRateLimited, "Too many requests", "Please wait a moment and try again.")
				p.RetryAfter = max(1, int(math.Ceil(retry.Seconds())))
				writeProblem(w, r, l.log, p)
				return
			}
		}
		next(w, r)
	}
}
