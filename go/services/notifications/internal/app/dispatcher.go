package app

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"strings"
	"sync"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/port"
)

// DispatcherConfig tunes the outbox dispatcher.
type DispatcherConfig struct {
	Batch        int
	Lease        time.Duration
	PollInterval time.Duration
	Concurrency  int
	Retry        domain.RetryPolicy
	EmailFrom    string
	EmailReplyTo string
}

// Dispatcher drains the notification table: claim (SKIP LOCKED) → consent check → route with failover →
// mark sent, reschedule (quiet hours), retry with backoff, or dead-letter.
type Dispatcher interface {
	// RunOnce processes one batch and returns how many notifications it handled.
	RunOnce(ctx context.Context) (int, error)
	// Run polls until ctx is cancelled.
	Run(ctx context.Context) error
}

type dispatcherImpl struct {
	cfg     DispatcherConfig
	store   port.NotificationStore
	consent port.ConsentChecker
	router  Router
	log     *slog.Logger
	now     func() time.Time
}

var _ Dispatcher = (*dispatcherImpl)(nil)

// NewDispatcher returns the Dispatcher.
func NewDispatcher(cfg DispatcherConfig, store port.NotificationStore, consent port.ConsentChecker, router Router, log *slog.Logger) Dispatcher {
	if cfg.Batch <= 0 {
		cfg.Batch = 50
	}
	if cfg.Lease <= 0 {
		cfg.Lease = 2 * time.Minute
	}
	if cfg.PollInterval <= 0 {
		cfg.PollInterval = time.Second
	}
	if cfg.Concurrency <= 0 {
		cfg.Concurrency = 8
	}
	if cfg.Retry.MaxAttempts <= 0 {
		cfg.Retry = domain.DefaultRetryPolicy
	}
	return &dispatcherImpl{cfg: cfg, store: store, consent: consent, router: router, log: log, now: time.Now}
}

func (d *dispatcherImpl) Run(ctx context.Context) error {
	t := time.NewTicker(d.cfg.PollInterval)
	defer t.Stop()
	for {
		for {
			n, err := d.RunOnce(ctx)
			if err != nil {
				if ctx.Err() == nil {
					d.log.ErrorContext(ctx, "dispatch batch failed", "error", err)
				}
				break
			}
			if n < d.cfg.Batch {
				break
			}
		}
		select {
		case <-ctx.Done():
			return nil
		case <-t.C:
		}
	}
}

func (d *dispatcherImpl) RunOnce(ctx context.Context) (int, error) {
	batch, err := d.store.Claim(ctx, d.cfg.Batch, d.cfg.Lease)
	if err != nil {
		return 0, fmt.Errorf("claim: %w", err)
	}
	sem := make(chan struct{}, d.cfg.Concurrency)
	var wg sync.WaitGroup
	var mu sync.Mutex
	var errs []error
	for _, n := range batch {
		sem <- struct{}{}
		wg.Go(func() {
			defer func() { <-sem }()
			if err := d.process(ctx, n); err != nil {
				mu.Lock()
				errs = append(errs, fmt.Errorf("notification %s: %w", n.ID, err))
				mu.Unlock()
			}
		})
	}
	wg.Wait()
	return len(batch), errors.Join(errs...)
}

