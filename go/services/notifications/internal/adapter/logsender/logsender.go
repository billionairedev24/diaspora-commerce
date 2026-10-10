// Package logsender implements every sender port by logging the message (local profile and tests). Message
// bodies are logged in full only at debug level so OTPs do not end up in shared logs.
package logsender

import (
	"context"
	"log/slog"
	"time"

	"github.com/google/uuid"

	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/port"
)

// Name is the provider name used in routing config.
const Name = "log"

type base struct{ log *slog.Logger }

func (base) Name() string { return Name }

func (b base) receipt(ctx context.Context, channel domain.Channel, to, body string, attrs ...any) domain.Receipt {
	id := "log-" + uuid.NewString()
	b.log.InfoContext(ctx, "notification (log provider)", append([]any{"channel", channel, "to", to, "messageId", id}, attrs...)...)
	b.log.DebugContext(ctx, "notification body", "messageId", id, "body", body)
	return domain.Receipt{Provider: Name, ProviderMessageID: id, AcceptedAt: time.Now().UTC()}
}

type emailImpl struct{ base }
type smsImpl struct{ base }
type pushImpl struct{ base }
type chatImpl struct{ base }

var (
	_ port.EmailSender = (*emailImpl)(nil)
	_ port.SmsSender   = (*smsImpl)(nil)
	_ port.PushSender  = (*pushImpl)(nil)
	_ port.ChatSender  = (*chatImpl)(nil)
)

// NewEmail returns a logging EmailSender.
func NewEmail(log *slog.Logger) port.EmailSender { return &emailImpl{base{log}} }

// NewSMS returns a logging SmsSender.
func NewSMS(log *slog.Logger) port.SmsSender { return &smsImpl{base{log}} }

// NewPush returns a logging PushSender (accepts every platform).
func NewPush(log *slog.Logger) port.PushSender { return &pushImpl{base{log}} }

// NewChat returns a logging ChatSender.
func NewChat(log *slog.Logger) port.ChatSender { return &chatImpl{base{log}} }

func (s *emailImpl) Send(ctx context.Context, m domain.EmailMessage) (domain.Receipt, error) {
	return s.receipt(ctx, domain.ChannelEmail, m.To, m.Text, "subject", m.Subject), nil
}

func (s *smsImpl) Send(ctx context.Context, m domain.SmsMessage) (domain.Receipt, error) {
	return s.receipt(ctx, domain.ChannelSMS, m.To, m.Body), nil
}

func (s *pushImpl) Accepts(domain.PushPlatform) bool { return true }

func (s *pushImpl) Send(ctx context.Context, m domain.PushMessage) (domain.Receipt, error) {
	return s.receipt(ctx, domain.ChannelPush, string(m.Token.Platform), m.Body, "title", m.Title), nil
}

func (s *chatImpl) Send(ctx context.Context, m domain.ChatMessage) (domain.Receipt, error) {
	return s.receipt(ctx, domain.ChannelChat, m.To, m.Body, "template", m.TemplateName), nil
}
