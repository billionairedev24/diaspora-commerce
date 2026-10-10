package app

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"fmt"
	"time"

	"github.com/google/uuid"

	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/port"
)

// Notifier turns requests into rendered, idempotent notification rows that the dispatcher sends.
type Notifier interface {
	// Submit renders and enqueues a request (synchronous API). Repeating an idempotency key returns the
	// original notifications.
	Submit(ctx context.Context, req domain.Request) ([]domain.Notification, error)
	// SubmitForEvent enqueues requests derived from one event, de-duplicated by event id in the inbox. Requests that
	// cannot be delivered (no address, no template) are dropped; it fails with domain.ErrInvalidRequest only when
	// none can be.
	SubmitForEvent(ctx context.Context, consumer, eventID string, reqs []domain.Request) (bool, error)
}

type notifierImpl struct {
	templates port.TemplateStore
	renderer  Renderer
	store     port.NotificationStore
	unsub     Unsubscriber
	now       func() time.Time
}

var _ Notifier = (*notifierImpl)(nil)

// NewNotifier returns the Notifier.
func NewNotifier(templates port.TemplateStore, renderer Renderer, store port.NotificationStore, unsub Unsubscriber) Notifier {
	return &notifierImpl{templates: templates, renderer: renderer, store: store, unsub: unsub, now: time.Now}
}

func (n *notifierImpl) Submit(ctx context.Context, req domain.Request) ([]domain.Notification, error) {
	if req.IdempotencyKey == "" {
		return nil, errors.Join(domain.ErrInvalidRequest, errors.New("Idempotency-Key is required"))
	}
	rows, err := n.build(ctx, req)
	if err != nil {
		return nil, err
	}
	stored, err := n.store.Enqueue(ctx, rows)
	if err != nil {
		return nil, fmt.Errorf("enqueue: %w", err)
	}
	return stored, nil
}

func (n *notifierImpl) SubmitForEvent(ctx context.Context, consumer, eventID string, reqs []domain.Request) (bool, error) {
	var rows []domain.Notification
	var invalid []error
	for _, r := range reqs {
		r.EventID = eventID
		built, err := n.build(ctx, r)
		if errors.Is(err, domain.ErrInvalidRequest) {
			// One undeliverable recipient (e.g. an org owner without contact details) must not drop the others.
			invalid = append(invalid, err)
			continue
		}
		if err != nil {
			return false, err
		}
		rows = append(rows, built...)
	}
	if len(rows) == 0 && len(invalid) > 0 {
		return false, errors.Join(invalid...)
	}
	first, err := n.store.EnqueueForEvent(ctx, consumer, eventID, rows)
	if err != nil {
		return false, fmt.Errorf("enqueue event %s: %w", eventID, err)
	}
	return first, nil
}

func addresses(ch domain.Channel, r domain.Recipient) []string {
	switch ch {
	case domain.ChannelEmail:
		if e := domain.NormalizeEmail(r.Email); e != "" {
			return []string{e}
		}
	case domain.ChannelSMS, domain.ChannelChat:
		if p := domain.NormalizePhone(r.Phone); p != "" {
			return []string{p}
		}
	case domain.ChannelPush:
		out := make([]string, 0, len(r.PushTokens))
		for _, t := range r.PushTokens {
			if t.Token != "" && (t.Platform == domain.PushFCM || t.Platform == domain.PushAPNs) {
				out = append(out, t.Address())
			}
		}
		return out
	}
	return nil
}

// build renders one row per channel and address. Channels without an address or a template are skipped; a
// request that yields nothing is invalid.
func (n *notifierImpl) build(ctx context.Context, req domain.Request) ([]domain.Notification, error) {
	req, err := req.Validate()
	if err != nil {
		return nil, fmt.Errorf("validate: %w", err)
	}
	base := req.IdempotencyKey
	if base == "" && req.EventID != "" {
		base = "evt:" + req.EventID + ":" + req.TemplateKey
	}
	if base == "" {
		base = uuid.NewString()
	}
	now := n.now().UTC()
	refs := domain.CleanAttachmentRefs(req.Attachments)
	if len(refs) == 0 {
		refs = domain.AttachmentRefsFrom(req.Data)
	}
	var rows []domain.Notification
	var skipped []error
	for _, ch := range req.Channels {
		addrs := addresses(ch, req.Recipient)
		if len(addrs) == 0 {
			skipped = append(skipped, fmt.Errorf("no %s address", ch))
			continue
		}
		tpl, err := n.templates.Find(ctx, req.TemplateKey, domain.LocaleFallbacks(req.Locale), ch, 0)
		if errors.Is(err, domain.ErrTemplateNotFound) {
			skipped = append(skipped, fmt.Errorf("no %s template", ch))
			continue
		}
		if err != nil {
			return nil, fmt.Errorf("template %s/%s: %w", req.TemplateKey, ch, err)
		}
		rendered, err := n.renderer.Render(tpl, req.Data)
		if err != nil {
			return nil, errors.Join(domain.ErrInvalidRequest, fmt.Errorf("render %s/%s: %w", req.TemplateKey, ch, err))
		}
		for _, addr := range addrs {
			row := domain.Notification{
				ID:              uuid.NewString(),
				IdempotencyKey:  base + ":" + string(ch) + ":" + shortHash(addr),
				EventID:         req.EventID,
				TemplateKey:     tpl.Key,
				TemplateVersion: tpl.Version,
				Locale:          tpl.Locale,
				Channel:         ch,
				Category:        tpl.Category,
				Market:          req.Market,
				RecipientID:     req.Recipient.ID,
				Address:         addr,
				TimeZone:        req.Recipient.TimeZone,
				Subject:         rendered.Subject,
				BodyHTML:        rendered.HTML,
				BodyText:        rendered.Text,
				Status:          domain.StatusQueued,
				NextAttemptAt:   now,
				CreatedAt:       now,
				UpdatedAt:       now,
			}
			if ch == domain.ChannelEmail {
				row.Attachments = refs
			}
			if ch == domain.ChannelEmail && tpl.Category == domain.CategoryMarketing && n.unsub != nil {
				row.Headers = n.unsub.Headers(domain.Key(req.Recipient.ID, addr), addr, tpl.Category, now)
			}
			rows = append(rows, row)
		}
	}
	if len(rows) == 0 {
		return nil, errors.Join(append([]error{domain.ErrInvalidRequest, domain.ErrTemplateNotFound}, skipped...)...)
	}
	return rows, nil
}

func shortHash(s string) string {
	h := sha256.Sum256([]byte(s))
	return hex.EncodeToString(h[:8])
}
