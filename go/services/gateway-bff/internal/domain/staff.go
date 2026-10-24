package domain

import (
	"fmt"
	"net/http"
	"regexp"
	"slices"
	"strings"
	"time"
)

// Staff console (contracts/openapi/gateway-bff-staff.yaml): shell, RBAC, server-driven tables and sheets, exports and
// the support console. Module keys follow identity's PLATFORM module matrix; "support", "exports" and "notifications"
// are derived.

// Staff problem codes.
const (
	CodeNotStaff       = "NOT_STAFF"
	CodeRefundLimit    = "REFUND_LIMIT"
	CodeExportNotReady = "EXPORT_NOT_READY"
	CodeExportExpired  = "EXPORT_EXPIRED"
)

// PrincipalStaff is identity's principal type for AfroTransact employees (token claim pt=STAFF).
const PrincipalStaff = "STAFF"

// NotStaff is the 403 for signed-in customers and seller members.
func NotStaff() *Problem {
	return NewProblem(http.StatusForbidden, CodeNotStaff, "Staff only", "The staff console is for AfroTransact staff accounts.")
}

// StaffModule is one sidebar entry: console key, identity matrix key, label and the line under the page title.
type StaffModule struct {
	Key, Identity, Label, Description string
}

// StaffNavGroupDef is one sidebar group.
type StaffNavGroupDef struct {
	Key, Label string
	Modules    []StaffModule
}

// StaffNav is the grouped sidebar (Overview, Commerce, Partners, Finance, Growth, Trust & compliance, Settings).
var StaffNav = []StaffNavGroupDef{
	{"overview", "Overview", []StaffModule{
		{"dash", "dash", "Dashboard", "Marketplace health for the USA market today."},
		{"exports", "dash", "Exports", "Large exports build on the server; download links last 24 hours."},
	}},
	{"commerce", "Commerce", []StaffModule{
		{"orders", "orders", "Orders & shipments", "All marketplace and AfroTransact Retail orders."},
		{"catalog", "catalog", "Listing moderation", "AI-flagged listings awaiting a human decision."},
		{"inventory", "inventory", "Inventory", "AfroTransact-owned and FBA stock by fulfilment centre, lot by lot."},
		{"retail", "retail", "AfroTransact Retail (1P)", "Purchase orders for AfroTransact-owned and AfroTransact Brand inventory."},
		{"dispatch", "dispatch", "Delivery dispatch", "AfroTransact Delivery routes, drivers and internal delivery."},
		{"preorders", "preorders", "Pre-orders", "Open pre-order windows by city with opening, closing and delivery dates. Upload advertorials, watch every pre-order live, export and add late orders."},
	}},
	{"partners", "Partners", []StaffModule{
		{"orgs", "orgs", "Sellers & providers", "Every seller and service-provider organisation on the marketplace, plus Stripe Connect compliance."},
		{"apps", "apps", "Applications", "Seller and service-provider onboarding queue. Every application goes through two people: a reviewer, then a different approver."},
		{"support", "", "Customer support", "Lola hand-offs, customer lookup, refunds within your limit, notifications and shipments."},
		{"rbac", "rbac", "Users & roles", "Who can do what in every back office. Role changes are audited."},
	}},
	{"finance", "Finance", []StaffModule{
		{"payouts", "payouts", "Payouts & ledger", "Weekly Stripe Connect payouts. Holding or releasing money needs Approve."},
		{"fees", "fees", "Fees, commissions & tax", "Fee schedule versions and marketplace-facilitator tax rates. Changes apply to new orders only."},
		{"plans", "", "Plans & billing", "Seller and provider plans, trials, rewards and billing adjustments. AfroTransact owns them; Stripe executes the charges."},
	}},
	{"growth", "Growth", []StaffModule{
		{"marketing", "marketing", "Marketing", "Announcements, placements & ads, and platform checkout coupons."},
	}},
	{"trust", "Trust & compliance", []StaffModule{
		{"disputes", "disputes", "Disputes & returns", "A-to-z claims and returns with SLAs."},
		{"audit", "audit", "Audit log", "Immutable, hash-chained record of every privileged action."},
	}},
	{"settings", "Settings", []StaffModule{
		{"markets", "markets", "Markets & locations", "Turn AfroTransact on or off by country and by state."},
		{"siteCfg", "siteCfg", "Site content & legal", "Contacts, social links and legal document versions, published without a deployment."},
		{"dev", "dev", "Developers", "Identity providers: which social sign-in buttons appear."},
		{IdentityModule, "", "Identity & access", "Developer tools for the identity service: OAuth clients, sessions and tokens, signing keys, the token inspector and sign-in settings."},
		{"notifications", "", "Notifications", "Email, SMS, push and WhatsApp templates: live preview with production-shaped sample data, version history and new versions."},
	}},
}

