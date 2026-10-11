package app

import (
	"context"
	"net/http"
	"slices"
	"strings"
	"sync"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

// SellerMoneyService serves payouts (payments) and plan & billing (seller service).
type SellerMoneyService interface {
	Payouts(ctx context.Context, c *domain.Caller) (domain.PayoutsView, error)
	RequestPayout(ctx context.Context, c *domain.Caller, idempotencyKey string, amountMinor int64, all bool) (domain.Payout, error)
	Billing(ctx context.Context, c *domain.Caller) (domain.BillingView, error)
	ChangePlan(ctx context.Context, c *domain.Caller, planCode, cadence string) (domain.ChangePlanResult, error)
	MakeDefaultPaymentMethod(ctx context.Context, c *domain.Caller, id string) ([]domain.BillingPaymentMethod, error)
	RemovePaymentMethod(ctx context.Context, c *domain.Caller, id string) ([]domain.BillingPaymentMethod, error)
	// Rewards lists the rewards the organisation can earn (with progress) and what it received (S296).
	Rewards(ctx context.Context, c *domain.Caller) (domain.SellerRewardsView, error)
	// PreviewPlanChange says what a plan or cadence change would do, with Stripe's proration (nothing changes).
	PreviewPlanChange(ctx context.Context, c *domain.Caller, planCode, cadence string) (domain.PlanChangePreview, error)
	// SetupIntent starts adding a card or bank account with the Payment Element (owner only).
	SetupIntent(ctx context.Context, c *domain.Caller) (domain.BillingSetupIntent, error)
	// AddPaymentMethod registers the payment method the SetupIntent confirmed (owner only).
	AddPaymentMethod(ctx context.Context, c *domain.Caller, in domain.AddBillingPaymentMethod) (domain.BillingPaymentMethod, error)
}

type sellerMoneyImpl struct{ r *orgResolver }

var _ SellerMoneyService = (*sellerMoneyImpl)(nil)

// NewSellerMoneyService returns the SellerMoneyService.
func NewSellerMoneyService(d SellerDeps) SellerMoneyService {
	return &sellerMoneyImpl{r: newResolver(d)}
}

func payoutOf(p port.PayoutResponse) domain.Payout {
	return domain.Payout{ID: p.ID, AmountMinor: p.AmountMinor, Currency: firstNonEmpty(p.Currency, domain.Currency), Status: strings.ToUpper(p.Status),
		RequestedAt: p.RequestedAt, HoldReason: strPtr(p.HoldReason), FailureMessage: strPtr(p.FailureMessage)}
}

func (s *sellerMoneyImpl) Payouts(ctx context.Context, c *domain.Caller) (domain.PayoutsView, error) {
	sc, err := s.r.require(ctx, c, "", "payouts", domain.LevelView)
	if err != nil {
		return domain.PayoutsView{}, err
	}
	var (
		wg      sync.WaitGroup
		bal     port.PayoutBalanceResponse
		balErr  error
		history []port.PayoutResponse
		histErr error
	)
	wg.Go(func() { bal, balErr = s.r.d.Payouts.Balance(ctx, c.BearerToken, sc.org.ID) })
	wg.Go(func() { history, histErr = s.r.d.Payouts.Requests(ctx, c.BearerToken, sc.org.ID) })
	wg.Wait()
	if balErr != nil {
		return domain.PayoutsView{}, mapErr("payments", balErr)
	}
	out := domain.PayoutsView{Balance: domain.PayoutBalance{Currency: firstNonEmpty(bal.Currency, domain.Currency), AvailableMinor: bal.AvailableMinor,
		OnHoldMinor: bal.OnHoldMinor, RequestedOrInTransitMinor: bal.RequestedOrInTransitMinor, PaidMinor: bal.PaidMinor,
		ProviderFeesMinor: bal.ProviderFeesMinor, MinimumPayoutMinor: bal.MinimumPayoutMinor, CanRequestToday: bal.CanRequestToday && !bal.PayoutsPaused,
		PayoutsPaused: bal.PayoutsPaused, Holds: []domain.PayoutHold{}},
		Payouts: []domain.Payout{}, HistoryAvailable: histErr == nil}
	if bal.PayoutsPaused {
		reason := strings.ToUpper(bal.PayoutsPausedReason)
		if domain.IsPausedReason(reason) {
			out.Balance.PayoutsPausedReason = &reason
		}
		msg := domain.PayoutsPausedMessage(reason)
		out.Balance.PayoutsPausedMessage = &msg
	}
	for _, h := range bal.OnHold {
		out.Balance.Holds = append(out.Balance.Holds, domain.PayoutHold{OrderID: h.OrderID, AmountMinor: h.AmountMinor, ReleaseAt: strPtr(h.ReleaseAt)})
	}
	for _, p := range history {
		out.Payouts = append(out.Payouts, payoutOf(p))
	}
	return out, nil
}

func (s *sellerMoneyImpl) RequestPayout(ctx context.Context, c *domain.Caller, key string, amountMinor int64, all bool) (domain.Payout, error) {
	if !all && amountMinor <= 0 {
		return domain.Payout{}, domain.ValidationProblem(map[string]string{"amountMinor": "Enter an amount or request everything available"})
	}
	if _, err := s.r.require(ctx, c, "", "payouts", domain.LevelEdit); err != nil {
		return domain.Payout{}, err
	}
	p, err := s.r.d.Payouts.RequestPayout(ctx, c.BearerToken, key, amountMinor, all)
	if err != nil {
		return domain.Payout{}, mapErr("payments", err)
	}
	return payoutOf(p), nil
}

func isOwner(role string) bool { return strings.HasSuffix(role, ".owner") }

func cadenceOf(v string) string {
	switch strings.ToUpper(v) {
	case "QUARTERLY", "QUARTER":
		return "QUARTERLY"
	case "ANNUAL", "YEAR", "ANNUALLY":
		return "ANNUAL"
	default:
		return "MONTHLY"
	}
}

func paymentMethodOf(p port.SellerPaymentMethod) domain.BillingPaymentMethod {
	kind := "card"
	if strings.EqualFold(p.Kind, "bank") || strings.EqualFold(p.Kind, "us_bank_account") || p.BankName != "" {
		kind = "bank"
	}
	out := domain.BillingPaymentMethod{ID: p.ID, Kind: kind, Brand: strPtr(strings.ToLower(p.Brand)), Last4: p.Last4, BankName: strPtr(p.BankName),
		AccountType: strPtr(p.AccountType), Verified: p.Verified, ReceivesPayouts: p.ReceivesPayouts, IsDefault: p.IsDefault, Label: p.Label}
	if p.ExpMonth > 0 {
		out.ExpMonth, out.ExpYear = &p.ExpMonth, &p.ExpYear
	}
	if out.Label == "" {
		if kind == "bank" {
			out.Label = firstNonEmpty(p.BankName, "Bank account") + " ••" + p.Last4
		} else {
			out.Label = firstNonEmpty(p.Brand, "Card") + " ••" + p.Last4
		}
	}
	return out
}

func paymentMethodsOf(list []port.SellerPaymentMethod) []domain.BillingPaymentMethod {
	out := make([]domain.BillingPaymentMethod, 0, len(list))
	for _, p := range list {
		out = append(out, paymentMethodOf(p))
	}
	return out
}

func priceOf(p port.SellerPlanPrice) domain.PlanPrice {
	return domain.PlanPrice{Cadence: cadenceOf(p.Cadence), Months: max(1, p.Months), TotalMinor: p.TotalCents, PerMonthMinor: p.PerMonthCents,
		SavingMinor: p.SavingCents, PerMonthLabel: p.PerMonthLabel, BilledLabel: p.BilledLabel, SavingLabel: p.SavingLabel}
}

func (s *sellerMoneyImpl) billingView(b port.SellerBilling, plans []port.SellerPlan, invoices []port.SellerInvoice, role string) domain.BillingView {
	sub := domain.Subscription{PlanCode: b.PlanCode, PlanName: b.PlanName, Cadence: cadenceOf(b.Cadence), Status: strings.ToUpper(b.Status),
		TrialEndsAt: b.TrialEndsAt, CurrentPeriodEnd: b.CurrentPeriodEnd, NextChargeAt: b.NextChargeAt, NextChargeMinor: b.NextChargeCents,
		NextChargeLabel: b.NextChargeLabel, CancelAtPeriodEnd: b.CancelAtPeriodEnd, Dunning: strPtr(b.Dunning),
		PlanVersion: max(1, b.PlanVersion), CreditBalanceMinor: max(0, b.CreditBalanceCents), TrialSource: strPtr(b.TrialSource)}
	if b.Price != nil {
		sub.PriceLabel, sub.BilledLabel = b.Price.PerMonthLabel, b.Price.BilledLabel
	}
	if b.PendingChange != nil {
		sub.PendingChange = &domain.PendingPlanChange{PlanCode: b.PendingChange.PlanCode, PlanName: b.PendingChange.PlanName,
			Cadence: cadenceOf(b.PendingChange.Cadence), EffectiveAt: b.PendingChange.EffectiveAt}
	}
	out := domain.BillingView{Subscription: sub, Plans: []domain.Plan{}, PaymentMethods: paymentMethodsOf(b.PaymentMethods), Invoices: []domain.Invoice{},
		CanManage: isOwner(role)}
	for _, p := range plans {
		plan := domain.Plan{Code: p.Code, Name: p.Name, Tagline: p.Tagline, MonthlyMinor: p.MonthlyCents, TrialDays: p.TrialDays,
			Features: append([]string{}, p.Features...), Prices: []domain.PlanPrice{}, Current: p.Code == b.PlanCode}
		for _, pr := range p.Prices {
			plan.Prices = append(plan.Prices, priceOf(pr))
		}
		out.Plans = append(out.Plans, plan)
	}
	for _, in := range invoices {
		out.Invoices = append(out.Invoices, domain.Invoice{ID: in.ID, Number: firstNonEmpty(in.Number, in.ID), PeriodStart: in.PeriodStart, PeriodEnd: in.PeriodEnd,
			AmountDueMinor: in.AmountDueCents, AmountPaidMinor: in.AmountPaidCents, Currency: strings.ToUpper(firstNonEmpty(in.Currency, domain.Currency)),
			Status: strings.ToUpper(in.Status), PDFURL: strPtr(in.InvoicePDF), HostedURL: strPtr(in.HostedInvoiceURL), CreatedAt: in.CreatedAt})
	}
	return out
}

func (s *sellerMoneyImpl) load(ctx context.Context, c *domain.Caller, sc sellerCtx) (domain.BillingView, error) {
	var (
		wg       sync.WaitGroup
		b        port.SellerBilling
		bErr     error
		plans    []port.SellerPlan
		invoices []port.SellerInvoice
	)
	wg.Go(func() { b, bErr = s.r.d.Sellers.Billing(ctx, c.BearerToken, sc.org.ID) })
	// The plans this organisation may choose: public, invited and its current plan (our catalogue, S292).
	wg.Go(func() { plans, _ = s.r.d.Sellers.OrgPlans(ctx, c.BearerToken, sc.org.ID) })
	wg.Go(func() { invoices, _ = s.r.d.Sellers.Invoices(ctx, c.BearerToken, sc.org.ID) })
	wg.Wait()
	if bErr != nil {
		return domain.BillingView{}, mapErr("seller", bErr)
	}
	return s.billingView(b, plans, invoices, sc.org.Role), nil
}

func (s *sellerMoneyImpl) Billing(ctx context.Context, c *domain.Caller) (domain.BillingView, error) {
	sc, err := s.r.require(ctx, c, "", "billing", domain.LevelView)
	if err != nil {
		return domain.BillingView{}, err
	}
	return s.load(ctx, c, sc)
}

func ownerOnly() error {
	return domain.NewProblem(http.StatusForbidden, domain.CodeForbidden, "Owner only", "Only the owner can change billing.")
}

func (s *sellerMoneyImpl) ChangePlan(ctx context.Context, c *domain.Caller, planCode, cadence string) (domain.ChangePlanResult, error) {
	errs := map[string]string{}
	if strings.TrimSpace(planCode) == "" {
		errs["planCode"] = "Choose a plan"
	}
	if !slices.Contains(domain.Cadences, cadence) {
		errs["cadence"] = "Choose monthly, quarterly or annual billing"
	}
	if len(errs) > 0 {
		return domain.ChangePlanResult{}, domain.ValidationProblem(errs)
	}
	sc, err := s.r.require(ctx, c, "", "billing", domain.LevelView)
	if err != nil {
		return domain.ChangePlanResult{}, err
	}
	if !isOwner(sc.org.Role) {
		return domain.ChangePlanResult{}, ownerOnly()
	}
	applied, _, err := s.r.d.Sellers.ChangePlan(ctx, c.BearerToken, sc.org.ID, planCode, cadence)
	if err != nil {
		return domain.ChangePlanResult{}, mapErr("seller", err)
	}
	view, err := s.load(ctx, c, sc)
	if err != nil {
		return domain.ChangePlanResult{}, err
	}
	switch applied = strings.ToUpper(applied); applied {
	case "IMMEDIATE_UPGRADE", "AT_RENEWAL", "NO_CHANGE":
	default:
		applied = "AT_RENEWAL"
	}
	return domain.ChangePlanResult{Applied: applied, Billing: view}, nil
}

func (s *sellerMoneyImpl) paymentMethodChange(ctx context.Context, c *domain.Caller, op func(bearer, orgID string) ([]port.SellerPaymentMethod, error)) ([]domain.BillingPaymentMethod, error) {
	sc, err := s.r.require(ctx, c, "", "billing", domain.LevelView)
	if err != nil {
		return nil, err
	}
	if !isOwner(sc.org.Role) {
		return nil, ownerOnly()
	}
	list, err := op(c.BearerToken, sc.org.ID)
	if err != nil {
		return nil, mapErr("seller", err)
	}
	return paymentMethodsOf(list), nil
}

func (s *sellerMoneyImpl) MakeDefaultPaymentMethod(ctx context.Context, c *domain.Caller, id string) ([]domain.BillingPaymentMethod, error) {
	return s.paymentMethodChange(ctx, c, func(bearer, orgID string) ([]port.SellerPaymentMethod, error) {
		return s.r.d.Sellers.MakeDefaultPaymentMethod(ctx, bearer, orgID, id)
	})
}

func (s *sellerMoneyImpl) RemovePaymentMethod(ctx context.Context, c *domain.Caller, id string) ([]domain.BillingPaymentMethod, error) {
	return s.paymentMethodChange(ctx, c, func(bearer, orgID string) ([]port.SellerPaymentMethod, error) {
		return s.r.d.Sellers.RemovePaymentMethod(ctx, bearer, orgID, id)
	})
}

// ---------------------------------------------------------------- rewards, previews, payment methods (S292–S297)

func (s *sellerMoneyImpl) Rewards(ctx context.Context, c *domain.Caller) (domain.SellerRewardsView, error) {
	sc, err := s.r.require(ctx, c, "", "billing", domain.LevelView)
	if err != nil {
		return domain.SellerRewardsView{}, err
	}
	rw, err := s.r.d.Sellers.Rewards(ctx, c.BearerToken, sc.org.ID)
	if err != nil {
		return domain.SellerRewardsView{}, mapErr("seller", err)
	}
	out := domain.SellerRewardsView{Available: []domain.RewardProgress{}, Earned: []domain.EarnedReward{}}
	for _, p := range rw.Available {
		out.Available = append(out.Available, domain.RewardProgress{RuleID: p.RuleID, Name: p.Name, Description: strPtr(p.Description),
			Metric: p.Metric, Value: p.Value, Threshold: p.Threshold, Percent: min(100, max(0, p.Percent)), Qualifies: p.Qualifies,
			Label: p.Label, RewardLabel: p.RewardLabel, ConditionLabel: p.ConditionLabel, EndsAt: p.EndsAt})
	}
	for _, g := range rw.Earned {
		var reason *string
		if g.Source == "RULE" {
			reason = strPtr(g.Note)
		}
		out.Earned = append(out.Earned, domain.EarnedReward{ID: g.ID, Source: g.Source, Type: g.Type, Summary: g.Summary, Reason: reason,
			Status: g.Status, CreditMinor: g.CreditCents, FreeDays: g.FreeDays, CreatedAt: g.CreatedAt, AppliedAt: g.AppliedAt})
	}
	return out, nil
}

func (s *sellerMoneyImpl) PreviewPlanChange(ctx context.Context, c *domain.Caller, planCode, cadence string) (domain.PlanChangePreview, error) {
	if strings.TrimSpace(planCode) == "" {
		return domain.PlanChangePreview{}, domain.ValidationProblem(map[string]string{"planCode": "Choose a plan"})
	}
	cadence = strings.ToUpper(strings.TrimSpace(cadence))
	if cadence != "" && !slices.Contains(domain.Cadences, cadence) {
		return domain.PlanChangePreview{}, domain.ValidationProblem(map[string]string{"cadence": "Choose monthly, quarterly or annual billing"})
	}
	sc, err := s.r.require(ctx, c, "", "billing", domain.LevelView)
	if err != nil {
		return domain.PlanChangePreview{}, err
	}
	p, err := s.r.d.Sellers.PreviewPlanChange(ctx, c.BearerToken, sc.org.ID, planCode, cadence)
	if err != nil {
		return domain.PlanChangePreview{}, mapErr("seller", err)
	}
	return domain.PlanChangePreview{Change: firstNonEmpty(strings.ToUpper(p.Change), "AT_RENEWAL"), PlanCode: p.PlanCode, PlanName: p.PlanName,
		Cadence: cadenceOf(p.Cadence), EffectiveAt: p.EffectiveAt, AmountDueNowMinor: p.AmountDueNowCents, NextChargeMinor: p.NextChargeCents,
		NextChargeAt: p.NextChargeAt, Trialing: p.Trialing}, nil
}

func (s *sellerMoneyImpl) SetupIntent(ctx context.Context, c *domain.Caller) (domain.BillingSetupIntent, error) {
	sc, err := s.r.require(ctx, c, "", "billing", domain.LevelView)
	if err != nil {
		return domain.BillingSetupIntent{}, err
	}
	if !isOwner(sc.org.Role) {
		return domain.BillingSetupIntent{}, ownerOnly()
	}
	si, err := s.r.d.Sellers.CreateSetupIntent(ctx, c.BearerToken, sc.org.ID)
	if err != nil {
		return domain.BillingSetupIntent{}, mapErr("seller", err)
	}
	local := strings.HasPrefix(si.SetupIntentID, "seti_local_")
	return domain.BillingSetupIntent{SetupIntentID: si.SetupIntentID, ClientSecret: si.ClientSecret, PublishableKey: s.r.d.PublishableKey,
		LocalTest: local}, nil
}

func (s *sellerMoneyImpl) AddPaymentMethod(ctx context.Context, c *domain.Caller, in domain.AddBillingPaymentMethod) (domain.BillingPaymentMethod, error) {
	errs := map[string]string{}
	if !strings.HasPrefix(in.StripePaymentMethodID, "pm_") {
		errs["stripePaymentMethodId"] = "Confirm the card or bank account with Stripe first"
	}
	kind := strings.ToUpper(in.Kind)
	if kind != "CARD" && kind != "US_BANK_ACCOUNT" {
		errs["kind"] = "Card or bank account"
	}
	if len(in.Last4) != 4 {
		errs["last4"] = "Last 4 digits"
	}
	if len(errs) > 0 {
		return domain.BillingPaymentMethod{}, domain.ValidationProblem(errs)
	}
	sc, err := s.r.require(ctx, c, "", "billing", domain.LevelView)
	if err != nil {
		return domain.BillingPaymentMethod{}, err
	}
	if !isOwner(sc.org.Role) {
		return domain.BillingPaymentMethod{}, ownerOnly()
	}
	pm, err := s.r.d.Sellers.AddPaymentMethod(ctx, c.BearerToken, sc.org.ID, port.SellerPaymentMethodInput{
		StripePaymentMethodID: in.StripePaymentMethodID, Kind: kind, Brand: strings.ToLower(in.Brand), Last4: in.Last4,
		ExpMonth: in.ExpMonth, ExpYear: in.ExpYear, BankName: in.BankName, AccountType: in.AccountType, MakeDefault: in.MakeDefault})
	if err != nil {
		return domain.BillingPaymentMethod{}, mapErr("seller", err)
	}
	return paymentMethodOf(pm), nil
}
