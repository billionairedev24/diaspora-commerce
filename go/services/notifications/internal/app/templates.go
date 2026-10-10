package app

import (
	"context"
	"errors"
	"fmt"

	"github.com/google/uuid"

	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/port"
)

// PreviewRequest selects a template version (or unsaved draft content) and the sample data to render it with.
type PreviewRequest struct {
	Key     string         `json:"-"`
	Locale  string         `json:"locale"`
	Channel domain.Channel `json:"channel"`
	Version int            `json:"version"`
	Market  string         `json:"market"`
	Data    map[string]any `json:"data"`
	// Draft renders unsaved content instead of a stored version (the staff console's editor previews and
	// test-sends a new version before it is created).
	Draft *TemplateDraft `json:"draft,omitempty"`
}

// TemplateDraft is template content that is not stored yet.
type TemplateDraft struct {
	// Category defaults to the stored template's (else transactional); it decides consent rules on test-send.
	Category domain.Category `json:"category,omitempty"`
	Subject  string          `json:"subject,omitempty"`
	BodyHTML string          `json:"bodyHtml,omitempty"`
	BodyText string          `json:"bodyText"`
}

// TestSendResult reports where a test message went.
type TestSendResult struct {
	To      string         `json:"to"`
	Receipt domain.Receipt `json:"receipt"`
}

// ErrNoOwnContact is returned when the editor has no email/phone to test-send to.
var ErrNoOwnContact = errors.New("no own contact for test-send")

// Templates manages versioned templates: list, create a new version, preview, and test-send to the editor.
type Templates interface {
	List(ctx context.Context, key string) ([]domain.Template, error)
	Create(ctx context.Context, t domain.Template, author string) (domain.Template, error)
	Preview(ctx context.Context, req PreviewRequest) (domain.Rendered, error)
	// TestSend renders and sends straight through the router to the caller's own email or phone only.
	TestSend(ctx context.Context, req PreviewRequest) (TestSendResult, error)
}

type templatesImpl struct {
	store    port.TemplateStore
	renderer Renderer
	router   Router
	contacts port.ContactResolver
	from     string
}

var _ Templates = (*templatesImpl)(nil)

// NewTemplates returns the Templates use case.
func NewTemplates(store port.TemplateStore, renderer Renderer, router Router, contacts port.ContactResolver, from string) Templates {
	return &templatesImpl{store: store, renderer: renderer, router: router, contacts: contacts, from: from}
}

func (s *templatesImpl) List(ctx context.Context, key string) ([]domain.Template, error) {
	out, err := s.store.List(ctx, key)
	if err != nil {
		return nil, fmt.Errorf("list templates: %w", err)
	}
	return out, nil
}

func (s *templatesImpl) Create(ctx context.Context, t domain.Template, author string) (domain.Template, error) {
	if err := t.Validate(); err != nil {
		return domain.Template{}, fmt.Errorf("validate: %w", err)
	}
	if err := s.renderer.Parse(t); err != nil {
		return domain.Template{}, errors.Join(domain.ErrInvalidRequest, err)
	}
	t.CreatedBy = author
	out, err := s.store.Create(ctx, t)
	if err != nil {
		return domain.Template{}, fmt.Errorf("create template: %w", err)
	}
	return out, nil
}

func (s *templatesImpl) render(ctx context.Context, req PreviewRequest) (domain.Rendered, error) {
	if !req.Channel.Valid() {
		return domain.Rendered{}, errors.Join(domain.ErrInvalidRequest, errors.New("unknown channel"))
	}
	var t domain.Template
	if req.Draft != nil {
		var err error
		if t, err = s.draft(ctx, req); err != nil {
			return domain.Rendered{}, err
		}
	} else {
		var err error
		if t, err = s.store.Find(ctx, req.Key, domain.LocaleFallbacks(req.Locale), req.Channel, req.Version); err != nil {
			return domain.Rendered{}, fmt.Errorf("find template: %w", err)
		}
	}
	if req.Data == nil {
		req.Data = map[string]any{}
	}
	r, err := s.renderer.Render(t, req.Data)
	if err != nil {
		return domain.Rendered{}, errors.Join(domain.ErrInvalidRequest, err)
	}
	return r, nil
}

// draft builds the unsaved template of a draft preview; it must compile like a version about to be stored.
func (s *templatesImpl) draft(ctx context.Context, req PreviewRequest) (domain.Template, error) {
	locale := req.Locale
	if locale == "" {
		locale = "en"
	}
	t := domain.Template{Key: req.Key, Locale: locale, Channel: req.Channel, Category: req.Draft.Category,
		Subject: req.Draft.Subject, BodyHTML: req.Draft.BodyHTML, BodyText: req.Draft.BodyText}
	if !t.Category.Valid() {
		t.Category = domain.CategoryTransactional
		stored, err := s.store.Find(ctx, req.Key, domain.LocaleFallbacks(locale), req.Channel, 0)
		switch {
		case err == nil:
			t.Category = stored.Category
		case !errors.Is(err, domain.ErrTemplateNotFound):
			return t, fmt.Errorf("find template: %w", err)
		}
	}
	if err := s.renderer.Parse(t); err != nil {
		return t, errors.Join(domain.ErrInvalidRequest, err)
	}
	return t, nil
}

func (s *templatesImpl) Preview(ctx context.Context, req PreviewRequest) (domain.Rendered, error) {
	return s.render(ctx, req)
}

func (s *templatesImpl) TestSend(ctx context.Context, req PreviewRequest) (TestSendResult, error) {
	email, phone, err := s.contacts.Resolve(ctx)
	if err != nil {
		return TestSendResult{}, fmt.Errorf("%w: %w", ErrNoOwnContact, err)
	}
	r, err := s.render(ctx, req)
	if err != nil {
		return TestSendResult{}, err
	}
	id := "test-" + uuid.NewString()
	out := domain.Outbound{Channel: req.Channel, Market: req.Market}
	var to string
	switch req.Channel {
	case domain.ChannelEmail:
		to = domain.NormalizeEmail(email)
		out.Email = domain.EmailMessage{NotificationID: id, From: s.from, To: to, Subject: "[TEST] " + r.Subject,
			HTML: r.HTML, Text: r.Text, Category: r.Category, IdempotencyKey: id}
	case domain.ChannelSMS:
		to = domain.NormalizePhone(phone)
		out.SMS = domain.SmsMessage{NotificationID: id, To: to, Body: r.Text, Category: r.Category, IdempotencyKey: id}
	case domain.ChannelChat:
		to = domain.NormalizePhone(phone)
		out.Chat = domain.ChatMessage{NotificationID: id, To: to, Body: r.Text, Category: r.Category, IdempotencyKey: id}
	case domain.ChannelPush:
		return TestSendResult{}, errors.Join(domain.ErrInvalidRequest, errors.New("push test-send is not supported; use preview"))
	}
	if to == "" {
		return TestSendResult{}, ErrNoOwnContact
	}
	rec, err := s.router.Send(ctx, out)
	if err != nil {
		return TestSendResult{}, fmt.Errorf("test-send: %w", err)
	}
	return TestSendResult{To: to, Receipt: rec}, nil
}
