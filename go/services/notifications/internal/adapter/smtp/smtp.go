// Package smtp implements port.EmailSender over SMTP (Mailpit at localhost:1025 in the local profile, or any relay).
package smtp

import (
	"bytes"
	"context"
	"crypto/rand"
	"crypto/tls"
	"encoding/base64"
	"encoding/hex"
	"errors"
	"fmt"
	"mime"
	"mime/quotedprintable"
	"net"
	"net/mail"
	netsmtp "net/smtp"
	"net/textproto"
	"sort"
	"strconv"
	"strings"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/port"
)

// Name is the provider name used in routing config.
const Name = "smtp"

// Config configures the relay. Username/Password are optional (Mailpit needs none).
type Config struct {
	Host     string
	Port     int
	Username string
	Password string
	// StartTLS upgrades the connection when the server offers it (required for authenticated relays).
	StartTLS bool
	// Domain is used for Message-ID.
	Domain string
}

type senderImpl struct{ cfg Config }

var (
	_ port.EmailSender       = (*senderImpl)(nil)
	_ port.AttachmentCapable = (*senderImpl)(nil)
)

// New returns the SMTP email sender.
func New(cfg Config) port.EmailSender {
	if cfg.Domain == "" {
		cfg.Domain = "afrotransact.local"
	}
	return &senderImpl{cfg: cfg}
}

func (s *senderImpl) Name() string { return Name }

// SupportsAttachments is true: attachments become base64 parts of a multipart/mixed message.
func (s *senderImpl) SupportsAttachments() bool { return true }

func (s *senderImpl) Send(ctx context.Context, msg domain.EmailMessage) (domain.Receipt, error) {
	from, err := mail.ParseAddress(msg.From)
	if err != nil {
		return domain.Receipt{}, domain.Permanent(Name, 0, "from").Wrap(err)
	}
	if _, err := mail.ParseAddress(msg.To); err != nil {
		return domain.Receipt{}, domain.Permanent(Name, 0, "to").Wrap(err)
	}
	msgID := "<" + randomID() + "@" + s.cfg.Domain + ">"
	raw, err := BuildMIME(msg, msgID, time.Now())
	if err != nil {
		return domain.Receipt{}, domain.Permanent(Name, 0, "mime").Wrap(err)
	}
	if err := s.deliver(ctx, from.Address, msg.To, raw); err != nil {
		return domain.Receipt{}, err
	}
	return domain.Receipt{Provider: Name, ProviderMessageID: msgID, AcceptedAt: time.Now().UTC()}, nil
}

func (s *senderImpl) deliver(ctx context.Context, from, to string, raw []byte) error {
	addr := net.JoinHostPort(s.cfg.Host, strconv.Itoa(s.cfg.Port))
	var d net.Dialer
	conn, err := d.DialContext(ctx, "tcp", addr)
	if err != nil {
		return domain.Transport(Name).Wrap(err)
	}
	if dl, ok := ctx.Deadline(); ok {
		_ = conn.SetDeadline(dl)
	}
	c, err := netsmtp.NewClient(conn, s.cfg.Host)
	if err != nil {
		_ = conn.Close()
		return classify(err)
	}
	defer func() { _ = c.Close() }()
	if err := c.Hello(s.cfg.Domain); err != nil {
		return classify(err)
	}
	if ok, _ := c.Extension("STARTTLS"); ok && s.cfg.StartTLS {
		if err := c.StartTLS(&tls.Config{ServerName: s.cfg.Host, MinVersion: tls.VersionTLS12}); err != nil {
			return classify(err)
		}
	}
	if s.cfg.Username != "" {
		if err := c.Auth(netsmtp.PlainAuth("", s.cfg.Username, s.cfg.Password, s.cfg.Host)); err != nil {
			return classify(err)
		}
	}
	if err := c.Mail(from); err != nil {
		return classify(err)
	}
	if err := c.Rcpt(to); err != nil {
		return classify(err)
	}
	w, err := c.Data()
	if err != nil {
		return classify(err)
	}
	if _, err := w.Write(raw); err != nil {
		return classify(err)
	}
	if err := w.Close(); err != nil {
		return classify(err)
	}
	if err := c.Quit(); err != nil {
		return classify(err)
	}
	return nil
}

