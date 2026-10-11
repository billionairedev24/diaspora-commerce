package domain

import (
	"fmt"
	"slices"
	"strings"
	"time"
)

// Seller-portal problem codes (contracts/openapi/gateway-bff-seller.yaml).
const (
	CodeNotApproved    = "NOT_APPROVED"
	CodeNoOrganisation = "NO_ORGANISATION"
	// Team & roles (identity's identity.* codes, S236).
	CodeLastOwner         = "LAST_OWNER"
	CodeInvalidRole       = "INVALID_ROLE"
	CodeOwnerOnly         = "OWNER_ONLY"
	CodeOwnRole           = "OWN_ROLE"
	CodeAlreadyMember     = "ALREADY_MEMBER"
	CodeInvitationExpired = "INVITATION_EXPIRED"
	CodeInvitationUsed    = "INVITATION_USED"
	CodeInvitationRevoked = "INVITATION_REVOKED"
	CodeInvitationEmail   = "INVITATION_EMAIL_MISMATCH"
)

// Organisation kinds and portals.
const (
	OrgKindSeller   = "SELLER"
	OrgKindProvider = "PROVIDER"
	PortalSeller    = "SELLER_CENTER"
	PortalProvider  = "PROVIDER_HUB"
	OrgApproved     = "APPROVED"
)

// Access levels, weakest first. NONE never reaches clients: modules without access are left out.
const (
	LevelNone    = "NONE"
	LevelView    = "VIEW"
	LevelEdit    = "EDIT"
	LevelApprove = "APPROVE"
	LevelFull    = "FULL"
)

var levelRank = map[string]int{LevelNone: 0, LevelView: 1, LevelEdit: 2, LevelApprove: 3, LevelFull: 4}

// NormalizeLevel maps identity's level spellings (none/view/edit/approve/full, any case) onto the constants.
func NormalizeLevel(v string) string {
	u := strings.ToUpper(strings.TrimSpace(v))
	if _, ok := levelRank[u]; ok {
		return u
	}
	return LevelNone
}

// AtLeast reports whether level grants at least min.
func AtLeast(level, minLevel string) bool {
	return levelRank[NormalizeLevel(level)] >= levelRank[minLevel]
}

// SellerUser is the signed-in member.
type SellerUser struct {
	ID        string `json:"id"`
	FirstName string `json:"firstName"`
	LastName  string `json:"lastName"`
	Email     string `json:"email"`
	Initials  string `json:"initials"`
}

// OrgSummary is one organisation the member belongs to.
type OrgSummary struct {
	ID        string `json:"id"`
	Kind      string `json:"kind"`
	Name      string `json:"name"`
	Status    string `json:"status"`
	Approved  bool   `json:"approved"`
	RoleKey   string `json:"roleKey"`
	RoleLabel string `json:"roleLabel"`
}

// NavModule is one sidebar entry.
type NavModule struct {
	Key   string `json:"key"`
	Label string `json:"label"`
	Level string `json:"level"`
	Badge *int   `json:"badge"`
}

// NavGroup is a collapsible sidebar group.
type NavGroup struct {
	Key     string      `json:"key"`
	Label   string      `json:"label"`
	Modules []NavModule `json:"modules"`
}

// SellerSession is GET /v1/seller/session.
type SellerSession struct {
	User       SellerUser   `json:"user"`
	Orgs       []OrgSummary `json:"orgs"`
	ActiveOrg  *OrgSummary  `json:"activeOrg"`
	Portal     *string      `json:"portal"`
	Approved   bool         `json:"approved"`
	NavGroups  []NavGroup   `json:"navGroups"`
	Landing    string       `json:"landing"`
	AccessNote string       `json:"accessNote"`
}

// PortalOf returns the portal for an organisation kind.
func PortalOf(kind string) string {
	if kind == OrgKindProvider {
		return PortalProvider
	}
	return PortalSeller
}

// ---------------------------------------------------------------- module access matrix

// ModuleDef maps an identity module key (sOrders, pCalendar…) onto a Seller Center module.
type ModuleDef struct {
	Identity string // identity's module key (access_module.key)
	Key      string // contract ModuleKey
	Label    string
	Group    string
	Detail   string // what the module covers, for capability cards ("orders, shipping labels and refunds")
}

