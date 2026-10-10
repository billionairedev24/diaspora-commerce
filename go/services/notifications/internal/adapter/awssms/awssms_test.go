package awssms_test

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/aws/aws-sdk-go-v2/aws"
	"github.com/aws/aws-sdk-go-v2/credentials"
	"github.com/aws/aws-sdk-go-v2/service/pinpointsmsvoicev2"

	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/adapter/awssms"
	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/domain"
)

func client(srv *httptest.Server) *pinpointsmsvoicev2.Client {
	return pinpointsmsvoicev2.New(pinpointsmsvoicev2.Options{Region: "us-east-1", BaseEndpoint: aws.String(srv.URL),
		HTTPClient: srv.Client(), Credentials: credentials.NewStaticCredentialsProvider("AKID", "SECRET", ""), RetryMaxAttempts: 1})
}

func TestSendTextMessageShape(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("X-Amz-Target") != "PinpointSMSVoiceV2.SendTextMessage" {
			t.Errorf("target %q", r.Header.Get("X-Amz-Target"))
		}
		var b map[string]any
		_ = json.NewDecoder(r.Body).Decode(&b)
		if b["DestinationPhoneNumber"] != "+15551234567" || b["OriginationIdentity"] != "+18885550100" ||
			b["MessageType"] != "PROMOTIONAL" || b["MessageBody"] != "deal" || b["Context"].(map[string]any)["notification_id"] != "n1" {
			t.Errorf("%v", b)
		}
		w.Header().Set("Content-Type", "application/x-amz-json-1.0")
		_, _ = w.Write([]byte(`{"MessageId":"aws-msg-1"}`))
	}))
	defer srv.Close()
	rec, err := awssms.New(client(srv), awssms.Config{OriginationIdentity: "+18885550100"}).Send(context.Background(),
		domain.SmsMessage{NotificationID: "n1", To: "+15551234567", Body: "deal", Category: domain.CategoryMarketing})
	if err != nil || rec.ProviderMessageID != "aws-msg-1" {
		t.Fatalf("%+v %v", rec, err)
	}
}

func TestErrorMapping(t *testing.T) {
	for code, retry := range map[string]bool{"ValidationException": false, "ConflictException": false,
		"ThrottlingException": true, "InternalServerException": true} {
		srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
			w.Header().Set("Content-Type", "application/x-amz-json-1.0")
			w.WriteHeader(http.StatusBadRequest)
			_, _ = w.Write([]byte(`{"__type":"` + code + `","Message":"x"}`))
		}))
		_, err := awssms.New(client(srv), awssms.Config{}).Send(context.Background(), domain.SmsMessage{To: "+1", Body: "x"})
		srv.Close()
		var pe *domain.ProviderError
		if !errors.As(err, &pe) || pe.Retryable != retry || pe.Code != code {
			t.Errorf("%s: %v", code, err)
		}
	}
}