// StaffAccess is a staff member's effective access: platform roles, effective permissions and the module matrix.
type StaffAccess struct {
	Roles       []string
	Permissions []string
	Matrix      ModuleMatrix
}

// Can reports whether the staff member holds a permission.
func (a StaffAccess) Can(permission string) bool { return slices.Contains(a.Permissions, permission) }

// CanAny reports whether the staff member holds one of the permissions.
func (a StaffAccess) CanAny(permissions ...string) bool {
	return slices.ContainsFunc(permissions, a.Can)
}

// Level is the best level over the staff member's roles on a console module (NONE without access).
func (a StaffAccess) Level(module string) string {
	switch module {
	case "support":
		switch {
		case a.Can("support:respond"):
			return LevelEdit
		case a.Can("users:read"):
			return LevelView
		default:
			return LevelNone
		}
	case "notifications":
		// Derived from the notifications service's own permissions (identity's matrix has no notifications column).
		switch {
		case a.Can(PermNotificationsTemplatesWrite):
			return LevelEdit
		case a.Can(PermNotificationsRead):
			return LevelView
		default:
			return LevelNone
		}
	case PlansModule:
		// Derived from the seller service's own permissions (identity's matrix has no Plans & billing column).
		switch {
		case a.CanAny(PermPlansManage, PermBillingAdjust):
			return LevelEdit
		case a.CanAny(PermSellerBilling, "billing:usage"):
			return LevelView
		default:
			return LevelNone
		}
	case IdentityModule:
		// Derived from identity's own permission: Super Admin by default, not part of the module matrix (S291).
		if a.Can(PermIdentityAdmin) {
			return LevelEdit
		}
		return LevelNone
	case "exports":
		module = "dash"
	}
	matrix := a.Matrix
	if (module == PreorderModule || module == "orgs") && len(matrix[module]) == 0 {
		// identity's matrix predates the Pre-orders / Sellers & providers columns: use the seeded levels.
		matrix = DefaultPlatformMatrix
	}
	best := LevelNone
	for _, r := range a.Roles {
		if lv := matrix.Level(module, r); levelRank[lv] > levelRank[best] {
			best = lv
		}
	}
	return best
}

// Allows reports whether the staff member has at least minLevel on a console module.
func (a StaffAccess) Allows(module, minLevel string) bool {
	return a.Level(module) != LevelNone && AtLeast(a.Level(module), minLevel)
}

// StaffUser is the signed-in staff member.
type StaffUser struct {
	ID        string `json:"id"`
	FirstName string `json:"firstName"`
	LastName  string `json:"lastName"`
	Email     string `json:"email"`
	Initials  string `json:"initials"`
}

// StaffRole is a platform role with its label.
type StaffRole struct {
	Key   string `json:"key"`
	Label string `json:"label"`
}

// StaffNavModule is one sidebar entry the role may open.
type StaffNavModule struct {
	Key         string `json:"key"`
	Label       string `json:"label"`
	Description string `json:"description"`
	Level       string `json:"level"`
	Badge       *int   `json:"badge"`
}

// StaffNavGroup is one sidebar group with the modules the role may open.
type StaffNavGroup struct {
	Key     string           `json:"key"`
	Label   string           `json:"label"`
	Modules []StaffNavModule `json:"modules"`
}