// Navigation groups in sidebar order.
var navGroupLabels = map[string]string{
	"overview": "Overview", "commerce": "Commerce", "bookings": "Bookings", "setup": "Set up", "growth": "Growth",
	"finance": "Finance", "money": "Money", "trust": "Trust & compliance", "settings": "Settings",
}

var navGroupOrder = []string{"overview", "commerce", "bookings", "setup", "growth", "finance", "money", "trust", "settings"}

// SellerModules is the Seller Center module catalogue (identity portal SELLER), in sidebar order.
var SellerModules = []ModuleDef{
	{"sHome", "home", "Home", "overview", "the store overview"},
	{"sOrders", "orders", "Orders", "commerce", "orders, shipping labels and refunds"},
	{"sListings", "listings", "Listings", "commerce", "product listings"},
	{"sInventory", "inventory", "Inventory & FBA", "commerce", "stock levels"},
	{"sStorefront", "storefront", "Storefront & theme", "growth", "the storefront design"},
	{"sPayouts", "payouts", "Payouts", "finance", "payouts and balances"},
	{"sPayouts", "fees", "Fees & commission", "finance", "commission and fees"},
	{"sBilling", "billing", "Plan & billing", "finance", "the plan and invoices"},
	{"sCompliance", "account-health", "Account health", "trust", "Stripe verification requirements"},
	{"sTeam", "team", "Team & roles", "settings", "team members and their roles"},
}

// ProviderModules is the Provider Hub module catalogue (identity portal PROVIDER), in sidebar order. Earnings
// and fees share the payouts access level (fees do in the Seller Center too).
var ProviderModules = []ModuleDef{
	{"pHome", "home", "Home", "overview", "today’s appointments and alerts"},
	{"pCalendar", "calendar", "Calendar", "bookings", "the appointment calendar — block time, reschedule, cancel"},
	{"pBookings", "requests", "Requests", "bookings", "booking requests — accept, decline, mark no-show"},
	{"pAvail", "hours", "Working hours", "setup", "working hours, breaks and time off"},
	{"pServices", "services", "Services & prices", "setup", "services, prices and deposits"},
	{"pPolicy", "policy", "Cancellation policy", "setup", "the cancellation and no-show policy"},
	{"pPage", "public-page", "Public page", "setup", "the public page design, gallery and social links"},
	{"pPayouts", "earnings", "Earnings", "money", "earnings per booking"},
	{"pPayouts", "payouts", "Payouts", "money", "payouts and balances"},
	{"pPayouts", "fees", "Fees & commission", "money", "commission and fees"},
	{"pBilling", "billing", "Plan & billing", "money", "the Provider Hub plan and invoices"},
	{"pCompliance", "account-health", "Account health", "trust", "Stripe verification requirements"},
	{"pTeam", "team", "Team & roles", "settings", "team members and their roles"},
}

// ModulesFor returns the module catalogue of an organisation kind.
func ModulesFor(kind string) []ModuleDef {
	if kind == OrgKindProvider {
		return ProviderModules
	}
	return SellerModules
}

// RoleDef is an organisation role with its card texts.
type RoleDef struct {
	Key     string
	Label   string
	Summary string
	Note    string
}

// SellerRoles and ProviderRoles mirror identity's seeded organisation roles (V2__seed_rbac.sql).
var SellerRoles = []RoleDef{
	{"seller.owner", "Owner", "Everything in Seller Center, including billing and team", "The owner can do everything, including transferring ownership and closing the store."},
	{"seller.admin", "Admin", "Everything except plan and billing changes", "Admins run the store day to day but cannot change the plan or payout bank account."},
	{"seller.catalog", "Catalog manager", "Listings, inventory and storefront content", ""},
	{"seller.fulfilment", "Fulfilment", "Orders, shipping and inventory", "Fulfilment can buy labels and confirm, pack and mark orders ready — never refunds or payouts."},
	{"seller.finance", "Finance", "Payouts, billing and compliance", "Finance can see money but cannot change listings or orders."},
	{"seller.ads", "Marketing", "Advertising and storefront", ""},
	{"seller.support", "Support agent", "Customer messages, orders and returns", ""},
}

