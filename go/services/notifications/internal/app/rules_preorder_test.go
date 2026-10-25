package app_test

import (
	"context"
	"log/slog"
	"net/url"
	"strings"
	"testing"

	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/app"
	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/port"
)

// TestEverySeededSmsFitsOneGsm7Segment renders every seeded SMS with its sample data: GSM-7 (no "·", en dashes or
// curly quotes) and at most 160 characters, so each text is one segment.
func TestEverySeededSmsFitsOneGsm7Segment(t *testing.T) {
	samples, err := app.NewTemplateSamples(context.Background(), sampleLinks)
	if err != nil {
		t.Fatal(err)
	}
	renderer := app.NewRenderer()
	for _, tpl := range seededTemplates(t) {
		if tpl.Channel != domain.ChannelSMS {
			continue
		}
		s, _ := samples.Sample(tpl.Key)
		r, err := renderer.Render(tpl, s.Data)
		if err != nil {
			t.Errorf("%s: %v", tpl.Key, err)
			continue
		}
		if seg := domain.CountSmsSegments(r.Text); seg.Encoding != domain.EncodingGSM7 || seg.Segments > maxSmsSegments(tpl.Key) {
			t.Errorf("%s: %s, %d units, %d segments: %q", tpl.Key, seg.Encoding, seg.Units, seg.Segments, r.Text)
		}
	}
}

// fakeAudience pages a window's audience from fixed members per channel and segment.
type fakeAudience struct {
	members map[string][]port.AudienceMember // "<channel>/<segment>"
	calls   []string
}

func (f *fakeAudience) Audience(_ context.Context, windowID string, ch domain.Channel, segment, after string, _ int) (port.AudiencePage, error) {
	f.calls = append(f.calls, windowID+"/"+string(ch)+"/"+segment+"/"+after)
	all := f.members[string(ch)+"/"+segment]
	if after == "" && len(all) > 1 {
		return port.AudiencePage{Members: all[:1], Next: "p2"}, nil
	}
	if after == "p2" {
		return port.AudiencePage{Members: all[1:]}, nil
	}
	return port.AudiencePage{Members: all}, nil
}

// recordingNotifier keeps the requests an event produces.
type recordingNotifier struct{ reqs []domain.Request }

func (r *recordingNotifier) Submit(_ context.Context, req domain.Request) ([]domain.Notification, error) {
	r.reqs = append(r.reqs, req)
	return nil, nil
}

func (r *recordingNotifier) SubmitForEvent(_ context.Context, _, _ string, reqs []domain.Request) (bool, error) {
	r.reqs = append(r.reqs, reqs...)
	return true, nil
}

// stubResolver answers every user like identity would for a verified shopper.
type stubResolver struct{}

func (stubResolver) Contact(_ context.Context, id string) (port.Contact, error) {
	return port.Contact{UserID: id, FirstName: "Ada", Email: id + "@example.com", Phone: "+15550001111",
		PushTokens: []domain.PushToken{{Token: "tok-" + id, Platform: domain.PushFCM}}, Market: "us"}, nil
}

func (stubResolver) OrgOwners(context.Context, string) ([]string, error) { return nil, nil }

func TestPreorderCommsFanOutToTheAudienceWithTrackedLinks(t *testing.T) {
	n := &recordingNotifier{}
	aud := &fakeAudience{members: map[string][]port.AudienceMember{
		"email/SUBSCRIBERS": {{UserID: "cust-1", UnsubscribeURL: "https://u/1"}, {Email: "anon@example.com", UnsubscribeURL: "https://u/2"}},
		"sms/SUBSCRIBERS":   {{UserID: "cust-1"}, {Phone: "+15550009999"}},
		"push/SUBSCRIBERS":  {{UserID: "cust-1"}},
		"email/WAITLIST":    {{Email: "wait@example.com", UnsubscribeURL: "https://u/w"}},
	}}
	ing := app.NewIngest(n, app.IngestConfig{Resolver: stubResolver{}, Links: sampleLinks, Audience: aud}, slog.New(slog.DiscardHandler))
	fixtures := app.SampleEvents(sampleLinks)["preorder.comms.due"]
	handle(t, ing, "preorder.comms.due", fixtures[0]) // ANNOUNCE with the window's SMS copy

	byKey := map[string][]domain.Request{}
	for _, r := range n.reqs {
		byKey[r.TemplateKey] = append(byKey[r.TemplateKey], r)
	}
	// announce: 2 email + 2 sms + 1 push targets, one channel each; waitlist: 1 email.
	if len(byKey["preorder.announce"]) != 5 || len(byKey["preorder.waitlist.opened"]) != 1 {
		t.Fatalf("requests = %v", byKey)
	}
	for _, r := range byKey["preorder.announce"] {
		if len(r.Channels) != 1 {
			t.Fatalf("one channel per audience target: %+v", r)
		}
		email, _ := r.Data["emailUrl"].(string)
		u, err := url.Parse(email)
		if err != nil || u.Query().Get("src") != "email" || u.Query().Get("po") != "LBK-7" || u.Query().Get("cr") != "announce" {
			t.Fatalf("emailUrl = %q", email)
		}
		sms, _ := r.Data["smsCopy"].(string)
		if !strings.Contains(sms, "https://afrotransact.com/pre-orders?src=sms&po=LBK-7&cr=announce") || strings.Contains(sms, "{link}") {
			t.Fatalf("smsCopy = %q", sms)
		}
		if r.Data["doorFee"] != "$7.99" || r.Data["pickupFee"] != "free" || r.Data["pickupWindow"] != "10am-2pm" {
			t.Fatalf("fees = %v %v %v", r.Data["doorFee"], r.Data["pickupFee"], r.Data["pickupWindow"])
		}
	}
	if r := byKey["preorder.announce"][1]; r.Recipient.Email != "anon@example.com" || r.Data["unsubscribeUrl"] != "https://u/2" {
		t.Fatalf("email-only member = %+v", r)
	}
	if r := byKey["preorder.waitlist.opened"][0]; r.Recipient.Email != "wait@example.com" || !strings.Contains(r.Data["emailUrl"].(string), "cr=waitlist") {
		t.Fatalf("waitlist = %+v", r)
	}
	// CLOSED goes to the window's buyers, not the audience.
	n.reqs, aud.calls = nil, nil
	handle(t, ing, "preorder.comms.due", fixtures[4])
	if len(n.reqs) != 1 || n.reqs[0].TemplateKey != "preorder.closed" || n.reqs[0].Recipient.ID != "cust-1" || len(aud.calls) != 0 {
		t.Fatalf("closed = %+v (audience calls %v)", n.reqs, aud.calls)
	}
}