// StaffSession is GET /v1/staff/session.
type StaffSession struct {
	User             StaffUser       `json:"user"`
	Roles            []StaffRole     `json:"roles"`
	RoleLabel        string          `json:"roleLabel"`
	Permissions      []string        `json:"permissions"`
	NavGroups        []StaffNavGroup `json:"navGroups"`
	Landing          string          `json:"landing"`
	AccessNote       string          `json:"accessNote"`
	RefundLimitMinor *int64          `json:"refundLimitMinor"`
}

// StaffNavigation filters the sidebar to the modules the access opens (groups without entries disappear).
func StaffNavigation(a StaffAccess, badges map[string]int) []StaffNavGroup {
	out := []StaffNavGroup{}
	for _, g := range StaffNav {
		grp := StaffNavGroup{Key: g.Key, Label: g.Label, Modules: []StaffNavModule{}}
		for _, m := range g.Modules {
			lv := a.Level(m.Key)
			if lv == LevelNone {
				continue
			}
			nm := StaffNavModule{Key: m.Key, Label: m.Label, Description: m.Description, Level: lv}
			if n, ok := badges[m.Key]; ok && n > 0 {
				nm.Badge = &n
			}
			grp.Modules = append(grp.Modules, nm)
		}
		if len(grp.Modules) > 0 {
			out = append(out, grp)
		}
	}
	return out
}

var platformRoleLabels = map[string]string{
	"platform.super_admin": "Super Admin", "platform.operations_manager": "Operations Manager", "platform.trust_safety": "Trust & Safety",
	"platform.finance": "Finance", "platform.customer_support": "Customer Support", "platform.marketing": "Marketing",
	"platform.catalog_manager": "Catalog Manager", "platform.retail_buyer": "Retail Buyer (1P)", "platform.fulfilment_ops": "Fulfilment Ops",
	"platform.inventory_manager": "Inventory Manager", "platform.dispatcher": "Dispatcher", "platform.product_owner": "Product Owner",
	"platform.developer": "Developer", "platform.auditor": "Auditor (read-only)", "platform.driver": "Driver",
}

// PlatformRoleLabel names a platform role ("platform.customer_support" → "Customer Support").
func PlatformRoleLabel(key string) string {
	if l, ok := platformRoleLabels[key]; ok {
		return l
	}
	return RoleLabel(key)
}

// PlatformRoles keeps the platform.* roles of a token's roles.
func PlatformRoles(roles []string) []string {
	out := []string{}
	for _, r := range roles {
		if strings.HasPrefix(r, "platform.") {
			out = append(out, r)
		}
	}
	return out
}

// refundLimits is the refund ceiling per platform role in minor units (-1 = no limit). Roles not listed may not refund.
var refundLimits = map[string]int64{
	"platform.super_admin": -1, "platform.finance": -1, "platform.operations_manager": 100_000,
	"platform.trust_safety": 50_000, "platform.customer_support": 25_000,
}

// RefundLimit is the largest refund the roles allow: nil = no limit, 0 = may not refund.
func RefundLimit(roles []string) *int64 {
	var limit int64
	for _, r := range roles {
		l, ok := refundLimits[r]
		if !ok {
			continue
		}
		if l < 0 {
			return nil
		}
		limit = max(limit, l)
	}
	return &limit
}

// RefundLimitProblem is the 403 for a refund above the role's limit.
func RefundLimitProblem(limit int64) *Problem {
	return NewProblem(http.StatusForbidden, CodeRefundLimit, "Above your refund limit",
		fmt.Sprintf("Your roles can refund up to $%.2f. Ask Finance for larger refunds.", float64(limit)/100))
}

// ---------------------------------------------------------------- tables and sheets

