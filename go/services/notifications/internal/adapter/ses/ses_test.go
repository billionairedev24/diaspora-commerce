package ses_test

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/aws/aws-sdk-go-v2/aws"
	"github.com/aws/aws-sdk-go-v2/credentials"
	"github.com/aws/aws-sdk-go-v2/service/sesv2"

	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/adapter/ses"
	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/domain"
)

func client(srv *httptest.Server) *sesv2.Client {
	return sesv2.New(sesv2.Options{Region: "us-east-1", BaseEndpoint: aws.String(srv.URL), HTTPClient: srv.Client(),
		Credentials: credentials.NewStaticCredentialsProvider("AKID", "SECRET", ""), RetryMaxAttempts: 1})
}

func TestSendEmailRequestShape(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost || r.URL.Path != "/v2/email/outbound-emails" || r.Header.Get("Authorization") == "" {
			t.Errorf("%s %s", r.Method, r.URL.Path)
		}
		var b struct {
			FromEmailAddress     string
			ConfigurationSetName string
			Destination          struct{ ToAddresses []string }
			Content              struct {
				Simple struct {
					Subject struct{ Data string }
					Body    struct{ Html, Text struct{ Data string } }
					Headers []struct{ Name, Value string }
				}
			}
			EmailTags []struct{ Name, Value string }
		}
		_ = json.NewDecoder(r.Body).Decode(&b)
		s := b.Content.Simple
		if b.FromEmailAddress != "no-reply@at.com" || b.Destination.ToAddresses[0] != "ada@example.com" || s.Subject.Data != "Hi" ||
			s.Body.Html.Data != "<p>h</p>" || len(s.Headers) != 1 || s.Headers[0].Name != "List-Unsubscribe" ||
			b.ConfigurationSetName != "notifications-dev" || b.EmailTags[0].Value != "n1" {
			t.Errorf("%+v", b)
		}
		_, _ = w.Write([]byte(`{"MessageId":"0100018f-ses"}`))
	}))
	defer srv.Close()
	rec, err := ses.New(client(srv), "notifications-dev").Send(context.Background(), domain.EmailMessage{NotificationID: "n1",
		From: "no-reply@at.com", To: "ada@example.com", Subject: "Hi", HTML: "<p>h</p>", Text: "h",
		Headers: map[string]string{"List-Unsubscribe": "<https://x>"}})
	if err != nil || rec.ProviderMessageID != "0100018f-ses" {
		t.Fatalf("%+v %v", rec, err)
	}
}

func TestErrorMapping(t *testing.T) {
	cases := []struct {
		status    int
		code      string
		retryable bool
	}{
		{400, "MessageRejected", false},
		{400, "BadRequestException", false},
		{429, "TooManyRequestsException", true},
		{400, "SendingPausedException", true},
		{500, "InternalFailure", true},
	}
	for _, tc := range cases {
		srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
			w.Header().Set("X-Amzn-ErrorType", tc.code)
			w.Header().Set("Content-Type", "application/json")
			w.WriteHeader(tc.status)
			_, _ = w.Write([]byte(`{"message":"x"}`))
		}))
		_, err := ses.New(client(srv), "").Send(context.Background(), domain.EmailMessage{From: "a@b.c", To: "c@d.e"})
		srv.Close()
		var pe *domain.ProviderError
		if !errors.As(err, &pe) || pe.Retryable != tc.retryable || pe.Code != tc.code {
			t.Errorf("%s: %v", tc.code, err)
		}
	}
}
