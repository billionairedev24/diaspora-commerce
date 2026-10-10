// Command notifications sends templated, consent-checked email, SMS, push and WhatsApp messages with
// per-market provider failover, and records delivery receipts.
package main

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"strconv"
	"sync"
	"syscall"
	"time"
	_ "time/tzdata" // recipient time zones for quiet hours, independent of the base image

	"github.com/billionairedev24/diaspora-commerce/go/pkg/auth"
	pkgconfig "github.com/billionairedev24/diaspora-commerce/go/pkg/config"
	"github.com/billionairedev24/diaspora-commerce/go/pkg/events"
	"github.com/billionairedev24/diaspora-commerce/go/pkg/httpserver"
	"github.com/billionairedev24/diaspora-commerce/go/pkg/logging"
	"github.com/billionairedev24/diaspora-commerce/go/pkg/telemetry"
	"github.com/billionairedev24/diaspora-commerce/go/services/notifications"
	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/adapter/contactcache"
	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/adapter/deliveryruns"
	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/adapter/httpapi"
	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/adapter/identity"
	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/adapter/jwtcontact"
	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/adapter/mediafetch"
	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/adapter/postgres"
	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/adapter/webhooksig"
	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/app"
	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/config"
	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/port"
)

const serviceName = "notifications"

func main() {
	if err := run(); err != nil {
		slog.Error("notifications exited", "error", err)
		os.Exit(1)
	}
}