// ProviderRoles are the Provider Hub roles.
var ProviderRoles = []RoleDef{
	{"provider.owner", "Owner", "Everything in Provider Hub, including billing and team", "The owner can do everything, including transferring ownership and closing the business."},
	{"provider.manager", "Manager", "Calendar, services, page and team", "Managers run day-to-day operations but cannot change the plan, payout bank account or the owner."},
	{"provider.practitioner", "Practitioner", "Own calendar and working hours", "Practitioners only see and change their own calendar, hours and bookings — never other staff’s."},
	{"provider.finance", "Finance", "Payouts, billing and compliance", "Finance can see money but cannot change bookings, services or the page."},
}

// RolesFor returns the roles of an organisation kind.
func RolesFor(kind string) []RoleDef {
	if kind == OrgKindProvider {
		return ProviderRoles
	}
	return SellerRoles
}

// RoleLabel names a role key ("seller.fulfilment" → "Fulfilment").
func RoleLabel(key string) string {
	for _, r := range append(slices.Clone(SellerRoles), ProviderRoles...) {
		if r.Key == key {
			return r.Label
		}
	}
	if _, after, ok := strings.Cut(key, "."); ok && after != "" {
		return strings.ToUpper(after[:1]) + after[1:]
	}
	return key
}

// ModuleMatrix maps identity module key → role key → level.
type ModuleMatrix map[string]map[string]string

// Level returns the role's level on an identity module.
func (m ModuleMatrix) Level(module, role string) string {
	if m == nil {
		return LevelNone
	}
	return NormalizeLevel(m[module][role])
}

func matrixFrom(rows map[string]string, roles []string) ModuleMatrix {
	m := ModuleMatrix{}
	for module, levels := range rows {
		cells := strings.Split(levels, ",")
		m[module] = map[string]string{}
		for i, r := range roles {
			if i < len(cells) {
				m[module][r] = NormalizeLevel(cells[i])
			}
		}
	}
	return m
}

// DefaultSellerMatrix is identity's seeded SELLER matrix (owner, admin, catalog, fulfilment, finance, ads, support).
// The BFF uses it when the member may not read identity's module matrix.
var DefaultSellerMatrix = matrixFrom(map[string]string{
	"sHome":       "FULL,FULL,VIEW,VIEW,VIEW,VIEW,VIEW",
	"sOrders":     "FULL,FULL,VIEW,FULL,VIEW,NONE,EDIT",
	"sListings":   "FULL,FULL,FULL,VIEW,NONE,VIEW,VIEW",
	"sInventory":  "FULL,FULL,EDIT,FULL,NONE,NONE,NONE",
	"sAds":        "FULL,FULL,NONE,NONE,VIEW,FULL,NONE",
	"sStorefront": "FULL,FULL,EDIT,NONE,NONE,FULL,NONE",
	"sPayouts":    "FULL,VIEW,NONE,NONE,FULL,NONE,NONE",
	"sBilling":    "FULL,VIEW,NONE,NONE,FULL,NONE,NONE",
	"sTeam":       "FULL,FULL,NONE,NONE,NONE,NONE,NONE",
	"sCompliance": "FULL,FULL,NONE,NONE,EDIT,NONE,NONE",
}, []string{"seller.owner", "seller.admin", "seller.catalog", "seller.fulfilment", "seller.finance", "seller.ads", "seller.support"})

// DefaultProviderMatrix is identity's seeded PROVIDER matrix (owner, manager, practitioner, finance).
var DefaultProviderMatrix = matrixFrom(map[string]string{
	"pHome":       "FULL,FULL,VIEW,VIEW",
	"pCalendar":   "FULL,FULL,EDIT,VIEW",
	"pBookings":   "FULL,FULL,EDIT,VIEW",
	"pAvail":      "FULL,FULL,EDIT,NONE",
	"pServices":   "FULL,FULL,VIEW,VIEW",
	"pPolicy":     "FULL,EDIT,VIEW,VIEW",
	"pPage":       "FULL,FULL,NONE,NONE",
	"pReviews":    "FULL,FULL,VIEW,VIEW",
	"pPayouts":    "FULL,VIEW,NONE,FULL",
	"pBilling":    "FULL,VIEW,NONE,FULL",
	"pTeam":       "FULL,EDIT,NONE,NONE",
	"pCompliance": "FULL,EDIT,NONE,EDIT",
}, []string{"provider.owner", "provider.manager", "provider.practitioner", "provider.finance"})

