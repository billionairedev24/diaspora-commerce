package main

import (
	"context"
	"fmt"
	"log/slog"
	"net/http"

	"github.com/aws/aws-sdk-go-v2/aws"
	awsconfig "github.com/aws/aws-sdk-go-v2/config"
	"github.com/aws/aws-sdk-go-v2/service/pinpointsmsvoicev2"
	"github.com/aws/aws-sdk-go-v2/service/sesv2"

	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/adapter/apns"
	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/adapter/awssms"
	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/adapter/fcm"
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
		if f.cfg.PublicBaseURL != "" {
			callback = f.cfg.PublicBaseURL + "/v1/webhooks/twilio/status"
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
	rc := f.cfg.Routing
	for market, names := range rc.Email {
		r.Email[market] = resolve(ctx, f.log, "email", market, names, f.emailSender)
	}
	for market, names := range rc.SMS {
		r.SMS[market] = resolve(ctx, f.log, "sms", market, names, f.smsSender)
	}
	for market, names := range rc.Push {
		r.Push[market] = resolve(ctx, f.log, "push", market, names, f.pushSender)
	}
	for market, names := range rc.Chat {
		r.Chat[market] = resolve(ctx, f.log, "chat", market, names, f.chatSender)
	}
	return r
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
