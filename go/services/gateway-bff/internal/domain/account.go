package domain

import (
	"strings"
)

// ---------------------------------------------------------------- account settings (storefront, Seller Center, staff console)

// Account kinds: which settings apply to the signed-in person.
const (
	AccountCustomer = "customer"
	AccountSeller   = "seller"
	AccountStaff    = "staff"
)

// StaffEmailDomain is the company domain of staff accounts (addresses managed by AfroTransact IT).
const StaffEmailDomain = "afrotransact.com"

// AccountMembership is one seller or provider organisation the person belongs to.
type AccountMembership struct {
	OrgID     string `json:"orgId"`
	Name      string `json:"name"`
	Kind      string `json:"kind"`
	Status    string `json:"status"`
	RoleKey   string `json:"roleKey"`
	RoleLabel string `json:"roleLabel"`
	Owner     bool   `json:"owner"`
	Active    bool   `json:"active"`
}

// AccountProfile is the profile page of every app.
type AccountProfile struct {
	ID            string              `json:"id"`
	Kind          string              `json:"kind"`
	FirstName     string              `json:"firstName"`
	LastName      string              `json:"lastName"`
	Email         string              `json:"email"`
	EmailVerified bool                `json:"emailVerified"`
	EmailManaged  bool                `json:"emailManaged"`
	Initials      string              `json:"initials"`
	Phone         *string             `json:"phone"`
	PhoneVerified bool                `json:"phoneVerified"`
	JobTitle      *string             `json:"jobTitle"`
	Memberships   []AccountMembership `json:"memberships"`
	Roles         []StaffRole         `json:"roles"`
}

// AccountProfileInput changes the profile; nil fields are left unchanged. JobTitle "" clears it.
type AccountProfileInput struct {
	FirstName *string `json:"firstName"`
	LastName  *string `json:"lastName"`
	Phone     *string `json:"phone"`
	JobTitle  *string `json:"jobTitle"`
}

// AccountPreferences are the display preferences.
type AccountPreferences struct {
	Locale       *string `json:"locale"`
	TimeZone     *string `json:"timeZone"`
	DateFormat   string  `json:"dateFormat"`
	NumberFormat string  `json:"numberFormat"`
	TableDensity string  `json:"tableDensity"`
}

// Display preference values (identity's UserPreferences enums).
var (
	DateFormats    = []string{"AUTO", "MDY", "DMY", "YMD"}
	NumberFormats  = []string{"AUTO", "COMMA_DOT", "DOT_COMMA", "SPACE_COMMA"}
	TableDensities = []string{"COMFORTABLE", "COMPACT"}
)

// AlertPreference is one back-office alert topic on one channel.
type AlertPreference struct {
	Topic   string `json:"topic"`
	Channel string `json:"channel"`
	Enabled bool   `json:"enabled"`
	Locked  bool   `json:"locked"`
}

// QuietHours is a daily do-not-disturb window ("21:00"–"07:00").
type QuietHours struct {
	Enabled bool   `json:"enabled"`
	Start   string `json:"start"`
	End     string `json:"end"`
}

// AlertPreferences are a back-office member's alert topics and quiet hours.
type AlertPreferences struct {
	Audience    string            `json:"audience"`
	Preferences []AlertPreference `json:"preferences"`
	QuietHours  QuietHours        `json:"quietHours"`
}

// AlertPreferencesInput changes alert topics (only those listed) and, when set, the quiet hours.
type AlertPreferencesInput struct {
	Preferences []AlertPreference `json:"preferences"`
	QuietHours  *QuietHours       `json:"quietHours"`
}

// SignInEvent is one recent sign-in of the account.
type SignInEvent struct {
	At      string  `json:"at"`
	Method  string  `json:"method"`
	Outcome string  `json:"outcome"`
	App     string  `json:"app"`
	Device  string  `json:"device"`
	IP      *string `json:"ip"`
}

// AccountKind classifies a person: staff when identity says STAFF, seller when they belong to an organisation,
// otherwise customer.
func AccountKind(principalType string, orgs int) string {
	switch {
	case strings.EqualFold(principalType, "STAFF"):
		return AccountStaff
	case orgs > 0 || strings.EqualFold(principalType, "SELLER_MEMBER") || strings.EqualFold(principalType, "PROVIDER_MEMBER"):
		return AccountSeller
	default:
		return AccountCustomer
	}
}

// IsStaffEmail reports whether an address is on the staff company domain.
func IsStaffEmail(email string) bool {
	at := strings.LastIndex(email, "@")
	return at >= 0 && strings.EqualFold(email[at+1:], StaffEmailDomain)
}

// ClientLabel names the app behind an OAuth client id for people ("Seller Center", "Driver app").
func ClientLabel(clientID, fallback string) string {
	switch clientID {
	case "gateway-bff":
		return "AfroTransact on the web"
	case "customer-app":
		return "AfroTransact app"
	case "driver-app":
		return "Driver app"
	case "":
		return fallback
	}
	if fallback != "" {
		return fallback
	}
	return clientID
}

// DescribeUserAgent turns a user agent into "Chrome on macOS" (or "Unknown browser").
func DescribeUserAgent(ua string) string {
	if strings.TrimSpace(ua) == "" {
		return "Unknown browser"
	}
	browser := "Browser"
	switch {
	case strings.Contains(ua, "Edg/"):
		browser = "Edge"
	case strings.Contains(ua, "OPR/") || strings.Contains(ua, "Opera"):
		browser = "Opera"
	case strings.Contains(ua, "Firefox/") || strings.Contains(ua, "FxiOS/"):
		browser = "Firefox"
	case strings.Contains(ua, "CriOS/") || (strings.Contains(ua, "Chrome/") && !strings.Contains(ua, "Chromium/")):
		browser = "Chrome"
	case strings.Contains(ua, "Safari/") && strings.Contains(ua, "Version/"):
		browser = "Safari"
	case strings.Contains(ua, "okhttp") || strings.Contains(ua, "Dalvik"):
		browser = "Android app"
	case strings.Contains(ua, "CFNetwork") || strings.Contains(ua, "Darwin"):
		browser = "iOS app"
	case strings.HasPrefix(ua, "Java-http-client") || strings.HasPrefix(ua, "Go-http-client") || strings.HasPrefix(ua, "curl/"):
		browser = "API client"
	}
	system := ""
	switch {
	case strings.Contains(ua, "iPhone") || strings.Contains(ua, "iPad") || strings.Contains(ua, "iOS"):
		system = "iOS"
	case strings.Contains(ua, "Android"):
		system = "Android"
	case strings.Contains(ua, "Mac OS X") || strings.Contains(ua, "Macintosh"):
		system = "macOS"
	case strings.Contains(ua, "Windows"):
		system = "Windows"
	case strings.Contains(ua, "CrOS"):
		system = "ChromeOS"
	case strings.Contains(ua, "Linux"):
		system = "Linux"
	}
	if system == "" {
		return browser
	}
	return browser + " on " + system
}