// DefaultMatrix returns the seeded matrix of an organisation kind.
func DefaultMatrix(kind string) ModuleMatrix {
	if kind == OrgKindProvider {
		return DefaultProviderMatrix
	}
	return DefaultSellerMatrix
}

// Access is the member's effective access in the active organisation.
type Access struct {
	Kind   string
	Role   string
	Matrix ModuleMatrix
}

// Level returns the access level for a contract module key (NONE when the role has no access).
func (a Access) Level(moduleKey string) string {
	for _, d := range ModulesFor(a.Kind) {
		if d.Key == moduleKey {
			return a.Matrix.Level(d.Identity, a.Role)
		}
	}
	return LevelNone
}

// Allows reports whether the role has at least minLevel on the module.
func (a Access) Allows(moduleKey, minLevel string) bool { return AtLeast(a.Level(moduleKey), minLevel) }

// AccessDenied is the 403 for a role without enough access to a module.
func AccessDenied(role, minLevel string) *Problem {
	verb := "view"
	if minLevel != LevelView {
		verb = "change"
	}
	return NewProblem(403, CodeForbidden, "Forbidden", fmt.Sprintf("Your role (%s) can't %s this.", RoleLabel(role), verb))
}

// Navigation builds the sidebar for the access. Badges are keyed by module key.
func Navigation(a Access, approved bool, badges map[string]int) []NavGroup {
	if !approved {
		return []NavGroup{{Key: "onboarding", Label: "Onboarding", Modules: []NavModule{{Key: "onboarding", Label: "Onboarding", Level: LevelEdit}}}}
	}
	groups := map[string][]NavModule{}
	for _, d := range ModulesFor(a.Kind) {
		lv := a.Matrix.Level(d.Identity, a.Role)
		if lv == LevelNone {
			continue
		}
		m := NavModule{Key: d.Key, Label: d.Label, Level: lv}
		if n, ok := badges[d.Key]; ok && n > 0 {
			m.Badge = &n
		}
		groups[d.Group] = append(groups[d.Group], m)
	}
	out := []NavGroup{}
	for _, g := range navGroupOrder {
		if len(groups[g]) > 0 {
			out = append(out, NavGroup{Key: g, Label: navGroupLabels[g], Modules: groups[g]})
		}
	}
	return out
}

// Landing is the first module of the navigation ("onboarding" before approval).
func Landing(groups []NavGroup) string {
	for _, g := range groups {
		for _, m := range g.Modules {
			return m.Key
		}
	}
	return "home"
}

// RoleCard says what a role can change, only view, and can't access.
type RoleCard struct {
	Key     string   `json:"key"`
	Label   string   `json:"label"`
	Summary string   `json:"summary"`
	Note    *string  `json:"note"`
	Can     []string `json:"can"`
	View    []string `json:"view"`
	Cannot  []string `json:"cannot"`
}

// RoleCards builds one capability card per role of the kind from the matrix.
func RoleCards(kind string, m ModuleMatrix) []RoleCard {
	out := []RoleCard{}
	for _, r := range RolesFor(kind) {
		c := RoleCard{Key: r.Key, Label: r.Label, Summary: r.Summary, Note: strPtrOrNil(r.Note), Can: []string{}, View: []string{}, Cannot: []string{}}
		for _, d := range ModulesFor(kind) {
			switch lv := m.Level(d.Identity, r.Key); {
			case AtLeast(lv, LevelEdit):
				c.Can = append(c.Can, d.Detail)
			case lv == LevelView:
				c.View = append(c.View, d.Detail)
			default:
				c.Cannot = append(c.Cannot, d.Detail)
			}
		}
		out = append(out, c)
	}
	return out
}

func strPtrOrNil(s string) *string {
	if s == "" {
		return nil
	}
	return &s
}

// ---------------------------------------------------------------- onboarding and compliance