// ResourceKeys are the tables of the console (contract ResourceKey).
var ResourceKeys = []string{"users", "roles", "applications", "organisations", "connect-accounts", "orders", "products", "moderation", "claims", "returns", "payouts",
	"inventory", "purchase-orders", "routes", "support-cases", "audit", "markets", "site-settings", "legal-documents", "help-articles", "privacy-requests", "announcements",
	"placements", "identity-providers", "fee-schedules", "tax-rates", "tax-codes", "tax-rules", "tax-review", "dispatch-shipments", "sos-alerts", "vehicle-defects", "driver-events", "pickup-locations", "coupons",
	"reconciliation", "reconciliation-runs", "ledger-adjustments", "stripe-lookup", "payment-settings", "tax-settings", "maintenance", "tax-filings", "tax-nexus",
	"oauth-clients", "identity-sessions", "signing-keys", "auth-settings"}

// Column is one table column; Width is its share of the table in percent.
type Column struct {
	Key   string `json:"key"`
	Label string `json:"label"`
	Kind  string `json:"kind"`
	Width int    `json:"width"`
	// Sortable columns accept sort=<key>,asc|desc (every column of a server-driven table).
	Sortable bool `json:"sortable,omitempty"`
}

// Facet is one status filter chip.
type Facet struct {
	Value string `json:"value"`
	Label string `json:"label"`
}

// Row is one table row; cell values are strings, numbers or null.
type Row struct {
	ID    string         `json:"id"`
	Cells map[string]any `json:"cells"`
	Tone  *string        `json:"tone"`
}

// FieldOption is one choice of a select field.
type FieldOption struct {
	Value string `json:"value"`
	Label string `json:"label"`
}

// FormField is one input of a create, edit or action form.
type FormField struct {
	Key         string        `json:"key"`
	Label       string        `json:"label"`
	Type        string        `json:"type"`
	Required    bool          `json:"required"`
	Options     []FieldOption `json:"options,omitempty"`
	Min         *float64      `json:"min,omitempty"`
	Max         *float64      `json:"max,omitempty"`
	MaxLength   int           `json:"maxLength,omitempty"`
	Pattern     string        `json:"pattern,omitempty"`
	Help        string        `json:"help,omitempty"`
	Placeholder string        `json:"placeholder,omitempty"`
	Value       any           `json:"value,omitempty"`
}

// Form is a create, edit or action form.
type Form struct {
	Title       string `json:"title"`
	SubmitLabel string `json:"submitLabel"`
	// Intro says in a sentence or two what submitting does (shown above the fields).
	Intro  string      `json:"intro,omitempty"`
	Fields []FormField `json:"fields"`
	// ChangeKeys are the fields whose change makes the form worth saving: when set, Save stays disabled until one of
	// them differs from its initial value, and the server refuses a save in which none does.
	ChangeKeys []string `json:"changeKeys,omitempty"`
}

// ResourcePage is GET /v1/staff/resources/{resource}.
type ResourcePage struct {
	Resource          string   `json:"resource"`
	Title             string   `json:"title"`
	Columns           []Column `json:"columns"`
	Rows              []Row    `json:"rows"`
	Page              int      `json:"page"`
	Size              int      `json:"size"`
	Total             *int64   `json:"total"`
	NextCursor        *string  `json:"nextCursor"`
	HasNext           bool     `json:"hasNext"`
	Facets            []Facet  `json:"facets"`
	Searchable        bool     `json:"searchable"`
	SearchPlaceholder string   `json:"searchPlaceholder,omitempty"`
	CanCreate         bool     `json:"canCreate"`
	CreateForm        *Form    `json:"createForm"`
	Exportable        bool     `json:"exportable"`
	// Sort is the applied sort (`<column>,asc|desc`).
	Sort []string `json:"sort"`
	// Partial is true when the search or sort ran over the first TableScanMaxRows rows of a bigger table.
	Partial bool `json:"partial"`
}

// DetailField is one label/value on a sheet.
type DetailField struct {
	Label string `json:"label"`
	Value string `json:"value"`
	Kind  string `json:"kind"`
	Href  string `json:"href,omitempty"`
}

// DetailSection groups sheet fields.
type DetailSection struct {
	Title  string        `json:"title"`
	Fields []DetailField `json:"fields"`
}

// DetailTable is a nested list on a sheet (shipments, PO lines, route stops…).
type DetailTable struct {
	Title   string   `json:"title"`
	Columns []Column `json:"columns"`
	Rows    []Row    `json:"rows"`
}

