package app

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"math"
	"net/http"
	"strings"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

// GuestStockAlertScope is the client-credentials scope notifications requires to subscribe an address without an
// account (identity grants it to the gateway-bff client).
const GuestStockAlertScope = "notifications:stock-alerts.guest"

// StockAlertLimits are the abuse limits of "Email me when available", per hour. Zero fields take the defaults.
// Per client IP, per email address and per variant: an attacker can neither mail-bomb one address nor flood one
// product's subscriber list from a single place.
type StockAlertLimits struct {
	PerIPPerHour    int
	PerEmailPerHour int
	PerATINPerHour  int
}

// Default stock-alert limits per hour.
const (
	DefaultAlertsPerIP    = 20
	DefaultAlertsPerEmail = 10
	DefaultAlertsPerATIN  = 300
)

func (l StockAlertLimits) withDefaults() StockAlertLimits {
	if l.PerIPPerHour <= 0 {
		l.PerIPPerHour = DefaultAlertsPerIP
	}
	if l.PerEmailPerHour <= 0 {
		l.PerEmailPerHour = DefaultAlertsPerEmail
	}
	if l.PerATINPerHour <= 0 {
		l.PerATINPerHour = DefaultAlertsPerATIN
	}
	return l
}

// alertLocales are the storefront's locales; the email is sent in one of them.
var alertLocales = []string{"en", "fr", "es", "ar", "yo"}

// alertLocale picks the subscriber's locale from Accept-Language: the first listed tag whose language is supported.
func alertLocale(header string) string {
	for part := range strings.SplitSeq(header, ",") {
		tag, _, _ := strings.Cut(strings.TrimSpace(part), ";")
		lang, _, _ := strings.Cut(strings.ToLower(tag), "-")
		for _, l := range alertLocales {
			if lang == l {
				return l
			}
		}
	}
	return "en"
}

// allow counts one hit against key and returns a 429 problem when the hourly budget is spent. The limiter failing
// open mirrors the BFF's other limits.
func (s *productServiceImpl) allow(ctx context.Context, key string, limit int) error {
	if s.limiter == nil {
		return nil
	}
	ok, retry, err := s.limiter.Allow(ctx, key, limit, time.Hour)
	if err != nil {
		s.log.WarnContext(ctx, "stock alert rate limiter unavailable; allowing", "error", err)
		return nil
	}
	if ok {
		return nil
	}
	p := domain.NewProblem(http.StatusTooManyRequests, domain.CodeRateLimited, "Too many requests",
		"You've asked for a lot of alerts. Please try again a little later.")
	p.RetryAfter = max(1, int(math.Ceil(retry.Seconds())))
	return p
}

// emailKey is the rate-limit key of an address: a hash, so the address is never written to the limiter's store.
func emailKey(email string) string {
	sum := sha256.Sum256([]byte(email))
	return hex.EncodeToString(sum[:12])
}

// StockAlert arms "Email me when available" for the product's requested variant (or its first variant). A signed-in
// shopper's alert belongs to their account (their bearer goes upstream; it lists under /v1/account/stock-alerts). A
// guest needs no account: the typed address is subscribed with the BFF's own service token. Both answer the same way
// whether or not the address already has the alert or an account; nothing is emailed at subscription time.
func (s *productServiceImpl) StockAlert(ctx context.Context, c *domain.Caller, atin, email string) error {
	ip := c.ClientIP
	if ip == "" {
		ip = "unknown"
	}
	atin = strings.ToUpper(strings.TrimSpace(atin))
	if err := s.allow(ctx, "stockalert:ip:"+ip, s.limits.PerIPPerHour); err != nil {
		return err
	}
	if atinRe.MatchString(atin) {
		if err := s.allow(ctx, "stockalert:atin:"+atin, s.limits.PerATINPerHour); err != nil {
			return err
		}
	}
	email = strings.TrimSpace(email)
	if email == "" && c.Session != nil && c.Session.Profile != nil {
		email = c.Session.Profile.Email
	}
	if email == "" {
		p := domain.NewProblem(http.StatusUnprocessableEntity, domain.CodeValidationFailed, "Email required", "Enter an email address so we can tell you when it’s back.")
		p.Errors = map[string]string{"email": "Enter your email"}
		return p
	}
	clean, err := domain.ParseEmail(email)
	if err != nil {
		return domain.ValidationProblem(map[string]string{"email": "Enter a valid email address, like name@example.com"})
	}
	if err := s.allow(ctx, "stockalert:email:"+emailKey(clean), s.limits.PerEmailPerHour); err != nil {
		return err
	}
	p, wanted, err := resolveProduct(ctx, s.catalog, atin)
	if err != nil {
		return err
	}
	variant := wanted
	if variant == "" && len(p.Variants) > 0 {
		variant = p.Variants[0].ATIN
	}
	locale := alertLocale(c.Language)
	if c.Authenticated() {
		in := port.StockAlertInput{ATIN: variant, Email: clean, Locale: locale, ProductTitle: p.Title}
		if err := s.notifications.SubscribeStockAlert(ctx, c.BearerToken, in); err != nil {
			return mapErr("notifications", err)
		}
		return nil
	}
	if s.tokens == nil {
		return domain.UpstreamUnavailable("notifications")
	}
	token, err := s.tokens.Token(ctx, GuestStockAlertScope)
	if err != nil {
		return mapErr("identity", err)
	}
	// The title is the catalog's, never the visitor's.
	in := port.GuestStockAlertInput{ATIN: variant, Email: clean, Locale: locale, ProductTitle: p.Title}
	if err := s.notifications.SubscribeGuestStockAlert(ctx, token, in); err != nil {
		return mapErr("notifications", err)
	}
	return nil
}
