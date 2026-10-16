package port

import (
	"context"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
)

// Ports for the Seller Center & Provider Hub routes (contracts/openapi/gateway-bff-seller.yaml). Every call relays the
// member's access token. Live upstreams: identity, seller, catalog, inventory, payments, storefront, media and
// carrier-gateway. Planned (being built): orders (seller queue/transitions exist in code, not yet deployed), bookings
// and offers — their adapters use the planned paths and surface 503 until the services serve them.

// ---------------------------------------------------------------- identity (organisations, module matrix)

// MemberOrg is identity's Org entry of GET /v1/me.
type MemberOrg struct {
	ID     string `json:"id"`
	Kind   string `json:"kind"`
	Name   string `json:"name"`
	Status string `json:"status"`
	Role   string `json:"role"`
}

// Member is identity's GET /v1/me with organisations and the active org (token org claim).
type Member struct {
	ID          string      `json:"id"`
	FirstName   string      `json:"firstName"`
	LastName    string      `json:"lastName"`
	Email       string      `json:"email"`
	OrgID       string      `json:"orgId"`
	Roles       []string    `json:"roles"`
	Permissions []string    `json:"permissions"`
	Orgs        []MemberOrg `json:"orgs"`
	Landing     string      `json:"landing"`
	// Phone is the account's mobile number (E.164), verified or not.
	Phone string `json:"phone"`
}

// Invitation is identity's OrgInvitation (also the body of POST /v1/orgs/{orgId}/invitations).
type Invitation struct {
	ID        string    `json:"id"`
	OrgID     string    `json:"orgId"`
	OrgName   string    `json:"orgName"`
	OrgKind   string    `json:"orgKind"`
	Email     string    `json:"email"`
	RoleKey   string    `json:"roleKey"`
	RoleLabel string    `json:"roleLabel"`
	Status    string    `json:"status"`
	CreatedAt time.Time `json:"createdAt"`
	ExpiresAt time.Time `json:"expiresAt"`
}

// OrgMember is one entry of identity's member directory (GET /v1/orgs/{orgId}/members).
type OrgMember struct {
	UserID    string    `json:"userId"`
	Name      string    `json:"name"`
	FirstName string    `json:"firstName"`
	LastName  string    `json:"lastName"`
	Email     string    `json:"email"`
	RoleKey   string    `json:"roleKey"`
	RoleLabel string    `json:"roleLabel"`
	Status    string    `json:"status"`
	JoinedAt  time.Time `json:"joinedAt"`
}

// InvitationPreview is identity's public POST /v1/invitations/lookup answer.
type InvitationPreview struct {
	OrgName   string    `json:"orgName"`
	OrgKind   string    `json:"orgKind"`
	Email     string    `json:"email"`
	RoleKey   string    `json:"roleKey"`
	RoleLabel string    `json:"roleLabel"`
	Status    string    `json:"status"`
	ExpiresAt time.Time `json:"expiresAt"`
}

// AcceptedInvitation is identity's POST /v1/invitations/accept answer.
type AcceptedInvitation struct {
	InvitationID  string `json:"invitationId"`
	OrgID         string `json:"orgId"`
	OrgName       string `json:"orgName"`
	OrgKind       string `json:"orgKind"`
	RoleKey       string `json:"roleKey"`
	UserID        string `json:"userId"`
	AlreadyMember bool   `json:"alreadyMember"`
}

// OrgSync is identity's copy of an organisation after POST /v1/orgs/{id}/sync.
type OrgSync struct {
	OrgID    string `json:"orgId"`
	Status   string `json:"status"`
	Repaired bool   `json:"repaired"`
}

// OrgAccessClient reads and changes organisation membership at identity.
type OrgAccessClient interface {
	Member(ctx context.Context, bearer string) (Member, error)
	// SyncOrg asks identity to repair its copy of the organisation (status, the owners' memberships) from the seller
	// service, the source of truth (POST /v1/orgs/{id}/sync): the caller saw the seller service approve it first.
	SyncOrg(ctx context.Context, bearer, orgID string) (OrgSync, error)
	SelectOrg(ctx context.Context, bearer, orgID string) (Member, error)
	// ModuleMatrix reads GET /v1/admin/module-access?portal= (needs access:read; sellers usually get 403).
	ModuleMatrix(ctx context.Context, bearer, portal string) (domain.ModuleMatrix, error)
	SetMemberRole(ctx context.Context, bearer, orgID, userID, roleKey string) error
	Invite(ctx context.Context, bearer, orgID, email, roleKey string) (Invitation, error)
	Members(ctx context.Context, bearer, orgID string) ([]OrgMember, error)
	Invitations(ctx context.Context, bearer, orgID string) ([]Invitation, error)
	RevokeInvitation(ctx context.Context, bearer, orgID, invitationID string) error
	RemoveMember(ctx context.Context, bearer, orgID, userID string) error
	// LookupInvitation is public (no bearer); the token travels in the body.
	LookupInvitation(ctx context.Context, token string) (InvitationPreview, error)
	AcceptInvitation(ctx context.Context, bearer, token string) (AcceptedInvitation, error)
	// BecomeApplicant adds the seller or provider applicant role to the caller's account (identity
	// POST /v1/me/roles/{seller|provider}-applicant, idempotent). kind is SELLER or PROVIDER.
	BecomeApplicant(ctx context.Context, bearer, kind string) error
}

// ---------------------------------------------------------------- seller service

// SellerOrgMember is seller's OrgMember.
type SellerOrgMember struct {
	UserID    string    `json:"userId"`
	RoleKey   string    `json:"roleKey"`
	CreatedAt time.Time `json:"createdAt"`
}

// SellerOrg is seller's Organisation.
type SellerOrg struct {
	ID          string            `json:"id"`
	Kind        string            `json:"kind"`
	LegalName   string            `json:"legalName"`
	DisplayName string            `json:"displayName"`
	Country     string            `json:"country"`
	Status      string            `json:"status"`
	Members     []SellerOrgMember `json:"members"`
}

// SellerApplication is the subset of seller's Application the onboarding screen shows.
type SellerApplication struct {
	ID             string   `json:"id"`
	Reference      string   `json:"reference"`
	OrgID          string   `json:"orgId"`
	Kind           string   `json:"kind"`
	Status         string   `json:"status"`
	CurrentStep    string   `json:"currentStep"`
	Steps          []string `json:"steps"`
	CompletedSteps []string `json:"completedSteps"`
	RequestedItems []string `json:"requestedItems"`
	// PrefilledFields lists answers taken from the account and still unchanged (business.phone, …).
	PrefilledFields []string                     `json:"prefilledFields"`
	Business        *SellerApplicationBiz        `json:"business"`
	BusinessDetails *SellerApplicationDetails    `json:"businessDetails"`
	Owners          []SellerApplicationOwner     `json:"owners"`
	Stripe          *SellerApplicationStripe     `json:"stripe"`
	Payouts         *SellerApplicationPayouts    `json:"payouts"`
	Plan            *SellerApplicationPlan       `json:"plan"`
	Compliance      *SellerApplicationCompliance `json:"compliance"`
	Documents       []SellerApplicationDocument  `json:"documents"`
	SubmittedAt     *time.Time                   `json:"submittedAt"`
	Timeline        []SellerApplicationEvent     `json:"timeline"`
	// PlanFeeCollectionOption is what the Plan step offers (S298); nil from an older seller service.
	PlanFeeCollectionOption *SellerPlanFeeOption `json:"planFeeCollectionOption"`
}

// SellerApplicationOwner is seller's OwnerView (and Owner when saving).
type SellerApplicationOwner struct {
	FirstName          string        `json:"firstName,omitempty"`
	LastName           string        `json:"lastName,omitempty"`
	Title              string        `json:"title,omitempty"`
	Email              string        `json:"email,omitempty"`
	OwnershipPercent   *float64      `json:"ownershipPercent,omitempty"`
	SignificantControl bool          `json:"significantControl"`
	DateOfBirth        string        `json:"dateOfBirth,omitempty"`
	Address            *SellerPostal `json:"address,omitempty"`
}