// ActivityEntry is one line of a record's history.
type ActivityEntry struct {
	At    time.Time `json:"at"`
	Actor string    `json:"actor"`
	Text  string    `json:"text"`
}

// Note is an internal note on a record.
type Note struct {
	ID     string    `json:"id"`
	At     time.Time `json:"at"`
	Author string    `json:"author"`
	Text   string    `json:"text"`
}

// RecordAction is one action of a sheet with whether the staff member may run it now.
type RecordAction struct {
	Key        string  `json:"key"`
	Label      string  `json:"label"`
	Tone       string  `json:"tone"`
	Allowed    bool    `json:"allowed"`
	Reason     *string `json:"reason"`
	Permission string  `json:"permission"`
	Confirm    *string `json:"confirm"`
	Form       *Form   `json:"form"`
}

// RecordLink points at another console page (customer 360, the order of a claim…).
type RecordLink struct {
	Label string `json:"label"`
	Href  string `json:"href"`
	// External opens the link in a new tab (an uploaded document the reviewer reads).
	External bool `json:"external,omitempty"`
}

// RecordDetail is the detail sheet of one record.
type RecordDetail struct {
	Resource     string          `json:"resource"`
	ID           string          `json:"id"`
	Title        string          `json:"title"`
	Subtitle     string          `json:"subtitle"`
	Status       *string         `json:"status"`
	Tone         *string         `json:"tone"`
	Sections     []DetailSection `json:"sections"`
	Tables       []DetailTable   `json:"tables"`
	Activity     []ActivityEntry `json:"activity"`
	Notes        []Note          `json:"notes"`
	NotesEnabled bool            `json:"notesEnabled"`
	Actions      []RecordAction  `json:"actions"`
	EditForm     *Form           `json:"editForm"`
	Links        []RecordLink    `json:"links"`
	Version      *string         `json:"version"`
	// Removed is set when an action deleted the record; the console closes the sheet and refreshes the table.
	Removed *bool `json:"removed,omitempty"`
	// Reveal carries a secret that is shown once (a new OAuth client secret); it is in this answer only and nowhere else.
	Reveal *OneTimeSecret `json:"reveal,omitempty"`
}

var statusTones = map[string]string{
	"PENDING_REVIEW": "brand", "FLAGGED": "bad", "AWAITING_FINAL_APPROVAL": "warn", "INFO_REQUESTED": "warn", "APPROVED": "good", "REJECTED": "bad",
	"LIVE": "good", "IN_REVIEW": "brand", "PAUSED": "info", "TAKEN_DOWN": "bad", "PENDING": "warn", "ESCALATED": "bad", "CLOSED": "info",
	"UNDER_REVIEW": "brand", "AWAITING_SELLER": "warn", "RESOLVED_REFUND": "good", "RESOLVED_DENIED": "info", "WITHDRAWN": "info",
	"REQUESTED": "brand", "ON_HOLD": "bad", "HELD": "bad", "PAID": "good", "FAILED": "bad", "SELLABLE": "good", "QUARANTINED": "bad", "DAMAGED": "bad",
	"EXPIRED": "info", "DRAFT": "info", "PENDING_APPROVAL": "warn", "SENT": "info", "CONFIRMED": "info", "RECEIVED": "good", "CANCELLED": "bad",
	"PLANNED": "info", "ASSIGNED": "brand", "IN_PROGRESS": "brand", "COMPLETED": "good", "OPEN": "warn", "RESOLVED": "good",
	"COMING_SOON": "brand", "SCHEDULED": "brand", "ENDED": "info", "ENABLED": "good", "DISABLED": "bad", "ACTIVE": "good", "IN_TRANSIT": "info",
	"REFUNDED": "good", "DELIVERED": "good", "PLACED": "brand", "DELIVERY_FAILED": "bad", "OUT_FOR_DELIVERY": "info", "PACKED": "info",
	"STATE": "info", "LOCAL": "brand", "SUBMITTED": "brand",
	"ALERT": "bad", "BLOCKED": "bad", "AWAITING_ROUTE": "warn", "APPLIED": "good", "DUPLICATE": "info",
}

