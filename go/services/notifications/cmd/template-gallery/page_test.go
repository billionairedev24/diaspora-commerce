package main

import (
	"bytes"
	"io"
	"log/slog"
	"strings"
	"testing"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/app"
	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/domain"
)

func TestPageListsFailuresAndRendersEveryChannel(t *testing.T) {
	sms := domain.CountSmsSegments("Sat, Oct 17 · 10:00 AM")
	entries := []app.GalleryEntry{
		{Template: domain.Template{Key: "order.confirmed", Locale: "en", Channel: domain.ChannelEmail, Version: 3, Category: domain.CategoryTransactional},
			Sample:   app.TemplateSample{Key: "order.confirmed", Area: app.AreaOrders, Source: app.SampleFromEvent, EventTypes: []string{"orders.order.placed"}, Data: map[string]any{"total": "$48.00"}},
			Rendered: domain.Rendered{Subject: "Order AT-1 is confirmed", HTML: `<p class="x">Thanks & welcome</p>`, Text: "Thanks"}},
		{Template: domain.Template{Key: "booking.confirmed", Locale: "en", Channel: domain.ChannelSMS, Version: 1, Category: domain.CategoryTransactional},
			Sample:   app.TemplateSample{Key: "booking.confirmed", Area: app.AreaBookings, EventTypes: []string{}},
			Rendered: domain.Rendered{Text: "Sat, Oct 17 · 10:00 AM"}, SMS: &sms},
		{Template: domain.Template{Key: "booking.confirmed", Locale: "en", Channel: domain.ChannelPush, Version: 1},
			Sample:   app.TemplateSample{Key: "booking.confirmed", Area: app.AreaBookings, EventTypes: []string{}},
			Rendered: domain.Rendered{Subject: "Booking confirmed", Text: "See you Saturday"}},
		{Template: domain.Template{Key: "promo.weekly", Locale: "fr", Channel: domain.ChannelChat, Version: 2, Subject: "weekly_promo"},
			Sample: app.TemplateSample{Key: "promo.weekly", Area: app.AreaOther, EventTypes: []string{}},
			Err:    `template render failed: text: map has no entry for key "discount"`},
	}
	var b bytes.Buffer
	if err := newPageWriter(findAssets("", slog.New(slog.NewTextHandler(io.Discard, nil)))).Write(&b, entries, time.Unix(0, 0)); err != nil {
		t.Fatal(err)
	}
	html := b.String()
	for _, want := range []string{
		"1 template(s) failed to render", `map has no entry for key &#34;discount&#34;`, // listed, with the error
		`srcdoc="&lt;p class=&#34;x&#34;&gt;Thanks &amp; welcome&lt;/p&gt;"`, // email HTML escaped into a sandboxed iframe
		`sandbox="allow-same-origin"`, "UCS-2 · 22 units", "1 segment", "Booking confirmed", "orders.order.placed",
		`id="t-order-confirmed-en"`, "Orders &amp; shipments", "<option value=\"fr\">fr</option>",
	} {
		if !strings.Contains(html, want) {
			t.Errorf("page lacks %q", want)
		}
	}
}
