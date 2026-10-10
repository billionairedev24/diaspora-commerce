package domain_test

import (
	"testing"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/domain"
)

func mustLoc(t *testing.T, name string) *time.Location {
	t.Helper()
	l, err := time.LoadLocation(name)
	if err != nil {
		t.Fatal(err)
	}
	return l
}

func optedIn() *domain.SmsConsent {
	return &domain.SmsConsent{Phone: "+15551234567", OptedIn: true, Source: "web-signup", TextVersion: "v1"}
}

func TestDecide(t *testing.T) {
	ny := mustLoc(t, "America/New_York")
	// 02:30 UTC on 15 Jan is 21:30 in New York (quiet) and 11:30 in Tokyo (not quiet).
	night := time.Date(2026, 1, 15, 2, 30, 0, 0, time.UTC)
	noon := time.Date(2026, 1, 15, 17, 0, 0, 0, time.UTC) // 12:00 New York
	on, off := &domain.Preference{Enabled: true}, &domain.Preference{Enabled: false}
	stopped := &domain.SmsConsent{Phone: "+15551234567", OptedIn: false, RevokedVia: "sms-STOP"}

	cases := []struct {
		name string
		f    domain.ConsentFacts
		want domain.Verdict
	}{
		{"otp always sends, even suppressed, stopped, at night",
			domain.ConsentFacts{Channel: domain.ChannelSMS, Category: domain.CategorySecurity, Suppressed: true, SmsConsent: stopped, Location: ny, Now: night}, domain.VerdictAllow},
		{"suppressed email denied", domain.ConsentFacts{Channel: domain.ChannelEmail, Category: domain.CategoryTransactional, Suppressed: true, Now: noon}, domain.VerdictDeny},
		{"sms without consent denied", domain.ConsentFacts{Channel: domain.ChannelSMS, Category: domain.CategoryTransactional, Now: noon}, domain.VerdictDeny},
		{"sms after STOP denied", domain.ConsentFacts{Channel: domain.ChannelSMS, Category: domain.CategoryTransactional, SmsConsent: stopped, Now: noon}, domain.VerdictDeny},
		{"sms with consent allowed", domain.ConsentFacts{Channel: domain.ChannelSMS, Category: domain.CategoryTransactional, SmsConsent: optedIn(), Now: noon}, domain.VerdictAllow},
		{"marketing without opt-in denied", domain.ConsentFacts{Channel: domain.ChannelEmail, Category: domain.CategoryMarketing, Location: ny, Now: noon}, domain.VerdictDeny},
		{"marketing with opt-in allowed by day", domain.ConsentFacts{Channel: domain.ChannelEmail, Category: domain.CategoryMarketing, Preference: on, Location: ny, Now: noon}, domain.VerdictAllow},
		{"transactional off by preference", domain.ConsentFacts{Channel: domain.ChannelPush, Category: domain.CategoryTransactional, Preference: off, Now: noon}, domain.VerdictDeny},
		{"transactional ignores quiet hours", domain.ConsentFacts{Channel: domain.ChannelEmail, Category: domain.CategoryTransactional, Location: ny, Now: night}, domain.VerdictAllow},
		{"reminder deferred in recipient's quiet hours", domain.ConsentFacts{Channel: domain.ChannelEmail, Category: domain.CategoryReminder, Location: ny, Now: night}, domain.VerdictDefer},
		{"same instant is daytime in Tokyo", domain.ConsentFacts{Channel: domain.ChannelEmail, Category: domain.CategoryReminder, Location: mustLoc(t, "Asia/Tokyo"), Now: night}, domain.VerdictAllow},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			tc.f.Quiet = domain.DefaultQuietHours
			got := domain.Decide(tc.f)
			if got.Verdict != tc.want {
				t.Fatalf("want %s, got %s (%s)", tc.want, got.Verdict, got.Reason)
			}
		})
	}

	d := domain.Decide(domain.ConsentFacts{Channel: domain.ChannelEmail, Category: domain.CategoryReminder, Location: ny, Now: night, Quiet: domain.DefaultQuietHours})
	want := time.Date(2026, 1, 15, 8, 0, 0, 0, ny)
	if !d.Until.Equal(want) {
		t.Fatalf("deferred until %s, want %s", d.Until, want)
	}
}

func TestQuietHoursUntil(t *testing.T) {
	q, err := domain.ParseQuietHours("21:00", "08:00")
	if err != nil {
		t.Fatal(err)
	}
	la := mustLoc(t, "America/Los_Angeles")
	at := func(h, m int) time.Time { return time.Date(2026, 3, 7, h, m, 0, 0, la) }
	cases := []struct {
		now   time.Time
		quiet bool
		until time.Time
	}{
		{at(20, 59), false, time.Time{}},
		{at(21, 0), true, time.Date(2026, 3, 8, 8, 0, 0, 0, la)}, // crosses the DST change on 8 March
		{at(23, 59), true, time.Date(2026, 3, 8, 8, 0, 0, 0, la)},
		{at(0, 0), true, at(8, 0)},
		{at(7, 59), true, at(8, 0)},
		{at(8, 0), false, time.Time{}},
	}
	for _, tc := range cases {
		until, quiet := q.Until(tc.now)
		if quiet != tc.quiet || !until.Equal(tc.until) {
			t.Errorf("%s: got (%v, %s), want (%v, %s)", tc.now.Format("15:04"), quiet, until, tc.quiet, tc.until)
		}
	}
	day, _ := domain.ParseQuietHours("13:00", "15:00")
	if _, quiet := day.Until(at(14, 0)); !quiet {
		t.Error("same-day window")
	}
	if _, err := domain.ParseQuietHours("9pm", "08:00"); err == nil {
		t.Error("invalid clock accepted")
	}
}

func TestClassifyKeyword(t *testing.T) {
	cases := map[string]domain.Keyword{
		"STOP": domain.KeywordStop, " stop ": domain.KeywordStop, "End": domain.KeywordStop, "cancel": domain.KeywordStop,
		"UNSUBSCRIBE": domain.KeywordStop, "quit.": domain.KeywordStop, "START": domain.KeywordStart, "unstop": domain.KeywordStart,
		"help": domain.KeywordHelp, "INFO": domain.KeywordHelp, "please stop texting": domain.KeywordNone, "": domain.KeywordNone,
		"thanks": domain.KeywordNone,
	}
	for in, want := range cases {
		if got := domain.ClassifyKeyword(in); got != want {
			t.Errorf("%q: got %q want %q", in, got, want)
		}
	}
}
