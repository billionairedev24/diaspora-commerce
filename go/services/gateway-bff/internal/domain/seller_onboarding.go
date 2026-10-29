package domain

import "strings"

// The seller / provider application wizard (S286, S288): GET /v1/seller/onboarding and the step saves.

// OnboardingStep is one step of the wizard. Key is the upper-case step key (BUSINESS, BUSINESS_DETAILS, …, REVIEW),
// Slug the URL segment Seller Center shows it under (/onboarding/<slug>).
type OnboardingStep struct {
	Key   string `json:"key"`
	Slug  string `json:"slug"`
	Label string `json:"label"`
	Sub   string `json:"sub"`
	State string `json:"state"`
}

// OnboardingTimelineEntry is one event of the application (submitted, approved, …) for status tracking.
type OnboardingTimelineEntry struct {
	Action     string  `json:"action"`
	Status     string  `json:"status"`
	Note       *string `json:"note"`
	OccurredAt string  `json:"occurredAt"`
}

// OnboardingStripe is what Stripe reports for the applicant's connected account.
type OnboardingStripe struct {
	// Status is NOT_STARTED, PENDING (details not submitted or Stripe still checking), REQUIREMENTS_DUE, VERIFIED or
	// REJECTED.
	Status           string   `json:"status"`
	DetailsSubmitted bool     `json:"detailsSubmitted"`
	ChargesEnabled   bool     `json:"chargesEnabled"`
	PayoutsEnabled   bool     `json:"payoutsEnabled"`
	IdentityStatus   *string  `json:"identityStatus"`
	TaxIDStatus      *string  `json:"taxIdStatus"`
	CurrentlyDue     []string `json:"currentlyDue"`
}

// OnboardingStatus is GET /v1/seller/onboarding.
type OnboardingStatus struct {
	OrgID          string           `json:"orgId"`
	Kind           string           `json:"kind"`
	Status         string           `json:"status"`
	Reference      *string          `json:"reference"`
	Steps          []OnboardingStep `json:"steps"`
	RequestedItems []string         `json:"requestedItems"`
	CanResume      bool             `json:"canResume"`
	// ResumeStep is the slug of the first incomplete step ("review" once every step is complete).
	ResumeStep  string                    `json:"resumeStep"`
	SubmittedAt *string                   `json:"submittedAt"`
	Stripe      OnboardingStripe          `json:"stripe"`
	Timeline    []OnboardingTimelineEntry `json:"timeline"`
	Form        OnboardingForm            `json:"form"`
}

// Start-selling problem codes (S286).
const (
	CodeStaffCannotApply  = "STAFF_CANNOT_APPLY"
	CodeAlreadyApproved   = "ALREADY_APPROVED"
	CodeApplicationExists = "APPLICATION_EXISTS"
)

// OnboardingAddress is an address in the wizard (every field nullable).
type OnboardingAddress struct {
	Line1      *string `json:"line1"`
	Line2      *string `json:"line2"`
	City       *string `json:"city"`
	State      *string `json:"state"`
	PostalCode *string `json:"postalCode"`
	Country    *string `json:"country"`
}

// OnboardingBusiness is the wizard's "Your business" step.
type OnboardingBusiness struct {
	Offering     *string `json:"offering"`
	EntityType   *string `json:"entityType"`
	Country      *string `json:"country"`
	StoreName    *string `json:"storeName"`
	ContactEmail *string `json:"contactEmail"`
	Phone        *string `json:"phone"`
	Website      *string `json:"website"`
}

// OnboardingDetails is the wizard's "About you" (sole proprietor) / "Business details & TIN" step. The tax ID is only
// ever returned masked (last 4).
type OnboardingDetails struct {
	LegalFirstName                *string            `json:"legalFirstName"`
	LegalLastName                 *string            `json:"legalLastName"`
	LegalName                     *string            `json:"legalName"`
	DBA                           *string            `json:"dba"`
	TaxIDType                     *string            `json:"taxIdType"`
	TaxIDMasked                   *string            `json:"taxIdMasked"`
	DateOfBirth                   *string            `json:"dateOfBirth"`
	StateOfFormation              *string            `json:"stateOfFormation"`
	DateOfFormation               *string            `json:"dateOfFormation"`
	Address                       *OnboardingAddress `json:"address"`
	FormationDocumentAssetID      *string            `json:"formationDocumentAssetId"`
	IRSDeterminationLetterAssetID *string            `json:"irsDeterminationLetterAssetId"`
}

