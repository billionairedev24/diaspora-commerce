package domain

import (
	"net/mail"
	"regexp"
	"strings"
)

// LegalDocumentKeys are the documents of the Legal & privacy center (content-service LegalDocumentType keys).
var LegalDocumentKeys = []string{"conditions-of-use", "privacy", "cookies", "privacy-choices", "returns", "seller-terms",
	"prohibited-items", "accessibility", "sms-terms", "gift-card-terms", "store-credit-terms", "membership-terms",
	"referral-terms", "preorder-delivery-terms", "community-guidelines", "ip-policy", "provider-terms", "payments-terms",
	"subprocessors", "data-processing", "seller-fees"}

// IsLegalDocument reports whether key names a legal document.
func IsLegalDocument(key string) bool {
	for _, k := range LegalDocumentKeys {
		if k == key {
			return true
		}
	}
	return false
}

// LegalSummary is one row of the legal center index: a document's current version.
type LegalSummary struct {
	Key           string `json:"key"`
	Title         string `json:"title"`
	Version       string `json:"version"`
	EffectiveDate string `json:"effectiveDate"`
	Href          string `json:"href"`
	DraftBanner   bool   `json:"draftBanner"`
}

// LegalIndex lists the current version of every published legal document, in the legal center's order.
type LegalIndex struct {
	Documents []LegalSummary `json:"documents"`
}

// LegalPage is a rendered legal document (contracts/openapi/gateway-bff.yaml LegalPage).
type LegalPage struct {
	Key                 string            `json:"key"`
	Title               string            `json:"title"`
	Version             string            `json:"version"`
	EffectiveDate       string            `json:"effectiveDate"`
	Status              string            `json:"status"`
	DraftBanner         bool              `json:"draftBanner"`
	Locale              string            `json:"locale"`
	TranslationFallback bool              `json:"translationFallback"`
	Body                string            `json:"body"`
	Outline             []LegalHeading    `json:"outline"`
	Versions            []LegalVersionRef `json:"versions"`
	Href                string            `json:"href"`
}

// LegalHeading is one `##` section of a legal document and its anchor id.
type LegalHeading struct {
	ID    string `json:"id"`
	Title string `json:"title"`
}

// LegalVersionRef is one version of a legal document.
type LegalVersionRef struct {
	Version       string  `json:"version"`
	EffectiveDate string  `json:"effectiveDate"`
	Status        string  `json:"status"`
	ChangeSummary *string `json:"changeSummary"`
}

// CookiePolicy is the cookie banner configuration and cookie table.
type CookiePolicy struct {
	NoticeVersion string             `json:"noticeVersion"`
	EffectiveDate string             `json:"effectiveDate"`
	NoticeHref    string             `json:"noticeHref"`
	Categories    []CookieCategory   `json:"categories"`
	Cookies       []CookieDefinition `json:"cookies"`
}

// CookieCategory is a consent category (strictly necessary ones are required).
type CookieCategory struct {
	Key         string `json:"key"`
	Name        string `json:"name"`
	Description string `json:"description"`
	Required    bool   `json:"required"`
	GPCOptOut   bool   `json:"gpcOptOut"`
}

// CookieDefinition is a row of the cookie table.
type CookieDefinition struct {
	Name     string `json:"name"`
	Provider string `json:"provider"`
	Purpose  string `json:"purpose"`
	Duration string `json:"duration"`
	Category string `json:"category"`
}

// ConsentChoice is what the visitor chose in the cookie banner, the preferences or Your Privacy Choices.
type ConsentChoice struct {
	Categories map[string]bool `json:"categories"`
	SaleOptOut bool            `json:"saleOptOut"`
	GPC        bool            `json:"gpc"`
	Source     string          `json:"source"`
}

// ConsentRecord is the stored, normalised decision.
type ConsentRecord struct {
	ID            string          `json:"id"`
	Categories    map[string]bool `json:"categories"`
	GPC           bool            `json:"gpc"`
	SaleOptOut    bool            `json:"saleOptOut"`
	PolicyVersion string          `json:"policyVersion"`
	RecordedAt    string          `json:"recordedAt"`
}

// ConsentSources are the places a consent decision can be made from the storefront.
var ConsentSources = map[string]bool{"banner": true, "preferences": true, "privacy-choices": true}

// PrivacyRequest is a request from Your Privacy Choices.
type PrivacyRequest struct {
	Type    string `json:"type"`
	Email   string `json:"email"`
	State   string `json:"state"`
	Details string `json:"details,omitempty"`
	Locale  string `json:"locale,omitempty"`
}

// PrivacyRequestReceipt confirms a privacy request.
type PrivacyRequestReceipt struct {
	Reference   string `json:"reference"`
	Type        string `json:"type"`
	Status      string `json:"status"`
	SubmittedAt string `json:"submittedAt"`
	DueAt       string `json:"dueAt"`
}

var (
	privacyTypes = map[string]bool{"access": true, "delete": true, "correct": true, "optout": true}
	usState      = regexp.MustCompile(`^(A[KLRZ]|C[AOT]|D[CE]|FL|GA|HI|I[ADLN]|K[SY]|LA|M[ADEINOST]|N[CDEHJMVY]|O[HKR]|PA|RI|S[CD]|T[NX]|UT|V[AT]|W[AIVY])$`)
)

// MaxPrivacyDetails is the longest free text accepted on a privacy request.
const MaxPrivacyDetails = 2000

// NormalisePrivacyRequest trims and upper-cases the request and returns per-field messages (the storefront's
// wording) for every problem.
func NormalisePrivacyRequest(in PrivacyRequest) (PrivacyRequest, map[string]string) {
	out := PrivacyRequest{Type: strings.ToLower(strings.TrimSpace(in.Type)), Email: strings.TrimSpace(in.Email),
		State: strings.ToUpper(strings.TrimSpace(in.State)), Details: strings.TrimSpace(in.Details), Locale: strings.TrimSpace(in.Locale)}
	errs := map[string]string{}
	if !privacyTypes[out.Type] {
		errs["type"] = "Choose what you would like to do"
	}
	if a, err := mail.ParseAddress(out.Email); err != nil || a.Address != out.Email || !strings.Contains(out.Email[strings.LastIndex(out.Email, "@")+1:], ".") {
		errs["email"] = "Enter the email address on your account"
	}
	if !usState.MatchString(out.State) {
		errs["state"] = "Choose your state"
	}
	if len([]rune(out.Details)) > MaxPrivacyDetails {
		errs["details"] = "Use 2000 characters or fewer"
	}
	return out, errs
}