func run() error {
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	loader := pkgconfig.NewLoader(notifications.Files)
	var cfg config.Config
	if err := loader.Load(&cfg); err != nil {
		return fmt.Errorf("load config: %w", err)
	}
	cfg.Resolve()
	log := logging.New(serviceName, loader.Env())
	slog.SetDefault(log)
	local := loader.Env() == pkgconfig.EnvLocal

	// Tracing is best-effort: OTPs and order updates must still go out when the collector config is broken.
	shutdownTracing, err := telemetry.Setup(ctx, serviceName, loader.Env(), cfg.Telemetry.OTLPEndpoint, cfg.Telemetry.SampleRatio)
	if err != nil {
		log.Warn("tracing disabled", "error", err)
		shutdownTracing = func(context.Context) error { return nil }
	}
	defer func() { _ = shutdownTracing(context.WithoutCancel(ctx)) }()

	// ---- persistence ----
	pool, err := postgres.Open(ctx, cfg.Database.URL)
	if err != nil {
		return fmt.Errorf("database: %w", err)
	}
	defer pool.Close()
	if cfg.Database.Migrate {
		if err := postgres.Migrate(ctx, pool); err != nil {
			return fmt.Errorf("database: %w", err)
		}
	}
	store := postgres.NewNotificationStore(pool)
	templateStore := postgres.NewTemplateStore(pool)
	consentStore := postgres.NewConsentStore(pool)
	quiet, err := domain.ParseQuietHours(cfg.QuietHours.Start, cfg.QuietHours.End)
	if err != nil {
		return fmt.Errorf("quiet hours: %w", err)
	}
	checker := postgres.NewConsentChecker(pool, quiet, cfg.MarketTimeZones)

	// ---- providers and routing ----
	httpClient := &http.Client{Timeout: 10 * time.Second} // the router bounds each attempt to routing.sendTimeout
	routes := buildRoutes(ctx, newProviderFactory(cfg, httpClient, log))
	breakers := domain.NewBreakers(domain.BreakerSettings{FailureThreshold: cfg.Breaker.FailureThreshold,
		CoolDown: cfg.Breaker.CoolDown, HalfOpenProbes: cfg.Breaker.HalfOpenProbes})
	router := app.NewRouter(routes, breakers, cfg.Routing.SendTimeout, log)

	// ---- use cases ----
	signingKey := cfg.UnsubscribeSigningKey
	if signingKey == "" {
		if !local {
			return errors.New("UNSUBSCRIBE_SIGNING_KEY is required outside local")
		}
		signingKey = "local-only-unsubscribe-key"
	}
	unsub := app.NewUnsubscriber(cfg.PublicBaseURL, cfg.Email.UnsubscribeMailto, []byte(signingKey))
	renderer := app.NewRenderer()
	notifier := app.NewNotifier(templateStore, renderer, store, unsub)
	retry := domain.DefaultRetryPolicy
	if cfg.Dispatcher.MaxAttempts > 0 {
		retry.MaxAttempts = cfg.Dispatcher.MaxAttempts
	}
	if cfg.Dispatcher.BackoffBase > 0 {
		retry.Base = cfg.Dispatcher.BackoffBase
	}
	if cfg.Dispatcher.BackoffMax > 0 {
		retry.Max = cfg.Dispatcher.BackoffMax
	}
	fetcher, err := attachmentFetcher(cfg, log)
	if err != nil {
		return fmt.Errorf("attachments: %w", err)
	}
	dispatcher := app.NewDispatcher(app.DispatcherConfig{Batch: cfg.Dispatcher.Batch, Lease: cfg.Dispatcher.Lease,
		PollInterval: cfg.Dispatcher.PollInterval, Concurrency: cfg.Dispatcher.Concurrency, Retry: retry,
		EmailFrom: cfg.Email.From, EmailReplyTo: cfg.Email.ReplyTo, Attachments: fetcher,
		MaxAttachmentBytes: cfg.Attachments.MaxTotalBytes}, store, checker, router, log)
	contacts := jwtcontact.New(localOnly(local, cfg.TestSend.LocalEmail), localOnly(local, cfg.TestSend.LocalPhone))
	templates := app.NewTemplates(templateStore, renderer, router, contacts, cfg.Email.From)
	prefs := app.NewPreferences(consentStore, unsub)
	receipts := app.NewReceipts(store, consentStore, log)
	compliance := app.NewSmsCompliance(consentStore, app.ComplianceTexts{Help: cfg.SMSCompliance.Help,
		Stop: cfg.SMSCompliance.Stop, Start: cfg.SMSCompliance.Start, TextVersion: cfg.SMSCompliance.TextVersion})
	topics, err := app.ResolveTopics(cfg.Kafka.Topics)
	if err != nil {
		return fmt.Errorf("config: %w", err)
	}
	var resolver port.RecipientResolver
	var contactCache port.ContactInvalidator
	if c := recipientResolver(cfg, local, log); c != nil {
		resolver, contactCache = c, c
	}
	links := app.Links{Storefront: cfg.Links.Storefront, SellerCenter: cfg.Links.SellerCenter, ProviderHub: cfg.Links.ProviderHub}
	samples, err := app.NewTemplateSamples(ctx, links)
	if err != nil {
		return fmt.Errorf("template samples: %w", err)
	}
	stockAlerts := app.NewStockAlerts(postgres.NewStockAlertStore(pool), notifier, resolver, links, log)
	ingest := app.NewIngest(notifier, app.IngestConfig{Resolver: resolver, Links: links, StockAlerts: stockAlerts,
		Contacts: contactCache, Audience: preorderAudience(cfg, local, log)}, log)

	twilioV, resendV, sendGridV, err := verifiers(cfg)
	if err != nil {
		return err
	}

	// ---- HTTP ----
	var verifier auth.Verifier
	if cfg.Auth.Enabled {
		verifier = auth.NewJWKSVerifier(cfg.Auth.Issuer, cfg.Auth.Audience, &http.Client{Timeout: 5 * time.Second})
	}
	openapi, err := notifications.Files.ReadFile("api/openapi.yaml")
	if err != nil {
		return fmt.Errorf("read openapi: %w", err)
	}
	srv := httpserver.New(httpserver.Options{
		Name:    "Notifications API",
		Addr:    ":" + strconv.Itoa(cfg.Port),
		OpenAPI: openapi,
		DocsOn:  cfg.DocsEnabled,
		Checks:  []httpserver.ReadinessCheck{readiness{store: store}},
		Logger:  log,
	})
	authMW := auth.Middleware(verifier, false)
	authn := func(h http.Handler) http.Handler { return authMW(contacts.Capture(h)) }
	httpapi.New(httpapi.Deps{Notifier: notifier, Log: app.NewNotificationLog(store), Templates: templates,
		Preferences: prefs, Receipts: receipts, Compliance: compliance, Unsubscriber: unsub, StockAlerts: stockAlerts,
		Samples: samples, Twilio: twilioV, Resend: resendV, SendGrid: sendGridV, Logger: log}).Register(srv.Mux(), authn)

	// ---- background workers ----
	var wg sync.WaitGroup
	if cfg.Dispatcher.Enabled {
		wg.Go(func() { _ = dispatcher.Run(ctx) })
	}
	if cfg.Kafka.Enabled {
		wg.Go(func() { consume(ctx, cfg.Kafka.Brokers, app.TopicAddresses(topics), ingest, log) })
	}
	err = srv.Run(ctx)
	stop()
	wg.Wait()
	if err != nil {
		return fmt.Errorf("serve: %w", err)
	}
	return nil
}

// localClientSecret is identity's local default for the notifications-service client (application-local.yml).
const localClientSecret = "local-notifications-service-secret" //nolint:gosec // local-only default, not a credential

// recipientResolver builds the identity lookup (client credentials + TTL cache). Without it, events that carry only
// user or org ids cannot be delivered, so a missing secret outside local is logged loudly rather than crashing the
// service that also sends OTPs. The cache is also the ContactInvalidator the ingest uses on contact-change events.
func recipientResolver(cfg config.Config, local bool, log *slog.Logger) contactcache.Cache {
	if !cfg.Identity.Enabled {
		log.Warn("identity lookups disabled: only events with addresses in their payload are delivered")
		return nil
	}
	secret := cfg.Identity.ClientSecret
	if secret == "" && local {
		secret = localClientSecret
	}
	if secret == "" {
		log.Error("NOTIFICATIONS_CLIENT_SECRET missing: identity lookups disabled; order, seller and payout events need them")
		return nil
	}
	timeout := cfg.Identity.Timeout
	if timeout <= 0 {
		timeout = 3 * time.Second
	}
	inner := identity.New(identity.Config{BaseURL: cfg.Identity.BaseURL, TokenURL: cfg.Identity.TokenURL,
		ClientID: cfg.Identity.ClientID, ClientSecret: secret, Scopes: []string{"users:read"}},
		&http.Client{Timeout: timeout})
	return contactcache.New(inner, contactcache.Options{TTL: cfg.Identity.CacheTTL, NotFoundTTL: cfg.Identity.NotFoundTTL,
		MaxEntries: cfg.Identity.CacheMax})
}

