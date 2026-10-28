package app

import (
	"context"
	"net/http"
	"strings"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

// MembershipService is the shopper's AfroTransact membership (S323, ADR 0006): the tiers staff configure, joining on a
// saved card, upgrading, downgrading and canceling with the benefits running to the end of the paid period, the refund
// rule for the fee, and what the membership does for the cart and checkout. Store credit that cashback or the monthly
// credit puts in the balance can only buy products and services; nothing here pays it out.
type MembershipService interface {
	// Offer is the tiers a shopper can join; it works for visitors who are not signed in.
	Offer(ctx context.Context, c *domain.Caller) (domain.MembershipOffer, error)
	// Account is the shopper's membership with what it gives, the benefits used and the refund rule.
	Account(ctx context.Context, c *domain.Caller) (domain.MembershipAccount, error)
	// Join starts a membership on a saved card (the default one unless named): a trial for first-time members, else the
	// first period is charged now. A member on free days continues on the card when they are over.
	Join(ctx context.Context, c *domain.Caller, key string, req domain.MembershipJoinRequest) (domain.MembershipAccount, error)
	// Change upgrades now (paying the difference for the rest of the period) or schedules a lower tier or another
	// interval for the end of the period; naming the current plan takes a scheduled change back.
	Change(ctx context.Context, c *domain.Caller, key string, req domain.MembershipJoinRequest) (domain.MembershipAccount, error)
	// Cancel stops renewing (the benefits run to the end of the period); with refund the latest fee goes back to the
	// card and the membership ends now, which the refund rule allows only early and before any benefit was used.
	Cancel(ctx context.Context, c *domain.Caller, refund bool) (domain.MembershipAccount, error)
	// Resume takes back a cancellation or a scheduled change.
	Resume(ctx context.Context, c *domain.Caller) (domain.MembershipAccount, error)
	// Benefits lists the benefits the shopper used, newest first.
	Benefits(ctx context.Context, c *domain.Caller, limit int) (domain.Page[domain.MembershipBenefitUse], error)
	// CartSavings is what the membership does for the shopper's cart: each delivery fee waived and, for a shopper who is
	// not a member, what joining would save.
	CartSavings(ctx context.Context, c *domain.Caller) (domain.MembershipSavings, error)
}

type membershipServiceImpl struct {
	client port.MembershipClient
	tokens port.ServiceTokens
	carts  CartService
}

var _ MembershipService = (*membershipServiceImpl)(nil)

// NewMembershipService wires the membership use cases. tokens lets visitors who are not signed in see the tiers; carts
// feeds the cart savings.
func NewMembershipService(client port.MembershipClient, tokens port.ServiceTokens, carts CartService) MembershipService {
	return &membershipServiceImpl{client: client, tokens: tokens, carts: carts}
}

const membershipUsesMax = 100

func toBenefits(in []port.UpMembershipBenefit) []domain.MembershipBenefit {
	out := make([]domain.MembershipBenefit, 0, len(in))
	for _, b := range in {
		out = append(out, domain.MembershipBenefit{Code: b.Code, MinOrderMinor: b.MinOrderMinor, CapMinor: b.CapMinor, PercentBps: b.PercentBps,
			Hours: b.Hours, AmountMinor: b.AmountMinor})
	}
	return out
}

func toMembershipTier(t port.UpMembershipTier) domain.MembershipTier {
	return domain.MembershipTier{Code: t.Code, Name: t.Name, Description: t.Description, SortOrder: t.SortOrder,
		Currency: firstNonEmpty(t.Currency, domain.Currency), MonthlyPriceMinor: t.MonthlyPriceMinor, YearlyPriceMinor: t.YearlyPriceMinor,
		TrialDays: t.TrialDays, Benefits: toBenefits(t.Benefits)}
}

func toMembershipTiers(in []port.UpMembershipTier) []domain.MembershipTier {
	out := make([]domain.MembershipTier, 0, len(in))
	for _, t := range in {
		out = append(out, toMembershipTier(t))
	}
	return out
}

func tierPtr(t *port.UpMembershipTier) *domain.MembershipTier {
	if t == nil {
		return nil
	}
	v := toMembershipTier(*t)
	return &v
}

func (s *membershipServiceImpl) Offer(ctx context.Context, c *domain.Caller) (domain.MembershipOffer, error) {
	bearer := ""
	if c != nil && c.Authenticated() {
		bearer = c.BearerToken
	} else if s.tokens != nil {
		t, err := s.tokens.Token(ctx, "")
		if err != nil {
			return domain.MembershipOffer{}, mapErr("identity", err)
		}
		bearer = t
	}
	tiers, err := s.client.Tiers(ctx, bearer)
	if err != nil {
		return domain.MembershipOffer{}, membershipErr(err)
	}
	return domain.MembershipOffer{Tiers: toMembershipTiers(tiers)}, nil
}

func (s *membershipServiceImpl) account(ctx context.Context, bearer string, v port.UpMyMembership) (domain.MembershipAccount, error) {
	tiers, err := s.client.Tiers(ctx, bearer)
	if err != nil {
		return domain.MembershipAccount{}, membershipErr(err)
	}
	out := domain.MembershipAccount{Tier: tierPtr(v.Tier), PendingTier: tierPtr(v.PendingTier), Charges: []domain.MembershipCharge{},
		Usage: []domain.MembershipUsage{}, TrialAvailable: v.TrialAvailable, Tiers: toMembershipTiers(tiers),
		Refund: domain.MembershipRefundRule{Eligible: v.Refund.Eligible, Reason: v.Refund.Reason, AmountMinor: v.Refund.AmountMinor,
			Currency: firstNonEmpty(v.Refund.Currency, domain.Currency), Until: v.Refund.Until, WindowDays: v.RefundWindowDays}}
	if m := v.Membership; m != nil {
		sum := domain.MembershipSummary{Status: m.Status, Source: m.Source, TierCode: m.TierCode, Interval: m.Interval, PriceMinor: m.PriceMinor,
			Currency: firstNonEmpty(m.Currency, domain.Currency), StartedAt: m.StartedAt, PeriodEnd: m.PeriodEnd, TrialEnd: m.TrialEnd,
			CancelAtPeriodEnd: m.CancelAtPeriodEnd, EndedAt: m.EndedAt, PendingTierCode: m.PendingTierCode, PendingInterval: m.PendingInterval,
			NextRetryAt: m.NextRetryAt, FreeDaysGranted: m.FreeDaysGranted}
		if v.Tier != nil {
			sum.TierName = v.Tier.Name
		}
		if v.PendingTier != nil {
			sum.PendingTierName = ptr(v.PendingTier.Name)
		}
		out.Membership = &sum
		out.Member = m.Status == "TRIALING" || m.Status == "ACTIVE" || m.Status == "PAST_DUE"
	}
	for _, ch := range v.Charges {
		out.Charges = append(out.Charges, domain.MembershipCharge{Kind: ch.Kind, AmountMinor: ch.AmountMinor, Currency: firstNonEmpty(ch.Currency, domain.Currency),
			Status: ch.Status, RefundedMinor: ch.RefundedMinor, PeriodEnd: ch.PeriodEnd, CreatedAt: ch.CreatedAt})
	}
	for _, u := range v.Usage {
		out.Usage = append(out.Usage, domain.MembershipUsage{Benefit: u.Benefit, Uses: u.Uses, AmountMinor: u.AmountMinor,
			Currency: firstNonEmpty(u.Currency, domain.Currency)})
	}
	if cd := v.Card; cd != nil {
		out.Card = &domain.MembershipCard{Brand: cd.Brand, Last4: cd.Last4, ExpMonth: cd.ExpMonth, ExpYear: cd.ExpYear}
	}
	return out, nil
}

func (s *membershipServiceImpl) Account(ctx context.Context, c *domain.Caller) (domain.MembershipAccount, error) {
	if err := requireCustomer(c); err != nil {
		return domain.MembershipAccount{}, err
	}
	v, err := s.client.Me(ctx, c.BearerToken)
	if err != nil {
		return domain.MembershipAccount{}, membershipErr(err)
	}
	return s.account(ctx, c.BearerToken, v)
}

func validateMembershipPlan(req *domain.MembershipJoinRequest) error {
	req.TierCode = strings.TrimSpace(req.TierCode)
	req.Interval = strings.ToUpper(strings.TrimSpace(req.Interval))
	errs := map[string]string{}
	if req.TierCode == "" || len(req.TierCode) > 40 {
		errs["tierCode"] = "Choose a membership"
	}
	if req.Interval != "MONTHLY" && req.Interval != "YEARLY" {
		errs["interval"] = "Choose monthly or yearly"
	}
	if len(req.PaymentMethodID) > 120 {
		errs["paymentMethodId"] = "Choose one of your saved cards"
	}
	if len(errs) > 0 {
		return domain.ValidationProblem(errs)
	}
	return nil
}

func checkMembershipKey(key string) error {
	if l := len(key); l < 16 || l > 64 {
		return domain.ValidationProblem(map[string]string{"Idempotency-Key": "Send a 16–64 character Idempotency-Key header"})
	}
	return nil
}

func (s *membershipServiceImpl) Join(ctx context.Context, c *domain.Caller, key string, req domain.MembershipJoinRequest) (domain.MembershipAccount, error) {
	return s.move(ctx, c, key, req, s.client.Join)
}

func (s *membershipServiceImpl) Change(ctx context.Context, c *domain.Caller, key string, req domain.MembershipJoinRequest) (domain.MembershipAccount, error) {
	return s.move(ctx, c, key, req, s.client.Change)
}

type membershipMove func(ctx context.Context, bearer, key string, req port.UpMembershipJoin) (port.UpMyMembership, error)

func (s *membershipServiceImpl) move(ctx context.Context, c *domain.Caller, key string, req domain.MembershipJoinRequest, do membershipMove) (domain.MembershipAccount, error) {
	if err := requireCustomer(c); err != nil {
		return domain.MembershipAccount{}, err
	}
	if err := checkMembershipKey(key); err != nil {
		return domain.MembershipAccount{}, err
	}
	if err := validateMembershipPlan(&req); err != nil {
		return domain.MembershipAccount{}, err
	}
	v, err := do(ctx, c.BearerToken, key, port.UpMembershipJoin{TierCode: req.TierCode, Interval: req.Interval, PaymentMethodID: req.PaymentMethodID})
	if err != nil {
		return domain.MembershipAccount{}, membershipErr(err)
	}
	return s.account(ctx, c.BearerToken, v)
}

func (s *membershipServiceImpl) Cancel(ctx context.Context, c *domain.Caller, refund bool) (domain.MembershipAccount, error) {
	if err := requireCustomer(c); err != nil {
		return domain.MembershipAccount{}, err
	}
	v, err := s.client.Cancel(ctx, c.BearerToken, refund)
	if err != nil {
		return domain.MembershipAccount{}, membershipErr(err)
	}
	return s.account(ctx, c.BearerToken, v)
}

func (s *membershipServiceImpl) Resume(ctx context.Context, c *domain.Caller) (domain.MembershipAccount, error) {
	if err := requireCustomer(c); err != nil {
		return domain.MembershipAccount{}, err
	}
	v, err := s.client.Resume(ctx, c.BearerToken)
	if err != nil {
		return domain.MembershipAccount{}, membershipErr(err)
	}
	return s.account(ctx, c.BearerToken, v)
}

func (s *membershipServiceImpl) Benefits(ctx context.Context, c *domain.Caller, limit int) (domain.Page[domain.MembershipBenefitUse], error) {
	if err := requireCustomer(c); err != nil {
		return domain.Page[domain.MembershipBenefitUse]{}, err
	}
	if limit <= 0 {
		limit = 20
	}
	rows, err := s.client.Uses(ctx, c.BearerToken, min(limit, membershipUsesMax))
	if err != nil {
		return domain.Page[domain.MembershipBenefitUse]{}, membershipErr(err)
	}
	items := make([]domain.MembershipBenefitUse, 0, len(rows))
	for _, u := range rows {
		items = append(items, domain.MembershipBenefitUse{Benefit: u.Benefit, TierName: u.TierCode, AmountMinor: u.AmountMinor,
			Currency: firstNonEmpty(u.Currency, domain.Currency), CreatedAt: u.CreatedAt})
	}
	return domain.Page[domain.MembershipBenefitUse]{Items: items}, nil
}

func (s *membershipServiceImpl) CartSavings(ctx context.Context, c *domain.Caller) (domain.MembershipSavings, error) {
	if err := requireCustomer(c); err != nil {
		return domain.MembershipSavings{}, err
	}
	cart, err := s.carts.Get(ctx, c)
	if err != nil {
		return domain.MembershipSavings{}, err
	}
	var pkgs []domain.CheckoutPackage
	for _, p := range cart.Packages {
		if p.ItemCount > 0 {
			pkgs = append(pkgs, domain.CheckoutPackage{CartPackage: p})
		}
	}
	return quoteMembership(ctx, s.client, c, pkgs, nil, firstNonEmpty(cart.Currency, domain.Currency)), nil
}

// ---------------------------------------------------------------- savings of an order's fees

// membershipFeeKind says which kind of delivery fee a chosen delivery option is: only AfroTransact Delivery and standard
// shipping can be waived; pickup and express never are.
func membershipFeeKind(optionKey string) string {
	switch optionKey {
	case "afrotransact_delivery":
		return "AFROTRANSACT_DELIVERY"
	case "express":
		return "EXPRESS"
	case "pickup":
		return "PICKUP"
	default:
		return "CARRIER"
	}
}

// chosenOption is the delivery option a package ships with: the shopper's choice, else the first one.
func chosenOption(p domain.CheckoutPackage, packageDelivery map[string]string) (domain.DeliveryOption, bool) {
	key := packageDelivery[p.PackageID]
	for _, o := range p.DeliveryOptions {
		if key != "" && o.Key == key {
			return o, true
		}
	}
	if len(p.DeliveryOptions) > 0 {
		return p.DeliveryOptions[0], true
	}
	return domain.DeliveryOption{}, false
}

// quoteMembership asks payments which of the packages' fees the shopper's membership waives (the one place the rule is
// applied: order minimum and per-order cap), and what joining would save a shopper who is not a member. It is best
// effort: without an answer the page shows the fees as they are.
func quoteMembership(ctx context.Context, client port.MembershipClient, c *domain.Caller, pkgs []domain.CheckoutPackage,
	packageDelivery map[string]string, currency string) domain.MembershipSavings {
	out := domain.MembershipSavings{Waivers: []domain.MembershipWaiver{}}
	if client == nil || c == nil || !c.Authenticated() || len(pkgs) == 0 {
		return out
	}
	var items int64
	fees := make([]port.UpMembershipFee, 0, len(pkgs))
	seller := map[string]string{}
	for _, p := range pkgs {
		items += p.SubtotalMinor
		seller[p.PackageID] = p.Seller.Name
		if o, ok := chosenOption(p, packageDelivery); ok && o.FeeMinor > 0 {
			fees = append(fees, port.UpMembershipFee{Key: p.PackageID, Kind: membershipFeeKind(o.Key), FeeMinor: o.FeeMinor})
		}
	}
	q, err := client.Savings(ctx, c.BearerToken, port.UpMembershipSavingsRequest{ItemsMinor: items, Currency: currency, Fees: fees})
	if err != nil {
		return out
	}
	out.Member = q.Member
	if q.Member {
		out.TierName = ptr(firstNonEmpty(q.TierName, q.TierCode))
		out.CashbackMinor = q.CashbackMinor
	}
	for _, w := range q.Waivers {
		out.Waivers = append(out.Waivers, domain.MembershipWaiver{PackageID: w.Key, Seller: seller[w.Key], Benefit: w.Benefit, WaivedMinor: w.WaivedMinor})
		out.TotalWaivedMinor += w.WaivedMinor
	}
	if u := q.Upsell; u != nil && !q.Member {
		out.Upsell = &domain.MembershipUpsell{TierCode: u.TierCode, TierName: u.TierName, MonthlyPriceMinor: u.MonthlyPriceMinor,
			YearlyPriceMinor: u.YearlyPriceMinor, TrialDays: u.TrialDays, SavingMinor: u.SavingMinor, Currency: firstNonEmpty(u.Currency, currency)}
	}
	return out
}

// priceMembership applies the shopper's membership to the checkout page (S323): each delivery fee it waives is shown as
// "fee waived: membership", the total is lower by that amount, and a shopper who is not a member sees what joining
// would save on this order. Orders applies the same rule (payments resolves it) when the order is placed.
func (s *checkoutPaymentServiceImpl) priceMembership(ctx context.Context, c *domain.Caller, sum *domain.CheckoutSummary,
	packageDelivery map[string]string) {
	sum.Membership, sum.MembershipWaivedMinor = nil, 0
	for i := range sum.Packages {
		sum.Packages[i].MembershipWaivedMinor = 0
	}
	if s.d.Membership == nil || !c.Authenticated() || len(sum.Packages) == 0 {
		return
	}
	q := quoteMembership(ctx, s.d.Membership, c, sum.Packages, packageDelivery, firstNonEmpty(sum.Currency, domain.Currency))
	if !q.Member && q.Upsell == nil {
		return
	}
	for i := range sum.Packages {
		p := &sum.Packages[i]
		for _, w := range q.Waivers {
			if w.PackageID == p.PackageID {
				p.MembershipWaivedMinor = min(w.WaivedMinor, packageShippingFee(*p, packageDelivery))
				sum.MembershipWaivedMinor += p.MembershipWaivedMinor
			}
		}
	}
	sum.Membership = &q
}

// membershipErr names the answers of payments a shopper can act on; everything else is the usual mapping.
func membershipErr(err error) error {
	if p, ok := port.AsUpstreamProblem(err); ok {
		switch {
		case p.Code == "payments.membership-exists":
			return domain.NewProblem(http.StatusConflict, domain.CodeMembershipExists, "Already a member",
				"You are already a member. Change your plan instead.").WithCause(err)
		case p.Code == "payments.membership-not-found":
			return domain.NewProblem(http.StatusNotFound, domain.CodeMembershipNotFound, "Not a member", "You are not a member yet.").WithCause(err)
		case p.Code == "payments.membership-tier-not-found" || p.Code == "payments.membership-tier-unavailable":
			return domain.NewProblem(http.StatusConflict, domain.CodeMembershipTierUnavailable, "Membership not available",
				"That membership is not open to new members.").WithCause(err)
		case p.Code == "payments.no-saved-method":
			return domain.NewProblem(http.StatusUnprocessableEntity, domain.CodeMembershipNoCard, "Save a card first",
				"Membership is billed to a saved card. Add a card in Your Payments and try again.").WithCause(err)
		case p.Code == "payments.membership-charge-failed":
			return domain.NewProblem(http.StatusUnprocessableEntity, domain.CodeMembershipChargeFailed, "Card declined",
				"Your card was not charged and nothing changed. Try another card.").WithCause(err)
		case p.Code == "payments.membership-authentication-required":
			return domain.NewProblem(http.StatusConflict, domain.CodeMembershipAuthRequired, "Your bank needs a confirmation",
				"Your bank asks you to confirm this payment. Try another card.").WithCause(err)
		case p.Code == "payments.membership-refund-not-eligible":
			return domain.NewProblem(http.StatusConflict, domain.CodeMembershipRefundNotEligible, "No refund",
				"The fee can no longer be refunded. You can still cancel and keep your benefits until the period ends.").WithCause(err)
		case p.Code == "payments.membership-past-due":
			return domain.NewProblem(http.StatusConflict, domain.CodeMembershipPastDue, "Payment needs attention",
				"Your last payment failed. Update your card and wait for the retry before changing plan.").WithCause(err)
		case p.Code == "payments.membership-no-change":
			return domain.NewProblem(http.StatusConflict, domain.CodeMembershipNoChange, "Nothing to change", "That is your current plan.").WithCause(err)
		case p.Code == "payments.membership-complimentary":
			return domain.NewProblem(http.StatusConflict, domain.CodeMembershipComplimentary, "Free days",
				"Your membership is on free days. Join on your card to continue after them.").WithCause(err)
		}
	}
	return mapErr("payments", err)
}
