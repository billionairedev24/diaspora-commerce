package app_test

import (
	"context"
	"log/slog"
	"maps"
	"net/url"
	"strings"
	"testing"

	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/app"
	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/port"
)

// fakeLocations pages a location's run-alert subscribers from fixed members per channel.
type fakeLocations struct {
	members map[domain.Channel][]port.AudienceMember
	calls   []string
}

func (f *fakeLocations) LocationAudience(_ context.Context, locationID string, ch domain.Channel, after string, _ int) (port.AudiencePage, error) {
	f.calls = append(f.calls, locationID+"/"+string(ch)+"/"+after)
	all := f.members[ch]
	if after == "" && len(all) > 1 {
		return port.AudiencePage{Members: all[:1], Next: "p2"}, nil
	}
	if after == "p2" {
		return port.AudiencePage{Members: all[1:]}, nil
	}
	return port.AudiencePage{Members: all}, nil
}

func runAudience() *fakeLocations {
	return &fakeLocations{members: map[domain.Channel][]port.AudienceMember{
		domain.ChannelEmail: {{UserID: "cust-1", UnsubscribeURL: "https://u/1"}, {Email: "anon@example.com", UnsubscribeURL: "https://u/2"}},
		domain.ChannelSMS:   {{UserID: "cust-1"}},
		domain.ChannelPush:  {{UserID: "cust-1"}},
		domain.ChannelChat:  {{UserID: "cust-1"}, {Email: "email-only@example.com"}},
	}}
}

func runIngest(n app.Notifier, aud port.LocationAudience, whatsApp bool) app.Ingest {
	rules := maps.Clone(app.DefaultEventRules)
	if whatsApp {
		maps.Copy(rules, app.RunRulesWithWhatsApp(true))
	}
	return app.NewIngest(n, app.IngestConfig{Resolver: stubResolver{}, Links: sampleLinks, Locations: aud, Rules: rules},
		slog.New(slog.DiscardHandler))
}

func channelsOf(reqs []domain.Request) []string {
	var out []string
	for _, r := range reqs {
		for _, c := range r.Channels {
			out = append(out, r.TemplateKey+"/"+string(c))
		}
	}
	return out
}

// Announcements go to the location's subscribers on each channel they consented to, once per member and channel, with
// the run link carrying its UTM per channel.
func TestRunOpenedFansOutToTheLocationsSubscribersWithAttributedLinks(t *testing.T) {
	n := &recordingNotifier{}
	aud := runAudience()
	handle(t, runIngest(n, aud, false), "delivery-runs.run.opened", app.SampleEvents(sampleLinks)["delivery-runs.run.opened"][0])

	got := channelsOf(n.reqs)
	want := []string{"run.opened/email", "run.opened/email", "run.opened/sms", "run.opened/push"}
	if strings.Join(got, ",") != strings.Join(want, ",") {
		t.Fatalf("channels = %v, want %v (no WhatsApp unless enabled)", got, want)
	}
	if len(aud.calls) != 4 || !strings.HasPrefix(aud.calls[0], "4b2f9d3a-0000-4000-8000-00000000000a/email/") {
		t.Fatalf("audience calls = %v", aud.calls) // email is paged twice, the others once
	}
	for _, r := range n.reqs {
		if len(r.Channels) != 1 {
			t.Fatalf("one channel per target: %+v", r)
		}
		var link string
		switch r.Channels[0] {
		case domain.ChannelEmail:
			link, _ = r.Data["emailUrl"].(string)
		case domain.ChannelSMS:
			link, _ = r.Data["smsUrl"].(string)
		case domain.ChannelPush:
			link, _ = r.Data["pushUrl"].(string)
		case domain.ChannelChat:
			t.Fatalf("WhatsApp is off: %+v", r)
		}
		u, err := url.Parse(link)
		if err != nil {
			t.Fatal(err)
		}
		ch := string(r.Channels[0])
		q := u.Query()
		if q.Get("utm_source") != ch || q.Get("utm_medium") != ch || q.Get("utm_campaign") != "delivery-run-bastrop-tx-20261024" ||
			q.Get("utm_content") != "opened" {
			t.Fatalf("%s link has no UTM attribution: %q", ch, link)
		}
	}
	email := n.reqs[1]
	if email.Recipient.Email != "anon@example.com" || email.Data["unsubscribeUrl"] != "https://u/2" {
		t.Fatalf("email-only subscriber = %+v", email)
	}
	if sms := n.reqs[2]; !strings.HasPrefix(sms.Data["smsUrl"].(string), "https://afrotransact.com/delivery-runs/bastrop-tx?") ||
		strings.Contains(sms.Data["smsUrl"].(string), "run=") {
		t.Fatalf("the SMS link is the short location link with the UTM: %v", sms.Data["smsUrl"])
	}
	d := n.reqs[0].Data
	if d["cutoffText"] != "Thu, Oct 22 at 8:00 PM CDT" || d["dropText"] != "Delivered to your door" ||
		d["minimumText"] != "4 of 10 orders so far" || d["firstName"] != "Ada" {
		t.Fatalf("display fields = %v", d)
	}
}