func TestPreorderOrderPlacedAndLatePayment(t *testing.T) {
	n := &recordingNotifier{}
	ing := app.NewIngest(n, app.IngestConfig{Resolver: stubResolver{}, Links: sampleLinks}, slog.New(slog.DiscardHandler))
	evs := app.SampleEvents(sampleLinks)
	handle(t, ing, "orders.order.placed", evs["orders.order.placed"][1]) // pre-order package + items that ship now
	keys := []string{}
	for _, r := range n.reqs {
		keys = append(keys, r.TemplateKey)
		if r.TemplateKey == "preorder.confirmed" {
			d := r.Data
			if d["name"] != "Lubbock Pre-order #7" || d["preorderNumber"] != "LBK-1042" || d["doorFee"] != "$7.99" || d["total"] != "$51.96" ||
				d["nowNote"] != "1 other item ships now with tracking." || d["cancelUntil"] != "Wed, Oct 21 11:59 PM" {
				t.Fatalf("confirmed data = %v", d)
			}
		}
	}
	if strings.Join(keys, ",") != "order.confirmed,preorder.confirmed" {
		t.Fatalf("mixed cart sends %v", keys)
	}
	// A pre-order-only order gets only "You're in".
	n.reqs = nil
	only := evs["orders.order.placed"][1]
	only["shipments"] = only["shipments"].([]any)[:1]
	handle(t, ing, "orders.order.placed", only)
	if len(n.reqs) != 1 || n.reqs[0].TemplateKey != "preorder.confirmed" {
		t.Fatalf("pre-order only = %+v", n.reqs)
	}
	// Late add with a pay-now link → SMS with the link to our own pay page; saved card → nothing here.
	n.reqs = nil
	late := evs["preorder.late_order.added"][0]
	handle(t, ing, "preorder.late_order.added", late)
	if len(n.reqs) != 1 || n.reqs[0].Recipient.Phone != "+15550001111" ||
		n.reqs[0].Data["paymentUrl"] != "http://localhost:3000/orders/AT-2026-PAY001/pay?token=1.vbkms0.Fh2nQ8l5XyZr0aB3cD4eFg" {
		t.Fatalf("late payment = %+v", n.reqs)
	}
	n.reqs = nil
	late["payment"] = "SAVED_CARD"
	handle(t, ing, "preorder.late_order.added", late)
	if len(n.reqs) != 0 {
		t.Fatalf("saved card = %+v", n.reqs)
	}
	// Missed delivery: refund and new-date links carry the attribution.
	n.reqs = nil
	handle(t, ing, "orders.preorder.delivery_missed", evs["orders.preorder.delivery_missed"][0])
	if len(n.reqs) != 1 || !strings.Contains(n.reqs[0].Data["refundUrl"].(string), "choice=refund&src=email&po=LBK-7&cr=missed") ||
		n.reqs[0].Data["newDateDisplay"] != "Wed, Oct 28" {
		t.Fatalf("missed = %+v", n.reqs)
	}
}

func TestBookingStartsAtIsGsm7(t *testing.T) {
	n := &recordingNotifier{}
	ing := app.NewIngest(n, app.IngestConfig{Resolver: stubResolver{}, Links: sampleLinks}, slog.New(slog.DiscardHandler))
	b := app.SampleEvents(sampleLinks)["booking.confirmed"][0]
	b["startsAt"] = "Sat, Oct 17 · 10:00 AM"
	handle(t, ing, "booking.confirmed", b)
	if len(n.reqs) != 1 || n.reqs[0].Data["startsAt"] != "Sat, Oct 17 at 10:00 AM" {
		t.Fatalf("booking = %+v", n.reqs)
	}
}