// SellerApplicationStripe is seller's StripeVerification (results only; never ID images or tax numbers).
type SellerApplicationStripe struct {
	AccountID        string   `json:"accountId"`
	DetailsSubmitted bool     `json:"detailsSubmitted"`
	ChargesEnabled   bool     `json:"chargesEnabled"`
	PayoutsEnabled   bool     `json:"payoutsEnabled"`
	IdentityStatus   string   `json:"identityStatus"`
	TaxIDStatus      string   `json:"taxIdStatus"`
	CurrentlyDue     []string `json:"currentlyDue"`
	DisabledReason   string   `json:"disabledReason"`
}

// SellerApplicationPayouts is seller's PayoutSection: the payout bank account Stripe holds, mirrored read-only
// (Stripe collects it in its embedded components; the account is Stripe-collected).
type SellerApplicationPayouts struct {
	BankName              string `json:"bankName"`
	AccountLast4          string `json:"accountLast4"`
	ExternalAccountLinked bool   `json:"externalAccountLinked"`
	BankStatus            string `json:"bankStatus"`
}

// SellerFirstCharge is seller's FirstCharge.
type SellerFirstCharge struct {
	ChargeOn    string `json:"chargeOn"`
	AmountCents int64  `json:"amountCents"`
	Sentence    string `json:"sentence"`
}

// SellerApplicationPlan is seller's PlanSelection.
type SellerApplicationPlan struct {
	PlanCode          string             `json:"planCode"`
	PlanName          string             `json:"planName"`
	Cadence           string             `json:"cadence"`
	PaymentMethodKind string             `json:"paymentMethodKind"`
	CardBrand         string             `json:"cardBrand"`
	CardLast4         string             `json:"cardLast4"`
	CardExpMonth      *int               `json:"cardExpMonth"`
	CardExpYear       *int               `json:"cardExpYear"`
	FirstCharge       *SellerFirstCharge `json:"firstCharge"`
	// PlanFeeCollection is the applicant's choice (S298): EARNINGS_FIRST, CARD or empty for the platform default.
	PlanFeeCollection string `json:"planFeeCollection"`
}

// SellerPlanFeeOption is seller's PlanFeeCollectionOption: whether the Plan step offers paying the plan from sales.
type SellerPlanFeeOption struct {
	Available       bool   `json:"available"`
	PlatformDefault string `json:"platformDefault"`
}

// SellerFoodPermit is seller's FoodPermit.
type SellerFoodPermit struct {
	PermitType       string `json:"permitType,omitempty"`
	PermitNumber     string `json:"permitNumber,omitempty"`
	IssuingAuthority string `json:"issuingAuthority,omitempty"`
	ExpiresOn        string `json:"expiresOn,omitempty"`
	DocumentAssetID  string `json:"documentAssetId,omitempty"`
}

// SellerServiceLicence is seller's ServiceLicence.
type SellerServiceLicence struct {
	ServiceName     string `json:"serviceName"`
	Regulated       bool   `json:"regulated"`
	LicenceNumber   string `json:"licenceNumber,omitempty"`
	IssuingState    string `json:"issuingState,omitempty"`
	ExpiresOn       string `json:"expiresOn,omitempty"`
	DocumentAssetID string `json:"documentAssetId,omitempty"`
}

// SellerApplicationCompliance is seller's ComplianceSection (CatalogComplianceStep when saving).
type SellerApplicationCompliance struct {
	Categories               []string               `json:"categories"`
	FoodPermit               *SellerFoodPermit      `json:"foodPermit,omitempty"`
	BeautyLabellingConfirmed *bool                  `json:"beautyLabellingConfirmed,omitempty"`
	Services                 []SellerServiceLicence `json:"services"`
	InsuranceDocumentAssetID string                 `json:"insuranceDocumentAssetId,omitempty"`
	AgreementsAccepted       *bool                  `json:"agreementsAccepted,omitempty"`
	W9Certified              *bool                  `json:"w9Certified,omitempty"`
	W9SignerName             string                 `json:"w9SignerName,omitempty"`
}

// SellerApplicationDocument is seller's DocumentRef.
type SellerApplicationDocument struct {
	AssetID string `json:"assetId"`
	Kind    string `json:"kind"`
	Label   string `json:"label"`
}

// SellerApplicationEvent is seller's TimelineEntry.
type SellerApplicationEvent struct {
	Action     string    `json:"action"`
	ToStatus   string    `json:"toStatus"`
	Note       string    `json:"note"`
	OccurredAt time.Time `json:"occurredAt"`
}

// SellerAccountSession is seller's AccountSession.
type SellerAccountSession struct {
	AccountID    string     `json:"accountId"`
	ClientSecret string     `json:"clientSecret"`
	ExpiresAt    *time.Time `json:"expiresAt"`
	LocalTest    bool       `json:"localTest"`
}

// SellerApplicationPlanStep is seller's PlanStep.
type SellerApplicationPlanStep struct {
	PlanCode              string `json:"planCode,omitempty"`
	Cadence               string `json:"cadence,omitempty"`
	PaymentMethodKind     string `json:"paymentMethodKind,omitempty"`
	StripePaymentMethodID string `json:"stripePaymentMethodId,omitempty"`
	CardBrand             string `json:"cardBrand,omitempty"`
	CardLast4             string `json:"cardLast4,omitempty"`
	CardExpMonth          *int   `json:"cardExpMonth,omitempty"`
	CardExpYear           *int   `json:"cardExpYear,omitempty"`
	PromoCode             string `json:"promoCode,omitempty"`
	PlanFeeCollection     string `json:"planFeeCollection,omitempty"`
}

// SellerApplicationBiz is seller's BusinessSection (step "business").
type SellerApplicationBiz struct {
	Offering     string `json:"offering,omitempty"`
	EntityType   string `json:"entityType,omitempty"`
	Country      string `json:"country,omitempty"`
	StoreName    string `json:"storeName,omitempty"`
	ContactEmail string `json:"contactEmail,omitempty"`
	Phone        string `json:"phone,omitempty"`
	Website      string `json:"website,omitempty"`
}

// SellerApplicationDetails is seller's BusinessDetails (BusinessDetailsStep when saving: TaxID is write-only, empty
// keeps the stored one; TaxIDMasked and TaxIDType are read-only).
type SellerApplicationDetails struct {
	LegalFirstName                string        `json:"legalFirstName,omitempty"`
	LegalLastName                 string        `json:"legalLastName,omitempty"`
	LegalName                     string        `json:"legalName,omitempty"`
	DBA                           string        `json:"dba,omitempty"`
	TaxID                         string        `json:"taxId,omitempty"`
	TaxIDType                     string        `json:"taxIdType,omitempty"`
	TaxIDMasked                   string        `json:"taxIdMasked,omitempty"`
	DateOfBirth                   string        `json:"dateOfBirth,omitempty"`
	StateOfFormation              string        `json:"stateOfFormation,omitempty"`
	DateOfFormation               string        `json:"dateOfFormation,omitempty"`
	Address                       *SellerPostal `json:"address,omitempty"`
	FormationDocumentAssetID      string        `json:"formationDocumentAssetId,omitempty"`
	IRSDeterminationLetterAssetID string        `json:"irsDeterminationLetterAssetId,omitempty"`
}

// SellerPostal is seller's Address.
type SellerPostal struct {
	Line1      string `json:"line1,omitempty"`
	Line2      string `json:"line2,omitempty"`
	City       string `json:"city,omitempty"`
	State      string `json:"state,omitempty"`
	PostalCode string `json:"postalCode,omitempty"`
	Country    string `json:"country,omitempty"`
}