// WhatsApp only with the flag, only for members who consented on WhatsApp (delivery-runs returns just those), and
// only as the approved template seeded for the key.
func TestRunAnnouncementsReachWhatsAppOnlyWhenEnabledAndOnlyOptedInMembersWithAPhone(t *testing.T) {
	n := &recordingNotifier{}
	aud := runAudience()
	ing := runIngest(n, aud, true)
	handle(t, ing, "delivery-runs.run.opened", app.SampleEvents(sampleLinks)["delivery-runs.run.opened"][0])
	var chat []domain.Request
	for _, r := range n.reqs {
		if r.Channels[0] == domain.ChannelChat {
			chat = append(chat, r)
		}
	}
	// cust-1 has a phone in identity; the email-only member has no WhatsApp number to send to.
	if len(chat) != 1 || chat[0].Recipient.ID != "cust-1" || chat[0].Recipient.Phone != "+15550001111" ||
		chat[0].TemplateKey != "run.opened" {
		t.Fatalf("whatsapp = %+v", chat)
	}
	if !strings.Contains(chat[0].Data["whatsappUrl"].(string), "utm_source=whatsapp") {
		t.Fatalf("whatsapp link = %v", chat[0].Data["whatsappUrl"])
	}
	n.reqs = nil
	handle(t, ing, "delivery-runs.run.cutoff_reminder", app.SampleEvents(sampleLinks)["delivery-runs.run.cutoff_reminder"][0])
	got := strings.Join(channelsOf(n.reqs), ",")
	if !strings.Contains(got, "run.cutoff_reminder/chat") || !strings.Contains(got, "run.cutoff_reminder/sms") {
		t.Fatalf("reminder channels = %s", got)
	}
}

func TestRunAnnouncedGoesToEmailAndPushOnly(t *testing.T) {
	n := &recordingNotifier{}
	handle(t, runIngest(n, runAudience(), true), "delivery-runs.run.announced",
		app.SampleEvents(sampleLinks)["delivery-runs.run.announced"][0])
	for _, c := range channelsOf(n.reqs) {
		if c != "run.announced/email" && c != "run.announced/push" {
			t.Fatalf("announced reached %s", c)
		}
	}
	if len(n.reqs) != 3 {
		t.Fatalf("requests = %v", channelsOf(n.reqs))
	}
}

func TestRunWithoutALocationAudienceSourceSendsNoAnnouncements(t *testing.T) {
	n := &recordingNotifier{}
	handle(t, runIngest(n, nil, false), "delivery-runs.run.opened", app.SampleEvents(sampleLinks)["delivery-runs.run.opened"][0])
	if len(n.reqs) != 0 {
		t.Fatalf("requests = %+v", n.reqs)
	}
}

// Dispatch tells the customers on the run; at a pickup point each gets their own handover code.
func TestRunOutForDeliveryTellsTheCustomersWithTheirHandoverCode(t *testing.T) {
	n := &recordingNotifier{}
	ev := app.SampleEvents(sampleLinks)["delivery-runs.run.out_for_delivery"][0]
	ev["dropMode"] = "PICKUP_POINT"
	ev["pickupPoint"] = map[string]any{"name": "Bastrop Public Library", "line1": "1100 Church St", "city": "Bastrop", "hours": "Sat 2–5 PM"}
	handle(t, runIngest(n, runAudience(), false), "delivery-runs.run.out_for_delivery", ev)
	if len(n.reqs) != 1 {
		t.Fatalf("requests = %+v", n.reqs)
	}
	r := n.reqs[0]
	if r.TemplateKey != "run.out_for_delivery" || r.Recipient.ID != "cust-1" || r.Data["handoverCode"] != "482913" ||
		r.Data["dropText"] != "Pickup at Bastrop Public Library, 1100 Church St, Bastrop (Sat 2-5 PM)" {
		t.Fatalf("out for delivery = %+v", r)
	}
	if got := strings.Join(channelsOf(n.reqs), ","); got != "run.out_for_delivery/sms,run.out_for_delivery/push,run.out_for_delivery/email" {
		t.Fatalf("channels = %s", got)
	}
}

// A run handed over from a pre-order window is told by the window's own out-for-delivery message
// (preorder.comms.due OUT_FOR_DELIVERY), so the generic run message is not sent on top.
func TestRunOutForDeliverySkipsRunsThatBelongToAPreorderWindow(t *testing.T) {
	n := &recordingNotifier{}
	ev := app.SampleEvents(sampleLinks)["delivery-runs.run.out_for_delivery"][0]
	ev["reason"] = "PREORDER LBK-7"
	handle(t, runIngest(n, runAudience(), false), "delivery-runs.run.out_for_delivery", ev)
	if len(n.reqs) != 0 {
		t.Fatalf("a window's run is told by the window's message: %v", channelsOf(n.reqs))
	}
}

