// Package awssms implements port.SmsSender with AWS End User Messaging SMS (pinpointsmsvoicev2 SendTextMessage).
package awssms

import (
	"context"
	"errors"
	"time"

	"github.com/aws/aws-sdk-go-v2/aws"
	"github.com/aws/aws-sdk-go-v2/service/pinpointsmsvoicev2"
	"github.com/aws/aws-sdk-go-v2/service/pinpointsmsvoicev2/types"
	"github.com/aws/smithy-go"

	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/port"
)

// Name is the provider name used in routing config.
const Name = "aws"

// API is the subset of the End User Messaging SMS client the adapter uses.
type API interface {
	SendTextMessage(ctx context.Context, in *pinpointsmsvoicev2.SendTextMessageInput,
		opts ...func(*pinpointsmsvoicev2.Options)) (*pinpointsmsvoicev2.SendTextMessageOutput, error)
}

// Config configures the adapter.
type Config struct {
	// OriginationIdentity is a phone number, sender id, pool id or ARN (10DLC number in the US).
	OriginationIdentity string
	ConfigurationSet    string
}

type senderImpl struct {
	api API
	cfg Config
}

var _ port.SmsSender = (*senderImpl)(nil)

// New returns the AWS SMS sender.
func New(api API, cfg Config) port.SmsSender { return &senderImpl{api: api, cfg: cfg} }

func (s *senderImpl) Name() string { return Name }

func (s *senderImpl) Send(ctx context.Context, msg domain.SmsMessage) (domain.Receipt, error) {
	mt := types.MessageTypeTransactional
	if msg.Category == domain.CategoryMarketing {
		mt = types.MessageTypePromotional
	}
	in := &pinpointsmsvoicev2.SendTextMessageInput{
		DestinationPhoneNumber: aws.String(msg.To),
		MessageBody:            aws.String(msg.Body),
		MessageType:            mt,
		Context:                map[string]string{"notification_id": msg.NotificationID},
	}
	if s.cfg.OriginationIdentity != "" {
		in.OriginationIdentity = aws.String(s.cfg.OriginationIdentity)
	}
	if s.cfg.ConfigurationSet != "" {
		in.ConfigurationSetName = aws.String(s.cfg.ConfigurationSet)
	}
	out, err := s.api.SendTextMessage(ctx, in)
	if err != nil {
		return domain.Receipt{}, classify(err)
	}
	return domain.Receipt{Provider: Name, ProviderMessageID: aws.ToString(out.MessageId), AcceptedAt: time.Now().UTC()}, nil
}

var retryableCodes = map[string]bool{
	"ThrottlingException": true, "InternalServerException": true, "ServiceQuotaExceededException": true,
	"AccessDeniedException": true,
}

func classify(err error) error {
	var ae smithy.APIError
	if errors.As(err, &ae) {
		if retryableCodes[ae.ErrorCode()] || ae.ErrorFault() == smithy.FaultServer {
			return domain.Retry(Name, 0, ae.ErrorCode()).Wrap(err)
		}
		// ValidationException (bad number), ConflictException (destination opted out), ResourceNotFound …
		return domain.Permanent(Name, 0, ae.ErrorCode()).Wrap(err)
	}
	return domain.Transport(Name).Wrap(err)
}