// ApplicationPrefill is seller's ApplicationPrefill: what the account knows beyond the token's claims.
type ApplicationPrefill struct {
	GivenName  string        `json:"givenName,omitempty"`
	FamilyName string        `json:"familyName,omitempty"`
	Email      string        `json:"email,omitempty"`
	Phone      string        `json:"phone,omitempty"`
	StoreName  string        `json:"storeName,omitempty"`
	Address    *SellerPostal `json:"address,omitempty"`
}

// AddressBook reads the signed-in person's saved addresses (identity GET /v1/me/addresses).
type AddressBook interface {
	Addresses(ctx context.Context, bearer string) ([]IdentityAddress, error)
}

// AccountHealthRequirement is one open Stripe requirement and the embedded component that collects it.
type AccountHealthRequirement struct {
	Requirement string `json:"requirement"`
	Bucket      string `json:"bucket"`
	Form        *struct {
		Component string `json:"component"`
	} `json:"form"`
}

// AccountHealthBank is seller's PayoutBank: the payout bank account Stripe holds (never the numbers).
type AccountHealthBank struct {
	BankName string `json:"bankName"`
	Last4    string `json:"last4"`
	Status   string `json:"status"`
}

// AccountHealth is seller's AccountHealth.
type AccountHealth struct {
	Status          string                     `json:"status"`
	ChargesEnabled  bool                       `json:"chargesEnabled"`
	PayoutsEnabled  bool                       `json:"payoutsEnabled"`
	PayoutsPaused   bool                       `json:"payoutsPaused"`
	ListingsHidden  bool                       `json:"listingsHidden"`
	OpenItems       int                        `json:"openItems"`
	CurrentDeadline *time.Time                 `json:"currentDeadline"`
	PayoutBank      *AccountHealthBank         `json:"payoutBank"`
	Requirements    []AccountHealthRequirement `json:"requirements"`
}

// SellerPlanPrice is seller's PlanPrice (cents).
type SellerPlanPrice struct {
	Cadence       string             `json:"cadence"`
	Months        int                `json:"months"`
	TotalCents    int64              `json:"totalCents"`
	PerMonthCents int64              `json:"perMonthCents"`
	SavingCents   int64              `json:"savingCents"`
	PerMonthLabel string             `json:"perMonthLabel"`
	BilledLabel   string             `json:"billedLabel"`
	SavingLabel   string             `json:"savingLabel"`
	FirstCharge   *SellerFirstCharge `json:"firstCharge"`
}

// SellerPlan is seller's Plan.
type SellerPlan struct {
	Code         string            `json:"code"`
	Kind         string            `json:"kind"`
	Name         string            `json:"name"`
	Tagline      string            `json:"tagline"`
	MonthlyCents int64             `json:"monthlyCents"`
	TrialDays    int               `json:"trialDays"`
	Features     []string          `json:"features"`
	Prices       []SellerPlanPrice `json:"prices"`
	// Visibility is PUBLIC, HIDDEN or INVITE_ONLY (invited organisations see the last).
	Visibility string `json:"visibility"`
	// Entitlements is what the plan allows (listing cap, AI listing writer, Lola tier).
	Entitlements *SellerEntitlements `json:"entitlements"`
}

// SellerPaymentMethod is seller's PaymentMethod.
type SellerPaymentMethod struct {
	ID              string `json:"id"`
	Kind            string `json:"kind"`
	Brand           string `json:"brand"`
	Last4           string `json:"last4"`
	ExpMonth        int    `json:"expMonth"`
	ExpYear         int    `json:"expYear"`
	BankName        string `json:"bankName"`
	AccountType     string `json:"accountType"`
	Verified        bool   `json:"verified"`
	ReceivesPayouts bool   `json:"receivesPayouts"`
	IsDefault       bool   `json:"isDefault"`
	Label           string `json:"label"`
}

// SellerBilling is seller's Billing.
type SellerBilling struct {
	OrgID             string           `json:"orgId"`
	PlanCode          string           `json:"planCode"`
	PlanName          string           `json:"planName"`
	Cadence           string           `json:"cadence"`
	Status            string           `json:"status"`
	Price             *SellerPlanPrice `json:"price"`
	TrialEndsAt       *time.Time       `json:"trialEndsAt"`
	CurrentPeriodEnd  *time.Time       `json:"currentPeriodEnd"`
	NextChargeAt      *time.Time       `json:"nextChargeAt"`
	NextChargeCents   *int64           `json:"nextChargeCents"`
	NextChargeLabel   string           `json:"nextChargeLabel"`
	CancelAtPeriodEnd bool             `json:"cancelAtPeriodEnd"`
	PendingChange     *struct {
		PlanCode    string    `json:"planCode"`
		PlanName    string    `json:"planName"`
		Cadence     string    `json:"cadence"`
		EffectiveAt time.Time `json:"effectiveAt"`
	} `json:"pendingChange"`
	Dunning        string                `json:"dunning"`
	PaymentMethods []SellerPaymentMethod `json:"paymentMethods"`
	// S292–S297: the plan version paid, the platform credit balance and where the trial came from.
	PlanVersion        int    `json:"planVersion"`
	CreditBalanceCents int64  `json:"creditBalanceCents"`
	TrialSource        string `json:"trialSource"`
	// ChargeGate is a condition staff put on the plan charges (start after X sales, waive while X listings, waive
	// until a date); nil when charges apply as normal.
	ChargeGate *SellerChargeGate `json:"chargeGate"`
	// Entitlements is what the plan allows (listing cap, AI listing writer, Lola tier).
	Entitlements *SellerEntitlements `json:"entitlements"`
}

// SellerChargeGate is seller's ChargeGate with the seller's progress.
type SellerChargeGate struct {
	Mode       string     `json:"mode"`
	Metric     string     `json:"metric"`
	Threshold  *int64     `json:"threshold"`
	Progress   int64      `json:"progress"`
	Since      *time.Time `json:"since"`
	Until      *time.Time `json:"until"`
	ReasonCode string     `json:"reasonCode"`
	Note       string     `json:"note"`
	SetAt      *time.Time `json:"setAt"`
	MetAt      *time.Time `json:"metAt"`
	Label      string     `json:"label"`
}

// SellerEntitlements is seller's PlanEntitlements: what a plan lets an organisation do. There is no limit on shops or
// users.
type SellerEntitlements struct {
	Listings          *int64 `json:"listings"`
	ListingsUnlimited bool   `json:"listingsUnlimited"`
	AIListingWriter   bool   `json:"aiListingWriter"`
	LolaTier          string `json:"lolaTier"`
}

// SellerInvoice is seller's Invoice: a plan invoice AfroTransact issued itself.
type SellerInvoice struct {
	ID              string                 `json:"id"`
	Number          string                 `json:"number"`
	PeriodStart     *time.Time             `json:"periodStart"`
	PeriodEnd       *time.Time             `json:"periodEnd"`
	SubtotalCents   int64                  `json:"subtotalCents"`
	DiscountCents   int64                  `json:"discountCents"`
	AmountDueCents  int64                  `json:"amountDueCents"`
	AmountPaidCents int64                  `json:"amountPaidCents"`
	Currency        string                 `json:"currency"`
	Status          string                 `json:"status"`
	BillingReason   string                 `json:"billingReason"`
	PlanCode        string                 `json:"planCode"`
	Cadence         string                 `json:"cadence"`
	DueAt           *time.Time             `json:"dueAt"`
	PaidAt          *time.Time             `json:"paidAt"`
	Attempts        int                    `json:"attempts"`
	NextAttemptAt   *time.Time             `json:"nextAttemptAt"`
	LastError       string                 `json:"lastError"`
	CreatedAt       time.Time              `json:"createdAt"`
	Lines           []SellerInvoiceLine    `json:"lines"`
	Payments        []SellerInvoicePayment `json:"payments"`
	// What settled it, in the order tried: platform credit, the seller's sales (only if chosen), the card, a waiver.
	PaidFromCreditCents int64 `json:"paidFromCreditCents"`
	PaidFromSalesCents  int64 `json:"paidFromSalesCents"`
	PaidByCardCents     int64 `json:"paidByCardCents"`
	WaivedCents         int64 `json:"waivedCents"`
}

