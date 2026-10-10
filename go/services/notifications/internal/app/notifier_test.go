package app_test

import (
	"context"
	"errors"
	"strings"
	"testing"

	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/app"
	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/domain"
)

func templates() *fakeTemplates {
	return &fakeTemplates{items: []domain.Template{
		{Key: "order.confirmed", Locale: "en", Channel: domain.ChannelEmail, Version: 1, Category: domain.CategoryTransactional, Subject: "Order {{.n}}", BodyText: "Order {{.n}}"},
		{Key: "order.confirmed", Locale: "en", Channel: domain.ChannelEmail, Version: 2, Category: domain.CategoryTransactional, Subject: "Order {{.n}} confirmed", BodyText: "Order {{.n}}"},
		{Key: "order.confirmed", Locale: "fr", Channel: domain.ChannelEmail, Version: 1, Category: domain.CategoryTransactional, Subject: "Commande {{.n}}", BodyText: "Commande {{.n}}"},
		{Key: "order.confirmed", Locale: "en", Channel: domain.ChannelPush, Version: 1, Category: domain.CategoryTransactional, Subject: "Order", BodyText: "Order {{.n}}"},
		{Key: "promo.weekly", Locale: "en", Channel: domain.ChannelEmail, Version: 1, Category: domain.CategoryMarketing, Subject: "Deals", BodyText: "Deals"},
	}}
}

func newNotifier(store *fakeStore) app.Notifier {
	return app.NewNotifier(templates(), app.NewRenderer(), store, app.NewUnsubscriber("https://n", "", []byte("k")))
}

func TestSubmitBuildsOneRowPerChannelAndAddress(t *testing.T) {
	store := newFakeStore()
	out, err := newNotifier(store).Submit(context.Background(), domain.Request{
		TemplateKey: "order.confirmed", Locale: "fr-CA", IdempotencyKey: "idem-1",
		Channels:  []domain.Channel{domain.ChannelEmail, domain.ChannelSMS, domain.ChannelPush},
		Recipient: domain.Recipient{ID: "u1", Email: " Ada@Example.com ", PushTokens: []domain.PushToken{{Token: "a", Platform: domain.PushFCM}, {Token: "b", Platform: domain.PushAPNs}}},
		Data:      map[string]any{"n": "AT-1"},
	})
	if err != nil {
		t.Fatal(err)
	}
	// email (fr fallback) + 2 push; SMS skipped (no phone).
	if len(out) != 3 {
		t.Fatalf("want 3 rows, got %d", len(out))
	}
	email := out[0]
	if email.Address != "ada@example.com" || email.Subject != "Commande AT-1" || email.Locale != "fr" || email.Status != domain.StatusQueued {
		t.Fatalf("%+v", email)
	}
	if !strings.HasPrefix(email.IdempotencyKey, "idem-1:email:") || out[1].IdempotencyKey == out[2].IdempotencyKey {
		t.Fatal("idempotency keys must be per channel and address")
	}
	if out[1].Address != "fcm:a" || out[2].Address != "apns:b" {
		t.Fatal(out[1].Address, out[2].Address)
	}
	if email.Headers != nil {
		t.Fatal("transactional email must not carry List-Unsubscribe")
	}
}

func TestSubmitUsesLatestVersionAndMarketingHeaders(t *testing.T) {
	store := newFakeStore()
	n := newNotifier(store)
	out, err := n.Submit(context.Background(), domain.Request{TemplateKey: "order.confirmed", IdempotencyKey: "k",
		Channels: []domain.Channel{domain.ChannelEmail}, Recipient: domain.Recipient{Email: "a@b.c"}, Data: map[string]any{"n": "1"}})
	if err != nil || out[0].TemplateVersion != 2 {
		t.Fatalf("want version 2: %+v %v", out, err)
	}
	out, err = n.Submit(context.Background(), domain.Request{TemplateKey: "promo.weekly", IdempotencyKey: "k2",
		Channels: []domain.Channel{domain.ChannelEmail}, Recipient: domain.Recipient{ID: "u1", Email: "a@b.c"}})
	if err != nil {
		t.Fatal(err)
	}
	if out[0].Headers["List-Unsubscribe-Post"] != "List-Unsubscribe=One-Click" || out[0].Category != domain.CategoryMarketing {
		t.Fatalf("%+v", out[0])
	}
}

func TestSubmitValidation(t *testing.T) {
	n := newNotifier(newFakeStore())
	ctx := context.Background()
	if _, err := n.Submit(ctx, domain.Request{TemplateKey: "order.confirmed", Channels: []domain.Channel{domain.ChannelEmail}}); !errors.Is(err, domain.ErrInvalidRequest) {
		t.Fatal("missing idempotency key must be rejected")
	}
	if _, err := n.Submit(ctx, domain.Request{TemplateKey: "order.confirmed", IdempotencyKey: "x", Channels: []domain.Channel{domain.ChannelSMS},
		Recipient: domain.Recipient{Email: "a@b.c"}}); !errors.Is(err, domain.ErrInvalidRequest) {
		t.Fatal("no deliverable channel must be rejected")
	}
	if _, err := n.Submit(ctx, domain.Request{TemplateKey: "order.confirmed", IdempotencyKey: "x", Channels: []domain.Channel{domain.ChannelEmail},
		Recipient: domain.Recipient{Email: "a@b.c"}}); !errors.Is(err, app.ErrRender) {
		t.Fatalf("missing template data must be rejected, got %v", err)
	}
}
