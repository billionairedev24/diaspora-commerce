package domain

import (
	"errors"
	"net/mail"
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

// StockAlert is a shopper's "tell me when it's back" subscription for one variant ATIN.
type StockAlert struct {
	ID           string           `json:"id"`
	UserID       string           `json:"-"`
	Atin         string           `json:"atin"`
	Email        string           `json:"email,omitempty"` // explicit address from the request; else identity's
	Market       string           `json:"market"`
	Locale       string           `json:"locale"`
	ProductTitle string           `json:"productTitle,omitempty"`
	Status       StockAlertStatus `json:"status"`
	CreatedAt    time.Time        `json:"createdAt"`
	NotifiedAt   *time.Time       `json:"notifiedAt,omitempty"`
}

var atinPattern = regexp.MustCompile(`^AT[0-9A-Z]{8}$`)

// NormalizeAtin upper-cases an ATIN and reports whether it is well formed (AT + 8 characters).
func NormalizeAtin(s string) (string, bool) {
	a := strings.ToUpper(strings.TrimSpace(s))
	return a, atinPattern.MatchString(a)
}

// Validate checks a new subscription and fills defaults.
func (a StockAlert) Validate() (StockAlert, error) {
	var errs []error
	if a.UserID == "" {
		errs = append(errs, errors.New("a signed-in shopper is required"))
	}
	atin, ok := NormalizeAtin(a.Atin)
	if !ok {
		errs = append(errs, errors.New("atin must be a variant ATIN (AT + 8 letters or digits)"))
	}
	a.Atin = atin
	if a.Email != "" {
		a.Email = NormalizeEmail(a.Email)
		if addr, err := mail.ParseAddress(a.Email); err != nil || addr.Address != a.Email {
			errs = append(errs, errors.New("email is not a valid address"))
		}
	}
	if len(a.ProductTitle) > 300 {
		errs = append(errs, errors.New("productTitle is too long"))
	}
	if len(errs) > 0 {
		return a, errors.Join(append([]error{ErrInvalidRequest}, errs...)...)
	}
	if a.Market == "" {
		a.Market = "us"
	}
	if a.Locale == "" {
		a.Locale = "en"
	}
	a.Market = strings.ToLower(a.Market)
	return a, nil
}

// ErrRecipientNotFound is returned by recipient lookups when identity does not know the user or organisation.
var ErrRecipientNotFound = errors.New("recipient not found")