// A run handed over from a pre-order window is covered by the window's emails; a run cancelled with its window by the
// cancelled pre-order email; only runs staff scheduled get these.
func TestRunConfirmedAndCancelledSkipRunsThatBelongToAPreorderWindow(t *testing.T) {
	n := &recordingNotifier{}
	ing := runIngest(n, runAudience(), false)
	evs := app.SampleEvents(sampleLinks)

	confirmed := evs["delivery-runs.run.confirmed"][0]
	handle(t, ing, "delivery-runs.run.confirmed", confirmed)
	if len(n.reqs) != 1 || n.reqs[0].TemplateKey != "run.confirmed" {
		t.Fatalf("confirmed = %v", channelsOf(n.reqs))
	}
	n.reqs = nil
	confirmed["reason"] = "PREORDER LBK-7"
	handle(t, ing, "delivery-runs.run.confirmed", confirmed)
	if len(n.reqs) != 0 {
		t.Fatalf("a window's run is not confirmed twice: %v", channelsOf(n.reqs))
	}

	cancelled := evs["delivery-runs.run.cancelled"][0]
	handle(t, ing, "delivery-runs.run.cancelled", cancelled)
	if got := strings.Join(channelsOf(n.reqs), ","); got != "run.cancelled/email,run.cancelled/sms,run.cancelled/push" {
		t.Fatalf("cancelled channels = %s", got)
	}
	d := n.reqs[0].Data
	if note, _ := d["refundNote"].(string); !strings.Contains(note, "refunded in full, delivery fee included") {
		t.Fatalf("refund wording = %q", note)
	}
	if d["reasonText"] != "There weren't enough orders to make the trip." {
		t.Fatalf("reason = %v", d["reasonText"])
	}
	n.reqs = nil
	cancelled["reason"] = "Pre-order LBK-7 cancelled: seller stock fell through"
	handle(t, ing, "delivery-runs.run.cancelled", cancelled)
	if len(n.reqs) != 0 {
		t.Fatalf("a window's run is told by the cancelled pre-order email: %v", channelsOf(n.reqs))
	}
	n.reqs = nil
	cancelled["reason"] = "Storm warning"
	handle(t, ing, "delivery-runs.run.cancelled", cancelled)
	if len(n.reqs) == 0 || n.reqs[0].Data["reasonText"] != "" {
		t.Fatalf("a staff reason is not shown to customers: %+v", n.reqs)
	}
}

func TestRunSubscriptionEmails(t *testing.T) {
	n := &recordingNotifier{}
	ing := runIngest(n, runAudience(), false)
	evs := app.SampleEvents(sampleLinks)

	created := evs["delivery-runs.subscription.created"]
	handle(t, ing, "delivery-runs.subscription.created", created[0]) // email-only, pending: the double opt-in link
	if len(n.reqs) != 1 || n.reqs[0].TemplateKey != "run.subscription.confirm" || n.reqs[0].Recipient.Email != "neighbour@example.com" ||
		!strings.Contains(n.reqs[0].Data["confirmUrl"].(string), "/delivery-runs/subscriptions/confirm?token=") {
		t.Fatalf("confirm = %+v", n.reqs)
	}
	n.reqs = nil
	handle(t, ing, "delivery-runs.subscription.created", created[1]) // signed in, active at once: the welcome
	if len(n.reqs) != 1 || n.reqs[0].TemplateKey != "run.subscription.welcome" || n.reqs[0].Recipient.ID != "cust-1" ||
		n.reqs[0].Data["channelList"] != "email, text messages and app notifications" || n.reqs[0].Data["hasWhatsappCommunity"] != true {
		t.Fatalf("welcome = %+v", n.reqs)
	}
	n.reqs = nil
	handle(t, ing, "delivery-runs.subscription.confirmed", evs["delivery-runs.subscription.confirmed"][0])
	if len(n.reqs) != 1 || n.reqs[0].TemplateKey != "run.subscription.welcome" || n.reqs[0].Recipient.Email != "neighbour@example.com" {
		t.Fatalf("confirmed welcome = %+v", n.reqs)
	}
	// Nothing for a pending subscriber who has no confirm link, and nothing on cancel (delivery-runs stops listing them).
	n.reqs = nil
	created[0]["confirmUrl"] = nil
	handle(t, ing, "delivery-runs.subscription.created", created[0])
	handle(t, ing, "delivery-runs.subscription.cancelled", created[0])
	if len(n.reqs) != 0 {
		t.Fatalf("unexpected = %+v", n.reqs)
	}
}
