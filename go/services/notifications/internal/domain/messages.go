package domain

// EmailMessage is a rendered email.
type EmailMessage struct {
	NotificationID string
	From           string
	ReplyTo        string
	To             string
	Subject        string
	HTML           string
	Text           string
	Headers        map[string]string // e.g. List-Unsubscribe, List-Unsubscribe-Post
	// Attachments are fetched files. Providers that cannot attach files never receive them (the router strips them
	// and the body's link is the fallback).
	Attachments    []Attachment
	Category       Category
	IdempotencyKey string
}

// SmsMessage is a rendered text message.
type SmsMessage struct {
	NotificationID string
	To             string // E.164
	Body           string
	Category       Category
	IdempotencyKey string
}

// PushMessage is a rendered push notification.
type PushMessage struct {
	NotificationID string
	Token          PushToken
	Title          string
	Body           string
	Data           map[string]string
	Category       Category
	IdempotencyKey string
}

// ChatMessage is a WhatsApp message. When TemplateName is set the approved WhatsApp template is used
// (required outside the 24-hour customer service window) with Params as body parameters; otherwise Body is
// sent as text.
type ChatMessage struct {
	NotificationID string
	To             string // E.164
	Body           string
	TemplateName   string
	Language       string
	Params         []string
	Category       Category
	IdempotencyKey string
}

// Outbound is one message to send, routed by channel and market.
type Outbound struct {
	Channel Channel
	Market  string
	Email   EmailMessage
	SMS     SmsMessage
	Push    PushMessage
	Chat    ChatMessage
}
