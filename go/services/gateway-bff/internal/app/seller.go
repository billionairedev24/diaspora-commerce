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
	// Cases reads the organisation's returns (tax-returns) and disputes (trust-ops); nil answers 503.
	Cases port.SellerCasesClient
	Orgs    port.OrgAccessClient
	Sellers port.SellerServiceClient
	// Addresses is the identity address book: its default address prefills the business address (S286). Optional.
	Addresses port.AddressBook
	Catalog   port.SellerCatalogClient
	Stock     port.StockClient
	Offers    port.OffersClient
	Media     port.MediaClient
	Orders    port.SellerOrdersClient
	Carrier   port.CarrierClient
	// ProofLinks signs the links to a delivery's proof photo and signature (media-service; optional: no pictures without it).
	ProofLinks port.ProofLinks
	Payouts    port.PayoutsClient

	// Listing edits products, keeps form drafts and runs bulk uploads at catalog (S307/S308); nil answers 503.
	Listing port.SellerListingClient
	// Writer is ai-gateway's AI listing writer (nil: never offered).
	Writer port.ListingWriterClient
	// Fees reads payments' fee schedules with the BFF's service token (nil: the Fees page answers 503).
	Fees     port.FeesClient
	Bookings port.BookingsClient
	// PublishableKey is the Stripe publishable key Connect.js uses for the embedded onboarding (empty locally: the seller
	// service's local stand-in then answers account sessions with localTest).
	PublishableKey string
	// InventoryAPI relays Seller Center's FBA inbound plans and lots to inventory-service with the member's token (S281).
	InventoryAPI port.StaffUpstream
	// Sessions rotates the session's tokens after identity repaired an approval it had missed (nil: never).
	Sessions TokenRefresher
	// Cache holds identity's module matrix per portal (not personal data).
	Cache     port.Cache
	MatrixTTL time.Duration
	// ListingCache caches table scans for seller listings across catalog, listing and offers.
	ListingCache *scanCache[domain.ListingRow]
	Location     *time.Location
	Clock        Clock
	Log          *slog.Logger
}

// NewListingCache returns a new scanCache for ListingRow.
func NewListingCache(c Clock) *scanCache[domain.ListingRow] {
	return newScanCache[domain.ListingRow](c)
}

func (d SellerDeps) listingCache() *scanCache[domain.ListingRow] {
	if d.ListingCache != nil {
		return d.ListingCache
	}
	return newScanCache[domain.ListingRow](d.clock())
}

// clock is the configured clock (time.Now by default).
func (d SellerDeps) clock() Clock {
	if d.Clock == nil {
		return time.Now
	}
	return d.Clock
}

// sellerCtx is the member, the active organisation and the role's access in it.
type sellerCtx struct {
	member   port.Member
	org      port.MemberOrg
	access   domain.Access
	approved bool
}

func (s sellerCtx) kind() string { return s.access.Kind }

// provider names the active organisation and the member for bookings' /v1/providers/{orgId}/… routes.
func (s sellerCtx) provider() port.ProviderRef {
	return port.ProviderRef{OrgID: s.org.ID, UserID: s.member.ID}
}

// orgResolver resolves the active organisation and the role's module access for every seller route.
type orgResolver struct {
	d    SellerDeps
	memo *planMemo
}

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
	return &orgResolver{d: d, memo: &planMemo{}}
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
	// StartOnboarding is "Start selling" / "Offer your services" for a signed-in account: idempotent, created is
	// false when an existing application was resumed.
	StartOnboarding(ctx context.Context, c *domain.Caller, kind string) (st domain.OnboardingStatus, created bool, err error)
	SaveOnboardingBusiness(ctx context.Context, c *domain.Caller, in domain.OnboardingBusinessInput) (domain.OnboardingStatus, error)
	SaveOnboardingDetails(ctx context.Context, c *domain.Caller, in domain.OnboardingDetailsInput) (domain.OnboardingStatus, error)
	SaveOnboardingOwners(ctx context.Context, c *domain.Caller, in domain.OnboardingOwnersInput) (domain.OnboardingStatus, error)
	SaveOnboardingPlan(ctx context.Context, c *domain.Caller, in domain.OnboardingPlanInput) (domain.OnboardingStatus, error)
	SaveOnboardingCompliance(ctx context.Context, c *domain.Caller, in domain.OnboardingComplianceInput) (domain.OnboardingStatus, error)
	// OnboardingStripeSession is the Account Session for Stripe's embedded Connect onboarding.
	OnboardingStripeSession(ctx context.Context, c *domain.Caller) (domain.OnboardingStripeSession, error)
	// SellerStripeSession is an Account Session for Account health (owner / finance of the active organisation).
	SellerStripeSession(ctx context.Context, c *domain.Caller) (domain.OnboardingStripeSession, error)
	// RefreshOnboardingStripe re-reads the verification results from Stripe.
	RefreshOnboardingStripe(ctx context.Context, c *domain.Caller) (domain.OnboardingStatus, error)
	// SubmitOnboarding sends the application to AfroTransact staff review.
	SubmitOnboarding(ctx context.Context, c *domain.Caller) (domain.OnboardingStatus, error)
	// OnboardingPlans lists the plans of the application's kind with prices and first charges.
	OnboardingPlans(ctx context.Context, c *domain.Caller) (domain.OnboardingPlans, error)
	// OnboardingUpload signs a private upload (media purpose kyc-document) for a permit, licence or formation document.
	OnboardingUpload(ctx context.Context, c *domain.Caller, fileName, contentType string, size int64) (domain.UploadTicket, error)
	// CompleteOnboardingUpload finalises an uploaded document.
	CompleteOnboardingUpload(ctx context.Context, c *domain.Caller, assetID string) (domain.Asset, error)
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
	out, stale, err := s.session(ctx, c, m)
	if err != nil || stale == "" {
		return out, err
	}
	// The seller service approved an organisation identity does not show approved yet (its approval event was missed
	// or is late): identity syncs it now and the session's tokens are rotated, then the session is read again.
	if !healOrg(ctx, s.r.d.Orgs, s.r.d.Sessions, s.r.d.Log, c, stale) {
		return out, nil
	}
	// Best effort: when identity cannot be read again, the session read before the repair still answers.
	if m2, merr := s.r.d.Orgs.Member(ctx, c.BearerToken); merr == nil {
		if healed, _, herr := s.session(ctx, c, m2); herr == nil {
			return healed, nil
		}
	}
	return out, nil
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
	out, _, err := s.session(ctx, c, m2)
	return out, err
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