// SellerInvoiceLine is seller's InvoiceLine.
type SellerInvoiceLine struct {
	Kind        string `json:"kind"`
	Description string `json:"description"`
	AmountCents int64  `json:"amountCents"`
	Meter       string `json:"meter"`
}

// SellerInvoicePayment is seller's InvoicePayment.
type SellerInvoicePayment struct {
	Source      string    `json:"source"`
	AmountCents int64     `json:"amountCents"`
	Status      string    `json:"status"`
	Note        string    `json:"note"`
	CreatedAt   time.Time `json:"createdAt"`
}

// ShipFromAddress is seller's ShipFromAddress.
type ShipFromAddress struct {
	ID         string `json:"id"`
	Label      string `json:"label"`
	Name       string `json:"name"`
	Company    string `json:"company"`
	Phone      string `json:"phone"`
	Line1      string `json:"line1"`
	Line2      string `json:"line2"`
	City       string `json:"city"`
	State      string `json:"state"`
	PostalCode string `json:"postalCode"`
	Country    string `json:"country"`
	IsDefault  bool   `json:"isDefault"`
	Version    int64  `json:"version"`
	// Position is geo-service's geocoded position (nil until geo located the address); S255.
	Position *ShipFromPosition `json:"position"`
	// AfrotransactPickup is AfroTransact Delivery from this address (S255).
	AfrotransactPickup *ShipFromPickup `json:"afrotransactPickup"`
}

// ShipFromPosition is seller's GeoPosition.
type ShipFromPosition struct {
	Lat        float64    `json:"lat"`
	Lng        float64    `json:"lng"`
	Precision  string     `json:"precision"`
	GeocodedAt *time.Time `json:"geocodedAt"`
}

// ShipFromPickup is seller's AfrotransactPickup.
type ShipFromPickup struct {
	Enabled   bool                  `json:"enabled"`
	Hours     []domain.PickupWindow `json:"hours"`
	ZoneName  string                `json:"zoneName"`
	EnabledAt *time.Time            `json:"enabledAt"`
}

// PickupRequest is seller's AfrotransactPickupRequest.
type PickupRequest struct {
	Enabled bool                  `json:"enabled"`
	Hours   []domain.PickupWindow `json:"hours"`
	Version *int64                `json:"version,omitempty"`
}

// ShipFromInput is seller's ShipFromAddressRequest.
type ShipFromInput struct {
	Label       string `json:"label"`
	Name        string `json:"name"`
	Company     string `json:"company,omitempty"`
	Phone       string `json:"phone,omitempty"`
	Line1       string `json:"line1"`
	Line2       string `json:"line2,omitempty"`
	City        string `json:"city"`
	State       string `json:"state"`
	PostalCode  string `json:"postalCode"`
	Country     string `json:"country,omitempty"`
	MakeDefault bool   `json:"makeDefault"`
	Version     *int64 `json:"version,omitempty"`
}

// ShipFromClient manages an organisation's ship-from addresses (seller /v1/orgs/{orgId}/ship-from-addresses).
type ShipFromClient interface {
	ShipFrom(ctx context.Context, bearer, orgID string) ([]ShipFromAddress, error)
	CreateShipFrom(ctx context.Context, bearer, orgID string, in ShipFromInput) (ShipFromAddress, error)
	UpdateShipFrom(ctx context.Context, bearer, orgID, id string, in ShipFromInput) (ShipFromAddress, error)
	DefaultShipFrom(ctx context.Context, bearer, orgID, id string) ([]ShipFromAddress, error)
	DeleteShipFrom(ctx context.Context, bearer, orgID, id string) ([]ShipFromAddress, error)
	// SetPickup is PUT …/{id}/afrotransact-pickup (S255).
	SetPickup(ctx context.Context, bearer, orgID, id string, in PickupRequest) (ShipFromAddress, error)
}

// SellerServiceClient calls the seller service (organisations, onboarding, compliance, plans & billing, ship-from
// addresses).
type SellerServiceClient interface {
	ShipFromClient
	SellerBillingClient
	Org(ctx context.Context, bearer, orgID string) (SellerOrg, error)
	Application(ctx context.Context, bearer string) (SellerApplication, error)
	// StartApplication starts (created=true) or resumes the caller's application (seller POST /v1/applications/me).
	StartApplication(ctx context.Context, bearer, kind string, prefill ApplicationPrefill) (a SellerApplication, created bool, err error)
	// SaveApplicationStep autosaves one wizard step (seller PUT /v1/applications/me/steps/{step}).
	SaveApplicationStep(ctx context.Context, bearer, step string, body any) (SellerApplication, error)
	// ApplicationStripeSession creates the connected account on first use and an Account Session for Stripe's
	// embedded components (seller POST /v1/applications/me/stripe/account-session).
	ApplicationStripeSession(ctx context.Context, bearer string) (SellerAccountSession, error)
	// OrgStripeSession is an Account Session for an organisation's owner or finance member (Account health: Stripe's
	// components answer what Stripe needs and change the payout bank; seller POST /v1/orgs/{orgId}/stripe/account-session).
	OrgStripeSession(ctx context.Context, bearer, orgID string) (SellerAccountSession, error)
	// RefreshApplicationStripe re-reads the verification results from Stripe (POST …/stripe/refresh).
	RefreshApplicationStripe(ctx context.Context, bearer string) (SellerApplication, error)
	// SubmitApplication validates every step and sends the application to staff review (POST …/submit).
	SubmitApplication(ctx context.Context, bearer string) (SellerApplication, error)
	AccountHealth(ctx context.Context, bearer, orgID string) (AccountHealth, error)
	Billing(ctx context.Context, bearer, orgID string) (SellerBilling, error)
	Invoices(ctx context.Context, bearer, orgID string) ([]SellerInvoice, error)
	Plans(ctx context.Context, kind string) ([]SellerPlan, error)
	ChangePlan(ctx context.Context, bearer, orgID, planCode, cadence string) (applied string, b SellerBilling, err error)
	MakeDefaultPaymentMethod(ctx context.Context, bearer, orgID, id string) ([]SellerPaymentMethod, error)
	RemovePaymentMethod(ctx context.Context, bearer, orgID, id string) ([]SellerPaymentMethod, error)
}

// ---------------------------------------------------------------- catalog (authenticated product builder)

// CatalogProductVariant is catalog's Variant.
type CatalogProductVariant struct {
	ID             string                     `json:"id"`
	ATIN           string                     `json:"atin"`
	SellerSKU      string                     `json:"sellerSku"`
	GTIN           string                     `json:"gtin"`
	GTINExempt     bool                       `json:"gtinExempt"`
	Options        map[string]string          `json:"options"`
	PriceMinor     int64                      `json:"priceMinor"`
	CompareAtMinor *int64                     `json:"compareAtMinor"`
	Currency       string                     `json:"currency"`
	Quantity       int                        `json:"quantity"`
	Images         []domain.ProductImageInput `json:"images"`
}

// CatalogListing is catalog's ListingDetails (S307).
type CatalogListing struct {
	Description  string                    `json:"description"`
	Condition    string                    `json:"condition"`
	Keywords     []string                  `json:"keywords"`
	Fulfilment   string                    `json:"fulfilment"`
	Packaging    *domain.PackageDimensions `json:"packaging"`
	HandlingDays int                       `json:"handlingDays"`
	ShipFrom     *CatalogShipFrom          `json:"shipFrom"`
}

// CatalogShipFrom is catalog's ShipFrom snapshot of a seller ship-from address.
type CatalogShipFrom struct {
	LocationID string `json:"locationId,omitempty"`
	Label      string `json:"label,omitempty"`
	City       string `json:"city,omitempty"`
	Region     string `json:"region,omitempty"`
	Country    string `json:"country,omitempty"`
	PostalCode string `json:"postalCode,omitempty"`
}

