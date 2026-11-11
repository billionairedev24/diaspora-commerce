package app

import (
	"context"
	"strconv"
	"strings"
	"unicode/utf8"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

// DeliveryQuotesService is "Ask for a delivery quote" for a shopper whose address is outside the delivery zones (listing
// rules D20). orders-service owns the request and its rules; this checks what only the BFF can (a signed-in shopper, a
// well-formed request) and relays it with the shopper's own token, so orders only ever shows a shopper their own requests.
type DeliveryQuotesService interface {
	// Request asks for a quote for the cart and a saved address; the same cart and address returns the same request.
	Request(ctx context.Context, c *domain.Caller, req domain.NewQuoteRequest) (domain.QuoteRequest, error)
	// Mine lists the shopper's requests, newest first ("My quote requests").
	Mine(ctx context.Context, c *domain.Caller) (domain.Page[domain.QuoteRequest], error)
	Get(ctx context.Context, c *domain.Caller, id string) (domain.QuoteRequest, error)
}

type deliveryQuotesServiceImpl struct{ quotes port.CustomerQuotesClient }

var _ DeliveryQuotesService = (*deliveryQuotesServiceImpl)(nil)

// NewDeliveryQuotesService returns the DeliveryQuotesService.
func NewDeliveryQuotesService(quotes port.CustomerQuotesClient) DeliveryQuotesService {
	return &deliveryQuotesServiceImpl{quotes: quotes}
}

const quoteNoteMax = 500

var quoteContacts = map[string]bool{"EMAIL": true, "PHONE": true, "SMS": true}

func (s *deliveryQuotesServiceImpl) Request(ctx context.Context, c *domain.Caller, req domain.NewQuoteRequest) (domain.QuoteRequest, error) {
	// A signed-out shopper signs in first: the request is theirs, and the answer comes to their account.
	if err := requireCustomer(c); err != nil {
		return domain.QuoteRequest{}, err
	}
	errs := map[string]string{}
	if strings.TrimSpace(req.AddressID) == "" {
		errs["addressId"] = "Choose a delivery address"
	}
	contact := strings.ToUpper(strings.TrimSpace(req.Contact))
	if contact != "" && !quoteContacts[contact] {
		errs["contact"] = "Choose email, phone or text"
	}
	note := strings.TrimSpace(req.Note)
	if utf8.RuneCountInString(note) > quoteNoteMax {
		errs["note"] = "At most 500 characters"
	}
	lines := make([]port.UpQuoteRequestLine, 0, len(req.Lines))
	for i, l := range req.Lines {
		if strings.TrimSpace(l.VariantAtin) == "" || l.Quantity < 1 {
			errs["lines."+strconv.Itoa(i)] = "Choose an item and a quantity"
			continue
		}
		lines = append(lines, port.UpQuoteRequestLine{VariantAtin: strings.TrimSpace(l.VariantAtin), Quantity: l.Quantity})
	}
	if len(errs) > 0 {
		return domain.QuoteRequest{}, domain.ValidationProblem(errs)
	}
	out, err := s.quotes.Request(ctx, c.BearerToken, port.UpQuoteRequest{AddressID: strings.TrimSpace(req.AddressID), Lines: lines, Contact: contact, Note: note})
	if err != nil {
		return domain.QuoteRequest{}, quotesErr(err)
	}
	return out, nil
}

func (s *deliveryQuotesServiceImpl) Mine(ctx context.Context, c *domain.Caller) (domain.Page[domain.QuoteRequest], error) {
	if err := requireCustomer(c); err != nil {
		return domain.Page[domain.QuoteRequest]{}, err
	}
	list, err := s.quotes.List(ctx, c.BearerToken)
	if err != nil {
		return domain.Page[domain.QuoteRequest]{}, quotesErr(err)
	}
	if list == nil {
		list = []domain.QuoteRequest{}
	}
	return domain.Page[domain.QuoteRequest]{Items: list}, nil
}

func (s *deliveryQuotesServiceImpl) Get(ctx context.Context, c *domain.Caller, id string) (domain.QuoteRequest, error) {
	if err := requireCustomer(c); err != nil {
		return domain.QuoteRequest{}, err
	}
	out, err := s.quotes.Get(ctx, c.BearerToken, id)
	if err != nil {
		return domain.QuoteRequest{}, quotesErr(err)
	}
	return out, nil
}

// quotesErr turns orders' refusals into the shopper's: a missing or foreign request is "not found", a cart that can't be
// quoted is the plain message orders wrote, too many requests asks to wait.
func quotesErr(err error) error {
	if p, ok := port.AsUpstreamProblem(err); ok && p.Status == 404 {
		return domain.NewProblem(404, domain.CodeNotFound, "Not found", "We couldn't find that quote request.").WithCause(err)
	}
	return mapErr("orders", err)
}