// ComplianceItem is one open Stripe requirement.
type ComplianceItem struct {
	Requirement string `json:"requirement"`
	Label       string `json:"label"`
	Bucket      string `json:"bucket"`
	// Component is the Stripe embedded component that collects it: account_management for the payout bank account,
	// account_onboarding for everything else. Seller accounts are Stripe-collected; nothing is answered through us.
	Component string `json:"component"`
}

// Stripe embedded components that collect a requirement.
const (
	ComponentAccountOnboarding = "account_onboarding"
	ComponentAccountManagement = "account_management"
)

// RequirementComponent is the embedded component for a requirement key when the seller service didn't name one.
func RequirementComponent(requirement string) string {
	if strings.HasPrefix(requirement, "external_account") {
		return ComponentAccountManagement
	}
	return ComponentAccountOnboarding
}

// ComplianceBank is the payout bank account Stripe holds.
type ComplianceBank struct {
	BankName *string `json:"bankName"`
	Last4    *string `json:"last4"`
	Status   *string `json:"status"`
}

// ComplianceSummary drives the account-health banner.
type ComplianceSummary struct {
	Status         string           `json:"status"`
	ShowBanner     bool             `json:"showBanner"`
	Tone           string           `json:"tone"`
	Title          string           `json:"title"`
	Message        string           `json:"message"`
	OpenItems      int              `json:"openItems"`
	Deadline       *time.Time       `json:"deadline"`
	PayoutsPaused  bool             `json:"payoutsPaused"`
	ChargesEnabled bool             `json:"chargesEnabled"`
	PayoutsEnabled bool             `json:"payoutsEnabled"`
	ListingsHidden bool             `json:"listingsHidden"`
	PayoutBank     *ComplianceBank  `json:"payoutBank"`
	Items          []ComplianceItem `json:"items"`
}

// Compliance statuses mirrored from the seller service.
const (
	ComplianceCompliant      = "COMPLIANT"
	ComplianceActionNeeded   = "ACTION_NEEDED"
	ComplianceRestrictedSoon = "RESTRICTED_SOON"
	ComplianceRestricted     = "RESTRICTED"
	ComplianceVerifying      = "VERIFYING"
)

// requirementLabels names the Stripe requirements the seller service forwards (design: backoffice-config REQ).
var requirementLabels = map[string]string{
	"company.tax_id":                                  "Company tax ID (EIN)",
	"company.verification.document":                   "Business verification document",
	"company.ownership_declaration":                   "Ownership declaration",
	"business_profile.url":                            "Business website or product description",
	"external_account":                                "Payout bank account",
	"individual.id_number":                            "Full SSN",
	"representative.verification.document":            "Representative photo ID",
	"representative.verification.additional_document": "Proof of address",
	"representative.dob":                              "Representative date of birth",
	"representative.relationship.title":               "Representative job title",
	"tos_acceptance":                                  "Stripe Connected Account Agreement",
}

// RequirementLabel names a Stripe requirement for people.
func RequirementLabel(req string) string {
	if l, ok := requirementLabels[req]; ok {
		return l
	}
	l := strings.NewReplacer(".", " · ", "_", " ").Replace(req)
	if l == "" {
		return req
	}
	return strings.ToUpper(l[:1]) + l[1:]
}

// ComplianceBanner fills the banner fields (copy from the design) from status, open items and deadline.
func ComplianceBanner(s *ComplianceSummary, loc *time.Location) {
	switch s.Status {
	case ComplianceCompliant, "":
		s.Status, s.ShowBanner, s.Tone, s.Title, s.Message = ComplianceCompliant, false, "none", "Account in good standing.", "Nothing to do."
		return
	case ComplianceVerifying:
		s.ShowBanner, s.Tone, s.Title, s.Message = true, "info", "We’re verifying your details.", "Nothing to do — we’ll let you know."
		return
	case ComplianceRestricted:
		s.Tone, s.Title = "danger", "Payouts paused — verification needed."
	default:
		s.Tone, s.Title = "warning", "We need a little more information."
	}
	s.ShowBanner = true
	due := ""
	if s.Deadline != nil {
		if loc == nil {
			loc = time.UTC
		}
		due = " due " + s.Deadline.In(loc).Format("Jan 2")
	}
	s.Message = fmt.Sprintf("%d item(s)%s. Open Account health to resolve.", s.OpenItems, due)
}
