package logsender_test

import (
	"bytes"
	"context"
	"log/slog"
	"strings"
	"testing"

	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/adapter/logsender"
	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/domain"
)

func TestLogSendersNeverLogBodiesAtInfo(t *testing.T) {
	var buf bytes.Buffer
	log := slog.New(slog.NewTextHandler(&buf, &slog.HandlerOptions{Level: slog.LevelInfo}))
	ctx := context.Background()
	if r, err := logsender.NewSMS(log).Send(ctx, domain.SmsMessage{To: "+1555", Body: "code 246810"}); err != nil || r.Provider != "log" {
		t.Fatal(r, err)
	}
	if _, err := logsender.NewEmail(log).Send(ctx, domain.EmailMessage{To: "a@b.c", Subject: "s", Text: "code 246810"}); err != nil {
		t.Fatal(err)
	}
	if _, err := logsender.NewPush(log).Send(ctx, domain.PushMessage{Body: "x"}); err != nil {
		t.Fatal(err)
	}
	if _, err := logsender.NewChat(log).Send(ctx, domain.ChatMessage{Body: "x"}); err != nil {
		t.Fatal(err)
	}
	if strings.Contains(buf.String(), "246810") {
		t.Fatal("OTP leaked into info logs")
	}
	if !logsender.NewPush(log).Accepts(domain.PushAPNs) {
		t.Fatal("log push accepts all platforms")
	}
}
