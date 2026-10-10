package smtp_test

import (
	"bufio"
	"context"
	"errors"
	"net"
	"strconv"
	"strings"
	"testing"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/adapter/smtp"
	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/domain"
)

// fakeSMTP accepts one session, answering RCPT with rcptReply, and returns the DATA it received.
func fakeSMTP(t *testing.T, rcptReply string) (host string, port int, data <-chan string) {
	t.Helper()
	ln, err := (&net.ListenConfig{}).Listen(context.Background(), "tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	out := make(chan string, 1)
	go func() {
		defer func() { _ = ln.Close() }()
		conn, err := ln.Accept()
		if err != nil {
			return
		}
		defer func() { _ = conn.Close() }()
		r := bufio.NewReader(conn)
		write := func(s string) { _, _ = conn.Write([]byte(s + "\r\n")) }
		write("220 fake ESMTP")
		var body strings.Builder
		for {
			line, err := r.ReadString('\n')
			if err != nil {
				out <- body.String()
				return
			}
			cmd := strings.ToUpper(strings.TrimSpace(line))
			switch {
			case strings.HasPrefix(cmd, "EHLO"):
				write("250-fake")
				write("250 8BITMIME")
			case strings.HasPrefix(cmd, "MAIL FROM"):
				write("250 OK")
			case strings.HasPrefix(cmd, "RCPT TO"):
				write(rcptReply)
			case cmd == "DATA":
				write("354 go ahead")
				for {
					l, _ := r.ReadString('\n')
					if l == ".\r\n" {
						break
					}
					body.WriteString(l)
				}
				write("250 queued")
			case cmd == "QUIT":
				write("221 bye")
				out <- body.String()
				return
			default:
				write("250 OK")
			}
		}
	}()
	addr := ln.Addr().(*net.TCPAddr)
	return "127.0.0.1", addr.Port, out
}

func TestSendToMailpitLikeServer(t *testing.T) {
	host, port, data := fakeSMTP(t, "250 OK")
	s := smtp.New(smtp.Config{Host: host, Port: port, Domain: "afrotransact.local"})
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	rec, err := s.Send(ctx, domain.EmailMessage{From: "AfroTransact <no-reply@afrotransact.local>", To: "ada@example.com",
		Subject: "Your code: 246810", Text: "code 246810", HTML: "<p>code <b>246810</b></p>",
		Headers: map[string]string{"List-Unsubscribe": "<https://x/u?t=1>", "List-Unsubscribe-Post": "List-Unsubscribe=One-Click"}})
	if err != nil || !strings.HasSuffix(rec.ProviderMessageID, "@afrotransact.local>") {
		t.Fatalf("%+v %v", rec, err)
	}
	msg := <-data
	for _, want := range []string{"From: AfroTransact <no-reply@afrotransact.local>", "To: ada@example.com",
		"Subject: Your code: 246810", "List-Unsubscribe: <https://x/u?t=1>", "List-Unsubscribe-Post: List-Unsubscribe=One-Click",
		"multipart/alternative", "text/plain", "text/html", "Message-Id: " + rec.ProviderMessageID} {
		if !strings.Contains(msg, want) && !strings.Contains(msg, strings.Replace(want, "Message-Id", "Message-ID", 1)) {
			t.Errorf("message lacks %q:\n%s", want, msg)
		}
	}
}

func TestSmtpReplyClassification(t *testing.T) {
	for reply, retry := range map[string]bool{"550 5.1.1 no such user": false, "451 4.7.1 try later": true} {
		host, port, _ := fakeSMTP(t, reply)
		_, err := smtp.New(smtp.Config{Host: host, Port: port}).Send(context.Background(),
			domain.EmailMessage{From: "a@b.c", To: "x@y.z", Subject: "s", Text: "t"})
		var pe *domain.ProviderError
		if !errors.As(err, &pe) || pe.Retryable != retry || pe.Code != strconv.Itoa(atoi(reply)) {
			t.Errorf("%s: %v", reply, err)
		}
	}
	_, err := smtp.New(smtp.Config{Host: "127.0.0.1", Port: 1}).Send(context.Background(), domain.EmailMessage{From: "a@b.c", To: "x@y.z"})
	if !domain.IsRetryable(err) {
		t.Errorf("connection refused must be retryable: %v", err)
	}
}

func atoi(s string) int {
	n, _ := strconv.Atoi(s[:3])
	return n
}
