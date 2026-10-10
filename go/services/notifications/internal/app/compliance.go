package app

import (
	"context"
	"fmt"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/port"
)

// ComplianceTexts are the auto-replies required by carriers and the 10DLC campaign registration.
type ComplianceTexts struct {
	Help        string
	Stop        string
	Start       string
	TextVersion string // consent disclosure version recorded for START opt-ins
}

// SmsCompliance handles inbound SMS keywords: STOP/END/CANCEL/UNSUBSCRIBE/QUIT opt out, START opts back in,
// HELP gets the program help text.
type SmsCompliance interface {
	// HandleInbound processes an inbound message and returns the reply to send (empty for none).
	HandleInbound(ctx context.Context, from, body string) (string, error)
}

type smsComplianceImpl struct {
	consent port.ConsentStore
	texts   ComplianceTexts
	now     func() time.Time
}

var _ SmsCompliance = (*smsComplianceImpl)(nil)

// NewSmsCompliance returns the keyword handler.
func NewSmsCompliance(consent port.ConsentStore, texts ComplianceTexts) SmsCompliance {
	return &smsComplianceImpl{consent: consent, texts: texts, now: time.Now}
}

func (s *smsComplianceImpl) HandleInbound(ctx context.Context, from, body string) (string, error) {
	phone := domain.NormalizePhone(from)
	if phone == "" {
		return "", nil
	}
	now := s.now().UTC()
	switch domain.ClassifyKeyword(body) {
	case domain.KeywordStop:
		if err := s.consent.OptOutSms(ctx, phone, "sms-"+keywordWord(body), now); err != nil {
			return "", fmt.Errorf("opt out: %w", err)
		}
		return s.texts.Stop, nil
	case domain.KeywordStart:
		if err := s.consent.OptInSms(ctx, phone, "sms-START", s.texts.TextVersion, now); err != nil {
			return "", fmt.Errorf("opt in: %w", err)
		}
		return s.texts.Start, nil
	case domain.KeywordHelp:
		return s.texts.Help, nil
	default:
		return "", nil
	}
}

func keywordWord(body string) string {
	w := []rune{}
	for _, r := range body {
		if r >= 'a' && r <= 'z' {
			r -= 'a' - 'A'
		}
		if r >= 'A' && r <= 'Z' {
			w = append(w, r)
		}
	}
	return string(w)
}
