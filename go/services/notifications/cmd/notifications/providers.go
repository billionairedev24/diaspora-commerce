package main

import (
	"context"
	"fmt"
	"log/slog"
	"net"
	"net/http"
	"net/url"
	"strings"

	"github.com/aws/aws-sdk-go-v2/aws"
	awsconfig "github.com/aws/aws-sdk-go-v2/config"
	"github.com/aws/aws-sdk-go-v2/service/pinpointsmsvoicev2"
	"github.com/aws/aws-sdk-go-v2/service/sesv2"

	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/adapter/apns"
	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/adapter/awssms"
	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/adapter/fcm"
	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/adapter/localguard"
	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/adapter/logsender"
	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/adapter/resend"
	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/adapter/sendgrid"
	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/adapter/ses"
	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/adapter/smtp"
	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/adapter/twilio"
	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/adapter/vonage"
	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/adapter/whatsapp"
	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/app"
	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/config"
	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/port"
)

// providerFactory builds each adapter named in the routing config exactly once (breakers are per provider).
type providerFactory struct {
	cfg    config.Config
	client *http.Client
	log    *slog.Logger

	email map[string]port.EmailSender
	sms   map[string]port.SmsSender
	push  map[string]port.PushSender
	chat  map[string]port.ChatSender
}

func newProviderFactory(cfg config.Config, client *http.Client, log *slog.Logger) *providerFactory {
	return &providerFactory{cfg: cfg, client: client, log: log, email: map[string]port.EmailSender{},
		sms: map[string]port.SmsSender{}, push: map[string]port.PushSender{}, chat: map[string]port.ChatSender{}}
}

func (f *providerFactory) awsConfig(ctx context.Context, region string) (aws.Config, error) {
	opts := []func(*awsconfig.LoadOptions) error{}
	if region != "" {
		opts = append(opts, awsconfig.WithRegion(region))
	}
	c, err := awsconfig.LoadDefaultConfig(ctx, opts...)
	if err != nil {
		return aws.Config{}, fmt.Errorf("aws config: %w", err)
	}
	return c, nil
}

func (f *providerFactory) emailSender(ctx context.Context, name string) (port.EmailSender, error) {
	if s, ok := f.email[name]; ok {
		return s, nil
	}
	p := f.cfg.Providers
	var s port.EmailSender
	switch name {
	case resend.Name:
		if p.Resend.APIKey == "" {
			return nil, fmt.Errorf("resend: RESEND_API_KEY is not set")
		}
		s = resend.New(resend.Config{APIKey: p.Resend.APIKey, BaseURL: p.Resend.BaseURL}, f.client)
	case sendgrid.Name:
		if p.SendGrid.APIKey == "" {
			return nil, fmt.Errorf("sendgrid: SENDGRID_API_KEY is not set")
		}
		s = sendgrid.New(sendgrid.Config{APIKey: p.SendGrid.APIKey, BaseURL: p.SendGrid.BaseURL}, f.client)
	case ses.Name:
		ac, err := f.awsConfig(ctx, p.SES.Region)
		if err != nil {
			return nil, err
		}
		s = ses.New(sesv2.NewFromConfig(ac, func(o *sesv2.Options) { o.RetryMaxAttempts = 1 }), p.SES.ConfigurationSet)
	case smtp.Name:
		s = smtp.New(smtp.Config{Host: p.SMTP.Host, Port: p.SMTP.Port, Username: p.SMTP.Username,
			Password: p.SMTP.Password, StartTLS: p.SMTP.StartTLS})
	case logsender.Name:
		s = logsender.NewEmail(f.log)
	default:
		return nil, fmt.Errorf("unknown email provider %q", name)
	}
	f.email[name] = s
	return s, nil
}

