package domain

import (
	"encoding/json"
	"net/http"
	"net/url"
	"regexp"
	"slices"
	"strings"
)

// Pre-orders (docs/spikes/preorder-design-implementation.md; design/AfroTransact Pre-Order v2.dc.html). delivery-runs
// owns windows, coverage, payment settings and the waitlist; the BFF resolves the shopper's area, relays the
// answers and gates the staff module.

// Pre-order staff module and permissions (identity V22).
const (
	PreorderModule         = "preorders"
	PermDeliveryRunsRead   = "delivery-runs:read"
	PermPreordersManage    = "preorders:manage"
	PermPreordersExport    = "preorders:export"
	PermPreordersLateAdd   = "preorders:late-add"
	PreorderAttrCookie     = "at_attr"
	PreorderWaitlistSource = "preorder-waitlist"
)

// PreorderCan says what the staff member may do in the Pre-orders module (greys out buttons in the console).
type PreorderCan struct {
	// Manage: open, edit, publish, pause and resume windows, creatives and coverage areas (Edit and above).
	Manage bool `json:"manage"`
	// Settings: the master switch, fallback, placements and payment setup (Full only).
	Settings bool `json:"settings"`
	// Export: CSV exports (preorders:export).
	Export bool `json:"export"`
	// LateAdd: add a late order for a customer (Approve, Edit or Full with preorders:late-add).
	LateAdd bool `json:"lateAdd"`
}

// PreorderPermissions derives the module abilities from the level (managers Full/Edit, support Approve = "Act",
// everyone else View) and the permissions identity granted.
func PreorderPermissions(a StaffAccess) PreorderCan {
	lv := a.Level(PreorderModule)
	return PreorderCan{
		Manage:   AtLeast(lv, LevelEdit) && lv != LevelApprove && a.Can(PermPreordersManage),
		Settings: lv == LevelFull && a.Can(PermPreordersManage),
		Export:   AtLeast(lv, LevelView) && a.Can(PermPreordersExport),
		LateAdd:  AtLeast(lv, LevelEdit) && a.Can(PermPreordersLateAdd),
	}
}

// PreorderSlots are the advertorial creative slots.
var PreorderSlots = []string{"HERO", "PDP", "STORY", "FEED", "EMAIL"}

// PreorderExports are the CSV exports of a window (file name without .csv).
var PreorderExports = []string{"orders", "orders-filtered", "picking-list", "advertorial-report"}

var (
	preorderIDRe = regexp.MustCompile(`^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$`)
	countryRe    = regexp.MustCompile(`^[A-Z]{2}$`)
	regionRe     = regexp.MustCompile(`^[A-Z0-9]{1,3}$`)
	attrSrcRe    = regexp.MustCompile(`^[a-z][a-z0-9_-]{0,31}$`)
	attrCrRe     = regexp.MustCompile(`^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$`)
	attrPoRe     = regexp.MustCompile(`^[A-Za-z0-9][A-Za-z0-9_-]{0,31}$`)
	cityDisallow = "/?#<>\"{}|\\^`"
)

// ValidPreorderID checks a window id or code path segment.
func ValidPreorderID(id string) *Problem {
	if !preorderIDRe.MatchString(id) {
		return NewProblem(http.StatusNotFound, CodeNotFound, "Not found", "No such pre-order.")
	}
	return nil
}

// PreorderPlace is the place a resolution is for, and where it came from ("query", "session" or "default").
type PreorderPlace struct {
	Country string `json:"country"`
	Region  string `json:"region"`
	City    string `json:"city"`
	Source  string `json:"source"`
}

// Key is the coverage key: "US/TX" or "US/TX/Lubbock".
func (p PreorderPlace) Key() string {
	if p.City == "" {
		return p.Country + "/" + p.Region
	}
	return p.Country + "/" + p.Region + "/" + p.City
}

// NormalizePlace upper-cases country and region, trims the city and validates all three; errs maps the query
// parameter names to messages.
func NormalizePlace(country, region, city string) (PreorderPlace, map[string]string) {
	p := PreorderPlace{Country: strings.ToUpper(strings.TrimSpace(country)), Region: strings.ToUpper(strings.TrimSpace(region)),
		City: strings.Join(strings.Fields(city), " ")}
	errs := map[string]string{}
	if !countryRe.MatchString(p.Country) {
		errs["country"] = "Use a two-letter country code"
	}
	if !regionRe.MatchString(p.Region) {
		errs["region"] = "Use the state or region code"
	}
	if len([]rune(p.City)) > 80 || strings.ContainsAny(p.City, cityDisallow) {
		errs["city"] = "Use a city name (up to 80 characters)"
	}
	return p, errs
}

