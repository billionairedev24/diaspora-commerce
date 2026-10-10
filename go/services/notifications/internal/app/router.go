// Package app holds the notification use cases: routing with failover, templating, consent-checked dispatch,
// event ingest, delivery receipts and compliance keyword handling.
package app

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/port"
)

// DefaultMarket is the route key used when a market has no list of its own.
const DefaultMarket = "default"

// Router sends a message through the market's ordered provider list for its channel, failing over on
// timeouts, 5xx and other retryable errors and skipping providers whose circuit breaker is open.
type Router interface {
	Send(ctx context.Context, out domain.Outbound) (domain.Receipt, error)
	// Providers returns the ordered provider names for a channel and market (for diagnostics).
	Providers(channel domain.Channel, market string) []string
}

// Routes are ordered provider lists per market (and DefaultMarket) for each channel.
type Routes struct {
	Email map[string][]port.EmailSender
	SMS   map[string][]port.SmsSender
	Push  map[string][]port.PushSender
	Chat  map[string][]port.ChatSender
}

type routerImpl struct {
	routes   Routes
	breakers domain.Breakers
	timeout  time.Duration
	log      *slog.Logger
}

var _ Router = (*routerImpl)(nil)

// NewRouter returns a Router. timeout bounds each provider attempt (3s by default).
func NewRouter(routes Routes, breakers domain.Breakers, timeout time.Duration, log *slog.Logger) Router {
	if timeout <= 0 {
		timeout = 3 * time.Second
	}
	return &routerImpl{routes: routes, breakers: breakers, timeout: timeout, log: log}
}

type sender[M any] interface {
	Name() string
	Send(ctx context.Context, msg M) (domain.Receipt, error)
}

func pick[S any](m map[string][]S, market string) []S {
	if ps, ok := m[market]; ok && len(ps) > 0 {
		return ps
	}
	return m[DefaultMarket]
}

func convert[S sender[M], M any](in []S) []sender[M] {
	out := make([]sender[M], 0, len(in))
	for _, s := range in {
		out = append(out, s)
	}
	return out
}

func (r *routerImpl) Send(ctx context.Context, out domain.Outbound) (domain.Receipt, error) {
	switch out.Channel {
	case domain.ChannelEmail:
		return failover(ctx, r, convert[port.EmailSender, domain.EmailMessage](pick(r.routes.Email, out.Market)), out.Email)
	case domain.ChannelSMS:
		return failover(ctx, r, convert[port.SmsSender, domain.SmsMessage](pick(r.routes.SMS, out.Market)), out.SMS)
	case domain.ChannelPush:
		var ps []port.PushSender
		for _, p := range pick(r.routes.Push, out.Market) {
			if p.Accepts(out.Push.Token.Platform) {
				ps = append(ps, p)
			}
		}
		return failover(ctx, r, convert[port.PushSender, domain.PushMessage](ps), out.Push)
	case domain.ChannelChat:
		return failover(ctx, r, convert[port.ChatSender, domain.ChatMessage](pick(r.routes.Chat, out.Market)), out.Chat)
	default:
		return domain.Receipt{}, fmt.Errorf("channel %q: %w", out.Channel, domain.ErrNoProvider)
	}
}

func (r *routerImpl) Providers(channel domain.Channel, market string) []string {
	var names []string
	add := func(n string) { names = append(names, n) }
	switch channel {
	case domain.ChannelEmail:
		for _, p := range pick(r.routes.Email, market) {
			add(p.Name())
		}
	case domain.ChannelSMS:
		for _, p := range pick(r.routes.SMS, market) {
			add(p.Name())
		}
	case domain.ChannelPush:
		for _, p := range pick(r.routes.Push, market) {
			add(p.Name())
		}
	case domain.ChannelChat:
		for _, p := range pick(r.routes.Chat, market) {
			add(p.Name())
		}
	}
	return names
}

// failover tries providers in order. A permanent error (bad address, rejected content) stops the chain because
// another provider would reject it too; retryable errors open breakers and move on. All failures are joined so
// the caller can re-queue with backoff (domain.IsRetryable) or dead-letter.
func failover[M any](ctx context.Context, r *routerImpl, providers []sender[M], msg M) (domain.Receipt, error) {
	if len(providers) == 0 {
		return domain.Receipt{}, domain.ErrNoProvider
	}
	errs := make([]error, 0, len(providers))
	for _, p := range providers {
		name := p.Name()
		if !r.breakers.Allow(name) {
			errs = append(errs, fmt.Errorf("%s: circuit open: %w", name, domain.ErrNoProvider))
			continue
		}
		cctx, cancel := context.WithTimeout(ctx, r.timeout)
		rec, err := p.Send(cctx, msg)
		timedOut := errors.Is(cctx.Err(), context.DeadlineExceeded)
		cancel()
		if err == nil {
			r.breakers.Success(name)
			if rec.Provider == "" {
				rec.Provider = name
			}
			if rec.AcceptedAt.IsZero() {
				rec.AcceptedAt = time.Now().UTC()
			}
			return rec, nil
		}
		if ctx.Err() != nil { // the caller gave up; not the provider's fault
			return domain.Receipt{}, errors.Join(append(errs, fmt.Errorf("%s: %w", name, ctx.Err()))...)
		}
		var pe *domain.ProviderError
		if errors.As(err, &pe) && !pe.Retryable && !timedOut {
			r.breakers.Success(name) // the provider answered properly; the message is at fault
			errs = append(errs, err)
			return domain.Receipt{}, errors.Join(errs...)
		}
		if timedOut && !errors.As(err, &pe) {
			err = domain.Retry(name, 0, "timeout").Wrap(err)
		}
		r.breakers.Failure(name)
		r.log.WarnContext(ctx, "provider failed; failing over", "provider", name, "error", err)
		errs = append(errs, err)
	}
	return domain.Receipt{}, errors.Join(errs...)
}