func (f *providerFactory) smsSender(ctx context.Context, name string) (port.SmsSender, error) {
	if s, ok := f.sms[name]; ok {
		return s, nil
	}
	p := f.cfg.Providers
	var s port.SmsSender
	switch name {
	case twilio.Name:
		if p.Twilio.AccountSID == "" || p.Twilio.AuthToken == "" {
			return nil, fmt.Errorf("twilio: TWILIO_ACCOUNT_SID / TWILIO_AUTH_TOKEN are not set")
		}
		callback := ""
		if reachableFromInternet(f.cfg.PublicBaseURL) {
			callback = f.cfg.PublicBaseURL + "/v1/webhooks/twilio/status"
		} else if f.cfg.PublicBaseURL != "" {
			f.log.Info("twilio: no delivery-status callbacks: Twilio cannot reach this service's PUBLIC_BASE_URL "+
				"(set it to a tunnel URL, e.g. cloudflared, to receive them)", "publicBaseUrl", f.cfg.PublicBaseURL)
		}
		s = twilio.New(twilio.Config{AccountSID: p.Twilio.AccountSID, AuthToken: p.Twilio.AuthToken, From: p.Twilio.From,
			MessagingServiceSID: p.Twilio.MessagingServiceSID, StatusCallbackURL: callback, BaseURL: p.Twilio.BaseURL}, f.client)
	case vonage.Name:
		if p.Vonage.APIKey == "" || p.Vonage.APISecret == "" {
			return nil, fmt.Errorf("vonage: VONAGE_API_KEY / VONAGE_API_SECRET are not set")
		}
		s = vonage.New(vonage.Config{APIKey: p.Vonage.APIKey, APISecret: p.Vonage.APISecret, From: p.Vonage.From,
			BaseURL: p.Vonage.BaseURL}, f.client)
	case awssms.Name:
		ac, err := f.awsConfig(ctx, p.AWSSMS.Region)
		if err != nil {
			return nil, err
		}
		api := pinpointsmsvoicev2.NewFromConfig(ac, func(o *pinpointsmsvoicev2.Options) { o.RetryMaxAttempts = 1 })
		s = awssms.New(api, awssms.Config{OriginationIdentity: p.AWSSMS.OriginationIdentity, ConfigurationSet: p.AWSSMS.ConfigurationSet})
	case logsender.Name:
		s = logsender.NewSMS(f.log)
	default:
		return nil, fmt.Errorf("unknown sms provider %q", name)
	}
	f.sms[name] = s
	return s, nil
}

func (f *providerFactory) pushSender(ctx context.Context, name string) (port.PushSender, error) {
	if s, ok := f.push[name]; ok {
		return s, nil
	}
	p := f.cfg.Providers
	var s port.PushSender
	switch name {
	case fcm.Name:
		if p.FCM.ServiceAccountJSON == "" {
			return nil, fmt.Errorf("fcm: FCM_SERVICE_ACCOUNT_JSON is not set")
		}
		ts, project, err := fcm.TokenSourceFromJSON(ctx, []byte(p.FCM.ServiceAccountJSON))
		if err != nil {
			return nil, fmt.Errorf("fcm: %w", err)
		}
		if p.FCM.ProjectID != "" {
			project = p.FCM.ProjectID
		}
		s = fcm.New(fcm.Config{ProjectID: project}, ts, f.client)
	case apns.Name:
		if p.APNs.KeyP8 == "" {
			return nil, fmt.Errorf("apns: APNS_KEY_P8 is not set")
		}
		key, err := apns.ParseKey([]byte(p.APNs.KeyP8))
		if err != nil {
			return nil, fmt.Errorf("apns: %w", err)
		}
		base := apns.SandboxURL
		if p.APNs.Production {
			base = apns.ProductionURL
		}
		s = apns.New(apns.Config{KeyID: p.APNs.KeyID, TeamID: p.APNs.TeamID, BundleID: p.APNs.BundleID, BaseURL: base}, key, f.client)
	case logsender.Name:
		s = logsender.NewPush(f.log)
	default:
		return nil, fmt.Errorf("unknown push provider %q", name)
	}
	f.push[name] = s
	return s, nil
}

func (f *providerFactory) chatSender(_ context.Context, name string) (port.ChatSender, error) {
	if s, ok := f.chat[name]; ok {
		return s, nil
	}
	p := f.cfg.Providers
	var s port.ChatSender
	switch name {
	case whatsapp.Name:
		if p.WhatsApp.Token == "" {
			return nil, fmt.Errorf("whatsapp: WHATSAPP_TOKEN is not set")
		}
		s = whatsapp.New(whatsapp.Config{PhoneNumberID: p.WhatsApp.PhoneNumberID, Token: p.WhatsApp.Token,
			APIVersion: p.WhatsApp.APIVersion}, f.client)
	case logsender.Name:
		s = logsender.NewChat(f.log)
	default:
		return nil, fmt.Errorf("unknown chat provider %q", name)
	}
	f.chat[name] = s
	return s, nil
}