// OnboardingOwner is a beneficial owner or person with significant control.
type OnboardingOwner struct {
	FirstName          *string            `json:"firstName"`
	LastName           *string            `json:"lastName"`
	Title              *string            `json:"title"`
	Email              *string            `json:"email"`
	OwnershipPercent   *float64           `json:"ownershipPercent"`
	SignificantControl bool               `json:"significantControl"`
	DateOfBirth        *string            `json:"dateOfBirth"`
	Address            *OnboardingAddress `json:"address"`
}

// OnboardingPayouts is the payout bank account as Stripe accepted it (last 4 digits only).
type OnboardingPayouts struct {
	AccountHolderName *string `json:"accountHolderName"`
	AccountType       *string `json:"accountType"`
	BankName          *string `json:"bankName"`
	RoutingLast4      *string `json:"routingLast4"`
	AccountLast4      *string `json:"accountLast4"`
	Linked            bool    `json:"linked"`
}

// OnboardingFirstCharge is the plan's first charge ("Free for 30 days. First charge of $39.00 on …").
type OnboardingFirstCharge struct {
	ChargeOn    *string `json:"chargeOn"`
	AmountCents int64   `json:"amountCents"`
	Sentence    string  `json:"sentence"`
}

// OnboardingPlan is the chosen plan and how it is paid (card brand and last 4 only).
type OnboardingPlan struct {
	PlanCode          *string                `json:"planCode"`
	PlanName          *string                `json:"planName"`
	Cadence           *string                `json:"cadence"`
	PaymentMethodKind *string                `json:"paymentMethodKind"`
	CardBrand         *string                `json:"cardBrand"`
	CardLast4         *string                `json:"cardLast4"`
	CardExpMonth      *int                   `json:"cardExpMonth"`
	CardExpYear       *int                   `json:"cardExpYear"`
	FirstCharge       *OnboardingFirstCharge `json:"firstCharge"`
}

// OnboardingFoodPermit is the food permit of a groceries / prepared-food seller.
type OnboardingFoodPermit struct {
	PermitType       *string `json:"permitType"`
	PermitNumber     *string `json:"permitNumber"`
	IssuingAuthority *string `json:"issuingAuthority"`
	ExpiresOn        *string `json:"expiresOn"`
	DocumentAssetID  *string `json:"documentAssetId"`
}

// OnboardingServiceLicence is a service a provider offers, with its licence.
type OnboardingServiceLicence struct {
	ServiceName     string  `json:"serviceName"`
	Regulated       bool    `json:"regulated"`
	LicenceNumber   *string `json:"licenceNumber"`
	IssuingState    *string `json:"issuingState"`
	ExpiresOn       *string `json:"expiresOn"`
	DocumentAssetID *string `json:"documentAssetId"`
}

// OnboardingCompliance is the "Catalog, permits & licences" / "Services & licences" step.
type OnboardingCompliance struct {
	Categories               []string                   `json:"categories"`
	FoodPermit               *OnboardingFoodPermit      `json:"foodPermit"`
	BeautyLabellingConfirmed *bool                      `json:"beautyLabellingConfirmed"`
	Services                 []OnboardingServiceLicence `json:"services"`
	InsuranceDocumentAssetID *string                    `json:"insuranceDocumentAssetId"`
	AgreementsAccepted       *bool                      `json:"agreementsAccepted"`
	W9Certified              *bool                      `json:"w9Certified"`
	W9SignerName             *string                    `json:"w9SignerName"`
	// Agreements are the legal documents accepted, each with the version that was shown and the date.
	Agreements []OnboardingAgreement `json:"agreements"`
}

// OnboardingAgreement is a legal document an applicant accepted: its key, the version shown and the date accepted.
type OnboardingAgreement struct {
	Key        string  `json:"key"`
	Version    string  `json:"version"`
	AcceptedOn *string `json:"acceptedOn"`
}