// CatalogOwnProduct is catalog's Product.
type CatalogOwnProduct struct {
	ID                string                     `json:"id"`
	ATIN              string                     `json:"atin"`
	OwnerKind         string                     `json:"ownerKind"`
	OwnerOrgID        string                     `json:"ownerOrgId"`
	CategoryID        string                     `json:"categoryId"`
	CategoryRequestID string                     `json:"categoryRequestId"`
	ProductType       string                     `json:"productType"`
	Title             string                     `json:"title"`
	Attributes        map[string]string          `json:"attributes"`
	Images            []domain.ProductImageInput `json:"images"`
	VariationTheme    string                     `json:"variationTheme"`
	Variants          []CatalogProductVariant    `json:"variants"`
	Status            string                     `json:"status"`
	ModerationNote    string                     `json:"moderationNote"`
	UpdatedAt         time.Time                  `json:"updatedAt"`
	Version           int64                      `json:"version"`
	PreorderEligible  bool                       `json:"preorderEligible"`
	Listing           *CatalogListing            `json:"listing"`
}

// CatalogProductPage is catalog's ProductPage.
type CatalogProductPage struct {
	Items []CatalogOwnProduct `json:"items"`
	Total int64               `json:"total"`
	Page  int                 `json:"page"`
	Size  int                 `json:"size"`
}

// CatalogCategoryNode is catalog's flat CategoryNode.
type CatalogCategoryNode struct {
	ID          string `json:"id"`
	ParentID    string `json:"parentId"`
	Name        string `json:"name"`
	Level       string `json:"level"`
	ProductType string `json:"productType"`
	Path        string `json:"path"`
}

// CatalogProductType is catalog's ProductType.
type CatalogProductType struct {
	Code           string `json:"code"`
	Label          string `json:"label"`
	IdentifierRule string `json:"identifierRule"`
	Attributes     []struct {
		Key       string   `json:"key"`
		Label     string   `json:"label"`
		Options   []string `json:"options"`
		Required  bool     `json:"required"`
		Multiline bool     `json:"multiline"`
		Kind      string   `json:"kind"`
		Units     []string `json:"units"`
		Section   string   `json:"section"`
		Help      string   `json:"help"`
	} `json:"attributes"`
	Themes []domain.VariationTheme  `json:"themes"`
	Traits domain.ProductTypeTraits `json:"traits"`
}

// CatalogGtinCheck is catalog's GtinCheck.
type CatalogGtinCheck struct {
	Digits       string `json:"digits"`
	Valid        bool   `json:"valid"`
	Suggestion   string `json:"suggestion"`
	ExistingATIN string `json:"existingAtin"`
	Message      string `json:"message"`
}

// CatalogCategoryRequest is catalog's CategoryRequest.
type CatalogCategoryRequest struct {
	ID           string    `json:"id"`
	Reference    string    `json:"reference"`
	Path         string    `json:"path"`
	Status       string    `json:"status"`
	DecisionNote string    `json:"decisionNote"`
	CreatedAt    time.Time `json:"createdAt"`
}

// ProductQuery filters the organisation's products.
type ProductQuery struct {
	Status string
	Q      string
	Page   int
	Size   int
}

// SellerCatalogClient calls catalog's authenticated builder endpoints.
type SellerCatalogClient interface {
	Products(ctx context.Context, bearer string, q ProductQuery) (CatalogProductPage, error)
	Create(ctx context.Context, bearer string, d domain.ProductDraft) (CatalogOwnProduct, error)
	SetActive(ctx context.Context, bearer, productID string, active bool) (CatalogOwnProduct, error)
	CategoryTree(ctx context.Context) ([]CatalogCategoryNode, error)
	ProductType(ctx context.Context, code string) (CatalogProductType, error)
	CheckGTIN(ctx context.Context, bearer, code string, isbn bool) (CatalogGtinCheck, error)
	CategoryRequests(ctx context.Context, bearer string) ([]CatalogCategoryRequest, error)
	RequestCategory(ctx context.Context, bearer string, in domain.CategoryRequestInput) (CatalogCategoryRequest, error)
}

// ---------------------------------------------------------------- inventory, offers, media

// StockClient reads FBA positions (inventory GET /v1/positions).
type StockClient interface {
	Positions(ctx context.Context, bearer, ownerOrg string, lowStock bool) ([]domain.StockPosition, error)
}

// OffersClient sets seller-managed stock (planned offers PUT /v1/offers/stock/{sellerSku}).
type OffersClient interface {
	SetStock(ctx context.Context, bearer, idempotencyKey, sku string, quantity int, reason string) (domain.StockAdjustment, error)
	// List is GET /v1/offers (the token's organisation), one page.
	List(ctx context.Context, bearer string, page, size int) (OfferPage, error)
	// Update is PATCH /v1/offers/{id} (only the fields present change).
	Update(ctx context.Context, bearer, id string, body map[string]any) (SellerOfferUp, error)
	// ListCoupons is GET /v1/coupons (seller-funded for the token's organisation).
	ListCoupons(ctx context.Context, bearer string) ([]SellerCouponUp, error)
	CreateCoupon(ctx context.Context, bearer string, body map[string]any) (SellerCouponUp, error)
	SetCouponStatus(ctx context.Context, bearer, id, status string) (SellerCouponUp, error)
}

// SellerCouponUp is offers-service CouponResponse.
type SellerCouponUp struct {
	ID            string `json:"id"`
	Code          string `json:"code"`
	Description   string `json:"description"`
	Type          string `json:"type"`
	Value         int64  `json:"value"`
	Currency      string `json:"currency"`
	Scope         string `json:"scope"`
	ScopeRef      string `json:"scopeRef"`
	MinSpendMinor int64  `json:"minSpendMinor"`
	Status        string `json:"status"`
	Funding       string `json:"funding"`
	FundedByOrg   string `json:"fundedByOrg"`
	Version       int64  `json:"version"`
}

// SellerOfferUp is offers' Offer as seller center sees it.
type SellerOfferUp struct {
	ID           string `json:"id"`
	ATIN         string `json:"atin"`
	ProductATIN  string `json:"productAtin"`
	ProductID    string `json:"productId"`
	SellerSKU    string `json:"sellerSku"`
	Condition    string `json:"condition"`
	PriceMinor   int64  `json:"priceMinor"`
	Currency     string `json:"currency"`
	Fulfilment   string `json:"fulfilment"`
	Quantity     int    `json:"quantity"`
	AvailableQty int    `json:"availableQty"`
	ShipFrom     *struct {
		City       string `json:"city"`
		Region     string `json:"region"`
		PostalCode string `json:"postalCode"`
		LocationID string `json:"locationId"`
	} `json:"shipFrom"`
	ShipsFrom string `json:"shipsFrom"`
	Status    string `json:"status"`
	Version   int64  `json:"version"`
}

// OfferPage is offers' OfferPage.
type OfferPage struct {
	Items []SellerOfferUp `json:"items"`
	Total int64           `json:"total"`
	Page  int             `json:"page"`
	Size  int             `json:"size"`
}

// MediaAsset is media's Asset.
type MediaAsset struct {
	ID          string `json:"id"`
	URL         string `json:"url"`
	ContentType string `json:"contentType"`
	Bytes       *int64 `json:"bytes"`
	Width       *int   `json:"width"`
	Height      *int   `json:"height"`
	Status      string `json:"status"`
}

// MediaUpload is media's upload answer.
type MediaUpload struct {
	Asset  MediaAsset `json:"asset"`
	Upload struct {
		Method    string            `json:"method"`
		URL       string            `json:"url"`
		Headers   map[string]string `json:"headers"`
		PublicURL string            `json:"publicUrl"`
		ExpiresAt *time.Time        `json:"expiresAt"`
	} `json:"upload"`
}

