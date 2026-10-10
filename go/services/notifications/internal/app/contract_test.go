package app_test

import (
	"os"
	"path/filepath"
	"regexp"
	"slices"
	"sort"
	"strings"
	"testing"

	"gopkg.in/yaml.v3"

	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/app"
	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/domain"
)

// asyncAPI is the subset of contracts/asyncapi/events.yaml the contract test reads.
type asyncAPI struct {
	Channels map[string]struct {
		Address  string `yaml:"address"`
		Messages map[string]struct {
			Ref string `yaml:"$ref"`
		} `yaml:"messages"`
	} `yaml:"channels"`
	Components struct {
		Messages map[string]struct {
			Name string `yaml:"name"`
		} `yaml:"messages"`
	} `yaml:"components"`
}

// eventsByAddress maps each channel address to the event types (message names) published on it.
func eventsByAddress(t *testing.T) map[string][]string {
	t.Helper()
	raw, err := os.ReadFile(filepath.Join("..", "..", "..", "..", "..", "contracts", "asyncapi", "events.yaml"))
	if err != nil {
		t.Fatalf("read events.yaml: %v", err)
	}
	var doc asyncAPI
	if err := yaml.Unmarshal(raw, &doc); err != nil {
		t.Fatalf("parse events.yaml: %v", err)
	}
	out := map[string][]string{}
	for id, ch := range doc.Channels {
		for mid, m := range ch.Messages {
			ref := strings.TrimPrefix(m.Ref, "#/components/messages/")
			msg, ok := doc.Components.Messages[ref]
			if !ok || msg.Name == "" {
				t.Fatalf("channel %s message %s: unresolved $ref %q", id, mid, m.Ref)
			}
			if !slices.Contains(out[ch.Address], msg.Name) {
				out[ch.Address] = append(out[ch.Address], msg.Name)
			}
		}
	}
	return out
}

// TestRulesMatchAsyncAPIContract fails when a mapped event type or topic is missing from the merged AsyncAPI
// document, or when an event type is published on a different topic than the rule consumes.
func TestRulesMatchAsyncAPIContract(t *testing.T) {
	byAddr := eventsByAddress(t)
	// Guard against a vacuous pass: the real orders types are there, the old speculative ones are not.
	if orders := byAddr["at.order.events"]; !slices.Contains(orders, "orders.order.placed") || slices.Contains(orders, "order.placed") {
		t.Fatalf("unexpected at.order.events messages: %v", orders)
	}
	for name, addr := range app.DefaultTopics {
		if _, ok := byAddr[addr]; !ok {
			t.Errorf("topic %s: channel address %q is not in events.yaml", name, addr)
		}
	}
	types := make([]string, 0, len(app.DefaultEventRules))
	for typ := range app.DefaultEventRules {
		types = append(types, typ)
	}
	sort.Strings(types)
	for _, typ := range types {
		for i, r := range app.DefaultEventRules[typ] {
			addr, ok := app.DefaultTopics[r.Topic]
			if !ok {
				t.Errorf("%s[%d]: unknown topic %q", typ, i, r.Topic)
				continue
			}
			if !slices.Contains(byAddr[addr], typ) {
				t.Errorf("%s[%d]: event type is not published on %s (%s) in events.yaml", typ, i, addr, r.Topic)
			}
		}
	}
	if !slices.Contains(byAddr[app.DefaultTopics[app.TopicInventory]], app.AvailabilityChangedType) {
		t.Errorf("%s is not published on %s", app.AvailabilityChangedType, app.DefaultTopics[app.TopicInventory])
	}
	for _, typ := range app.ContactChangedTypes {
		if !slices.Contains(byAddr[app.DefaultTopics[app.TopicIdentity]], typ) {
			t.Errorf("contact change %s is not published on %s", typ, app.DefaultTopics[app.TopicIdentity])
		}
	}
	// S230: every trust-ops decision that reaches a customer or seller is mapped.
	for _, typ := range []string{"trustops.claim.opened", "trustops.claim.decided", "trustops.moderation.decided",
		"trustops.seller.suspended", "trustops.seller.reinstated"} {
		rules := app.DefaultEventRules[typ]
		if len(rules) == 0 || rules[0].Topic != app.TopicTrustOps {
			t.Errorf("%s: no rule on %s", typ, app.TopicTrustOps)
		}
	}
	for typ, want := range map[string][]app.RecipientSource{
		"trustops.claim.opened":  {app.RecipientUser, app.RecipientOrgOwners},
		"trustops.claim.decided": {app.RecipientUser, app.RecipientOrgOwners},
	} {
		var got []app.RecipientSource
		for _, r := range app.DefaultEventRules[typ] {
			got = append(got, r.Recipient)
		}
		if !slices.Equal(got, want) {
			t.Errorf("%s: recipients %v, want customer and seller owners %v", typ, got, want)
		}
	}
	// trust-ops also publishes notification.requested on its topic; the superseded keys must be the ones it uses.
	if !slices.Contains(byAddr[app.DefaultTopics[app.TopicTrustOps]], app.GenericEventType) {
		t.Errorf("%s is not published on %s", app.GenericEventType, app.DefaultTopics[app.TopicTrustOps])
	}
}

