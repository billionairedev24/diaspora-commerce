package domain

import (
	"fmt"
	"strings"
	"time"
)

// SmsConsent is a TCPA consent record for a phone number.
type SmsConsent struct {
	Phone       string     `json:"phone"`
	RecipientID string     `json:"recipientId,omitempty"`
	OptedIn     bool       `json:"optedIn"`
	Source      string     `json:"source"`      // e.g. web-signup, mobile-signup, sms-START
	TextVersion string     `json:"textVersion"` // version of the disclosure the user agreed to
	ConsentedAt time.Time  `json:"consentedAt"`
	RevokedAt   *time.Time `json:"revokedAt,omitempty"`
	RevokedVia  string     `json:"revokedVia,omitempty"` // e.g. sms-STOP
}

// Preference is a recipient's choice for one channel and category.
type Preference struct {
	Channel  Channel  `json:"channel"`
	Category Category `json:"category"`
	Enabled  bool     `json:"enabled"`
}

// QuietHours is a daily window (recipient local time) in which non-transactional messages are held.
type QuietHours struct {
	Start time.Duration // offset from local midnight, e.g. 21h
	End   time.Duration // e.g. 8h; End < Start means the window spans midnight
}

// DefaultQuietHours is 21:00–08:00.
var DefaultQuietHours = QuietHours{Start: 21 * time.Hour, End: 8 * time.Hour}

// ParseQuietHours parses "21:00" and "08:00".
func ParseQuietHours(start, end string) (QuietHours, error) {
	s, err := parseClock(start)
	if err != nil {
		return QuietHours{}, err
	}
	e, err := parseClock(end)
	if err != nil {
		return QuietHours{}, err
	}
	return QuietHours{Start: s, End: e}, nil
}

func parseClock(v string) (time.Duration, error) {
	t, err := time.Parse("15:04", strings.TrimSpace(v))
	if err != nil {
		return 0, fmt.Errorf("quiet hours %q: %w", v, err)
	}
	return time.Duration(t.Hour())*time.Hour + time.Duration(t.Minute())*time.Minute, nil
}

// Until returns, for an instant inside the window, the instant the window ends; ok is false when now is
// outside the window. now must already be in the recipient's location.
func (q QuietHours) Until(now time.Time) (time.Time, bool) {
	if q.Start == q.End {
		return time.Time{}, false
	}
	midnight := time.Date(now.Year(), now.Month(), now.Day(), 0, 0, 0, 0, now.Location())
	offset := now.Sub(midnight)
	at := func(day time.Time, d time.Duration) time.Time {
		return time.Date(day.Year(), day.Month(), day.Day(), int(d/time.Hour), int(d%time.Hour/time.Minute), 0, 0, day.Location())
	}
	if q.Start < q.End { // same-day window, e.g. 13:00–15:00
		if offset >= q.Start && offset < q.End {
			return at(midnight, q.End), true
		}
		return time.Time{}, false
	}
	switch {
	case offset >= q.Start: // evening part: ends tomorrow morning
		return at(midnight.AddDate(0, 0, 1), q.End), true
	case offset < q.End: // early-morning part: ends this morning
		return at(midnight, q.End), true
	default:
		return time.Time{}, false
	}
}

// Verdict is the outcome of a consent check.
type Verdict string

// Verdicts.
const (
	VerdictAllow Verdict = "allow"
	VerdictDeny  Verdict = "deny"
	VerdictDefer Verdict = "defer"
)

// Decision is a consent verdict with its reason and, for Defer, when to try again.
type Decision struct {
	Verdict Verdict
	Reason  string
	Until   time.Time
}

// ConsentFacts are everything the consent rules need about a recipient, loaded by the ConsentChecker adapter.
type ConsentFacts struct {
	Channel     Channel
	Category    Category
	Suppressed  bool   // address on the suppression list (bounce, complaint)
	SuppressWhy string // reason recorded on the suppression list
	SmsConsent  *SmsConsent
	Preference  *Preference // explicit choice for (channel, category), nil when never set
	Location    *time.Location
	Now         time.Time
	Quiet       QuietHours
}

// Decide applies the platform's notification rules:
//
//  1. Security messages (OTP, verification, security alerts) always send.
//  2. Suppressed addresses (hard bounce, complaint) never receive anything else.
//  3. SMS needs a TCPA consent record that has not been revoked by STOP.
//  4. Marketing needs an explicit opt-in preference for the channel; other categories send unless opted out.
//  5. Reminders and marketing are held during quiet hours (recipient local time) and released when they end.
func Decide(f ConsentFacts) Decision {
	if f.Category == CategorySecurity {
		return Decision{Verdict: VerdictAllow, Reason: "security messages always send"}
	}
	if f.Suppressed {
		return Decision{Verdict: VerdictDeny, Reason: "address suppressed: " + f.SuppressWhy}
	}
	if f.Channel == ChannelSMS {
		if f.SmsConsent == nil {
			return Decision{Verdict: VerdictDeny, Reason: "no SMS consent on record"}
		}
		if !f.SmsConsent.OptedIn {
			return Decision{Verdict: VerdictDeny, Reason: "recipient opted out of SMS (" + f.SmsConsent.RevokedVia + ")"}
		}
	}
	switch {
	case f.Category == CategoryMarketing && (f.Preference == nil || !f.Preference.Enabled):
		return Decision{Verdict: VerdictDeny, Reason: "no marketing opt-in for " + string(f.Channel)}
	case f.Preference != nil && !f.Preference.Enabled:
		return Decision{Verdict: VerdictDeny, Reason: "recipient turned off " + string(f.Category) + " on " + string(f.Channel)}
	}
	if f.Category == CategoryReminder || f.Category == CategoryMarketing {
		loc := f.Location
		if loc == nil {
			loc = time.UTC
		}
		if until, quiet := f.Quiet.Until(f.Now.In(loc)); quiet {
			return Decision{Verdict: VerdictDefer, Reason: "quiet hours", Until: until}
		}
	}
	return Decision{Verdict: VerdictAllow}
}