// classify maps SMTP replies: 4xx transient (retry), 5xx permanent; anything else is a transport failure.
func classify(err error) error {
	var te *textproto.Error
	if errors.As(err, &te) {
		code := strconv.Itoa(te.Code)
		if te.Code >= 400 && te.Code < 500 {
			return domain.Retry(Name, 0, code).Wrap(err)
		}
		return domain.Permanent(Name, 0, code).Wrap(err)
	}
	return domain.Transport(Name).Wrap(err)
}

func randomID() string {
	b := make([]byte, 12)
	_, _ = rand.Read(b)
	return hex.EncodeToString(b)
}

// BuildMIME renders a multipart/alternative message with custom headers (List-Unsubscribe …). With attachments
// the alternative part is wrapped in multipart/mixed and each file follows as a base64 part.
func BuildMIME(msg domain.EmailMessage, messageID string, now time.Time) ([]byte, error) {
	var b bytes.Buffer
	boundary := "at-" + randomID()
	hdr := func(k, v string) { fmt.Fprintf(&b, "%s: %s\r\n", k, v) }
	hdr("From", msg.From)
	hdr("To", msg.To)
	if msg.ReplyTo != "" {
		hdr("Reply-To", msg.ReplyTo)
	}
	hdr("Subject", mime.QEncoding.Encode("utf-8", msg.Subject))
	hdr("Date", now.UTC().Format(time.RFC1123Z))
	hdr("Message-ID", messageID)
	hdr("MIME-Version", "1.0")
	keys := make([]string, 0, len(msg.Headers))
	for k := range msg.Headers {
		keys = append(keys, k)
	}
	sort.Strings(keys)
	for _, k := range keys {
		hdr(textproto.CanonicalMIMEHeaderKey(k), msg.Headers[k])
	}
	mixed := ""
	if len(msg.Attachments) > 0 {
		mixed = "at-mixed-" + randomID()
		hdr("Content-Type", `multipart/mixed; boundary="`+mixed+`"`)
		fmt.Fprintf(&b, "\r\n--%s\r\n", mixed)
	}
	hdr("Content-Type", `multipart/alternative; boundary="`+boundary+`"`)
	b.WriteString("\r\n")
	part := func(ctype, body string) error {
		fmt.Fprintf(&b, "--%s\r\nContent-Type: %s; charset=UTF-8\r\nContent-Transfer-Encoding: quoted-printable\r\n\r\n", boundary, ctype)
		w := quotedprintable.NewWriter(&b)
		if _, err := w.Write([]byte(body)); err != nil {
			return fmt.Errorf("encode %s: %w", ctype, err)
		}
		if err := w.Close(); err != nil {
			return fmt.Errorf("encode %s: %w", ctype, err)
		}
		b.WriteString("\r\n")
		return nil
	}
	if msg.Text != "" {
		if err := part("text/plain", msg.Text); err != nil {
			return nil, err
		}
	}
	if msg.HTML != "" {
		if err := part("text/html", msg.HTML); err != nil {
			return nil, err
		}
	}
	fmt.Fprintf(&b, "--%s--\r\n", boundary)
	if mixed == "" {
		return b.Bytes(), nil
	}
	for _, a := range msg.Attachments {
		writeAttachment(&b, mixed, a)
	}
	fmt.Fprintf(&b, "--%s--\r\n", mixed)
	return b.Bytes(), nil
}

// writeAttachment writes one base64 attachment part (76-character lines, RFC 2045).
func writeAttachment(b *bytes.Buffer, boundary string, a domain.Attachment) {
	ctype, _, err := mime.ParseMediaType(a.ContentType)
	if err != nil || !strings.Contains(ctype, "/") {
		ctype = "application/octet-stream"
	}
	name := domain.SafeFileName(a.FileName)
	fmt.Fprintf(b, "--%s\r\nContent-Type: %s\r\nContent-Transfer-Encoding: base64\r\nContent-Disposition: %s\r\n\r\n",
		boundary, mime.FormatMediaType(ctype, map[string]string{"name": name}),
		mime.FormatMediaType("attachment", map[string]string{"filename": name}))
	enc := base64.StdEncoding.EncodeToString(a.Content)
	for len(enc) > 76 {
		b.WriteString(enc[:76] + "\r\n")
		enc = enc[76:]
	}
	if enc != "" {
		b.WriteString(enc + "\r\n")
	}
}
