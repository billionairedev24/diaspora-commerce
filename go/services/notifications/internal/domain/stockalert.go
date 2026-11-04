package domain

import (
	"errors"
	"regexp"
	"strings"
	"time"
)

// StockAlertStatus is where a back-in-stock alert is in its life.
type StockAlertStatus string

// Stock alert statuses: active (waiting for a restock) → notified (sent once). Subscribing again after a
// notification re-arms the alert.
const (
	StockAlertActive   StockAlertStatus = "active"
	StockAlertNotified StockAlertStatus = "notified"
)

// StockAlert is a "tell me when it's back" subscription for one variant ATIN. A signed-in shopper's alert has a
// UserID; a guest's has none and is keyed by Email instead.
type StockAlert struct {
	ID           string           `json:"id"`
	UserID       string           `json:"-"` // empty for a guest alert
	Atin         string           `json:"atin"`
	Email        string           `json:"email,omitempty"` // normalised; required for guests, else identity's is used
	Market       string           `json:"market"`
	Locale       string           `json:"locale"`
	ProductTitle string           `json:"productTitle,omitempty"`
	Status       StockAlertStatus `json:"status"`
	CreatedAt    time.Time        `json:"createdAt"`
	NotifiedAt   *time.Time       `json:"notifiedAt,omitempty"`
}

var (
	atinPattern   = regexp.MustCompile(`^AT[0-9A-Z]{8}$`)
	marketPattern = regexp.MustCompile(`^[a-z]{2}$`)
	localePattern = regexp.MustCompile(`^[a-z]{2,3}(-[A-Za-z0-9]{2,8})?$`)
)

// NormalizeAtin upper-cases an ATIN and reports whether it is well formed (AT + 8 characters).
func NormalizeAtin(s string) (string, bool) {
	a := strings.ToUpper(strings.TrimSpace(s))
	return a, atinPattern.MatchString(a)
}

// NormalizeLocale returns a well-formed locale tag ("fr", "pt-BR") or "en": the value arrives from a request header.
func NormalizeLocale(l string) string {
	l = strings.TrimSpace(l)
	lang, region, found := strings.Cut(l, "-")
	l = strings.ToLower(lang)
	if found {
		l += "-" + region
	}
	if len(l) > 12 || !localePattern.MatchString(l) {
		return "en"
	}
	return l
}

// Guest reports whether the alert belongs to an address rather than an account.
func (a StockAlert) Guest() bool { return a.UserID == "" }

// cleanTitle drops control characters from a product title.
func cleanTitle(s string) string {
	return strings.TrimSpace(strings.Map(func(r rune) rune {
		if r < 0x20 || r == 0x7f {
			return -1
		}
		return r
	}, s))
}

// Validate checks a new subscription and fills defaults. A guest alert needs a valid email; a signed-in shopper's
// may carry one that overrides the account's.
func (a StockAlert) Validate() (StockAlert, error) {
	var errs []error
	atin, ok := NormalizeAtin(a.Atin)
	if !ok {
		errs = append(errs, errors.New("atin must be a variant ATIN (AT + 8 letters or digits)"))
	}
	a.Atin = atin
	if a.Guest() || strings.TrimSpace(a.Email) != "" {
		e, err := ParseEmail(a.Email)
		if err != nil {
			errs = append(errs, errors.New("email is not a valid address"))
		}
		a.Email = e
	}
	a.ProductTitle = cleanTitle(a.ProductTitle)
	if len(a.ProductTitle) > 300 {
		errs = append(errs, errors.New("productTitle is too long"))
	}
	if len(errs) > 0 {
		return a, errors.Join(append([]error{ErrInvalidRequest}, errs...)...)
	}
	a.Market = strings.ToLower(strings.TrimSpace(a.Market))
	if !marketPattern.MatchString(a.Market) {
		a.Market = "us"
	}
	a.Locale = NormalizeLocale(a.Locale)
	return a, nil
}

// ErrAlertLimit is returned when a shopper already has the maximum number of open alerts.
var ErrAlertLimit = errors.New("too many open stock alerts")

// ErrRecipientNotFound is returned by recipient lookups when identity does not know the user or organisation.
var ErrRecipientNotFound = errors.New("recipient not found")