// MediaLibraryImage is media's LibraryImage.
type MediaLibraryImage struct {
	AssetID   string    `json:"assetId"`
	FileName  string    `json:"fileName"`
	SKU       *string   `json:"sku"`
	Slot      *string   `json:"slot"`
	Status    string    `json:"status"`
	URL       *string   `json:"url"`
	Width     int       `json:"width"`
	Height    int       `json:"height"`
	Bytes     int64     `json:"bytes"`
	Flags     []string  `json:"flags"`
	CreatedAt time.Time `json:"createdAt"`
}

// MediaLibraryPage is media's LibraryPage.
type MediaLibraryPage struct {
	Items []MediaLibraryImage `json:"items"`
	Total int64               `json:"total"`
	Page  int                 `json:"page"`
	Size  int                 `json:"size"`
}

// MediaClient signs and finalises uploads and reads the organisation's image library (S310).
type MediaClient interface {
	RequestUpload(ctx context.Context, bearer, purpose, fileName, contentType string, size int64) (MediaUpload, error)
	// RequestLibraryUpload is RequestUpload of a product image that also goes into the image library.
	RequestLibraryUpload(ctx context.Context, bearer, fileName, contentType string, size int64) (MediaUpload, error)
	Complete(ctx context.Context, bearer, assetID string) (MediaAsset, error)
	Library(ctx context.Context, bearer, q, status, matched string, page, size int) (MediaLibraryPage, error)
	RemoveFromLibrary(ctx context.Context, bearer, assetID string) error
	RenameLibraryImage(ctx context.Context, bearer, assetID, fileName string) (MediaLibraryImage, error)
}

// ---------------------------------------------------------------- orders (seller side)

// ShipTo is orders' ShipTo snapshot.
type ShipTo struct {
	FullName   string `json:"fullName"`
	Phone      string `json:"phone"`
	Line1      string `json:"line1"`
	Line2      string `json:"line2"`
	City       string `json:"city"`
	State      string `json:"state"`
	PostalCode string `json:"postalCode"`
	Country    string `json:"country"`
}

// OrderShipmentView is orders' ShipmentView.
type OrderShipmentView struct {
	ID             string   `json:"id"`
	Label          string   `json:"label"`
	SellerOrgID    string   `json:"sellerOrgId"`
	FulfilmentType string   `json:"fulfilmentType"`
	DeliveryMethod string   `json:"deliveryMethod"`
	Perishable     bool     `json:"perishable"`
	Status         string   `json:"status"`
	Items          string   `json:"items"`
	ItemsMinor     int64    `json:"itemsMinor"`
	ShippingMinor  int64    `json:"shippingMinor"`
	TotalMinor     int64    `json:"totalMinor"`
	Carrier        string   `json:"carrier"`
	TrackingNumber string   `json:"trackingNumber"`
	NextActions    []string `json:"nextActions"`
	// PickupLocationID and LocalDelivery: AfroTransact Delivery from the seller's store (S255).
	PickupLocationID string             `json:"pickupLocationId"`
	LocalDelivery    *LocalDeliveryView `json:"localDelivery"`
	Lines            []struct {
		VariantATIN    string `json:"variantAtin"`
		Title          string `json:"title"`
		ImageURL       string `json:"imageUrl"`
		Quantity       int    `json:"quantity"`
		UnitPriceMinor int64  `json:"unitPriceMinor"`
		ItemsMinor     int64  `json:"itemsMinor"`
	} `json:"lines"`
	Timeline []struct {
		To        string           `json:"to"`
		ActorType string           `json:"actorType"`
		At        time.Time        `json:"at"`
		Proof     *UpDeliveryProof `json:"proof"`
	} `json:"timeline"`
}

// OrderView is orders' OrderView as a seller sees it.
type OrderView struct {
	OrderNumber     string              `json:"orderNumber"`
	Status          string              `json:"status"`
	PlacedAt        time.Time           `json:"placedAt"`
	Currency        string              `json:"currency"`
	TotalMinor      int64               `json:"totalMinor"`
	ShipTo          string              `json:"shipTo"`
	ShippingAddress *ShipTo             `json:"shippingAddress"`
	Shipments       []OrderShipmentView `json:"shipments"`
}

// SellerShipmentRow is orders' SellerShipmentView.
type SellerShipmentRow struct {
	OrderNumber string            `json:"orderNumber"`
	OrderStatus string            `json:"orderStatus"`
	PlacedAt    time.Time         `json:"placedAt"`
	ShipTo      *ShipTo           `json:"shipTo"`
	Shipment    OrderShipmentView `json:"shipment"`
}

// ShipmentQueue is orders' OrderPage<SellerShipmentView>.
type ShipmentQueue struct {
	Items      []SellerShipmentRow `json:"items"`
	NextCursor *string             `json:"nextCursor"`
	Total      int64               `json:"total"`
}

// SalesAnalytics is orders' GET /v1/seller/analytics answer (token's organisation, business time zone).
type SalesAnalytics struct {
	Currency string `json:"currency"`
	Days     []struct {
		Date           string  `json:"date"`
		SalesMinor     int64   `json:"salesMinor"`
		Units          int     `json:"units"`
		Orders         int     `json:"orders"`
		Checkouts      int     `json:"checkouts"`
		ConversionRate float64 `json:"conversionRate"`
	} `json:"days"`
	Totals struct {
		Orders         int     `json:"orders"`
		Checkouts      int     `json:"checkouts"`
		ConversionRate float64 `json:"conversionRate"`
	} `json:"totals"`
	TopProducts []struct {
		Title        string `json:"title"`
		RevenueMinor int64  `json:"revenueMinor"`
	} `json:"topProducts"`
	ByFulfilment []struct {
		Fulfilment string `json:"fulfilment"`
		Orders     int    `json:"orders"`
	} `json:"byFulfilment"`
	ToShip int `json:"toShip"`
}

// SellerOrdersClient calls orders for the seller side.
type SellerOrdersClient interface {
	Queue(ctx context.Context, bearer, status, cursor string, limit int) (ShipmentQueue, error)
	Order(ctx context.Context, bearer, orderNumber string) (OrderView, error)
	// Transition moves a shipment; handoverCode is the customer's 4-digit code for handing a pickup order over.
	Transition(ctx context.Context, bearer, idempotencyKey, shipmentID, to, handoverCode string) (OrderView, error)
	AttachTracking(ctx context.Context, bearer, shipmentID, carrier, trackingNumber string) (OrderView, error)
	Analytics(ctx context.Context, bearer string, from, to time.Time) (SalesAnalytics, error)
	// Split is GET /v1/orders/{number}/payment-split (a seller sees its own row).
	Split(ctx context.Context, bearer, orderNumber string) (OrderSplit, error)
}

// OrderSplit is orders' Split (payments' split of one order).
type OrderSplit struct {
	Recipients []struct {
		Recipient       string `json:"recipient"`
		Kind            string `json:"kind"`
		ItemsMinor      int64  `json:"itemsMinor"`
		ShippingMinor   int64  `json:"shippingMinor"`
		CommissionMinor int64  `json:"commissionMinor"`
		FbaFeesMinor    int64  `json:"fbaFeesMinor"`
		// DeliveryFeesMinor is payments' AfroTransact Delivery seller fees (4250); orders' Split does not relay it yet.
		DeliveryFeesMinor *int64 `json:"deliveryFeesMinor"`
		StripeFeeMinor    int64  `json:"stripeFeeMinor"`
		NetMinor          int64  `json:"netMinor"`
	} `json:"recipients"`
}

// LocalDeliveryView is orders' LocalDeliveryView (S255); the seller fee is hidden from customers.
type LocalDeliveryView struct {
	ShipFromLocationID  string   `json:"shipFromLocationId"`
	RoadMiles           *float64 `json:"roadMiles"`
	DurationMinutes     *float64 `json:"durationMinutes"`
	DistanceMethod      string   `json:"distanceMethod"`
	CustomerChargeMinor int64    `json:"customerChargeMinor"`
	SellerFeeMinor      *int64   `json:"sellerFeeMinor"`
	PricingVersion      int      `json:"pricingVersion"`
}