// preorderAudience builds delivery-runs' pre-order audience client with the identity client's credentials. Without
// it, pre-order announcements, reminders and last calls reach nobody (buyers still get closed / out for delivery).
func preorderAudience(cfg config.Config, local bool, log *slog.Logger) port.PreorderAudience {
	if !cfg.DeliveryRuns.Enabled {
		log.Warn("delivery-runs audience disabled: pre-order announcements, reminders and last calls are not sent")
		return nil
	}
	secret := cfg.Identity.ClientSecret
	if secret == "" && local {
		secret = localClientSecret
	}
	if secret == "" {
		log.Error("NOTIFICATIONS_CLIENT_SECRET missing: pre-order audiences disabled")
		return nil
	}
	timeout := cfg.DeliveryRuns.Timeout
	if timeout <= 0 {
		timeout = 5 * time.Second
	}
	return deliveryruns.New(deliveryruns.Config{BaseURL: cfg.DeliveryRuns.BaseURL, TokenURL: cfg.Identity.TokenURL,
		ClientID: cfg.Identity.ClientID, ClientSecret: secret}, &http.Client{Timeout: timeout})
}

// attachmentFetcher builds the email attachment fetcher (signed media links, allow-listed hosts, size limit). When
// attachments are disabled, emails carry only the link their template renders.
func attachmentFetcher(cfg config.Config, log *slog.Logger) (port.AttachmentFetcher, error) {
	a := cfg.Attachments
	if !a.Enabled {
		log.Info("email attachments disabled: documents are sent as links")
		return nil, nil
	}
	timeout := a.Timeout
	if timeout <= 0 {
		timeout = 10 * time.Second
	}
	f, err := mediafetch.New(mediafetch.Config{MediaBaseURL: a.MediaBaseURL, AllowedHosts: a.AllowedHosts,
		MaxBytes: a.MaxBytes}, &http.Client{Timeout: timeout})
	if err != nil {
		return nil, fmt.Errorf("fetcher: %w", err)
	}
	return f, nil
}

func localOnly(local bool, v string) string {
	if local {
		return v
	}
	return ""
}

// verifiers builds the webhook signature verifiers. Outside local, webhooks.verify must be true; a webhook whose
// secret is missing is disabled (503) rather than left open.
func verifiers(cfg config.Config) (webhooksig.Verifier, webhooksig.Verifier, webhooksig.Verifier, error) {
	if !cfg.Webhooks.Verify {
		allow := webhooksig.NewAllowAll()
		return allow, allow, allow, nil
	}
	var tw, rs, sg webhooksig.Verifier
	if cfg.Providers.Twilio.AuthToken != "" {
		tw = webhooksig.NewTwilio(cfg.Providers.Twilio.AuthToken, cfg.PublicBaseURL)
	}
	if cfg.Webhooks.ResendSecret != "" {
		v, err := webhooksig.NewSvix(cfg.Webhooks.ResendSecret)
		if err != nil {
			return nil, nil, nil, fmt.Errorf("resend webhook: %w", err)
		}
		rs = v
	}
	if cfg.Webhooks.SendGridPublicKey != "" {
		v, err := webhooksig.NewSendGrid(cfg.Webhooks.SendGridPublicKey)
		if err != nil {
			return nil, nil, nil, fmt.Errorf("sendgrid webhook: %w", err)
		}
		sg = v
	}
	return tw, rs, sg, nil
}

// consume runs the Kafka consumer group. When a handler fails the consumer is closed and recreated so the group
// resumes from the last committed offset and the failed event is redelivered.
func consume(ctx context.Context, brokers, topics []string, h events.Handler, log *slog.Logger) {
	backoff := time.Second
	log.InfoContext(ctx, "consuming", "group", app.ConsumerGroup, "topics", topics)
	for ctx.Err() == nil {
		c, err := events.NewKafkaConsumer(brokers, app.ConsumerGroup, topics...)
		if err == nil {
			err = c.Run(ctx, h)
			c.Close()
			if err == nil {
				return
			}
		}
		log.ErrorContext(ctx, "kafka consumer stopped; restarting", "error", err, "in", backoff)
		select {
		case <-ctx.Done():
			return
		case <-time.After(backoff):
		}
		backoff = min(backoff*2, time.Minute)
	}
}

type readiness struct{ store port.NotificationStore }

func (readiness) Name() string { return "postgres" }
func (r readiness) Check(ctx context.Context) error {
	if err := r.store.Ping(ctx); err != nil {
		return fmt.Errorf("ping: %w", err)
	}
	return nil
}