// StatusTone maps a status onto a chip tone (nil for no status).
func StatusTone(status string) *string {
	if status == "" {
		return nil
	}
	t, ok := statusTones[strings.ToUpper(status)]
	if !ok {
		t = "info"
	}
	return &t
}

var camel = regexp.MustCompile(`([a-z0-9])([A-Z])`)

// Humanize turns keys and enum values into labels ("AWAITING_FINAL_APPROVAL" → "Awaiting final approval",
// "amountMinor" → "Amount").
func Humanize(s string) string {
	s = strings.TrimSuffix(s, "Minor")
	s = camel.ReplaceAllString(s, "$1 $2")
	s = strings.ToLower(strings.TrimSpace(strings.NewReplacer("_", " ", "-", " ", ".", " ").Replace(s)))
	if s == "" {
		return s
	}
	return strings.ToUpper(s[:1]) + s[1:]
}

// StaffFacets builds status chips from enum values.
func StaffFacets(values ...string) []Facet {
	out := make([]Facet, 0, len(values))
	for _, v := range values {
		out = append(out, Facet{Value: v, Label: Humanize(v)})
	}
	return out
}

// StaffOptions builds select options from enum values.
func StaffOptions(values ...string) []FieldOption {
	out := make([]FieldOption, 0, len(values))
	for _, v := range values {
		out = append(out, FieldOption{Value: v, Label: Humanize(v)})
	}
	return out
}

// ValidateForm checks submitted values against a form (required, number range, length, pattern, email, options) and
// returns per-field messages (empty when valid). The browser applies the same rules before submitting.
func ValidateForm(f Form, values map[string]any) map[string]string {
	errs := map[string]string{}
	for _, fd := range f.Fields {
		v, present := values[fd.Key]
		if !present || v == nil || v == "" || isEmptyList(v) {
			if fd.Required && fd.Type != "toggle" {
				errs[fd.Key] = "Required"
			}
			continue
		}
		switch fd.Type {
		case "number", "money":
			n, ok := v.(float64)
			if !ok {
				errs[fd.Key] = "Enter a number"
				continue
			}
			if fd.Min != nil && n < *fd.Min {
				errs[fd.Key] = fmt.Sprintf("Must be at least %v", *fd.Min)
			} else if fd.Max != nil && n > *fd.Max {
				errs[fd.Key] = fmt.Sprintf("Must be at most %v", *fd.Max)
			}
		case "toggle":
			if _, ok := v.(bool); !ok {
				errs[fd.Key] = "Choose yes or no"
			}
		case "multiselect":
			list, ok := v.([]any)
			if !ok {
				errs[fd.Key] = "Choose one or more options"
				continue
			}
			for _, item := range list {
				s, _ := item.(string)
				if !hasOption(fd.Options, s) {
					errs[fd.Key] = "Choose from the options"
				}
			}
		default:
			s, ok := v.(string)
			if !ok {
				errs[fd.Key] = "Enter text"
				continue
			}
			if msg := checkText(fd, s); msg != "" {
				errs[fd.Key] = msg
			}
		}
	}
	return errs
}

var emailRe = regexp.MustCompile(`^[^@\s]+@[^@\s]+\.[^@\s]+$`)

func checkText(fd FormField, s string) string {
	switch {
	case fd.MaxLength > 0 && len([]rune(s)) > fd.MaxLength:
		return fmt.Sprintf("Keep it under %d characters", fd.MaxLength)
	case fd.Pattern != "" && !regexp.MustCompile(fd.Pattern).MatchString(s):
		if fd.Help != "" {
			return fd.Help
		}
		return "Invalid format"
	case fd.Type == "email" && !emailRe.MatchString(s):
		return "Enter a valid email"
	case fd.Type == "select" && len(fd.Options) > 0 && !hasOption(fd.Options, s):
		return "Choose one of the options"
	case fd.Type == "date":
		if _, err := time.Parse(time.DateOnly, s); err != nil {
			return "Enter a date (YYYY-MM-DD)"
		}
	case fd.Type == "datetime":
		if _, err := time.Parse(time.RFC3339, s); err != nil {
			return "Enter a date and time"
		}
	}
	return ""
}

