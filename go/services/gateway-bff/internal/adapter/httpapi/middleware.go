package httpapi

import (
	"context"
	"log/slog"
	"math"
	"net"
	"net/http"
	"slices"
	"strconv"
	"strings"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/app"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

// SecurityOptions configure CORS, CSRF, rate limits and body limits.
type SecurityOptions struct {
	AllowedOrigins []string
	// LocalDev (APP_ENV=local only) also accepts any http://localhost:<port> or http://127.0.0.1:<port> origin, so a web
	// app that moved to another port (3010 taken → 3020) is never refused as "not an AfroTransact page".
	LocalDev        bool
	CookieName      string
	CookieSecure    bool
	CookieDomain    string
	TrustForwarded  bool
	MaxBodyBytes    int64
	AuthPerMinute   int
	LookupPerMinute int
	// MediaRewriteFrom → MediaRewriteTo: local LAN mode only (media_rewrite.go); empty everywhere else.
	MediaRewriteFrom string
	MediaRewriteTo   string
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

// edgeHints reads the visitor-location headers Cloudflare adds ("Add visitor location headers" managed transform;
// CF-IPCountry is always there). Missing headers leave the field empty.
func edgeHints(r *http.Request) domain.EdgeHints {
	get := func(names ...string) string {
		for _, n := range names {
			if v := strings.TrimSpace(r.Header.Get(n)); v != "" {
				return v
			}
		}
		return ""
	}
	num := func(v string) *float64 {
		f, err := strconv.ParseFloat(v, 64)
		if err != nil || math.IsNaN(f) || math.IsInf(f, 0) {
			return nil
		}
		return &f
	}
	h := domain.EdgeHints{Country: get("CF-IPCountry"), City: get("CF-IPCity"), Region: get("CF-Region-Code", "CF-Region"),
		ZIP: get("CF-IPPostalCode", "CF-Postal-Code")}
	h.Lat, h.Lon = num(get("CF-IPLatitude")), num(get("CF-IPLongitude"))
	return h
}

// originAllowed reports whether a browser Origin may call the BFF with its cookies.
func (o SecurityOptions) originAllowed(origin string) bool {
	if origin == "" {
		return false
	}
	if slices.Contains(o.AllowedOrigins, origin) {
		return true
	}
	if !o.LocalDev {
		return false
	}
	for _, p := range []string{"http://localhost:", "http://127.0.0.1:"} {
		if port, ok := strings.CutPrefix(origin, p); ok && port != "" && strings.Trim(port, "0123456789") == "" {
			return true
		}
	}
	return false
}

// cors answers preflights and decorates responses for the configured web origins (credentials allowed).
func cors(o SecurityOptions) func(http.Handler) http.Handler {
	return func(next http.Handler) http.Handler {
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			origin := r.Header.Get("Origin")
			allowed := o.originAllowed(origin)
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
					w.Header().Set("Access-Control-Allow-Headers", "Accept, Accept-Language, Authorization, Content-Type, Idempotency-Key, If-None-Match, Sec-GPC, X-App, X-Background, X-Guest-Cart, X-Correlation-Id")
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
			if (origin != "" && !o.originAllowed(origin)) || (origin == "" && hasCookie) {
				writeProblem(w, r, log, domain.NewProblem(http.StatusForbidden, domain.CodeForbidden, "Forbidden",
					"This request did not come from an AfroTransact page."))
				return
			}
			next.ServeHTTP(w, r)
		})
	}
}

// largeBodies are the routes whose bodies may exceed the default limit: bulk product uploads (a 10 MB file plus
// the multipart framing, S308) and autosaved product-form drafts (256 KB of form input, S307).
var largeBodies = []struct {
	method, path string
	limit        int64
}{
	{http.MethodPost, "/v1/seller/imports", 26 << 20}, // a price and quantity feed may be 25 MB (the handler applies the mode's own limit)
	{http.MethodPost, "/v1/seller/stock/imports", 26 << 20},
	{http.MethodPost, "/v1/seller/product-drafts", 320 << 10},
	{http.MethodPut, "/v1/seller/product-drafts/", 320 << 10},
}

// bodyLimit caps request bodies (decode reports 413).
func bodyLimit(limit int64) func(http.Handler) http.Handler {
	return func(next http.Handler) http.Handler {
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			if r.Body != nil {
				max := limit
				for _, l := range largeBodies {
					if r.Method == l.method && (r.URL.Path == l.path || strings.HasSuffix(l.path, "/") && strings.HasPrefix(r.URL.Path, l.path)) {
						max = l.limit
					}
				}
				r.Body = http.MaxBytesReader(w, r.Body, max)
			}
			next.ServeHTTP(w, r)
		})
	}
}

// backOfficePrefixes are the Seller Center / Provider Hub (/v1/seller/…) and staff console (/v1/staff/…) routes. They
// relay the person's token as identity issued it, so a member acts for their active organisation there.
var backOfficePrefixes = []string{"/v1/seller/", "/v1/staff/"}

// actingContext marks every other route (storefront and customer app: cart, checkout, account, orders, bookings,
// pre-orders, Lola, /v1/me, sign-in) as a shopper request (port.WithShopper): upstream calls send
// X-Acting-As: CUSTOMER, so a person who belongs to a seller or provider organisation still shops as themselves.
func actingContext(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if slices.ContainsFunc(backOfficePrefixes, func(p string) bool { return strings.HasPrefix(r.URL.Path, p) }) {
			next.ServeHTTP(w, r)
			return
		}
		next.ServeHTTP(w, r.WithContext(port.WithShopper(r.Context())))
	})
}

// Session activity routes (contract tag Auth): the policy read never counts as activity; the activity ping always does.
const (
	sessionPolicyPath   = "/v1/session/policy"
	sessionActivityPath = "/v1/session/activity"
	backgroundHeader    = "X-Background"
)

// activityOf says whether the request is the person using the app. Background polling (X-Background: 1, which every
// web app sends on timers, refetch intervals and notification counts) and the policy read do not count, so a page left
// open does not keep a session alive; POST /v1/session/activity is the web app's report of real input.
func activityOf(r *http.Request) app.ActivityKind {
	switch {
	case r.Method == http.MethodPost && r.URL.Path == sessionActivityPath:
		return app.ActivityExplicit
	case r.Method == http.MethodGet && r.URL.Path == sessionPolicyPath, isBackground(r):
		return app.ActivityBackground
	default:
		return app.ActivityCounts
	}
}

func isBackground(r *http.Request) bool {
	v := strings.TrimSpace(r.Header.Get(backgroundHeader))
	return v == "1" || strings.EqualFold(v, "true")
}

// identify resolves the caller (cookie session with token refresh, or app bearer) for every /v1 request.
func identify(sessions app.SessionService, o SecurityOptions, log *slog.Logger) func(http.Handler) http.Handler {
	return func(next http.Handler) http.Handler {
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			in := app.ResolveInput{BearerToken: bearerOf(r), AppGuestCartID: r.Header.Get("X-Guest-Cart"),
				ClientIP: clientIP(r, o.TrustForwarded), Language: r.Header.Get("Accept-Language"),
				Origin: r.Header.Get("Origin"), Path: r.URL.Path, Activity: activityOf(r)}
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