func (d *dispatcherImpl) process(ctx context.Context, n domain.Notification) error {
	dec, err := d.consent.Check(ctx, port.ConsentQuery{RecipientID: n.RecipientID, Address: n.Address,
		Channel: n.Channel, Category: n.Category, TimeZone: n.TimeZone, Market: n.Market})
	if err != nil {
		return d.wrap(d.store.Reschedule(ctx, n.ID, d.now().Add(30*time.Second), "consent check failed: "+err.Error()))
	}
	switch dec.Verdict {
	case domain.VerdictDeny:
		d.log.InfoContext(ctx, "notification suppressed", "id", n.ID, "reason", dec.Reason)
		return d.wrap(d.store.MarkFinal(ctx, n.ID, domain.StatusSuppressed, n.Attempts, dec.Reason, false))
	case domain.VerdictDefer:
		return d.wrap(d.store.Reschedule(ctx, n.ID, dec.Until, dec.Reason))
	case domain.VerdictAllow:
	}
	out, err := d.outbound(n)
	if err != nil {
		return d.wrap(d.store.MarkFinal(ctx, n.ID, domain.StatusFailed, n.Attempts+1, err.Error(), true))
	}
	rec, sendErr := d.router.Send(ctx, out)
	if sendErr == nil {
		return d.wrap(d.store.MarkSent(ctx, n.ID, rec))
	}
	if ctxErr := ctx.Err(); ctxErr != nil {
		// Shutting down: leave the row leased; it becomes due again and another worker retries it.
		return fmt.Errorf("interrupted: %w", ctxErr)
	}
	attempts := n.Attempts + 1
	msg := truncate(sendErr.Error(), 2000)
	switch {
	case !domain.IsRetryable(sendErr):
		d.log.WarnContext(ctx, "permanent failure; dead-lettered", "id", n.ID, "error", sendErr)
		return d.wrap(d.store.MarkFinal(ctx, n.ID, domain.StatusFailed, attempts, msg, true))
	case d.cfg.Retry.Exhausted(attempts):
		d.log.WarnContext(ctx, "retries exhausted; dead-lettered", "id", n.ID, "attempts", attempts, "error", sendErr)
		return d.wrap(d.store.MarkFinal(ctx, n.ID, domain.StatusFailed, attempts, "retries exhausted: "+msg, true))
	default:
		next := d.now().Add(d.cfg.Retry.Delay(attempts))
		return d.wrap(d.store.MarkRetry(ctx, n.ID, attempts, next, msg))
	}
}

func (d *dispatcherImpl) wrap(err error) error {
	if err != nil {
		return fmt.Errorf("update: %w", err)
	}
	return nil
}

func (d *dispatcherImpl) outbound(n domain.Notification) (domain.Outbound, error) {
	out := domain.Outbound{Channel: n.Channel, Market: n.Market}
	switch n.Channel {
	case domain.ChannelEmail:
		out.Email = domain.EmailMessage{NotificationID: n.ID, From: d.cfg.EmailFrom, ReplyTo: d.cfg.EmailReplyTo,
			To: n.Address, Subject: n.Subject, HTML: n.BodyHTML, Text: n.BodyText, Headers: n.Headers,
			Category: n.Category, IdempotencyKey: n.IdempotencyKey}
	case domain.ChannelSMS:
		out.SMS = domain.SmsMessage{NotificationID: n.ID, To: n.Address, Body: n.BodyText, Category: n.Category,
			IdempotencyKey: n.IdempotencyKey}
	case domain.ChannelPush:
		tok, err := domain.ParsePushAddress(n.Address)
		if err != nil {
			return out, fmt.Errorf("push address: %w", err)
		}
		out.Push = domain.PushMessage{NotificationID: n.ID, Token: tok, Title: n.Subject, Body: n.BodyText,
			Data: map[string]string{"notificationId": n.ID, "templateKey": n.TemplateKey}, Category: n.Category,
			IdempotencyKey: n.IdempotencyKey}
	case domain.ChannelChat:
		msg := domain.ChatMessage{NotificationID: n.ID, To: n.Address, Body: n.BodyText, Language: n.Locale,
			Category: n.Category, IdempotencyKey: n.IdempotencyKey}
		if n.Subject != "" { // approved WhatsApp template: each non-empty body line is a parameter
			msg.TemplateName = n.Subject
			for _, l := range strings.Split(n.BodyText, "\n") {
				if l = strings.TrimSpace(l); l != "" {
					msg.Params = append(msg.Params, l)
				}
			}
		}
		out.Chat = msg
	default:
		return out, fmt.Errorf("unknown channel %q", n.Channel)
	}
	return out, nil
}

func truncate(s string, n int) string {
	if len(s) <= n {
		return s
	}
	return s[:n]
}