func hasOption(opts []FieldOption, v string) bool {
	return slices.ContainsFunc(opts, func(o FieldOption) bool { return o.Value == v })
}

func isEmptyList(v any) bool {
	l, ok := v.([]any)
	return ok && len(l) == 0
}

// ---------------------------------------------------------------- exports

// Export statuses and limits.
const (
	ExportQueued  = "QUEUED"
	ExportRunning = "RUNNING"
	ExportReady   = "READY"
	ExportFailed  = "FAILED"
	ExportExpired = "EXPIRED"
	// ExportSyncRows is the largest export built while the request waits; bigger ones run in the background.
	ExportSyncRows = 5000
	// ExportMaxRows caps one export.
	ExportMaxRows = 200_000
	// ExportScanMaxRows caps the rows an export reads to search, sort or pick selected rows in the BFF.
	ExportScanMaxRows = 50_000
	// ExportLinkTTL is how long a download link works after the file is ready.
	ExportLinkTTL = 24 * time.Hour
)

// ExportJob is one export (GET /v1/staff/exports/{id}).
type ExportJob struct {
	ID string `json:"id"`
	// Resource is the table exported: a staff resource key, a Seller Center table or a client-side table's key.
	Resource string `json:"resource"`
	// Title is the table's name as people read it ("Lots", "Orders").
	Title      string     `json:"title"`
	Format     string     `json:"format"`
	Status     string     `json:"status"`
	Rows       int        `json:"rows"`
	Async      bool       `json:"async"`
	FileName   string     `json:"fileName"`
	CreatedAt  time.Time  `json:"createdAt"`
	FinishedAt *time.Time `json:"finishedAt"`
	ExpiresAt  *time.Time `json:"expiresAt"`
	// DownloadURL is the console-relative path that redirects to the signed URL.
	DownloadURL *string `json:"downloadUrl"`
	Error       *string `json:"error"`
}

// StoredExportJob is the persisted form of a job: the owner, the stored file reference and the filters stay server-side.
type StoredExportJob struct {
	ExportJob
	Owner string `json:"owner"`
	Ref   string `json:"ref"`
	// Portal is staff (default) or seller; OrgID is the Seller Center organisation the export belongs to.
	Portal string `json:"portal,omitempty"`
	OrgID  string `json:"orgId,omitempty"`
	// Scope describes the rows in the file (search, sort, selection).
	Scope string `json:"scope,omitempty"`
}

// ---------------------------------------------------------------- support console

// CustomerHit is one customer search result.
type CustomerHit struct {
	ID            string `json:"id"`
	Name          string `json:"name"`
	Email         string `json:"email"`
	Enabled       bool   `json:"enabled"`
	PrincipalType string `json:"principalType"`
}

// SupportShipment is a shipment in the customer 360.
type SupportShipment struct {
	ID             string  `json:"id"`
	Label          string  `json:"label"`
	Status         string  `json:"status"`
	SellerName     string  `json:"sellerName"`
	Carrier        *string `json:"carrier"`
	TrackingNumber *string `json:"trackingNumber"`
	Cancellable    bool    `json:"cancellable"`
	TotalMinor     int64   `json:"totalMinor"`
}

// SupportOrder is an order in the customer 360.
type SupportOrder struct {
	ID          string            `json:"id"`
	OrderNumber string            `json:"orderNumber"`
	Status      string            `json:"status"`
	PlacedAt    time.Time         `json:"placedAt"`
	TotalMinor  int64             `json:"totalMinor"`
	Currency    string            `json:"currency"`
	Shipments   []SupportShipment `json:"shipments"`
}

// SupportClaim is an A-to-z claim in the customer 360.
type SupportClaim struct {
	ID          string `json:"id"`
	Reference   string `json:"reference"`
	Type        string `json:"type"`
	Status      string `json:"status"`
	OrderNumber string `json:"orderNumber"`
	AmountMinor int64  `json:"amountMinor"`
	RefundMinor *int64 `json:"refundMinor"`
	Open        bool   `json:"open"`
}

