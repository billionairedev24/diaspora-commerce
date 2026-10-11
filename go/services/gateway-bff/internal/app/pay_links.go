package app

import (
	"context"
	"net/http"
	"strings"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

// PayLinkService serves the storefront's pay-now page of late pre-order adds. No session is needed: the signed
// token from the SMS link is the credential, checked by orders. The page pays with Stripe Elements.
type PayLinkService interface {
	Lookup(ctx context.Context, orderNumber, token string) (domain.PayLink, error)
	// Confirm is called after stripe.confirmPayment: orders reads the intent and places the order once authorised.
	Confirm(ctx context.Context, orderNumber, token string) (domain.PayLink, error)
}

type payLinkServiceImpl struct {
	links          port.PayLinkClient
	publishableKey string
}

var _ PayLinkService = (*payLinkServiceImpl)(nil)

// NewPayLinkService returns the pay-now page use case.
func NewPayLinkService(links port.PayLinkClient, publishableKey string) PayLinkService {
	return &payLinkServiceImpl{links: links, publishableKey: publishableKey}
}

func (s *payLinkServiceImpl) Lookup(ctx context.Context, orderNumber, token string) (domain.PayLink, error) {
	if p := validPayLink(orderNumber, token); p != nil {
		return domain.PayLink{}, p
	}
	up, err := s.links.Lookup(ctx, strings.TrimSpace(orderNumber), strings.TrimSpace(token))
	return s.view(up, err)
}

func (s *payLinkServiceImpl) Confirm(ctx context.Context, orderNumber, token string) (domain.PayLink, error) {
	if p := validPayLink(orderNumber, token); p != nil {
		return domain.PayLink{}, p
	}
	up, err := s.links.Confirm(ctx, strings.TrimSpace(orderNumber), strings.TrimSpace(token))
	return s.view(up, err)
}

// invalidPayLink answers every unusable token the same way, so the page reveals nothing about other orders.
func invalidPayLink() *domain.Problem {
	return domain.NewProblem(http.StatusNotFound, domain.CodePayLinkNotFound, "Payment link not valid",
		"This payment link is not valid. Open the whole link from the text message, or contact us.")
}

func validPayLink(orderNumber, token string) *domain.Problem {
	n, t := strings.TrimSpace(orderNumber), strings.TrimSpace(token)
	if n == "" || len(n) > 40 || t == "" || len(t) > 200 {
		return invalidPayLink()
	}
	return nil
}

func (s *payLinkServiceImpl) view(up port.UpPayLink, err error) (domain.PayLink, error) {
	if err != nil {
		if p, ok := port.AsUpstreamProblem(err); ok {
			switch p.Status {
			case http.StatusNotFound:
				return domain.PayLink{}, invalidPayLink().WithCause(err)
			case http.StatusGone:
				return domain.PayLink{}, domain.NewProblem(http.StatusGone, domain.CodePayLinkExpired, "Payment link expired",
					"This payment link has expired, so nothing was charged. Contact us if you still want this pre-order.").WithCause(err)
			}
		}
		return domain.PayLink{}, mapErr("orders", err)
	}
	out := domain.PayLink{OrderNumber: up.OrderNumber, Status: strings.ToLower(up.Status), TotalMinor: up.TotalMinor,
		DeliveryFeeMinor: up.DeliveryFeeMinor, Currency: up.Currency, FailureMessage: up.FailureMessage, ExpiresAt: up.ExpiresAt,
		WindowName: up.WindowName, DeliversOn: up.DeliversOn, Fulfilment: up.Fulfilment, PublishableKey: s.publishableKey,
		Lines: make([]domain.PayLinkLine, 0, len(up.Lines))}
	if out.Status == "awaiting_payment" {
		out.ClientSecret = up.ClientSecret
	}
	for _, l := range up.Lines {
		out.Lines = append(out.Lines, domain.PayLinkLine{Title: l.Title, Quantity: l.Quantity, TotalMinor: l.TotalMinor})
	}
	return out, nil
}
