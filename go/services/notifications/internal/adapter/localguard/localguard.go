// Package localguard keeps the local stack's made-up recipients away from real providers. When a developer puts real
// Resend or Twilio credentials in .local/secrets.env (routing.preferConfigured, config/local.yaml), the seeded test
// users (@afrotransact.test) and their fictional phone numbers (+1 NXX 555-01xx) must still land in Mailpit: a real
// provider would bounce them (hurting the sending domain's reputation) or reject them as a permanent error, which
// stops the failover chain. Real addresses and numbers go to the real provider.
package localguard

import (
	"context"
	"fmt"
	"regexp"
	"strings"

	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/port"
)

// reservedSuffixes are domains that never receive mail (RFC 2606, RFC 6761, mDNS .local).
var reservedSuffixes = []string{".test", ".example", ".invalid", ".localhost", ".local",
	"@example.com", "@example.net", "@example.org", "@localhost"}

// fictionalNumber matches the North American numbers reserved for fiction: NXX-555-0100 to 0199.
var fictionalNumber = regexp.MustCompile(`^\+1\d{3}55501\d{2}$`)

// ReservedEmail reports whether an address belongs to a domain that never receives mail.
func ReservedEmail(addr string) bool {
	a := strings.ToLower(strings.TrimSpace(addr))
	if i := strings.LastIndex(a, "<"); i >= 0 { // "Name <user@host>"
		a = strings.TrimSuffix(a[i+1:], ">")
	}
	for _, s := range reservedSuffixes {
		if strings.HasSuffix(a, s) {
			return true
		}
	}
	return false
}

// FictionalPhone reports whether an E.164 number is in the range reserved for fiction (555-01xx).
func FictionalPhone(e164 string) bool {
	return fictionalNumber.MatchString(strings.TrimSpace(e164))
}

type emailImpl struct {
	real, mailpit port.EmailSender
}

type smsImpl struct {
	real    port.SmsSender
	mailpit port.EmailSender
	from    string
}

var (
	_ port.EmailSender       = (*emailImpl)(nil)
	_ port.AttachmentCapable = (*emailImpl)(nil)
	_ port.SmsSender         = (*smsImpl)(nil)
)

// NewEmail returns an EmailSender that sends to real through the real provider and to reserved domains through
// mailpit (the smtp sender). It keeps the real provider's name, so routing, breakers and logs are unchanged.
func NewEmail(real, mailpit port.EmailSender) port.EmailSender {
	return &emailImpl{real: real, mailpit: mailpit}
}

func (s *emailImpl) Name() string { return s.real.Name() }

// SupportsAttachments is the real provider's capability (the router strips attachments otherwise).
func (s *emailImpl) SupportsAttachments() bool {
	c, ok := s.real.(port.AttachmentCapable)
	return ok && c.SupportsAttachments()
}

func (s *emailImpl) Send(ctx context.Context, msg domain.EmailMessage) (domain.Receipt, error) {
	target := s.real
	if ReservedEmail(msg.To) {
		target = s.mailpit
	}
	rec, err := target.Send(ctx, msg)
	if err != nil {
		return rec, fmt.Errorf("%s: %w", target.Name(), err)
	}
	return rec, nil
}

// NewSMS returns an SmsSender that texts real numbers through the real provider and turns texts to fictional
// numbers (the test users' 555-01xx phones) into an email in Mailpit, "SMS to <number>", from the given address.
func NewSMS(real port.SmsSender, mailpit port.EmailSender, from string) port.SmsSender {
	return &smsImpl{real: real, mailpit: mailpit, from: from}
}

func (s *smsImpl) Name() string { return s.real.Name() }

func (s *smsImpl) Send(ctx context.Context, msg domain.SmsMessage) (domain.Receipt, error) {
	if !FictionalPhone(msg.To) {
		rec, err := s.real.Send(ctx, msg)
		if err != nil {
			return rec, fmt.Errorf("%s: %w", s.real.Name(), err)
		}
		return rec, nil
	}
	digits := strings.TrimPrefix(msg.To, "+")
	rec, err := s.mailpit.Send(ctx, domain.EmailMessage{NotificationID: msg.NotificationID, From: s.from,
		To: "sms-" + digits + "@sms.afrotransact.test", Subject: "SMS to " + msg.To, Text: msg.Body,
		Headers: map[string]string{"X-Tags": "sms"}})
	if err != nil {
		return rec, fmt.Errorf("mailpit sms: %w", err)
	}
	return rec, nil
}