// SupportReturn is a return in the customer 360.
type SupportReturn struct {
	ID            string  `json:"id"`
	Number        string  `json:"number"`
	Status        string  `json:"status"`
	OrderNumber   string  `json:"orderNumber"`
	RefundState   *string `json:"refundState"`
	EstimateMinor int64   `json:"estimateMinor"`
}

// SupportNotification is a notification sent to the customer.
type SupportNotification struct {
	ID          string    `json:"id"`
	Channel     string    `json:"channel"`
	TemplateKey string    `json:"templateKey"`
	Subject     *string   `json:"subject"`
	Status      string    `json:"status"`
	Address     string    `json:"address"`
	CreatedAt   time.Time `json:"createdAt"`
}

// SupportCaseSummary is a support case of the customer.
type SupportCaseSummary struct {
	ID        string    `json:"id"`
	Reference string    `json:"reference"`
	Reason    string    `json:"reason"`
	Summary   string    `json:"summary"`
	Status    string    `json:"status"`
	OpenedAt  time.Time `json:"openedAt"`
}

// SupportBooking is a service booking of the customer.
type SupportBooking struct {
	ID       string    `json:"id"`
	Service  string    `json:"service"`
	Status   string    `json:"status"`
	StartsAt time.Time `json:"startsAt"`
}

// AuditLine is an audit-log entry about the customer.
type AuditLine struct {
	At      time.Time `json:"at"`
	Actor   string    `json:"actor"`
	Action  string    `json:"action"`
	Summary string    `json:"summary"`
}

// SupportCustomer is the account part of the customer 360.
type SupportCustomer struct {
	ID            string   `json:"id"`
	Name          string   `json:"name"`
	Email         string   `json:"email"`
	Enabled       bool     `json:"enabled"`
	EmailVerified bool     `json:"emailVerified"`
	Roles         []string `json:"roles"`
}

// SupportPermissions says which support actions the staff member may run.
type SupportPermissions struct {
	Refund         bool `json:"refund"`
	Resend         bool `json:"resend"`
	CancelShipment bool `json:"cancelShipment"`
}

// Customer360 is GET /v1/staff/support/customers/{id}.
type Customer360 struct {
	Customer         SupportCustomer       `json:"customer"`
	Orders           []SupportOrder        `json:"orders"`
	Bookings         []SupportBooking      `json:"bookings"`
	Claims           []SupportClaim        `json:"claims"`
	Returns          []SupportReturn       `json:"returns"`
	Notifications    []SupportNotification `json:"notifications"`
	Cases            []SupportCaseSummary  `json:"cases"`
	Audit            []AuditLine           `json:"audit"`
	RefundLimitMinor *int64                `json:"refundLimitMinor"`
	Can              SupportPermissions    `json:"can"`
	Unavailable      []string              `json:"unavailable"`
}

// SupportActionResult answers support actions.
type SupportActionResult struct {
	OK        bool    `json:"ok"`
	Message   string  `json:"message"`
	Reference *string `json:"reference"`
	Audited   bool    `json:"audited"`
}

// StaffDashboard is GET /v1/staff/dashboard.
type StaffDashboard struct {
	RangeDays   int         `json:"rangeDays"`
	GeneratedAt time.Time   `json:"generatedAt"`
	Kpis        []Kpi       `json:"kpis"`
	Charts      []ChartSpec `json:"charts"`
}

// FormChanged reports whether the submitted values differ from the form's initial values in a field the form says
// matters (ChangeKeys); a form without ChangeKeys is always worth saving.
func FormChanged(f Form, values map[string]any) bool {
	if len(f.ChangeKeys) == 0 {
		return true
	}
	initial := map[string]any{}
	for _, field := range f.Fields {
		initial[field.Key] = field.Value
	}
	for _, k := range f.ChangeKeys {
		if fmt.Sprint(initial[k]) != fmt.Sprint(values[k]) {
			return true
		}
	}
	return false
}