// ---------------------------------------------------------------- carrier-gateway

// CarrierAddress is carrier-gateway's Address.
type CarrierAddress struct {
	Name       string `json:"name,omitempty"`
	Phone      string `json:"phone,omitempty"`
	Line1      string `json:"line1"`
	Line2      string `json:"line2,omitempty"`
	City       string `json:"city"`
	State      string `json:"state,omitempty"`
	PostalCode string `json:"postalCode"`
	Country    string `json:"country"`
}

// CarrierShipment is carrier-gateway's Shipment.
type CarrierShipment struct {
	Reference  string          `json:"reference,omitempty"`
	OriginNode string          `json:"originNode"`
	From       CarrierAddress  `json:"from"`
	To         CarrierAddress  `json:"to"`
	Parcels    []domain.Parcel `json:"parcels"`
}

// CarrierQuote is carrier-gateway's Quote.
type CarrierQuote struct {
	Carrier           string     `json:"carrier"`
	Service           string     `json:"service"`
	ServiceName       string     `json:"serviceName"`
	AmountMinor       int64      `json:"amountMinor"`
	Currency          string     `json:"currency"`
	TransitDays       *int       `json:"transitDays"`
	EstimatedDelivery *time.Time `json:"estimatedDelivery"`
}

// CarrierRates is carrier-gateway's RateResult.
type CarrierRates struct {
	Quotes  []CarrierQuote `json:"quotes"`
	Partial bool           `json:"partial"`
}

// CarrierLabel is carrier-gateway's Label.
type CarrierLabel struct {
	Carrier        string `json:"carrier"`
	Service        string `json:"service"`
	TrackingNumber string `json:"trackingNumber"`
	CostMinor      int64  `json:"costMinor"`
	Currency       string `json:"currency"`
	DocumentURL    string `json:"documentUrl"`
	Status         string `json:"status"`
}

// CarrierClient rate-shops and buys labels.
type CarrierClient interface {
	Rates(ctx context.Context, bearer string, s CarrierShipment, carrier string) (CarrierRates, error)
	BuyLabel(ctx context.Context, bearer, idempotencyKey, carrier, service string, s CarrierShipment) (CarrierLabel, error)
	// LabelDocument is the printable document of the purchased label with this tracking number
	// (GET /v1/labels/by-tracking/{carrier}/{trackingNumber}/document).
	LabelDocument(ctx context.Context, bearer, carrier, trackingNumber string) (domain.FileDownload, error)
	// ManagedPickupLocations lists the org's own pickup locations, inactive ones too (GET /v1/pickup-locations?includeInactive=true).
	ManagedPickupLocations(ctx context.Context, bearer, ownerOrg string) ([]PickupLocation, error)
	// SavePickupLocation creates or replaces a pickup location (PUT /v1/pickup-locations/{id}).
	SavePickupLocation(ctx context.Context, bearer string, l PickupLocation) (PickupLocation, error)
}

// ---------------------------------------------------------------- payments (payouts)

// PayoutBalanceResponse is payments' PayoutBalanceResponse.
type PayoutBalanceResponse struct {
	Currency                  string `json:"currency"`
	AvailableMinor            int64  `json:"availableMinor"`
	OnHoldMinor               int64  `json:"onHoldMinor"`
	RequestedOrInTransitMinor int64  `json:"requestedOrInTransitMinor"`
	PaidMinor                 int64  `json:"paidMinor"`
	ProviderFeesMinor         int64  `json:"providerFeesMinor"`
	PlanFeesMinor             int64  `json:"planFeesMinor"`
	MinimumPayoutMinor        int64  `json:"minimumPayoutMinor"`
	CanRequestToday           bool   `json:"canRequestToday"`
	PayoutsPaused             bool   `json:"payoutsPaused"`
	PayoutsPausedReason       string `json:"payoutsPausedReason"`
	OnHold                    []struct {
		OrderID     string `json:"orderId"`
		AmountMinor int64  `json:"amountMinor"`
		ReleaseAt   string `json:"releaseAt"`
	} `json:"onHold"`
}

// PayoutResponse is payments' PayoutResponse.
type PayoutResponse struct {
	ID             string `json:"id"`
	AmountMinor    int64  `json:"amountMinor"`
	Currency       string `json:"currency"`
	Status         string `json:"status"`
	RequestedAt    string `json:"requestedAt"`
	HoldReason     string `json:"holdReason"`
	FailureMessage string `json:"failureMessage"`
}

// ProviderFeeResponse is payments' ProviderFeeResponse (late provider cancellations).
type ProviderFeeResponse struct {
	ID               string     `json:"id"`
	BookingID        string     `json:"bookingId"`
	BookingReference string     `json:"bookingReference"`
	AmountMinor      int64      `json:"amountMinor"`
	Currency         string     `json:"currency"`
	Reason           string     `json:"reason"`
	Status           string     `json:"status"`
	NextAttemptAt    *time.Time `json:"nextAttemptAt"`
	CreatedAt        time.Time  `json:"createdAt"`
}

// PayoutsClient reads balances and provider fees and requests payouts.
type PayoutsClient interface {
	Balance(ctx context.Context, bearer, orgID string) (PayoutBalanceResponse, error)
	ProviderFees(ctx context.Context, bearer, orgID string) ([]ProviderFeeResponse, error)
	Requests(ctx context.Context, bearer, orgID string) ([]PayoutResponse, error)
	RequestPayout(ctx context.Context, bearer, idempotencyKey string, amountMinor int64, all bool) (PayoutResponse, error)
	// PlanFeeWithholdings is payments GET /v1/orgs/{orgId}/plan-fee-withholdings (S298): plan invoices paid from sales.
	PlanFeeWithholdings(ctx context.Context, bearer, orgID string) ([]PlanFeeWithholding, error)
}

// PlanFeeWithholding is payments' PlanFeeWithholdingResponse.
type PlanFeeWithholding struct {
	ID               string    `json:"id"`
	InvoiceID        string    `json:"invoiceId"`
	InvoiceNumber    string    `json:"invoiceNumber"`
	RequestedMinor   int64     `json:"requestedMinor"`
	WithheldMinor    int64     `json:"withheldMinor"`
	ReleasedMinor    int64     `json:"releasedMinor"`
	OutstandingMinor int64     `json:"outstandingMinor"`
	Status           string    `json:"status"`
	Label            string    `json:"label"`
	CreatedAt        time.Time `json:"createdAt"`
	Deductions       []struct {
		OrderID     string    `json:"orderId"`
		AmountMinor int64     `json:"amountMinor"`
		At          time.Time `json:"at"`
		Label       string    `json:"label"`
	} `json:"deductions"`
}

// ---------------------------------------------------------------- payments (fee schedules)

// FeeScheduleSummary is the part of payments' FeeScheduleResponse the Seller Center needs (never other sellers' overrides).
type FeeScheduleSummary struct {
	ID            string         `json:"id"`
	Version       int            `json:"version"`
	EffectiveFrom time.Time      `json:"effectiveFrom"`
	CategoryBps   map[string]int `json:"categoryBps"`
	Overrides     []struct {
		OrgID    string `json:"orgId"`
		Category string `json:"category"`
	} `json:"overrides"`
	// DeliveryPricing is the AfroTransact Delivery section (S255): the BFF shows the caller's own seller fee only.
	DeliveryPricing *domain.DeliveryPricing `json:"deliveryPricing"`
	CancelledAt     *time.Time              `json:"cancelledAt"`
}