// OnboardingAgreementInput is a document and version as the applicant saw it.
type OnboardingAgreementInput struct {
	Key     string `json:"key"`
	Version string `json:"version"`
}

// RequiredAgreements are the legal documents (content keys) an application must accept before it can be submitted:
// sellers the seller terms, payments terms and data processing addendum; providers the provider terms in place of the
// seller terms; an application for both accepts both sets. Mirrors seller-service ComplianceSection.
func RequiredAgreements(offering string) []string {
	products := []string{"seller-terms", "payments-terms", "data-processing"}
	services := []string{"provider-terms", "payments-terms", "data-processing"}
	switch strings.ToUpper(strings.TrimSpace(offering)) {
	case "SERVICES":
		return services
	case "BOTH":
		return []string{"seller-terms", "provider-terms", "payments-terms", "data-processing"}
	default:
		return products
	}
}

// OnboardingDocument is an uploaded document of the application.
type OnboardingDocument struct {
	AssetID string `json:"assetId"`
	Kind    string `json:"kind"`
	Label   string `json:"label"`
}

// OnboardingForm holds every answer of the wizard and which were prefilled from the account.
type OnboardingForm struct {
	Business   OnboardingBusiness   `json:"business"`
	Details    OnboardingDetails    `json:"details"`
	Owners     []OnboardingOwner    `json:"owners"`
	Payouts    OnboardingPayouts    `json:"payouts"`
	Plan       OnboardingPlan       `json:"plan"`
	Compliance OnboardingCompliance `json:"compliance"`
	Documents  []OnboardingDocument `json:"documents"`
	Prefilled  []string             `json:"prefilled"`
	Editable   bool                 `json:"editable"`
}

// OnboardingBusinessInput is PUT /v1/seller/onboarding/business.
type OnboardingBusinessInput struct {
	Offering     string `json:"offering"`
	EntityType   string `json:"entityType"`
	Country      string `json:"country"`
	StoreName    string `json:"storeName"`
	ContactEmail string `json:"contactEmail"`
	Phone        string `json:"phone"`
	Website      string `json:"website"`
}

// OnboardingAddressInput is an address in a step save.
type OnboardingAddressInput struct {
	Line1      string `json:"line1"`
	Line2      string `json:"line2"`
	City       string `json:"city"`
	State      string `json:"state"`
	PostalCode string `json:"postalCode"`
	Country    string `json:"country"`
}

// OnboardingDetailsInput is PUT /v1/seller/onboarding/business-details. TaxID (SSN/ITIN or EIN) is sent to the seller
// service, which encrypts it; empty keeps the stored one. It is never logged or returned.
type OnboardingDetailsInput struct {
	LegalFirstName                string                  `json:"legalFirstName"`
	LegalLastName                 string                  `json:"legalLastName"`
	LegalName                     string                  `json:"legalName"`
	DBA                           string                  `json:"dba"`
	TaxID                         string                  `json:"taxId"`
	DateOfBirth                   string                  `json:"dateOfBirth"`
	StateOfFormation              string                  `json:"stateOfFormation"`
	DateOfFormation               string                  `json:"dateOfFormation"`
	Address                       *OnboardingAddressInput `json:"address"`
	FormationDocumentAssetID      string                  `json:"formationDocumentAssetId"`
	IRSDeterminationLetterAssetID string                  `json:"irsDeterminationLetterAssetId"`
}

// OnboardingOwnerInput is one person of PUT /v1/seller/onboarding/owners.
type OnboardingOwnerInput struct {
	FirstName          string                  `json:"firstName"`
	LastName           string                  `json:"lastName"`
	Title              string                  `json:"title"`
	Email              string                  `json:"email"`
	OwnershipPercent   *float64                `json:"ownershipPercent"`
	SignificantControl bool                    `json:"significantControl"`
	DateOfBirth        string                  `json:"dateOfBirth"`
	Address            *OnboardingAddressInput `json:"address"`
}

