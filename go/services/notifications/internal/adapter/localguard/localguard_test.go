package localguard_test

import (
	"context"
	"errors"
	"testing"

	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/adapter/localguard"
	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/port"
)

type fakeEmail struct {
	name   string
	attach bool
	err    error
	sent   []domain.EmailMessage
}

func (f *fakeEmail) Name() string              { return f.name }
func (f *fakeEmail) SupportsAttachments() bool { return f.attach }
func (f *fakeEmail) Send(_ context.Context, m domain.EmailMessage) (domain.Receipt, error) {
	f.sent = append(f.sent, m)
	return domain.Receipt{Provider: f.name}, f.err
}

type fakeSMS struct {
	sent []domain.SmsMessage
}

func (f *fakeSMS) Name() string { return "twilio" }
func (f *fakeSMS) Send(_ context.Context, m domain.SmsMessage) (domain.Receipt, error) {
	f.sent = append(f.sent, m)
	return domain.Receipt{Provider: "twilio"}, nil
}

func TestReservedEmail(t *testing.T) {
	for addr, want := range map[string]bool{
		"customer.houston@afrotransact.test":       true,
		"Local Dev <local-dev@afrotransact.local>": true,
		"someone@example.com":                      true,
		"a@b.invalid":                              true,
		"you@gmail.com":                            false,
		"ops@afrotransact.com":                     false,
		"Name <person@company.co>":                 false,
		"test@contest.com":                         false,
	} {
		if got := localguard.ReservedEmail(addr); got != want {
			t.Errorf("ReservedEmail(%q) = %v, want %v", addr, got, want)
		}
	}
}

func TestFictionalPhone(t *testing.T) {
	for n, want := range map[string]bool{
		"+17135550199": true, "+15555550100": true, "+17135550100": true,
		"+17135550200": false, "+14155552671": false, "+447700900123": false,
	} {
		if got := localguard.FictionalPhone(n); got != want {
			t.Errorf("FictionalPhone(%q) = %v, want %v", n, got, want)
		}
	}
}

func TestEmailRoutesTestUsersToMailpit(t *testing.T) {
	real := &fakeEmail{name: "resend", attach: true}
	mailpit := &fakeEmail{name: "smtp"}
	s := localguard.NewEmail(real, mailpit)
	if s.Name() != "resend" {
		t.Fatalf("name %q: the guard must keep the real provider's name for routing and breakers", s.Name())
	}
	if c, ok := s.(port.AttachmentCapable); !ok || !c.SupportsAttachments() {
		t.Fatal("attachment capability must be the real provider's")
	}
	rec, err := s.Send(context.Background(), domain.EmailMessage{To: "customer.houston@afrotransact.test"})
	if err != nil || rec.Provider != "smtp" || len(mailpit.sent) != 1 || len(real.sent) != 0 {
		t.Fatalf("test user: %+v %v real=%d mailpit=%d", rec, err, len(real.sent), len(mailpit.sent))
	}
	rec, err = s.Send(context.Background(), domain.EmailMessage{To: "you@gmail.com"})
	if err != nil || rec.Provider != "resend" || len(real.sent) != 1 {
		t.Fatalf("real address: %+v %v", rec, err)
	}
}

func TestEmailKeepsProviderErrors(t *testing.T) {
	perm := domain.PermanentError("resend", 403, "validation_error", errors.New("domain not verified"))
	s := localguard.NewEmail(&fakeEmail{name: "resend", err: perm}, &fakeEmail{name: "smtp"})
	_, err := s.Send(context.Background(), domain.EmailMessage{To: "you@gmail.com"})
	var pe *domain.ProviderError
	if !errors.As(err, &pe) || pe.Retryable {
		t.Fatalf("the router needs the provider error to decide on failover: %v", err)
	}
}

func TestSMSFictionalNumbersBecomeMailpitEmails(t *testing.T) {
	real := &fakeSMS{}
	mailpit := &fakeEmail{name: "smtp"}
	s := localguard.NewSMS(real, mailpit, "AfroTransact <no-reply@afrotransact.local>")
	if _, err := s.Send(context.Background(), domain.SmsMessage{To: "+17135550101", Body: "Your code is 123456"}); err != nil {
		t.Fatal(err)
	}
	if len(real.sent) != 0 || len(mailpit.sent) != 1 {
		t.Fatalf("fictional number: real=%d mailpit=%d", len(real.sent), len(mailpit.sent))
	}
	m := mailpit.sent[0]
	if m.Subject != "SMS to +17135550101" || m.Text != "Your code is 123456" || m.Headers["X-Tags"] != "sms" {
		t.Fatalf("mailpit SMS: %+v", m)
	}
	if _, err := s.Send(context.Background(), domain.SmsMessage{To: "+14155552671", Body: "hi"}); err != nil {
		t.Fatal(err)
	}
	if len(real.sent) != 1 {
		t.Fatal("a real number must go to the real provider")
	}
}
