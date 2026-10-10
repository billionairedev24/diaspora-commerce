package app

import (
	"context"
	"encoding/json"
	"errors"
	"log/slog"
	"net/http"
	"strings"
	"sync"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

// SellerDeps are the upstreams of the Seller Center & Provider Hub routes.
type SellerDeps struct {
	Orgs       port.OrgAccessClient
	Sellers    port.SellerServiceClient
	Catalog    port.SellerCatalogClient
	Stock      port.StockClient
	Offers     port.OffersClient
	Media      port.MediaClient
	Orders     port.SellerOrdersClient
	Carrier    port.CarrierClient
	Payouts    port.PayoutsClient
	Storefront port.StorefrontClient
	Bookings   port.BookingsClient
	// Cache holds identity's module matrix per portal (not personal data).
	Cache     port.Cache
	MatrixTTL time.Duration
	Location  *time.Location
	Clock     Clock
	Log       *slog.Logger
}

// sellerCtx is the member, the active organisation and the role's access in it.
type sellerCtx struct {
	member   port.Member
	org      port.MemberOrg
	access   domain.Access
	approved bool
}

func (s sellerCtx) kind() string { return s.access.Kind }

// orgResolver resolves the active organisation and the role's module access for every seller route.
type orgResolver struct{ d SellerDeps }

func newResolver(d SellerDeps) *orgResolver {
	if d.Log == nil {
		d.Log = slog.Default()
	}
	if d.Location == nil {
		d.Location = time.UTC
	}
	if d.MatrixTTL <= 0 {
		d.MatrixTTL = time.Minute
	}
	return &orgResolver{d: d}
}

func normalizeKind(k string) string {
	if strings.EqualFold(k, domain.OrgKindProvider) {
		return domain.OrgKindProvider
	}
	if strings.EqualFold(k, domain.OrgKindSeller) {
		return domain.OrgKindSeller
	}
	return ""
}

// orgStatus maps identity's mirrored status (PENDING, APPROVED, SUSPENDED, REJECTED) or the seller service's
// detailed status onto the contract enum.
func orgStatus(s string) string {
	switch u := strings.ToUpper(s); u {
	case "PENDING", "":
		return "PENDING_REVIEW"
	case "DRAFT", "PENDING_REVIEW", "FLAGGED", "AWAITING_FINAL_APPROVAL", "INFO_REQUESTED", "APPROVED", "REJECTED", "SUSPENDED":
		return u
	default:
		return "PENDING_REVIEW"
	}
}

func memberOrgs(m port.Member) []port.MemberOrg {
	out := []port.MemberOrg{}
	for _, o := range m.Orgs {
		if normalizeKind(o.Kind) != "" {
			o.Kind = normalizeKind(o.Kind)
			out = append(out, o)
		}
	}
	return out
}

func activeOf(m port.Member) (port.MemberOrg, bool) {
	orgs := memberOrgs(m)
	for _, o := range orgs {
		if o.ID == m.OrgID {
			return o, true
		}
	}
	if len(orgs) > 0 {
		return orgs[0], true
	}
	return port.MemberOrg{}, false
}

// matrix reads identity's module matrix for the portal; members without access:read (403) and an unavailable
// identity fall back to the seeded default matrix. Either result is cached per portal.
func (r *orgResolver) matrix(ctx context.Context, bearer, kind string) domain.ModuleMatrix {
	key := "identity:module-matrix:" + kind
	if r.d.Cache != nil {
		if b, ok := r.d.Cache.Get(key); ok {
			var m domain.ModuleMatrix
			if json.Unmarshal(b, &m) == nil && len(m) > 0 {
				return m
			}
		}
	}
	m, err := r.d.Orgs.ModuleMatrix(ctx, bearer, kind)
	if err != nil || len(m) == 0 {
		if p, ok := port.AsUpstreamProblem(err); !ok || p.Status != http.StatusForbidden {
			r.d.Log.DebugContext(ctx, "module matrix unavailable; using defaults", "portal", kind, "error", errString(err))
		}
		m = domain.DefaultMatrix(kind)
	}
	if r.d.Cache != nil {
		if b, err := json.Marshal(m); err == nil {
			r.d.Cache.Set(key, b, r.d.MatrixTTL)
		}
	}
	return m
}

func errString(err error) string {
	if err == nil {
		return ""
	}
	return err.Error()
}

func (r *orgResolver) contextFor(ctx context.Context, c *domain.Caller, m port.Member) (sellerCtx, error) {
	org, ok := activeOf(m)
	if !ok {
		return sellerCtx{member: m}, domain.NewProblem(http.StatusForbidden, domain.CodeNoOrganisation, "No organisation",
			"You don't belong to a seller or service-provider organisation yet. Start selling to apply.")
	}
	org.Status = orgStatus(org.Status)
	kind := org.Kind
	return sellerCtx{member: m, org: org, approved: org.Status == domain.OrgApproved,
		access: domain.Access{Kind: kind, Role: org.Role, Matrix: r.matrix(ctx, c.BearerToken, kind)}}, nil
}

// resolve returns the caller's seller context (401 for guests, 403 NO_ORGANISATION without an organisation).
func (r *orgResolver) resolve(ctx context.Context, c *domain.Caller) (sellerCtx, error) {
	if err := requireCustomer(c); err != nil {
		return sellerCtx{}, err
	}
	m, err := r.d.Orgs.Member(ctx, c.BearerToken)
	if err != nil {
		return sellerCtx{}, mapErr("identity", err)
	}
	return r.contextFor(ctx, c, m)
}

// require resolves the context and checks approval, organisation kind (when kind != "") and module access.
func (r *orgResolver) require(ctx context.Context, c *domain.Caller, kind, module, level string) (sellerCtx, error) {
	sc, err := r.resolve(ctx, c)
	if err != nil {
		return sc, err
	}
	if !sc.approved {
		return sc, domain.NewProblem(http.StatusForbidden, domain.CodeNotApproved, "Not approved yet",
			"This part of the back office unlocks once your application is approved.")
	}
	if kind != "" && sc.kind() != kind {
		what := "Seller Center"
		if kind == domain.OrgKindProvider {
			what = "Provider Hub"
		}
		return sc, domain.NewProblem(http.StatusForbidden, domain.CodeForbidden, "Forbidden", "Switch to an organisation that uses "+what+".")
	}
	if module != "" && !sc.access.Allows(module, level) {
		return sc, domain.AccessDenied(sc.org.Role, level)
	}
	return sc, nil
}

// ---------------------------------------------------------------- shell

// SellerShellService serves the shell: session with role-aware navigation, org switcher, onboarding status and
// the compliance banner.
type SellerShellService interface {
	Session(ctx context.Context, c *domain.Caller) (domain.SellerSession, error)
	SetActiveOrg(ctx context.Context, c *domain.Caller, orgID string) (domain.SellerSession, error)
	Onboarding(ctx context.Context, c *domain.Caller) (domain.OnboardingStatus, error)
	Compliance(ctx context.Context, c *domain.Caller) (domain.ComplianceSummary, error)
}

type sellerShellImpl struct {
	r *orgResolver
}

var _ SellerShellService = (*sellerShellImpl)(nil)

// NewSellerShellService returns the SellerShellService.
func NewSellerShellService(d SellerDeps) SellerShellService {
	return &sellerShellImpl{r: newResolver(d)}
}

func (s *sellerShellImpl) Session(ctx context.Context, c *domain.Caller) (domain.SellerSession, error) {
	if err := requireCustomer(c); err != nil {
		return domain.SellerSession{}, err
	}
	m, err := s.r.d.Orgs.Member(ctx, c.BearerToken)
	if err != nil {
		return domain.SellerSession{}, mapErr("identity", err)
	}
	return s.session(ctx, c, m)
}

func (s *sellerShellImpl) SetActiveOrg(ctx context.Context, c *domain.Caller, orgID string) (domain.SellerSession, error) {
	if err := requireCustomer(c); err != nil {
		return domain.SellerSession{}, err
	}
	if strings.TrimSpace(orgID) == "" {
		return domain.SellerSession{}, domain.ValidationProblem(map[string]string{"orgId": "Choose an organisation"})
	}
	m, err := s.r.d.Orgs.Member(ctx, c.BearerToken)
	if err != nil {
		return domain.SellerSession{}, mapErr("identity", err)
	}
	member := false
	for _, o := range memberOrgs(m) {
		member = member || o.ID == orgID
	}
	if !member {
		return domain.SellerSession{}, domain.NewProblem(http.StatusForbidden, domain.CodeForbidden, "Forbidden", "You're not a member of that organisation.")
	}
	m2, err := s.r.d.Orgs.SelectOrg(ctx, c.BearerToken, orgID)
	if err != nil {
		return domain.SellerSession{}, mapErr("identity", err)
	}
	if m2.ID == "" {
		m2 = m
	}
	m2.OrgID = orgID
	return s.session(ctx, c, m2)
}

func userOf(m port.Member) domain.SellerUser {
	p := domain.Profile{FirstName: m.FirstName, LastName: m.LastName}
	return domain.SellerUser{ID: m.ID, FirstName: m.FirstName, LastName: m.LastName, Email: m.Email, Initials: p.Initials()}
}

func summaryOf(o port.MemberOrg) domain.OrgSummary {
	st := orgStatus(o.Status)
	return domain.OrgSummary{ID: o.ID, Kind: normalizeKind(o.Kind), Name: o.Name, Status: st, Approved: st == domain.OrgApproved,
		RoleKey: o.Role, RoleLabel: domain.RoleLabel(o.Role)}
}

func (s *sellerShellImpl) session(ctx context.Context, c *domain.Caller, m port.Member) (domain.SellerSession, error) {
	out := domain.SellerSession{User: userOf(m), Orgs: []domain.OrgSummary{}, NavGroups: []domain.NavGroup{}, Landing: "onboarding",
		AccessNote: "Least-privilege by role · enforced server-side"}
	for _, o := range memberOrgs(m) {
		out.Orgs = append(out.Orgs, summaryOf(o))
	}
	sc, err := s.r.contextFor(ctx, c, m)
	if err != nil {
		if p, ok := domain.AsProblem(err); ok && p.Code == domain.CodeNoOrganisation {
			return out, nil // a customer who has not applied yet: no organisation, no navigation
		}
		return out, err
	}
	// The seller service has the detailed application status (identity mirrors only PENDING/APPROVED/…).
	if org, err := s.r.d.Sellers.Org(ctx, c.BearerToken, sc.org.ID); err == nil && org.Status != "" {
		sc.org.Status = orgStatus(org.Status)
		sc.approved = sc.org.Status == domain.OrgApproved
	}
	active := summaryOf(sc.org)
	active.Status, active.Approved = sc.org.Status, sc.approved
	for i := range out.Orgs {
		if out.Orgs[i].ID == active.ID {
			out.Orgs[i] = active
		}
	}
	portal := domain.PortalOf(sc.kind())
	out.ActiveOrg, out.Portal, out.Approved = &active, &portal, sc.approved
	badges := map[string]int{}
	if sc.approved {
		badges = s.badges(ctx, c, sc)
	} else {
		out.AccessNote = "Token scope: onboarding only"
	}
	out.NavGroups = domain.Navigation(sc.access, sc.approved, badges)
	out.Landing = domain.Landing(out.NavGroups)
	return out, nil
}

// badges counts what needs attention: orders to ship, booking requests, open compliance items. Each source is
// best effort — an unavailable upstream just shows no badge.
func (s *sellerShellImpl) badges(ctx context.Context, c *domain.Caller, sc sellerCtx) map[string]int {
	var mu sync.Mutex
	out := map[string]int{}
	set := func(k string, n int) { mu.Lock(); out[k] = n; mu.Unlock() }
	bctx, cancel := context.WithTimeout(ctx, 1500*time.Millisecond)
	defer cancel()
	var wg sync.WaitGroup
	if domain.AtLeast(sc.access.Level("account-health"), domain.LevelView) {
		wg.Go(func() {
			if h, err := s.r.d.Sellers.AccountHealth(bctx, c.BearerToken, sc.org.ID); err == nil {
				set("account-health", h.OpenItems)
			}
		})
	}
	if sc.kind() == domain.OrgKindSeller && domain.AtLeast(sc.access.Level("orders"), domain.LevelView) {
		wg.Go(func() {
			if q, err := s.r.d.Orders.Queue(bctx, c.BearerToken, "PLACED", "", 1); err == nil {
				set("orders", int(q.Total))
			}
		})
	}
	if sc.kind() == domain.OrgKindProvider && domain.AtLeast(sc.access.Level("requests"), domain.LevelView) {
		wg.Go(func() {
			if list, err := s.r.d.Bookings.Requests(bctx, c.BearerToken); err == nil {
				set("requests", len(list))
			}
		})
	}
	wg.Wait()
	return out
}

var onboardingSteps = []struct{ key, label string }{
	{"ACCOUNT", "Account & email verified"}, {"BUSINESS", "Business details & TIN"}, {"OWNERS", "Owners"},
	{"STRIPE", "Stripe verification"}, {"PAYOUTS", "Payouts"}, {"PLAN", "Plan"}, {"COMPLIANCE", "Catalog, permits & licences"},
	{"REVIEW", "AfroTransact staff review"},
}

func (s *sellerShellImpl) Onboarding(ctx context.Context, c *domain.Caller) (domain.OnboardingStatus, error) {
	if err := requireCustomer(c); err != nil {
		return domain.OnboardingStatus{}, err
	}
	a, err := s.r.d.Sellers.Application(ctx, c.BearerToken)
	if err != nil {
		return domain.OnboardingStatus{}, mapErr("seller", err)
	}
	status := orgStatus(a.Status)
	labels := map[string]string{}
	for _, st := range onboardingSteps {
		labels[st.key] = st.label
	}
	keys := a.Steps
	if len(keys) == 0 {
		for _, st := range onboardingSteps[:7] {
			keys = append(keys, st.key)
		}
	}
	done := map[string]bool{}
	for _, k := range a.CompletedSteps {
		done[strings.ToUpper(k)] = true
	}
	out := domain.OnboardingStatus{OrgID: a.OrgID, Kind: normalizeKind(a.Kind), Status: status, Reference: strPtr(a.Reference),
		Steps: []domain.OnboardingStep{}, RequestedItems: append([]string{}, a.RequestedItems...),
		CanResume: status == "DRAFT" || status == "INFO_REQUESTED"}
	if out.Kind == "" {
		out.Kind = domain.OrgKindSeller
	}
	for _, k := range keys {
		ku := strings.ToUpper(k)
		label := labels[ku]
		if label == "" {
			label = strings.ToUpper(ku[:1]) + strings.ToLower(strings.ReplaceAll(ku[1:], "_", " "))
		}
		state := "TODO"
		switch {
		case status == domain.OrgApproved || done[ku]:
			state = "DONE"
		case status == "INFO_REQUESTED" && strings.EqualFold(k, a.CurrentStep):
			state = "ACTION"
		}
		out.Steps = append(out.Steps, domain.OnboardingStep{Key: ku, Label: label, State: state})
	}
	review := "TODO"
	switch status {
	case domain.OrgApproved:
		review = "DONE"
	case "REJECTED", "SUSPENDED":
		review = "BLOCKED"
	case "PENDING_REVIEW", "FLAGGED", "AWAITING_FINAL_APPROVAL":
		review = "WAITING"
	case "INFO_REQUESTED":
		review = "ACTION"
	}
	out.Steps = append(out.Steps, domain.OnboardingStep{Key: "REVIEW", Label: labels["REVIEW"], State: review})
	return out, nil
}

func (s *sellerShellImpl) Compliance(ctx context.Context, c *domain.Caller) (domain.ComplianceSummary, error) {
	sc, err := s.r.require(ctx, c, "", "", "")
	if err != nil {
		return domain.ComplianceSummary{}, err
	}
	h, err := s.r.d.Sellers.AccountHealth(ctx, c.BearerToken, sc.org.ID)
	if err != nil {
		if errors.Is(err, port.ErrNotFound) {
			// No Connect account yet (or not mirrored): nothing outstanding to show.
			out := domain.ComplianceSummary{Items: []domain.ComplianceItem{}}
			domain.ComplianceBanner(&out, s.r.d.Location)
			return out, nil
		}
		return domain.ComplianceSummary{}, mapErr("seller", err)
	}
	out := domain.ComplianceSummary{Status: strings.ToUpper(h.Status), OpenItems: h.OpenItems, Deadline: h.CurrentDeadline,
		PayoutsPaused: h.PayoutsPaused, ChargesEnabled: h.ChargesEnabled, PayoutsEnabled: h.PayoutsEnabled, ListingsHidden: h.ListingsHidden,
		Items: []domain.ComplianceItem{}}
	for _, r := range h.Requirements {
		out.Items = append(out.Items, domain.ComplianceItem{Requirement: r.Requirement, Label: domain.RequirementLabel(r.Requirement), Bucket: r.Bucket})
	}
	if out.OpenItems == 0 {
		out.OpenItems = len(out.Items)
	}
	domain.ComplianceBanner(&out, s.r.d.Location)
	return out, nil
}