// OnboardingOwnersInput is PUT /v1/seller/onboarding/owners.
type OnboardingOwnersInput struct {
	Owners []OnboardingOwnerInput `json:"owners"`
}

// OnboardingPayoutsInput is PUT /v1/seller/onboarding/payouts. The numbers go to Stripe once, through the seller
// service, and are never stored or logged.
type OnboardingPayoutsInput struct {
	AccountHolderName         string `json:"accountHolderName"`
	AccountType               string `json:"accountType"`
	RoutingNumber             string `json:"routingNumber"`
	AccountNumber             string `json:"accountNumber"`
	AccountNumberConfirmation string `json:"accountNumberConfirmation"`
}

// OnboardingPlanInput is PUT /v1/seller/onboarding/plan. Cards are tokenised by Stripe.js (pm_…).
type OnboardingPlanInput struct {
	PlanCode              string `json:"planCode"`
	Cadence               string `json:"cadence"`
	PaymentMethodKind     string `json:"paymentMethodKind"`
	StripePaymentMethodID string `json:"stripePaymentMethodId"`
	CardBrand             string `json:"cardBrand"`
	CardLast4             string `json:"cardLast4"`
	CardExpMonth          *int   `json:"cardExpMonth"`
	CardExpYear           *int   `json:"cardExpYear"`
}

// OnboardingComplianceInput is PUT /v1/seller/onboarding/catalog-compliance.
type OnboardingComplianceInput struct {
	Categories               []string                   `json:"categories"`
	FoodPermit               *OnboardingFoodPermit      `json:"foodPermit"`
	BeautyLabellingConfirmed *bool                      `json:"beautyLabellingConfirmed"`
	Services                 []OnboardingServiceLicence `json:"services"`
	InsuranceDocumentAssetID string                     `json:"insuranceDocumentAssetId"`
	AgreementsAccepted       bool                       `json:"agreementsAccepted"`
	W9Certified              bool                       `json:"w9Certified"`
	W9SignerName             string                     `json:"w9SignerName"`
	// Agreements are the documents and versions the applicant was shown; the BFF records the current version of each
	// required document and rejects the save when one changed since (the applicant must read it again).
	Agreements []OnboardingAgreementInput `json:"agreements"`
}

// OnboardingStripeSession is POST /v1/seller/onboarding/stripe/session: what Connect.js needs to show Stripe's
// embedded onboarding. LocalTest is true only for the local stand-in (no Stripe key): Seller Center then shows its
// labelled local verification instead of loading Stripe.
type OnboardingStripeSession struct {
	AccountID      string  `json:"accountId"`
	ClientSecret   string  `json:"clientSecret"`
	PublishableKey string  `json:"publishableKey"`
	ExpiresAt      *string `json:"expiresAt"`
	LocalTest      bool    `json:"localTest"`
}

// OnboardingPlanPrice is a plan's price for one cadence.
type OnboardingPlanPrice struct {
	Cadence       string                 `json:"cadence"`
	Months        int                    `json:"months"`
	TotalCents    int64                  `json:"totalCents"`
	PerMonthCents int64                  `json:"perMonthCents"`
	SavingCents   int64                  `json:"savingCents"`
	PerMonthLabel string                 `json:"perMonthLabel"`
	BilledLabel   string                 `json:"billedLabel"`
	SavingLabel   *string                `json:"savingLabel"`
	FirstCharge   *OnboardingFirstCharge `json:"firstCharge"`
}

// OnboardingPlanOffer is a plan the applicant can choose.
type OnboardingPlanOffer struct {
	Code         string                `json:"code"`
	Name         string                `json:"name"`
	Tagline      *string               `json:"tagline"`
	MonthlyCents int64                 `json:"monthlyCents"`
	TrialDays    int                   `json:"trialDays"`
	Features     []string              `json:"features"`
	Prices       []OnboardingPlanPrice `json:"prices"`
}

// OnboardingPlans is GET /v1/seller/onboarding/plans. PublishableKey is for Stripe Elements (a paid plan's card).
type OnboardingPlans struct {
	Plans          []OnboardingPlanOffer `json:"plans"`
	PublishableKey string                `json:"publishableKey"`
}
