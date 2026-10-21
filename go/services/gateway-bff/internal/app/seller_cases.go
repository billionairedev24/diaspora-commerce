package app

import (
	"context"
	"strings"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
)

// SellerCasesService is Returns and Disputes in Seller Center: what customers sent back or contested on the organisation's
// orders. Read only: tax-returns and trust-ops scope everything to the organisation and authorise the member again.
type SellerCasesService interface {
	Returns(ctx context.Context, c *domain.Caller, status string) ([]domain.SellerReturn, error)
	Disputes(ctx context.Context, c *domain.Caller, status string) ([]domain.SellerDispute, error)
}

type sellerCasesImpl struct{ r *orgResolver }

var _ SellerCasesService = (*sellerCasesImpl)(nil)

// NewSellerCasesService returns the SellerCasesService.
func NewSellerCasesService(d SellerDeps) SellerCasesService {
	return &sellerCasesImpl{r: newResolver(d)}
}

var returnStatusLabels = map[string]string{
	"REQUESTED": "Waiting for approval", "APPROVED": "Approved", "AUTO_APPROVED": "Approved", "LABEL_ISSUED": "Label issued",
	"IN_TRANSIT": "On its way back", "RECEIVED": "Received", "INSPECTED": "Inspected", "REFUNDED": "Refunded", "REJECTED": "Rejected",
	"CANCELLED": "Cancelled",
}

var disputeStatusLabels = map[string]string{
	"AWAITING_SELLER": "Waiting for your response", "UNDER_REVIEW": "Under review", "ESCALATED": "Escalated", "RESOLVED_REFUND": "Resolved: refunded",
	"RESOLVED_DENIED": "Resolved: denied", "WITHDRAWN": "Withdrawn",
}

var disputeTypeLabels = map[string]string{
	"ITEM_NOT_RECEIVED": "Item not received", "DAMAGED_ITEM": "Damaged item", "WRONG_ITEM": "Wrong item", "NOT_AS_DESCRIBED": "Not as described",
	"BOOKING_NO_SHOW": "Booking no-show", "OTHER": "Other",
}

func labelOf(m map[string]string, key string) string {
	if l, ok := m[strings.ToUpper(key)]; ok {
		return l
	}
	return strings.ToLower(strings.ReplaceAll(key, "_", " "))
}

func (s *sellerCasesImpl) Returns(ctx context.Context, c *domain.Caller, status string) ([]domain.SellerReturn, error) {
	if _, err := s.r.require(ctx, c, domain.OrgKindSeller, "returns", domain.LevelView); err != nil {
		return nil, err
	}
	if s.r.d.Cases == nil {
		return nil, domain.UpstreamUnavailable("tax-returns")
	}
	raw, err := s.r.d.Cases.Returns(ctx, c.BearerToken, strings.ToUpper(status))
	if err != nil {
		return nil, mapErr("tax-returns", err)
	}
	out := make([]domain.SellerReturn, 0, len(raw))
	for _, r := range raw {
		sum := toReturnSummary(r)
		items := make([]domain.SellerReturnLine, 0, len(r.Lines))
		for _, l := range r.Lines {
			items = append(items, domain.SellerReturnLine{Title: l.Title, Quantity: l.Quantity, Reason: l.Reason, Outcome: l.Outcome})
		}
		out = append(out, domain.SellerReturn{Number: r.Number, OrderNumber: r.OrderNumber, Title: sum.Title, Status: strings.ToUpper(r.Status),
			StatusLabel: labelOf(returnStatusLabels, r.Status), Method: r.Method, RefundMinor: r.EstimateMinor, Currency: sum.Currency, RefundState: r.RefundState,
			Items: items, Carrier: optStr(r.Carrier), Tracking: optStr(r.Tracking), DropOffBy: optStr(r.DropOffBy), CreatedAt: optStr(r.CreatedAt)})
	}
	return out, nil
}

func (s *sellerCasesImpl) Disputes(ctx context.Context, c *domain.Caller, status string) ([]domain.SellerDispute, error) {
	sc, err := s.r.require(ctx, c, domain.OrgKindSeller, "disputes", domain.LevelView)
	if err != nil {
		return nil, err
	}
	if s.r.d.Cases == nil {
		return nil, domain.UpstreamUnavailable("trust-ops")
	}
	raw, err := s.r.d.Cases.Claims(ctx, c.BearerToken, sc.org.ID, strings.ToUpper(status))
	if err != nil {
		return nil, mapErr("trust-ops", err)
	}
	out := make([]domain.SellerDispute, 0, len(raw))
	for _, x := range raw {
		d := domain.SellerDispute{Reference: x.Reference, Type: x.Type, TypeLabel: labelOf(disputeTypeLabels, x.Type), Status: x.Status,
			StatusLabel: labelOf(disputeStatusLabels, x.Status), OrderNumber: x.OrderNumber, AmountMinor: x.AmountMinor,
			Currency: firstNonEmpty(x.Currency, domain.Currency), Description: x.Description, Responded: x.SellerResponse != "",
			SellerResponse: optStr(x.SellerResponse), Escalated: x.Escalated, Outcome: optStr(x.Outcome), RefundMinor: x.RefundMinor, OpenedAt: optStr(x.OpenedAt)}
		if x.SLA != nil && x.Status == "AWAITING_SELLER" {
			d.RespondBy, d.DeadlineState = optStr(x.SLA.DueAt), optStr(x.SLA.State)
		}
		out = append(out, d)
	}
	return out, nil
}