// EffectiveFeesResponse is payments' EffectiveFeesResponse (fees for one organisation and category).
type EffectiveFeesResponse struct {
	FeeScheduleID             string    `json:"feeScheduleId"`
	Version                   int       `json:"version"`
	EffectiveFrom             time.Time `json:"effectiveFrom"`
	Currency                  string    `json:"currency"`
	Category                  string    `json:"category"`
	CommissionBps             int       `json:"commissionBps"`
	CommissionSource          string    `json:"commissionSource"`
	CardPctBps                int       `json:"cardPctBps"`
	CardFixedMinor            int64     `json:"cardFixedMinor"`
	FbaPerUnitMinor           int64     `json:"fbaPerUnitMinor"`
	ReverseCommissionOnRefund bool      `json:"reverseCommissionOnRefund"`
	// Kind PRODUCT (commission) or SERVICE (connection fee); empty from a payments without connection fees.
	Kind             string     `json:"kind"`
	PlanCode         string     `json:"planCode"`
	NegotiatedRateID string     `json:"negotiatedRateId"`
	NegotiatedUntil  *time.Time `json:"negotiatedUntil"`
}

// AppliedRateResponse is payments' AppliedRate: one rate with where it comes from.
type AppliedRateResponse struct {
	Category         string     `json:"category"`
	Bps              int        `json:"bps"`
	Source           string     `json:"source"`
	PlanCode         string     `json:"planCode"`
	NegotiatedRateID string     `json:"negotiatedRateId"`
	NegotiatedUntil  *time.Time `json:"negotiatedUntil"`
}

// NegotiatedRateResponse is payments' NegotiatedRate.
type NegotiatedRateResponse struct {
	ID           string     `json:"id"`
	OrgID        string     `json:"orgId"`
	Type         string     `json:"type"`
	Category     string     `json:"category"`
	RateBps      int        `json:"rateBps"`
	StartsAt     time.Time  `json:"startsAt"`
	EndsAt       *time.Time `json:"endsAt"`
	Status       string     `json:"status"`
	Reason       string     `json:"reason"`
	NegotiatedBy string     `json:"negotiatedBy"`
	Reference    *string    `json:"reference"`
	CreatedBy    string     `json:"createdBy"`
	CreatedAt    time.Time  `json:"createdAt"`
	EndedAt      *time.Time `json:"endedAt"`
	EndedBy      *string    `json:"endedBy"`
	EndReason    *string    `json:"endReason"`
	ExpiredAt    *time.Time `json:"expiredAt"`
	Audit        []struct {
		Action string    `json:"action"`
		Actor  string    `json:"actor"`
		Reason *string   `json:"reason"`
		At     time.Time `json:"at"`
	} `json:"audit"`
}

// RateCardResponse is payments' RateCard: every rate of one organisation and kind.
type RateCardResponse struct {
	OrgID          string                   `json:"orgId"`
	Kind           string                   `json:"kind"`
	Version        int                      `json:"version"`
	EffectiveFrom  time.Time                `json:"effectiveFrom"`
	Currency       string                   `json:"currency"`
	PlanCode       string                   `json:"planCode"`
	OtherRate      AppliedRateResponse      `json:"otherRate"`
	Categories     []AppliedRateResponse    `json:"categories"`
	Negotiated     []NegotiatedRateResponse `json:"negotiated"`
	CardPctBps     int                      `json:"cardPctBps"`
	CardFixedMinor int64                    `json:"cardFixedMinor"`
	Retail         bool                     `json:"retail"`
}

// FeesClient reads payments' fee schedules. payments lets only staff and services (payments:service) read them, so
// the client uses the BFF's own client-credentials token; callers pass the member's own organisation only.
type FeesClient interface {
	Schedules(ctx context.Context) ([]FeeScheduleSummary, error)
	// Effective is GET /v1/fee-schedules/effective?category=&orgId=&at=.
	Effective(ctx context.Context, category, orgID string, at time.Time) (EffectiveFeesResponse, error)
	// EffectiveKind is the same for one kind (PRODUCT commission or SERVICE connection fee).
	EffectiveKind(ctx context.Context, kind, category, orgID string, at time.Time) (EffectiveFeesResponse, error)
	// RateCard is GET /v1/orgs/{orgId}/rate-card?kind=&at=.
	RateCard(ctx context.Context, orgID, kind string, at time.Time) (RateCardResponse, error)
}

// ---------------------------------------------------------------- storefront

// Storefront is the storefront service's Storefront document (kept whole so saves never drop fields).
type Storefront map[string]any

// StorefrontClient reads, saves and publishes the member's storefront draft.
type StorefrontClient interface {
	Draft(ctx context.Context, bearer string) (Storefront, error)
	SaveDraft(ctx context.Context, bearer string, s Storefront) (Storefront, error)
	Publish(ctx context.Context, bearer string) error
}

// ---------------------------------------------------------------- bookings (contracts/openapi/bookings.yaml)

// BookingsEarnings is the provider's earnings mapped from bookings' GET /v1/providers/{orgId}/earnings.
type BookingsEarnings struct {
	Currency          string               `json:"currency"`
	CommissionPercent float64              `json:"commissionPercent"`
	HeldMinor         int64                `json:"heldMinor"`
	Rows              []domain.EarningsRow `json:"rows"`
}

// BookingsAnalytics is the provider dashboard data the BFF derives from bookings (bookings in range + earnings).
type BookingsAnalytics struct {
	Days []struct {
		Date         string `json:"date"`
		Bookings     int    `json:"bookings"`
		DepositMinor int64  `json:"depositMinor"`
	} `json:"days"`
	ByService []struct {
		Service  string `json:"service"`
		Bookings int    `json:"bookings"`
	} `json:"byService"`
	ByStatus []struct {
		Status string `json:"status"`
		Count  int    `json:"count"`
	} `json:"byStatus"`
	Weeks []struct {
		Week         string `json:"week"`
		PaidOutMinor int64  `json:"paidOutMinor"`
		PendingMinor int64  `json:"pendingMinor"`
	} `json:"weeks"`
}

// BookingsCalendar is the calendar mapped from bookings' GET /v1/providers/{orgId}/calendar.
type BookingsCalendar struct {
	Timezone  string               `json:"timezone"`
	OpenHours []domain.DayHours    `json:"openHours"`
	Bookings  []domain.BookingCard `json:"bookings"`
}

// ProviderRef names the provider organisation (the active org of the session token) and the signed-in member
// (identity subject), whose team-member row holds the working hours shown on the Hours screen.
type ProviderRef struct {
	OrgID  string
	UserID string
}

// BookingsClient calls the bookings service's Provider Hub routes (/v1/providers/{orgId}/…) and booking actions
// (/v1/bookings/{id}/…), relaying the member's token. Bookings enforces the member's role server-side.
type BookingsClient interface {
	Calendar(ctx context.Context, bearer string, p ProviderRef, from, to time.Time) (BookingsCalendar, error)
	Requests(ctx context.Context, bearer string, p ProviderRef) ([]domain.BookingCard, error)
	Act(ctx context.Context, bearer, idempotencyKey, bookingID, action, reason string, proposedStart *time.Time) (domain.BookingCard, error)
	Availability(ctx context.Context, bearer string, p ProviderRef) (domain.Availability, error)
	SaveAvailability(ctx context.Context, bearer string, p ProviderRef, a domain.Availability) (domain.Availability, error)
	Services(ctx context.Context, bearer string, p ProviderRef) ([]domain.ServiceOffer, error)
	CreateService(ctx context.Context, bearer string, p ProviderRef, in domain.ServiceInput) (domain.ServiceOffer, error)
	UpdateService(ctx context.Context, bearer string, p ProviderRef, id string, in domain.ServiceInput) (domain.ServiceOffer, error)
	Policy(ctx context.Context, bearer string, p ProviderRef) (domain.PolicyInput, error)
	SavePolicy(ctx context.Context, bearer string, p ProviderRef, in domain.PolicyInput) (domain.PolicyInput, error)
	Earnings(ctx context.Context, bearer string, p ProviderRef, from, to time.Time) (BookingsEarnings, error)
	Analytics(ctx context.Context, bearer string, p ProviderRef, from, to time.Time) (BookingsAnalytics, error)
}
