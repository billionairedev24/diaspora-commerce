package app

import (
	"context"
	"net/http"
	"slices"
	"strings"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

// Start selling / offer services (S286): a signed-in account applies with no second sign-up. The applicant role is
// added in identity, the application (and its DRAFT organisation) is created in the seller service, and the answers
// the account already has are prefilled. Identity only learns about the organisation once it is approved, so until
// then the seller session shows the application's DRAFT organisation (applicantSession).

// prefilledNames maps seller-service prefilledFields onto the wizard's field names (OnboardingForm.prefilled).
var prefilledNames = map[string]string{
	"business.contactEmail":          "business.contactEmail",
	"business.phone":                 "business.phone",
	"business.storeName":             "business.storeName",
	"businessDetails.legalFirstName": "details.legalFirstName",
	"businessDetails.legalLastName":  "details.legalLastName",
	"businessDetails.address":        "details.address",
}

// isStaff reports whether the roles include a platform staff role (drivers are not staff here).
func isStaff(roles []string) bool {
	for _, r := range roles {
		if strings.HasPrefix(r, "platform.") && r != "platform.driver" {
			return true
		}
	}
	return false
}

// approvedOrgOf returns the member's approved organisation of the kind, if any.
func approvedOrgOf(m port.Member, kind string) (port.MemberOrg, bool) {
	for _, o := range memberOrgs(m) {
		if o.Kind == kind && orgStatus(o.Status) == domain.OrgApproved {
			return o, true
		}
	}
	return port.MemberOrg{}, false
}

// applicationPrefill gathers what the account knows (access tokens may not carry profile claims): name, email, phone
// (verified or not), the default
// address-book entry (else the first one) and, for someone already in an organisation, its name as the store name.
func (s *sellerShellImpl) applicationPrefill(ctx context.Context, c *domain.Caller, m port.Member) port.ApplicationPrefill {
	p := port.ApplicationPrefill{GivenName: strings.TrimSpace(m.FirstName), FamilyName: strings.TrimSpace(m.LastName),
		Email: strings.TrimSpace(m.Email), Phone: strings.TrimSpace(m.Phone)}
	for _, o := range memberOrgs(m) {
		if strings.TrimSpace(o.Name) != "" {
			p.StoreName = strings.TrimSpace(o.Name)
			break
		}
	}
	if s.r.d.Addresses == nil {
		return p
	}
	list, err := s.r.d.Addresses.Addresses(ctx, c.BearerToken)
	if err != nil {
		s.r.d.Log.DebugContext(ctx, "address book unavailable; no address prefill", "error", err)
		return p
	}
	if a, ok := defaultAddress(list); ok {
		p.Address = &port.SellerPostal{Line1: a.Line1, Line2: a.Line2, City: a.City, State: a.State, PostalCode: a.PostalCode,
			Country: firstNonEmpty(a.Country, "US")}
	}
	return p
}

// defaultAddress is the address marked default, else the first one.
func defaultAddress(list []port.IdentityAddress) (port.IdentityAddress, bool) {
	for _, a := range list {
		if a.IsDefault {
			return a, true
		}
	}
	if len(list) > 0 {
		return list[0], true
	}
	return port.IdentityAddress{}, false
}

// StartOnboarding starts or resumes the caller's application of the kind (SELLER or PROVIDER).
func (s *sellerShellImpl) StartOnboarding(ctx context.Context, c *domain.Caller, kind string) (domain.OnboardingStatus, bool, error) {
	if err := requireCustomer(c); err != nil {
		return domain.OnboardingStatus{}, false, err
	}
	k := normalizeKind(kind)
	if k == "" {
		return domain.OnboardingStatus{}, false, domain.ValidationProblem(map[string]string{"kind": "Choose SELLER or PROVIDER"})
	}
	m, err := s.r.d.Orgs.Member(ctx, c.BearerToken)
	if err != nil {
		return domain.OnboardingStatus{}, false, mapErr("identity", err)
	}
	if isStaff(m.Roles) {
		return domain.OnboardingStatus{}, false, domain.NewProblem(http.StatusForbidden, domain.CodeStaffCannotApply, "Staff accounts can't apply",
			"AfroTransact staff accounts can't apply to sell or offer services. Sign in with a personal account to apply.")
	}
	if o, ok := approvedOrgOf(m, k); ok {
		return domain.OnboardingStatus{}, false, domain.NewProblem(http.StatusConflict, domain.CodeAlreadyApproved, "Already approved",
			o.Name+" is already approved. Open "+portalName(k)+" instead.")
	}
	if err := s.r.d.Orgs.BecomeApplicant(ctx, c.BearerToken, k); err != nil {
		return domain.OnboardingStatus{}, false, mapErr("identity", err)
	}
	a, created, err := s.r.d.Sellers.StartApplication(ctx, c.BearerToken, k, s.applicationPrefill(ctx, c, m))
	if err != nil {
		if p, ok := port.AsUpstreamProblem(err); ok && p.Status == http.StatusConflict && p.Code == "seller.application-exists" {
			return domain.OnboardingStatus{}, false, domain.NewProblem(http.StatusConflict, domain.CodeApplicationExists, "Application in progress",
				firstNonEmpty(p.Detail, "You already have an application of the other kind; continue that one.")).WithCause(err)
		}
		return domain.OnboardingStatus{}, false, mapErr("seller", err)
	}
	return onboardingStatus(a), created, nil
}

func portalName(kind string) string {
	if kind == domain.OrgKindProvider {
		return "Provider Hub"
	}
	return "Seller Center"
}

// SaveOnboardingBusiness autosaves the "Your business" step.
func (s *sellerShellImpl) SaveOnboardingBusiness(ctx context.Context, c *domain.Caller, in domain.OnboardingBusinessInput) (domain.OnboardingStatus, error) {
	if err := requireCustomer(c); err != nil {
		return domain.OnboardingStatus{}, err
	}
	body := port.SellerApplicationBiz{Offering: strings.ToUpper(strings.TrimSpace(in.Offering)),
		EntityType: strings.ToLower(strings.TrimSpace(in.EntityType)), Country: strings.ToUpper(strings.TrimSpace(in.Country)),
		StoreName: strings.TrimSpace(in.StoreName), ContactEmail: strings.TrimSpace(in.ContactEmail), Phone: strings.TrimSpace(in.Phone),
		Website: strings.TrimSpace(in.Website)}
	a, err := s.r.d.Sellers.SaveApplicationStep(ctx, c.BearerToken, "business", body)
	if err != nil {
		return domain.OnboardingStatus{}, mapErr("seller", err)
	}
	return onboardingStatus(a), nil
}

// SaveOnboardingDetails autosaves the "About you" step. The seller service replaces the whole section, so answers
// the wizard does not edit yet (date of birth, formation details, documents) are carried over from the application.
func (s *sellerShellImpl) SaveOnboardingDetails(ctx context.Context, c *domain.Caller, in domain.OnboardingDetailsInput) (domain.OnboardingStatus, error) {
	if err := requireCustomer(c); err != nil {
		return domain.OnboardingStatus{}, err
	}
	cur, err := s.r.d.Sellers.Application(ctx, c.BearerToken)
	if err != nil {
		return domain.OnboardingStatus{}, mapErr("seller", err)
	}
	body := port.SellerApplicationDetails{}
	if cur.BusinessDetails != nil {
		body = *cur.BusinessDetails
	}
	body.LegalFirstName, body.LegalLastName = strings.TrimSpace(in.LegalFirstName), strings.TrimSpace(in.LegalLastName)
	body.LegalName = strings.TrimSpace(in.LegalName)
	body.Address = nil
	if in.Address != nil {
		body.Address = &port.SellerPostal{Line1: strings.TrimSpace(in.Address.Line1), Line2: strings.TrimSpace(in.Address.Line2),
			City: strings.TrimSpace(in.Address.City), State: strings.ToUpper(strings.TrimSpace(in.Address.State)),
			PostalCode: strings.TrimSpace(in.Address.PostalCode), Country: strings.ToUpper(firstNonEmpty(strings.TrimSpace(in.Address.Country), "US"))}
	}
	a, err := s.r.d.Sellers.SaveApplicationStep(ctx, c.BearerToken, "business-details", body)
	if err != nil {
		return domain.OnboardingStatus{}, mapErr("seller", err)
	}
	return onboardingStatus(a), nil
}

// onboardingForm maps the application's first two steps onto the wizard's form.
func onboardingForm(a port.SellerApplication, status string) domain.OnboardingForm {
	f := domain.OnboardingForm{Prefilled: []string{}, Editable: status == "DRAFT" || status == "INFO_REQUESTED"}
	if b := a.Business; b != nil {
		f.Business = domain.OnboardingBusiness{Offering: strPtr(strings.ToUpper(b.Offering)), EntityType: strPtr(strings.ToUpper(b.EntityType)),
			Country: strPtr(b.Country), StoreName: strPtr(b.StoreName), ContactEmail: strPtr(b.ContactEmail), Phone: strPtr(b.Phone), Website: strPtr(b.Website)}
	}
	if d := a.BusinessDetails; d != nil {
		f.Details = domain.OnboardingDetails{LegalFirstName: strPtr(d.LegalFirstName), LegalLastName: strPtr(d.LegalLastName), LegalName: strPtr(d.LegalName)}
		if ad := d.Address; ad != nil {
			f.Details.Address = &domain.OnboardingAddress{Line1: strPtr(ad.Line1), Line2: strPtr(ad.Line2), City: strPtr(ad.City),
				State: strPtr(ad.State), PostalCode: strPtr(ad.PostalCode), Country: strPtr(ad.Country)}
		}
	}
	for _, k := range a.PrefilledFields {
		if n, ok := prefilledNames[k]; ok && !slices.Contains(f.Prefilled, n) {
			f.Prefilled = append(f.Prefilled, n)
		}
	}
	slices.Sort(f.Prefilled)
	return f
}

// applicantSession is the session of someone whose application is not approved yet: identity does not list the
// DRAFT organisation, so it comes from the seller service's application (only Onboarding is open).
func (s *sellerShellImpl) applicantSession(ctx context.Context, c *domain.Caller, out domain.SellerSession) (domain.SellerSession, bool) {
	a, err := s.r.d.Sellers.Application(ctx, c.BearerToken)
	if err != nil || a.OrgID == "" {
		return out, false
	}
	kind := normalizeKind(a.Kind)
	if kind == "" {
		kind = domain.OrgKindSeller
	}
	status := orgStatus(a.Status)
	if status == domain.OrgApproved || status == "REJECTED" {
		return out, false // approved: identity lists it once the approval event arrives; rejected: nothing to resume
	}
	role := "seller.owner"
	if kind == domain.OrgKindProvider {
		role = "provider.owner"
	}
	name := "Your " + map[bool]string{true: "provider", false: "seller"}[kind == domain.OrgKindProvider] + " application"
	if a.Business != nil && strings.TrimSpace(a.Business.StoreName) != "" {
		name = strings.TrimSpace(a.Business.StoreName)
	}
	org := domain.OrgSummary{ID: a.OrgID, Kind: kind, Name: name, Status: status, RoleKey: role, RoleLabel: domain.RoleLabel(role)}
	portal := domain.PortalOf(kind)
	out.Orgs = append(out.Orgs, org)
	out.ActiveOrg, out.Portal, out.Approved = &org, &portal, false
	out.AccessNote = "Token scope: onboarding only"
	out.NavGroups = domain.Navigation(domain.Access{Kind: kind, Role: role}, false, nil)
	out.Landing = domain.Landing(out.NavGroups)
	return out, true
}