// session builds the shell's session; stale names an organisation the seller service shows approved while identity
// does not (PENDING there, or not listed at all although the application was approved), which the caller may repair.
func (s *sellerShellImpl) session(ctx context.Context, c *domain.Caller, m port.Member) (out domain.SellerSession, stale string, err error) {
	out = domain.SellerSession{User: userOf(m), Orgs: []domain.OrgSummary{}, NavGroups: []domain.NavGroup{}, Capabilities: []string{}, Landing: "onboarding",
		AccessNote: "Least-privilege by role · enforced server-side"}
	for _, o := range memberOrgs(m) {
		out.Orgs = append(out.Orgs, summaryOf(o))
	}
	sc, err := s.r.contextFor(ctx, c, m)
	if err != nil {
		if p, ok := domain.AsProblem(err); ok && p.Code == domain.CodeNoOrganisation {
			// An applicant whose DRAFT organisation identity does not know yet, or a customer who has not applied.
			var approvedOrg string
			out, _, approvedOrg = s.applicantSession(ctx, c, out)
			return out, approvedOrg, nil
		}
		return out, "", err
	}
	// The seller service has the detailed application status (identity mirrors only PENDING/APPROVED/…).
	identityApproved := sc.approved
	if org, err := s.r.d.Sellers.Org(ctx, c.BearerToken, sc.org.ID); err == nil && org.Status != "" {
		sc.org.Status = orgStatus(org.Status)
		sc.approved = sc.org.Status == domain.OrgApproved
	}
	if sc.approved && !identityApproved {
		stale = sc.org.ID
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
	out.Capabilities = []string{}
	if sc.approved {
		// What the plan includes decides what is shown: a module the plan does not include is not in the navigation.
		if e, err := s.r.entitlements(ctx, c, sc.org.ID); err == nil {
			out.Capabilities = e.Capabilities()
			if sc.kind() == domain.OrgKindSeller && !e.Allows(domain.CapCoupons) {
				out.NavGroups = domain.WithoutModules(out.NavGroups, "coupons")
			}
		}
	}
	out.Landing = domain.Landing(out.NavGroups)
	return out, stale, nil
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
			if list, err := s.r.d.Bookings.Requests(bctx, c.BearerToken, sc.provider()); err == nil {
				set("requests", len(list))
			}
		})
	}
	wg.Wait()
	return out
}

func (s *sellerShellImpl) Compliance(ctx context.Context, c *domain.Caller) (domain.ComplianceSummary, error) {
	sc, err := s.r.require(ctx, c, "", "", "")
	if err != nil {
		return domain.ComplianceSummary{}, err
	}
	h, err := s.r.d.Sellers.AccountHealth(ctx, c.BearerToken, sc.org.ID)
	if err != nil {
		// No Connect account yet (or not mirrored), or a member whose role may not see account health (it is for the
		// owner and finance; the shell asks on every page for every member): nothing outstanding to show.
		if p, ok := port.AsUpstreamProblem(err); errors.Is(err, port.ErrNotFound) || ok && p.Status == http.StatusForbidden {
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
		component := domain.RequirementComponent(r.Requirement)
		if r.Form != nil && r.Form.Component != "" {
			component = r.Form.Component
		}
		out.Items = append(out.Items, domain.ComplianceItem{Requirement: r.Requirement, Label: domain.RequirementLabel(r.Requirement), Bucket: r.Bucket,
			Component: component})
	}
	if b := h.PayoutBank; b != nil {
		out.PayoutBank = &domain.ComplianceBank{BankName: strPtr(b.BankName), Last4: strPtr(b.Last4), Status: strPtr(b.Status)}
	}
	if out.OpenItems == 0 {
		out.OpenItems = len(out.Items)
	}
	domain.ComplianceBanner(&out, s.r.d.Location)
	return out, nil
}