// ValidAreaKey checks a coverage key ("US/TX" or "US/TX/Lubbock").
func ValidAreaKey(key string) bool {
	parts := strings.Split(key, "/")
	if len(parts) < 2 || len(parts) > 3 {
		return false
	}
	city := ""
	if len(parts) == 3 {
		if parts[2] == "" {
			return false
		}
		city = parts[2]
	}
	_, errs := NormalizePlace(parts[0], parts[1], city)
	return len(errs) == 0
}

// Attribution is the last-touch marketing source sent with checkout: src (channel or placement), cr (creative
// slot), po (window code). orders stores it on the order and echoes it in orders.order.placed.
type Attribution struct {
	Src string `json:"src,omitempty"`
	Cr  string `json:"cr,omitempty"`
	Po  string `json:"po,omitempty"`
}

// Empty reports whether no field is set.
func (a Attribution) Empty() bool { return a.Src == "" && a.Cr == "" && a.Po == "" }

// Clean drops fields that are not well-formed tokens (attribution is best effort: a bad value never fails checkout)
// and returns nil when nothing is left.
func (a *Attribution) Clean() *Attribution {
	if a == nil {
		return nil
	}
	out := Attribution{Src: strings.ToLower(strings.TrimSpace(a.Src)), Cr: strings.TrimSpace(a.Cr), Po: strings.ToUpper(strings.TrimSpace(a.Po))}
	if !attrSrcRe.MatchString(out.Src) {
		out.Src = ""
	}
	if !attrCrRe.MatchString(out.Cr) {
		out.Cr = ""
	}
	if !attrPoRe.MatchString(out.Po) {
		out.Po = ""
	}
	if out.Empty() {
		return nil
	}
	return &out
}

// ParseAttributionCookie reads the at_attr cookie value the storefront writes: URL-encoded JSON {"src","cr","po"}
// or a query string "src=ig&cr=story&po=LBK-7". Unreadable values give nil.
func ParseAttributionCookie(v string) *Attribution {
	v = strings.TrimSpace(v)
	if v == "" || len(v) > 512 {
		return nil
	}
	if dec, err := url.QueryUnescape(v); err == nil && strings.HasPrefix(strings.TrimSpace(dec), "{") {
		v = dec
	}
	var a Attribution
	if strings.HasPrefix(v, "{") {
		if json.Unmarshal([]byte(v), &a) != nil {
			return nil
		}
		return a.Clean()
	}
	q, err := url.ParseQuery(v)
	if err != nil {
		return nil
	}
	a = Attribution{Src: q.Get("src"), Cr: q.Get("cr"), Po: q.Get("po")}
	return a.Clean()
}

// PreorderFulfilments are the fulfilment choices of a pre-order package.
var PreorderFulfilments = []string{"DOOR", "PICKUP"}

// ValidPreorderFulfilment upper-cases and checks a fulfilment choice.
func ValidPreorderFulfilment(f string) (string, bool) {
	f = strings.ToUpper(strings.TrimSpace(f))
	return f, slices.Contains(PreorderFulfilments, f)
}

// LateAddAddress is a saved address of a customer in the late-add picker (default first).
type LateAddAddress struct {
	ID     string `json:"id"`
	Label  string `json:"label"`
	Line1  string `json:"line1"`
	City   string `json:"city"`
	Region string `json:"region"`
	ZIP    string `json:"zip"`
}

// LateAddCustomer is one customer search hit with their saved addresses; city, region and country are the default
// address's.
type LateAddCustomer struct {
	ID        string           `json:"id"`
	Name      string           `json:"name"`
	Phone     string           `json:"phone"`
	Email     string           `json:"email"`
	City      string           `json:"city"`
	Region    string           `json:"region"`
	Country   string           `json:"country"`
	Addresses []LateAddAddress `json:"addresses"`
	// AddressesUnavailable is true when identity could not list the customer's addresses (enter a new one).
	AddressesUnavailable bool `json:"addressesUnavailable"`
}

// LateAddCustomers is GET /v1/staff/customers/search.
type LateAddCustomers struct {
	Items []LateAddCustomer `json:"items"`
}

// AddressVerification is POST /v1/staff/preorders/address-verify.
type AddressVerification struct {
	Verified  bool    `json:"verified"`
	PlaceID   *string `json:"placeId"`
	Formatted *string `json:"formatted"`
}

// PreorderUploadTicket is POST /v1/staff/preorders/uploads: the media asset and its direct upload.
type PreorderUploadTicket struct {
	AssetID string             `json:"assetId"`
	Upload  PreorderUploadSpec `json:"upload"`
}

// PreorderUploadSpec is a signed direct upload.
type PreorderUploadSpec struct {
	Method  string            `json:"method"`
	URL     string            `json:"url"`
	Headers map[string]string `json:"headers"`
}
