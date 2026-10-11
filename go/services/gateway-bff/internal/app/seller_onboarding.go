package app

import (
	"context"
	"net/http"
	"slices"
	"strings"
	"time"

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

// SaveOnboardingDetails autosaves "About you" (sole proprietor) / "Business details & TIN". The seller service
// replaces the whole section, so the wizard sends the whole step; an empty tax ID keeps the stored (encrypted) one.
// The tax ID is passed through once and never logged.
func (s *sellerShellImpl) SaveOnboardingDetails(ctx context.Context, c *domain.Caller, in domain.OnboardingDetailsInput) (domain.OnboardingStatus, error) {
	if err := requireCustomer(c); err != nil {
		return domain.OnboardingStatus{}, err
	}
	body := port.SellerApplicationDetails{LegalFirstName: strings.TrimSpace(in.LegalFirstName), LegalLastName: strings.TrimSpace(in.LegalLastName),
		LegalName: strings.TrimSpace(in.LegalName), DBA: strings.TrimSpace(in.DBA), TaxID: strings.TrimSpace(in.TaxID),
		DateOfBirth: strings.TrimSpace(in.DateOfBirth), StateOfFormation: strings.ToUpper(strings.TrimSpace(in.StateOfFormation)),
		DateOfFormation: strings.TrimSpace(in.DateOfFormation), Address: postalOf(in.Address),
		FormationDocumentAssetID: strings.TrimSpace(in.FormationDocumentAssetID), IRSDeterminationLetterAssetID: strings.TrimSpace(in.IRSDeterminationLetterAssetID)}
	return s.saveStep(ctx, c, "business-details", body)
}

// SaveOnboardingOwners autosaves "Owners" (not for sole proprietors).
func (s *sellerShellImpl) SaveOnboardingOwners(ctx context.Context, c *domain.Caller, in domain.OnboardingOwnersInput) (domain.OnboardingStatus, error) {
	owners := make([]port.SellerApplicationOwner, 0, len(in.Owners))
	for _, o := range in.Owners {
		owners = append(owners, port.SellerApplicationOwner{FirstName: strings.TrimSpace(o.FirstName), LastName: strings.TrimSpace(o.LastName),
			Title: strings.TrimSpace(o.Title), Email: strings.TrimSpace(o.Email), OwnershipPercent: o.OwnershipPercent,
			SignificantControl: o.SignificantControl, DateOfBirth: strings.TrimSpace(o.DateOfBirth), Address: postalOf(o.Address)})
	}
	return s.saveStep(ctx, c, "owners", map[string]any{"owners": owners})
}

// SaveOnboardingPayouts sends the payout bank account to Stripe through the seller service (which keeps the last 4
// digits only). The step is complete once Stripe accepted it.
func (s *sellerShellImpl) SaveOnboardingPayouts(ctx context.Context, c *domain.Caller, in domain.OnboardingPayoutsInput) (domain.OnboardingStatus, error) {
	digits := func(v string) string {
		return strings.Map(func(r rune) rune {
			if r >= '0' && r <= '9' {
				return r
			}
			return -1
		}, v)
	}
	body := port.SellerApplicationPayoutsStep{AccountHolderName: strings.TrimSpace(in.AccountHolderName),
		AccountType: strings.ToLower(strings.TrimSpace(in.AccountType)), RoutingNumber: digits(in.RoutingNumber),
		AccountNumber: digits(in.AccountNumber), AccountNumberConfirmation: digits(in.AccountNumberConfirmation)}
	return s.saveStep(ctx, c, "payouts", body)
}

// SaveOnboardingPlan autosaves "Plan": plan, cadence and (for paid plans) the card tokenised by Stripe.js.
func (s *sellerShellImpl) SaveOnboardingPlan(ctx context.Context, c *domain.Caller, in domain.OnboardingPlanInput) (domain.OnboardingStatus, error) {
	body := port.SellerApplicationPlanStep{PlanCode: strings.TrimSpace(in.PlanCode), Cadence: strings.ToUpper(strings.TrimSpace(in.Cadence)),
		PaymentMethodKind: strings.ToUpper(strings.TrimSpace(in.PaymentMethodKind)), StripePaymentMethodID: strings.TrimSpace(in.StripePaymentMethodID),
		CardBrand: strings.ToLower(strings.TrimSpace(in.CardBrand)), CardLast4: strings.TrimSpace(in.CardLast4), CardExpMonth: in.CardExpMonth, CardExpYear: in.CardExpYear}
	return s.saveStep(ctx, c, "plan", body)
}

// SaveOnboardingCompliance autosaves "Catalog, permits & licences" (sellers) or "Services & licences" (providers).
func (s *sellerShellImpl) SaveOnboardingCompliance(ctx context.Context, c *domain.Caller, in domain.OnboardingComplianceInput) (domain.OnboardingStatus, error) {
	body := port.SellerApplicationCompliance{Categories: []string{}, Services: []port.SellerServiceLicence{},
		BeautyLabellingConfirmed: in.BeautyLabellingConfirmed, InsuranceDocumentAssetID: strings.TrimSpace(in.InsuranceDocumentAssetID),
		AgreementsAccepted: &in.AgreementsAccepted, W9Certified: &in.W9Certified, W9SignerName: strings.TrimSpace(in.W9SignerName)}
	for _, cat := range in.Categories {
		if k := strings.ToLower(strings.TrimSpace(cat)); k != "" && !slices.Contains(body.Categories, k) {
			body.Categories = append(body.Categories, k)
		}
	}
	if p := in.FoodPermit; p != nil {
		body.FoodPermit = &port.SellerFoodPermit{PermitType: strings.ToUpper(trimmedOf(p.PermitType)), PermitNumber: trimmedOf(p.PermitNumber),
			IssuingAuthority: trimmedOf(p.IssuingAuthority), ExpiresOn: trimmedOf(p.ExpiresOn), DocumentAssetID: trimmedOf(p.DocumentAssetID)}
	}
	for _, l := range in.Services {
		body.Services = append(body.Services, port.SellerServiceLicence{ServiceName: strings.TrimSpace(l.ServiceName), Regulated: l.Regulated,
			LicenceNumber: trimmedOf(l.LicenceNumber), IssuingState: strings.ToUpper(trimmedOf(l.IssuingState)), ExpiresOn: trimmedOf(l.ExpiresOn),
			DocumentAssetID: trimmedOf(l.DocumentAssetID)})
	}
	return s.saveStep(ctx, c, "catalog-compliance", body)
}

func (s *sellerShellImpl) saveStep(ctx context.Context, c *domain.Caller, step string, body any) (domain.OnboardingStatus, error) {
	if err := requireCustomer(c); err != nil {
		return domain.OnboardingStatus{}, err
	}
	a, err := s.r.d.Sellers.SaveApplicationStep(ctx, c.BearerToken, step, body)
	if err != nil {
		return domain.OnboardingStatus{}, mapErr("seller", err)
	}
	return onboardingStatus(a), nil
}

// OnboardingStripeSession creates (on first use) the platform-controlled connected account and an Account Session
// for Stripe's embedded onboarding component, with the publishable key Connect.js needs.
func (s *sellerShellImpl) OnboardingStripeSession(ctx context.Context, c *domain.Caller) (domain.OnboardingStripeSession, error) {
	if err := requireCustomer(c); err != nil {
		return domain.OnboardingStripeSession{}, err
	}
	se, err := s.r.d.Sellers.ApplicationStripeSession(ctx, c.BearerToken)
	if err != nil {
		return domain.OnboardingStripeSession{}, mapErr("seller", err)
	}
	out := domain.OnboardingStripeSession{AccountID: se.AccountID, ClientSecret: se.ClientSecret, PublishableKey: s.r.d.PublishableKey, LocalTest: se.LocalTest}
	if se.ExpiresAt != nil {
		out.ExpiresAt = strPtr(se.ExpiresAt.UTC().Format(time.RFC3339))
	}
	if !se.LocalTest && strings.TrimSpace(s.r.d.PublishableKey) == "" {
		return domain.OnboardingStripeSession{}, domain.NewProblem(http.StatusServiceUnavailable, "STRIPE_NOT_CONFIGURED", "Stripe isn't configured",
			"Stripe verification can't load: the BFF has no Stripe publishable key (STRIPE_PUBLISHABLE_KEY).")
	}
	return out, nil
}

// RefreshOnboardingStripe re-reads verification results from Stripe (after the embedded component closes).
func (s *sellerShellImpl) RefreshOnboardingStripe(ctx context.Context, c *domain.Caller) (domain.OnboardingStatus, error) {
	if err := requireCustomer(c); err != nil {
		return domain.OnboardingStatus{}, err
	}
	a, err := s.r.d.Sellers.RefreshApplicationStripe(ctx, c.BearerToken)
	if err != nil {
		return domain.OnboardingStatus{}, mapErr("seller", err)
	}
	return onboardingStatus(a), nil
}

// SubmitOnboarding sends the application to staff review; 422 lists every field still missing.
func (s *sellerShellImpl) SubmitOnboarding(ctx context.Context, c *domain.Caller) (domain.OnboardingStatus, error) {
	if err := requireCustomer(c); err != nil {
		return domain.OnboardingStatus{}, err
	}
	a, err := s.r.d.Sellers.SubmitApplication(ctx, c.BearerToken)
	if err != nil {
		return domain.OnboardingStatus{}, mapErr("seller", err)
	}
	return onboardingStatus(a), nil
}

// OnboardingPlans lists the plans for the application's kind (seller or provider).
func (s *sellerShellImpl) OnboardingPlans(ctx context.Context, c *domain.Caller) (domain.OnboardingPlans, error) {
	if err := requireCustomer(c); err != nil {
		return domain.OnboardingPlans{}, err
	}
	a, err := s.r.d.Sellers.Application(ctx, c.BearerToken)
	if err != nil {
		return domain.OnboardingPlans{}, mapErr("seller", err)
	}
	kind := firstNonEmpty(normalizeKind(a.Kind), domain.OrgKindSeller)
	plans, err := s.r.d.Sellers.Plans(ctx, strings.ToLower(kind))
	if err != nil {
		return domain.OnboardingPlans{}, mapErr("seller", err)
	}
	out := domain.OnboardingPlans{Plans: []domain.OnboardingPlanOffer{}, PublishableKey: s.r.d.PublishableKey}
	for _, p := range plans {
		if p.Kind != "" && normalizeKind(p.Kind) != kind {
			continue
		}
		offer := domain.OnboardingPlanOffer{Code: p.Code, Name: p.Name, Tagline: strPtr(p.Tagline), MonthlyCents: p.MonthlyCents, TrialDays: p.TrialDays,
			Features: append([]string{}, p.Features...), Prices: []domain.OnboardingPlanPrice{}}
		for _, pr := range p.Prices {
			offer.Prices = append(offer.Prices, domain.OnboardingPlanPrice{Cadence: strings.ToUpper(pr.Cadence), Months: pr.Months, TotalCents: pr.TotalCents,
				PerMonthCents: pr.PerMonthCents, SavingCents: pr.SavingCents, PerMonthLabel: pr.PerMonthLabel, BilledLabel: pr.BilledLabel,
				SavingLabel: strPtr(pr.SavingLabel), FirstCharge: firstChargeOf(pr.FirstCharge)})
		}
		out.Plans = append(out.Plans, offer)
	}
	return out, nil
}

// kycContentTypes are what the application's documents may be (media purpose kyc-document).
var kycContentTypes = []string{"application/pdf", "image/jpeg", "image/png", "image/webp"}

// OnboardingUpload signs a private, direct upload of an application document (permit, licence, insurance,
// formation document) to media (purpose kyc-document, ≤ 15 MB).
func (s *sellerShellImpl) OnboardingUpload(ctx context.Context, c *domain.Caller, fileName, contentType string, size int64) (domain.UploadTicket, error) {
	errs := map[string]string{}
	if strings.TrimSpace(fileName) == "" {
		errs["fileName"] = "Required"
	}
	if !slices.Contains(kycContentTypes, contentType) {
		errs["contentType"] = "Use a PDF, JPG, PNG or WebP"
	}
	if size <= 0 || size > 15<<20 {
		errs["size"] = "Documents must be 15 MB or smaller"
	}
	if len(errs) > 0 {
		return domain.UploadTicket{}, domain.ValidationProblem(errs)
	}
	if err := requireCustomer(c); err != nil {
		return domain.UploadTicket{}, err
	}
	u, err := s.r.d.Media.RequestUpload(ctx, c.BearerToken, "kyc-document", fileName, contentType, size)
	if err != nil {
		return domain.UploadTicket{}, mapErr("media", err)
	}
	headers := u.Upload.Headers
	if headers == nil {
		headers = map[string]string{}
	}
	return domain.UploadTicket{AssetID: u.Asset.ID, Method: firstNonEmpty(u.Upload.Method, http.MethodPut), URL: u.Upload.URL, Headers: headers,
		PublicURL: nil, ExpiresAt: u.Upload.ExpiresAt}, nil
}

// CompleteOnboardingUpload finalises an uploaded application document.
func (s *sellerShellImpl) CompleteOnboardingUpload(ctx context.Context, c *domain.Caller, assetID string) (domain.Asset, error) {
	if err := requireCustomer(c); err != nil {
		return domain.Asset{}, err
	}
	a, err := s.r.d.Media.Complete(ctx, c.BearerToken, assetID)
	if err != nil {
		return domain.Asset{}, mapErr("media", err)
	}
	return domain.Asset{ID: a.ID, URL: a.URL, Width: a.Width, Height: a.Height, Bytes: a.Bytes, ContentType: a.ContentType,
		Status: firstNonEmpty(strings.ToUpper(a.Status), "READY")}, nil
}

func postalOf(a *domain.OnboardingAddressInput) *port.SellerPostal {
	if a == nil {
		return nil
	}
	p := port.SellerPostal{Line1: strings.TrimSpace(a.Line1), Line2: strings.TrimSpace(a.Line2), City: strings.TrimSpace(a.City),
		State: strings.ToUpper(strings.TrimSpace(a.State)), PostalCode: strings.TrimSpace(a.PostalCode),
		Country: strings.ToUpper(firstNonEmpty(strings.TrimSpace(a.Country), "US"))}
	if p.Line1 == "" && p.City == "" && p.State == "" && p.PostalCode == "" {
		return nil
	}
	return &p
}

func onboardingAddressOf(a *port.SellerPostal) *domain.OnboardingAddress {
	if a == nil {
		return nil
	}
	return &domain.OnboardingAddress{Line1: strPtr(a.Line1), Line2: strPtr(a.Line2), City: strPtr(a.City), State: strPtr(a.State),
		PostalCode: strPtr(a.PostalCode), Country: strPtr(a.Country)}
}

func trimmedOf(p *string) string {
	if p == nil {
		return ""
	}
	return strings.TrimSpace(*p)
}

func firstChargeOf(f *port.SellerFirstCharge) *domain.OnboardingFirstCharge {
	if f == nil || f.Sentence == "" {
		return nil
	}
	return &domain.OnboardingFirstCharge{ChargeOn: strPtr(f.ChargeOn), AmountCents: f.AmountCents, Sentence: f.Sentence}
}

// onboardingForm maps every section of the application onto the wizard's form.
func onboardingForm(a port.SellerApplication, status string) domain.OnboardingForm {
	f := domain.OnboardingForm{Prefilled: []string{}, Owners: []domain.OnboardingOwner{}, Documents: []domain.OnboardingDocument{},
		Compliance: domain.OnboardingCompliance{Categories: []string{}, Services: []domain.OnboardingServiceLicence{}},
		Editable:   status == "DRAFT" || status == "INFO_REQUESTED"}
	if b := a.Business; b != nil {
		f.Business = domain.OnboardingBusiness{Offering: strPtr(strings.ToUpper(b.Offering)), EntityType: strPtr(strings.ToUpper(b.EntityType)),
			Country: strPtr(b.Country), StoreName: strPtr(b.StoreName), ContactEmail: strPtr(b.ContactEmail), Phone: strPtr(b.Phone), Website: strPtr(b.Website)}
	}
	if d := a.BusinessDetails; d != nil {
		f.Details = domain.OnboardingDetails{LegalFirstName: strPtr(d.LegalFirstName), LegalLastName: strPtr(d.LegalLastName), LegalName: strPtr(d.LegalName),
			DBA: strPtr(d.DBA), TaxIDType: strPtr(d.TaxIDType), TaxIDMasked: strPtr(d.TaxIDMasked), DateOfBirth: strPtr(d.DateOfBirth),
			StateOfFormation: strPtr(d.StateOfFormation), DateOfFormation: strPtr(d.DateOfFormation), Address: onboardingAddressOf(d.Address),
			FormationDocumentAssetID: strPtr(d.FormationDocumentAssetID), IRSDeterminationLetterAssetID: strPtr(d.IRSDeterminationLetterAssetID)}
	}
	for _, o := range a.Owners {
		f.Owners = append(f.Owners, domain.OnboardingOwner{FirstName: strPtr(o.FirstName), LastName: strPtr(o.LastName), Title: strPtr(o.Title),
			Email: strPtr(o.Email), OwnershipPercent: o.OwnershipPercent, SignificantControl: o.SignificantControl, DateOfBirth: strPtr(o.DateOfBirth),
			Address: onboardingAddressOf(o.Address)})
	}
	if p := a.Payouts; p != nil {
		f.Payouts = domain.OnboardingPayouts{AccountHolderName: strPtr(p.AccountHolderName), AccountType: strPtr(p.AccountType), BankName: strPtr(p.BankName),
			RoutingLast4: strPtr(p.RoutingLast4), AccountLast4: strPtr(p.AccountLast4), Linked: p.ExternalAccountLinked}
	}
	if p := a.Plan; p != nil {
		f.Plan = domain.OnboardingPlan{PlanCode: strPtr(p.PlanCode), PlanName: strPtr(p.PlanName), Cadence: strPtr(strings.ToUpper(p.Cadence)),
			PaymentMethodKind: strPtr(strings.ToUpper(p.PaymentMethodKind)), CardBrand: strPtr(p.CardBrand), CardLast4: strPtr(p.CardLast4),
			CardExpMonth: p.CardExpMonth, CardExpYear: p.CardExpYear, FirstCharge: firstChargeOf(p.FirstCharge)}
	}
	if c := a.Compliance; c != nil {
		f.Compliance.Categories = append(f.Compliance.Categories, c.Categories...)
		f.Compliance.BeautyLabellingConfirmed, f.Compliance.AgreementsAccepted, f.Compliance.W9Certified = c.BeautyLabellingConfirmed, c.AgreementsAccepted, c.W9Certified
		f.Compliance.InsuranceDocumentAssetID, f.Compliance.W9SignerName = strPtr(c.InsuranceDocumentAssetID), strPtr(c.W9SignerName)
		if p := c.FoodPermit; p != nil {
			f.Compliance.FoodPermit = &domain.OnboardingFoodPermit{PermitType: strPtr(p.PermitType), PermitNumber: strPtr(p.PermitNumber),
				IssuingAuthority: strPtr(p.IssuingAuthority), ExpiresOn: strPtr(p.ExpiresOn), DocumentAssetID: strPtr(p.DocumentAssetID)}
		}
		for _, l := range c.Services {
			f.Compliance.Services = append(f.Compliance.Services, domain.OnboardingServiceLicence{ServiceName: l.ServiceName, Regulated: l.Regulated,
				LicenceNumber: strPtr(l.LicenceNumber), IssuingState: strPtr(l.IssuingState), ExpiresOn: strPtr(l.ExpiresOn), DocumentAssetID: strPtr(l.DocumentAssetID)})
		}
	}
	for _, d := range a.Documents {
		f.Documents = append(f.Documents, domain.OnboardingDocument{AssetID: d.AssetID, Kind: d.Kind, Label: d.Label})
	}
	for _, k := range a.PrefilledFields {
		if n, ok := prefilledNames[k]; ok && !slices.Contains(f.Prefilled, n) {
			f.Prefilled = append(f.Prefilled, n)
		}
	}
	slices.Sort(f.Prefilled)
	return f
}

// wizardStep is a step of the wizard: its slug (URL segment, the seller service's step key), title and subtitle as
// designed. Labels that depend on the answers (entity type, offering) are resolved in stepLabel.
type wizardStep struct{ key, slug, label, sub string }

var wizardSteps = []wizardStep{
	{"BUSINESS", "business", "Your business", "What you offer and how you’re organised"},
	{"BUSINESS_DETAILS", "business-details", "Business details & TIN", "Legal name, EIN and registered address"},
	{"OWNERS", "owners", "Owners", "Everyone with 25%+ and a control person"},
	{"STRIPE_VERIFICATION", "stripe-verification", "Stripe verification", "Identity and tax ID checked by Stripe"},
	{"PAYOUTS", "payouts", "Payouts", "Bank account for your payouts"},
	{"PLAN", "plan", "Plan", "Choose a subscription"},
	{"CATALOG_COMPLIANCE", "catalog-compliance", "Catalog, permits & licences", "Categories, permits and agreements"},
	{"REVIEW", "review", "Review & submit", "Check everything before our team reviews it"},
}

// legacyStepKeys maps older step names (ACCOUNT, STRIPE, COMPLIANCE) onto the wizard's.
var legacyStepKeys = map[string]string{"STRIPE": "STRIPE_VERIFICATION", "COMPLIANCE": "CATALOG_COMPLIANCE"}

func stepKey(k string) string {
	u := strings.ToUpper(strings.ReplaceAll(strings.TrimSpace(k), "-", "_"))
	if m, ok := legacyStepKeys[u]; ok {
		return m
	}
	return u
}

func wizardStepOf(key string) (wizardStep, bool) {
	for _, w := range wizardSteps {
		if w.key == key {
			return w, true
		}
	}
	return wizardStep{}, false
}

// stepLabel is the design's title and subtitle: "About you" for a sole proprietor, "Services & licences" for
// someone offering services only.
func stepLabel(w wizardStep, a port.SellerApplication) (string, string) {
	entity, offering := "", ""
	if a.Business != nil {
		entity, offering = strings.ToUpper(a.Business.EntityType), strings.ToUpper(a.Business.Offering)
	}
	switch {
	case w.key == "BUSINESS_DETAILS" && entity == "SOLE_PROPRIETOR":
		return "About you", "Your legal name, tax ID and address"
	case w.key == "CATALOG_COMPLIANCE" && (offering == "SERVICES" || offering == "" && normalizeKind(a.Kind) == domain.OrgKindProvider):
		return "Services & licences", "What you provide and your licences"
	}
	return w.label, w.sub
}

// stripeStatus summarises the connected account: NOT_STARTED, IN_PROGRESS (details not submitted yet),
// REQUIREMENTS_DUE, PENDING (Stripe still verifying), VERIFIED or REJECTED.
func stripeStatus(st *port.SellerApplicationStripe) domain.OnboardingStripe {
	out := domain.OnboardingStripe{Status: "NOT_STARTED", CurrentlyDue: []string{}}
	if st == nil || st.AccountID == "" {
		return out
	}
	out.DetailsSubmitted, out.ChargesEnabled, out.PayoutsEnabled = st.DetailsSubmitted, st.ChargesEnabled, st.PayoutsEnabled
	out.IdentityStatus, out.TaxIDStatus = strPtr(st.IdentityStatus), strPtr(st.TaxIDStatus)
	out.CurrentlyDue = append(out.CurrentlyDue, st.CurrentlyDue...)
	switch {
	case strings.HasPrefix(st.DisabledReason, "rejected"):
		out.Status = "REJECTED"
	case !st.DetailsSubmitted:
		out.Status = "IN_PROGRESS"
	case len(st.CurrentlyDue) > 0:
		out.Status = "REQUIREMENTS_DUE"
	case st.IdentityStatus == "verified" && (st.TaxIDStatus == "verified" || st.TaxIDStatus == ""):
		out.Status = "VERIFIED"
	default:
		out.Status = "PENDING"
	}
	return out
}

// onboardingStatus is the wizard's steps (with their state), the step to resume at and every answer.
func onboardingStatus(a port.SellerApplication) domain.OnboardingStatus {
	status := orgStatus(a.Status)
	done := map[string]bool{}
	for _, k := range a.CompletedSteps {
		done[stepKey(k)] = true
	}
	keys := []string{}
	for _, k := range a.Steps {
		if u := stepKey(k); u != "REVIEW" && u != "ACCOUNT" && !slices.Contains(keys, u) {
			keys = append(keys, u)
		}
	}
	if len(keys) == 0 {
		for _, w := range wizardSteps[:len(wizardSteps)-1] {
			keys = append(keys, w.key)
		}
	}
	out := domain.OnboardingStatus{OrgID: a.OrgID, Kind: firstNonEmpty(normalizeKind(a.Kind), domain.OrgKindSeller), Status: status, Reference: strPtr(a.Reference),
		Steps: []domain.OnboardingStep{}, RequestedItems: append([]string{}, a.RequestedItems...), CanResume: status == "DRAFT" || status == "INFO_REQUESTED",
		Stripe: stripeStatus(a.Stripe), Timeline: []domain.OnboardingTimelineEntry{}, Form: onboardingForm(a, status)}
	if a.SubmittedAt != nil {
		out.SubmittedAt = strPtr(a.SubmittedAt.UTC().Format(time.RFC3339))
	}
	for _, k := range keys {
		w, ok := wizardStepOf(k)
		if !ok {
			w = wizardStep{key: k, slug: strings.ToLower(strings.ReplaceAll(k, "_", "-")), label: strings.ToUpper(k[:1]) + strings.ToLower(strings.ReplaceAll(k[1:], "_", " "))}
		}
		label, sub := stepLabel(w, a)
		state := "TODO"
		switch {
		case status == domain.OrgApproved || done[k]:
			state = "DONE"
		case status == "INFO_REQUESTED" && stepKey(a.CurrentStep) == k:
			state = "ACTION"
		}
		if state != "DONE" && out.ResumeStep == "" {
			out.ResumeStep = w.slug
		}
		out.Steps = append(out.Steps, domain.OnboardingStep{Key: k, Slug: w.slug, Label: label, Sub: sub, State: state})
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
	rv := wizardSteps[len(wizardSteps)-1]
	out.Steps = append(out.Steps, domain.OnboardingStep{Key: rv.key, Slug: rv.slug, Label: rv.label, Sub: rv.sub, State: review})
	if out.ResumeStep == "" {
		out.ResumeStep = rv.slug
	}
	for _, e := range a.Timeline {
		out.Timeline = append(out.Timeline, domain.OnboardingTimelineEntry{Action: strings.ToUpper(e.Action), Status: orgStatus(e.ToStatus),
			Note: strPtr(e.Note), OccurredAt: e.OccurredAt.UTC().Format(time.RFC3339)})
	}
	return out
}

// Onboarding is GET /v1/seller/onboarding.
func (s *sellerShellImpl) Onboarding(ctx context.Context, c *domain.Caller) (domain.OnboardingStatus, error) {
	if err := requireCustomer(c); err != nil {
		return domain.OnboardingStatus{}, err
	}
	a, err := s.r.d.Sellers.Application(ctx, c.BearerToken)
	if err != nil {
		return domain.OnboardingStatus{}, mapErr("seller", err)
	}
	return onboardingStatus(a), nil
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