// TestEveryRuleHasSeededTemplates checks each rule's template exists (en) for every channel it sends on.
func TestEveryRuleHasSeededTemplates(t *testing.T) {
	files, err := filepath.Glob(filepath.Join("..", "adapter", "postgres", "migrations", "*.sql"))
	if err != nil || len(files) == 0 {
		t.Fatalf("migrations: %v", err)
	}
	row := regexp.MustCompile(`(?m)^\('([a-z0-9_.]+)', 'en', '(email|sms|push|chat)'`)
	seeded := map[string]bool{}
	for _, f := range files {
		raw, err := os.ReadFile(f)
		if err != nil {
			t.Fatal(err)
		}
		for _, m := range row.FindAllStringSubmatch(string(raw), -1) {
			seeded[m[1]+"/"+m[2]] = true
		}
	}
	check := func(what, key string, chans []domain.Channel) {
		for _, ch := range chans {
			if !seeded[key+"/"+string(ch)] {
				t.Errorf("%s: no seeded %s template for %s", what, ch, key)
			}
		}
	}
	for typ, rules := range app.DefaultEventRules {
		for _, r := range rules {
			check(typ, r.Template, r.Channels)
		}
	}
	check("stock alerts", app.StockAlertTemplate, []domain.Channel{domain.ChannelEmail, domain.ChannelPush})
	check("identity.verification sms override", "identity.verification", []domain.Channel{domain.ChannelSMS})
	check("compliance in_app → push", "seller.compliance.reminder", []domain.Channel{domain.ChannelPush})
	for key := range app.SupersededGenericTemplates {
		check("superseded notification.requested", key, []domain.Channel{domain.ChannelEmail})
	}
}

func TestResolveTopics(t *testing.T) {
	got, err := app.ResolveTopics(map[string]string{app.TopicOrders: "dev.at.order.events"})
	if err != nil || got[app.TopicOrders] != "dev.at.order.events" || got[app.TopicIdentity] != "at.identity.events" {
		t.Fatalf("%v %v", got, err)
	}
	if _, err := app.ResolveTopics(map[string]string{"order": "x"}); err == nil {
		t.Fatal("unknown topic names must fail")
	}
	if _, err := app.ResolveTopics(map[string]string{app.TopicOrders: " "}); err == nil {
		t.Fatal("empty addresses must fail")
	}
	addrs := app.TopicAddresses(app.DefaultTopics)
	if len(addrs) != len(app.DefaultTopics) || !sort.StringsAreSorted(addrs) {
		t.Fatalf("%v", addrs)
	}
}