// buildRoutes resolves every configured route. A provider whose credentials are missing is skipped with a
// warning (the route keeps its other providers) so one unconfigured fallback does not stop the service.
func buildRoutes(ctx context.Context, f *providerFactory) app.Routes {
	r := app.Routes{Email: map[string][]port.EmailSender{}, SMS: map[string][]port.SmsSender{},
		Push: map[string][]port.PushSender{}, Chat: map[string][]port.ChatSender{}}
	rc, choices := f.cfg.EffectiveRouting()
	logChoices(ctx, f, choices)
	email, sms := f.emailSender, f.smsSender
	if rc.PreferConfigured {
		email, sms = f.guardedEmailSender, f.guardedSmsSender
	}
	for market, names := range rc.Email {
		r.Email[market] = resolve(ctx, f.log, "email", market, names, email)
	}
	for market, names := range rc.SMS {
		r.SMS[market] = resolve(ctx, f.log, "sms", market, names, sms)
	}
	for market, names := range rc.Push {
		r.Push[market] = resolve(ctx, f.log, "push", market, names, f.pushSender)
	}
	for market, names := range rc.Chat {
		r.Chat[market] = resolve(ctx, f.log, "chat", market, names, f.chatSender)
	}
	return r
}

// localFallback names what a channel does locally without a real provider.
var localFallback = map[string]string{
	"email": "smtp → Mailpit http://localhost:8025",
	"sms":   "log (one-time codes from identity are in Mailpit)",
	"push":  "log",
	"chat":  "log",
}

// logChoices prints, once at start-up, which provider each channel uses on the local profile (real or fallback).
func logChoices(ctx context.Context, f *providerFactory, choices []config.ProviderChoice) {
	for _, c := range choices {
		if c.Real == "" {
			f.log.InfoContext(ctx, "local provider: fallback", "channel", c.Channel, "active", localFallback[c.Channel],
				"why", c.Reason)
			continue
		}
		f.log.InfoContext(ctx, "local provider: REAL", "channel", c.Channel, "active", c.Real,
			"order", strings.Join(c.Providers, ","), "why", c.Reason,
			"note", "test users (@afrotransact.test, 555-01xx phones) still go to Mailpit")
		if c.Real == "resend" || c.Real == "sendgrid" {
			if localguard.ReservedEmail(f.cfg.Email.From) {
				f.log.WarnContext(ctx, "EMAIL_FROM is a local address the real provider will refuse: set EMAIL_FROM to "+
					"an address on your verified domain (or onboarding@resend.dev, which only delivers to your own "+
					"Resend account's email)", "from", f.cfg.Email.From)
			}
		}
	}
}

// guardedEmailSender wraps a real email provider so reserved test domains still go to Mailpit (smtp).
func (f *providerFactory) guardedEmailSender(ctx context.Context, name string) (port.EmailSender, error) {
	s, err := f.emailSender(ctx, name)
	if err != nil || name == smtp.Name || name == logsender.Name {
		return s, err
	}
	mailpit, err := f.emailSender(ctx, smtp.Name)
	if err != nil {
		return s, nil //nolint:nilerr // no Mailpit: the real provider alone
	}
	return localguard.NewEmail(s, mailpit), nil
}

// guardedSmsSender wraps a real SMS provider so the test users' fictional numbers become Mailpit emails.
func (f *providerFactory) guardedSmsSender(ctx context.Context, name string) (port.SmsSender, error) {
	s, err := f.smsSender(ctx, name)
	if err != nil || name == logsender.Name {
		return s, err
	}
	mailpit, err := f.emailSender(ctx, smtp.Name)
	if err != nil {
		return s, nil //nolint:nilerr // no Mailpit: the real provider alone
	}
	return localguard.NewSMS(s, mailpit, f.cfg.Email.From), nil
}

// reachableFromInternet is false for an empty URL and for localhost / loopback / private addresses, which a
// provider's webhook (Twilio status callbacks) cannot call.
func reachableFromInternet(raw string) bool {
	u, err := url.Parse(raw)
	if err != nil || u.Hostname() == "" {
		return false
	}
	h := u.Hostname()
	if h == "localhost" || strings.HasSuffix(h, ".localhost") || strings.HasSuffix(h, ".local") {
		return false
	}
	if ip := net.ParseIP(h); ip != nil && (ip.IsLoopback() || ip.IsPrivate() || ip.IsUnspecified()) {
		return false
	}
	return true
}

func resolve[S any](ctx context.Context, log *slog.Logger, channel, market string, names []string,
	build func(context.Context, string) (S, error)) []S {
	out := make([]S, 0, len(names))
	for _, n := range names {
		s, err := build(ctx, n)
		if err != nil {
			log.WarnContext(ctx, "provider not available; skipped", "channel", channel, "market", market, "provider", n, "error", err)
			continue
		}
		out = append(out, s)
	}
	if len(out) == 0 {
		log.WarnContext(ctx, "route has no usable provider", "channel", channel, "market", market)
	}
	return out
}
