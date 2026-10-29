package app

import (
	"context"
	"net/http"
	"net/mail"
	"strings"
	"time"
	"unicode/utf8"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

// CreditService is the shopper's AfroTransact credit and gift cards (S320, S321, ADR 0006). Credit only buys products
// and services: there is no operation here that pays it out, refunds it to a card or moves it to someone else.
type CreditService interface {
	// Account is the balance and a page of history, newest first; before pages backwards (an entry's createdAt).
	Account(ctx context.Context, c *domain.Caller, before string, limit int) (domain.CreditAccount, error)
	GiftCardConfig(ctx context.Context, c *domain.Caller) (domain.GiftCardConfig, error)
	MyGiftCards(ctx context.Context, c *domain.Caller) (domain.Page[domain.GiftCard], error)
	// Buy starts a purchase: the shopper confirms the payment with Stripe Elements using the client secret.
	Buy(ctx context.Context, c *domain.Caller, key string, req domain.GiftCardPurchaseRequest) (domain.GiftCardPurchase, error)
	ConfirmPurchase(ctx context.Context, c *domain.Caller, id string) (domain.GiftCard, error)
	// Redeem adds the card's value to the shopper's credit, once; clientAddress feeds the guessing limits.
	Redeem(ctx context.Context, c *domain.Caller, code, clientAddress string) (domain.GiftCardRedemption, error)
}

type creditServiceImpl struct {
	credit         port.CreditClient
	orders         port.CustomerOrdersClient
	publishableKey string
}

var _ CreditService = (*creditServiceImpl)(nil)

// NewCreditService wires the credit use cases; publishableKey is the Stripe key the buy page confirms with.
func NewCreditService(credit port.CreditClient, orders port.CustomerOrdersClient, publishableKey string) CreditService {
	return &creditServiceImpl{credit: credit, orders: orders, publishableKey: publishableKey}
}

const (
	creditPageDefault = 20
	creditPageMax     = 100
	giftMessageMax    = 500
	giftNameMax       = 120
)

func (s *creditServiceImpl) Account(ctx context.Context, c *domain.Caller, before string, limit int) (domain.CreditAccount, error) {
	if err := requireCustomer(c); err != nil {
		return domain.CreditAccount{}, err
	}
	if limit <= 0 {
		limit = creditPageDefault
	}
	limit = min(limit, creditPageMax)
	var from time.Time
	if before != "" {
		t, err := time.Parse(time.RFC3339Nano, before)
		if err != nil {
			return domain.CreditAccount{}, domain.ValidationProblem(map[string]string{"before": "Use the nextBefore of the previous page"})
		}
		from = t
	}
	bal, err := s.credit.Balance(ctx, c.BearerToken)
	if err != nil {
		return domain.CreditAccount{}, mapErr("payments", err)
	}
	// One more than asked tells whether an older page exists.
	rows, err := s.credit.Entries(ctx, c.BearerToken, from, limit+1)
	if err != nil {
		return domain.CreditAccount{}, mapErr("payments", err)
	}
	out := domain.CreditAccount{BalanceMinor: bal.BalanceMinor, Currency: firstNonEmpty(bal.Currency, domain.Currency), Entries: []domain.CreditEntry{}}
	if len(rows) > limit {
		rows = rows[:limit]
		next := rows[len(rows)-1].CreatedAt.UTC().Format(time.RFC3339Nano)
		out.NextBefore = &next
	}
	for _, e := range rows {
		out.Entries = append(out.Entries, domain.CreditEntry{ID: e.ID, Kind: e.Kind, Source: e.Source, AmountMinor: e.AmountMinor,
			Currency: e.Currency, BalanceAfterMinor: e.BalanceAfterMinor, CreatedAt: e.CreatedAt.UTC().Format(time.RFC3339)})
	}
	return out, nil
}

func (s *creditServiceImpl) GiftCardConfig(ctx context.Context, c *domain.Caller) (domain.GiftCardConfig, error) {
	if err := requireCustomer(c); err != nil {
		return domain.GiftCardConfig{}, err
	}
	cfg, err := s.credit.GiftCardConfig(ctx, c.BearerToken)
	if err != nil {
		return domain.GiftCardConfig{}, mapErr("payments", err)
	}
	out := domain.GiftCardConfig{MinMinor: cfg.MinMinor, MaxMinor: cfg.MaxMinor, Currency: firstNonEmpty(cfg.Currency, domain.Currency),
		PresetsMinor: cfg.PresetsMinor, Terms: cfg.Terms, PublishableKey: s.publishableKey}
	if out.PresetsMinor == nil {
		out.PresetsMinor = []int64{}
	}
	if out.Terms == nil {
		out.Terms = map[string]string{}
	}
	return out, nil
}

func toGiftCard(g port.UpGiftCard) domain.GiftCard {
	out := domain.GiftCard{ID: g.ID, Status: g.Status, AmountMinor: g.AmountMinor, Currency: firstNonEmpty(g.Currency, domain.Currency),
		RecipientEmail: g.RecipientEmail, RecipientName: g.RecipientName, SenderName: g.SenderName, Message: g.Message,
		SendAt: g.SendAt.UTC().Format(time.RFC3339), DeliveryStatus: g.DeliveryStatus, Redeemed: g.Redeemed,
		CreatedAt: g.CreatedAt.UTC().Format(time.RFC3339)}
	if g.SentAt != nil {
		out.SentAt = ptr(g.SentAt.UTC().Format(time.RFC3339))
	}
	return out
}

func (s *creditServiceImpl) MyGiftCards(ctx context.Context, c *domain.Caller) (domain.Page[domain.GiftCard], error) {
	if err := requireCustomer(c); err != nil {
		return domain.Page[domain.GiftCard]{}, err
	}
	rows, err := s.credit.MyGiftCards(ctx, c.BearerToken)
	if err != nil {
		return domain.Page[domain.GiftCard]{}, mapErr("payments", err)
	}
	items := make([]domain.GiftCard, 0, len(rows))
	for _, g := range rows {
		items = append(items, toGiftCard(g))
	}
	return domain.Page[domain.GiftCard]{Items: items}, nil
}

func (s *creditServiceImpl) Buy(ctx context.Context, c *domain.Caller, key string, req domain.GiftCardPurchaseRequest) (domain.GiftCardPurchase, error) {
	if err := requireCustomer(c); err != nil {
		return domain.GiftCardPurchase{}, err
	}
	errs := map[string]string{}
	if l := len(key); l < 16 || l > 64 {
		errs["Idempotency-Key"] = "Send a 16–64 character Idempotency-Key header"
	}
	if req.AmountMinor <= 0 {
		errs["amountMinor"] = "Choose an amount"
	}
	req.RecipientEmail = strings.TrimSpace(req.RecipientEmail)
	if a, err := mail.ParseAddress(req.RecipientEmail); err != nil || a.Address != req.RecipientEmail || len(req.RecipientEmail) > 254 {
		errs["recipientEmail"] = "Enter the recipient's email address"
	}
	if utf8.RuneCountInString(req.RecipientName) > giftNameMax {
		errs["recipientName"] = "At most 120 characters"
	}
	if utf8.RuneCountInString(req.SenderName) > giftNameMax {
		errs["senderName"] = "At most 120 characters"
	}
	if utf8.RuneCountInString(req.Message) > giftMessageMax {
		errs["message"] = "At most 500 characters"
	}
	if req.SendAt != "" {
		if _, err := time.Parse(time.RFC3339, req.SendAt); err != nil {
			errs["sendAt"] = "Use a date and time such as 2026-12-24T09:00:00Z"
		}
	}
	if len(errs) > 0 {
		return domain.GiftCardPurchase{}, domain.ValidationProblem(errs)
	}
	g, err := s.credit.StartGiftCard(ctx, c.BearerToken, key, port.UpGiftCardPurchaseRequest{AmountMinor: req.AmountMinor,
		RecipientEmail: req.RecipientEmail, RecipientName: strings.TrimSpace(req.RecipientName), SenderName: strings.TrimSpace(req.SenderName),
		Message: strings.TrimSpace(req.Message), SendAt: req.SendAt, Locale: req.Locale})
	if err != nil {
		return domain.GiftCardPurchase{}, giftErr(err)
	}
	return domain.GiftCardPurchase{GiftCard: toGiftCard(g), ClientSecret: g.ClientSecret, PublishableKey: s.publishableKey}, nil
}

func (s *creditServiceImpl) ConfirmPurchase(ctx context.Context, c *domain.Caller, id string) (domain.GiftCard, error) {
	if err := requireCustomer(c); err != nil {
		return domain.GiftCard{}, err
	}
	g, err := s.credit.ConfirmGiftCard(ctx, c.BearerToken, id)
	if err != nil {
		return domain.GiftCard{}, giftErr(err)
	}
	return toGiftCard(g), nil
}

func (s *creditServiceImpl) Redeem(ctx context.Context, c *domain.Caller, code, clientAddress string) (domain.GiftCardRedemption, error) {
	if err := requireCustomer(c); err != nil {
		return domain.GiftCardRedemption{}, err
	}
	code = strings.TrimSpace(code)
	if code == "" || len(code) > 64 {
		return domain.GiftCardRedemption{}, domain.ValidationProblem(map[string]string{"code": "Enter the code from your gift card"})
	}
	r, err := s.credit.Redeem(ctx, c.BearerToken, code, clientAddress)
	if err != nil {
		return domain.GiftCardRedemption{}, giftErr(err)
	}
	return domain.GiftCardRedemption{RedeemedMinor: r.RedeemedMinor, BalanceMinor: r.BalanceMinor, Currency: firstNonEmpty(r.Currency, domain.Currency)}, nil
}

// giftErr names the gift card and credit answers a shopper can act on; everything else is the usual mapping.
func giftErr(err error) error {
	if p, ok := port.AsUpstreamProblem(err); ok {
		switch {
		case p.Status == http.StatusNotFound && p.Code == "payments.gift-card-invalid":
			return domain.NewProblem(http.StatusNotFound, domain.CodeGiftCardInvalid, "Gift card not found",
				"That code is not valid. Check it and try again.").WithCause(err)
		case p.Status == http.StatusConflict && p.Code == "payments.gift-card-redeemed":
			return domain.NewProblem(http.StatusConflict, domain.CodeGiftCardRedeemed, "Already redeemed",
				"This gift card was already redeemed.").WithCause(err)
		case p.Status == http.StatusConflict && p.Code == "payments.no-card-portion":
			return domain.NewProblem(http.StatusConflict, domain.CodeNoCardPortion, "Nothing to change",
				"This order was paid with credit only, so its refunds already come back as credit.").WithCause(err)
		case p.Status == http.StatusUnprocessableEntity && p.Code == "payments.gift-card-invalid-request":
			if len(p.Fields) > 0 {
				return domain.ValidationProblem(p.Fields).WithCause(err)
			}
			return domain.NewProblem(http.StatusUnprocessableEntity, domain.CodeGiftCardInvalidRequest, "Check the gift card",
				firstNonEmpty(p.Detail, "That gift card is not allowed.")).WithCause(err)
		case p.Status == http.StatusPaymentRequired:
			return domain.NewProblem(http.StatusPaymentRequired, domain.CodePaymentDeclined, "Payment declined",
				firstNonEmpty(p.Detail, "Your payment was declined. Choose another payment method.")).WithCause(err)
		}
	}
	return mapErr("payments", err)
}
