// Package ses implements port.EmailSender with Amazon SES v2 (SendEmail). Credentials come from the AWS default
// chain (IRSA / workload identity in Kubernetes).
package ses

import (
	"context"
	"errors"
	"strings"
	"time"

	"github.com/aws/aws-sdk-go-v2/aws"
	"github.com/aws/aws-sdk-go-v2/service/sesv2"
	"github.com/aws/aws-sdk-go-v2/service/sesv2/types"
	"github.com/aws/smithy-go"

	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/port"
)

// Name is the provider name used in routing config.
const Name = "ses"

// API is the subset of the SES v2 client the adapter uses.
type API interface {
	SendEmail(ctx context.Context, in *sesv2.SendEmailInput, opts ...func(*sesv2.Options)) (*sesv2.SendEmailOutput, error)
}

type senderImpl struct {
	api              API
	configurationSet string
}

var (
	_ port.EmailSender       = (*senderImpl)(nil)
	_ port.AttachmentCapable = (*senderImpl)(nil)
)

// New returns the SES email sender. configurationSet (optional) routes events to SNS/EventBridge.
func New(api API, configurationSet string) port.EmailSender {
	return &senderImpl{api: api, configurationSet: configurationSet}
}

func (s *senderImpl) Name() string { return Name }

// SupportsAttachments is true: SES v2 simple messages carry attachments (the SDK base64-encodes them).
func (s *senderImpl) SupportsAttachments() bool { return true }

func (s *senderImpl) Send(ctx context.Context, msg domain.EmailMessage) (domain.Receipt, error) {
	body := &types.Body{}
	if msg.HTML != "" {
		body.Html = &types.Content{Data: aws.String(msg.HTML), Charset: aws.String("UTF-8")}
	}
	if msg.Text != "" {
		body.Text = &types.Content{Data: aws.String(msg.Text), Charset: aws.String("UTF-8")}
	}
	simple := &types.Message{Subject: &types.Content{Data: aws.String(msg.Subject), Charset: aws.String("UTF-8")}, Body: body}
	for k, v := range msg.Headers {
		simple.Headers = append(simple.Headers, types.MessageHeader{Name: aws.String(k), Value: aws.String(v)})
	}
	for _, a := range msg.Attachments {
		ct := a.ContentType
		if ct == "" {
			ct = "application/octet-stream"
		}
		simple.Attachments = append(simple.Attachments, types.Attachment{FileName: aws.String(a.FileName),
			RawContent: a.Content, ContentType: aws.String(ct),
			ContentDisposition:      types.AttachmentContentDispositionAttachment,
			ContentTransferEncoding: types.AttachmentContentTransferEncodingBase64})
	}
	in := &sesv2.SendEmailInput{
		FromEmailAddress: aws.String(msg.From),
		Destination:      &types.Destination{ToAddresses: []string{msg.To}},
		Content:          &types.EmailContent{Simple: simple},
		EmailTags: []types.MessageTag{
			{Name: aws.String("notification_id"), Value: aws.String(msg.NotificationID)},
			{Name: aws.String("category"), Value: aws.String(string(msg.Category))},
		},
	}
	if msg.ReplyTo != "" {
		in.ReplyToAddresses = []string{msg.ReplyTo}
	}
	if s.configurationSet != "" {
		in.ConfigurationSetName = aws.String(s.configurationSet)
	}
	out, err := s.api.SendEmail(ctx, in)
	if err != nil {
		return domain.Receipt{}, classify(err)
	}
	return domain.Receipt{Provider: Name, ProviderMessageID: aws.ToString(out.MessageId), AcceptedAt: time.Now().UTC()}, nil
}

// retryableCodes are throttling, server-side or account-level conditions another provider can work around.
var retryableCodes = map[string]bool{
	"TooManyRequestsException": true, "LimitExceededException": true, "ThrottlingException": true,
	"InternalFailure": true, "ServiceUnavailable": true, "AccountSuspendedException": true,
	"SendingPausedException": true, "MailFromDomainNotVerifiedException": true,
}

func classify(err error) error {
	var ae smithy.APIError
	if errors.As(err, &ae) {
		code := ae.ErrorCode()
		if retryableCodes[code] || ae.ErrorFault() == smithy.FaultServer || strings.HasSuffix(code, "ThrottlingException") {
			return domain.Retry(Name, 0, code).Wrap(err)
		}
		return domain.Permanent(Name, 0, code).Wrap(err)
	}
	return domain.Transport(Name).Wrap(err)
}
