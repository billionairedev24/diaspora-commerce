package app

import (
	"crypto/hmac"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"sort"
	"strings"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
)

// TaxQuoteTTL is how long a shown total stays locked (docs/engineering/checkout-tax-estimate.md).
const TaxQuoteTTL = 15 * time.Minute

// taxQuoteClaims is what a taxQuoteId carries: the total the shopper saw, and the inputs it was worked out for. The
// token is signed (HMAC-SHA256 with a key derived from the session signing key, shared by every gateway replica) so it
// cannot be forged or edited; nothing is stored server side.
type taxQuoteClaims struct {
	// Subject is the shopper (identity subject): a lock is never usable by another account.
	Subject string `json:"s"`
	// Inputs is a hash of everything the tax and total were worked out from (see taxQuoteInputs).
	Inputs string `json:"i"`
	// TaxMinor and TotalMinor are the estimated tax and the order total shown (items - discounts + delivery + tax).
	TaxMinor   int64  `json:"t"`
	TotalMinor int64  `json:"n"`
	Currency   string `json:"c"`
	// ExpiresAt is unix seconds.
	ExpiresAt int64 `json:"e"`
}

// Why a lock was not usable (the quote-lock metric's result label).
var (
	errQuoteMalformed = errors.New("malformed")
	errQuoteSignature = errors.New("signature")
	errQuoteExpired   = errors.New("expired")
	errQuoteSubject   = errors.New("subject")
)

var quoteEncoding = base64.RawURLEncoding

func signQuote(key []byte, c taxQuoteClaims) string {
	body, _ := json.Marshal(c) // a struct of strings and integers: cannot fail
	payload := quoteEncoding.EncodeToString(body)
	return payload + "." + quoteEncoding.EncodeToString(quoteMAC(key, payload))
}

func quoteMAC(key []byte, payload string) []byte {
	m := hmac.New(sha256.New, key)
	m.Write([]byte("taxquote-v1." + payload))
	return m.Sum(nil)
}

// verifyQuote checks the signature, the expiry and the owner; the inputs are compared by the caller.
func verifyQuote(key []byte, token, subject string, now time.Time) (taxQuoteClaims, error) {
	var c taxQuoteClaims
	payload, sig, ok := strings.Cut(strings.TrimSpace(token), ".")
	if !ok || payload == "" || sig == "" {
		return c, errQuoteMalformed
	}
	got, err := quoteEncoding.DecodeString(sig)
	if err != nil {
		return c, errQuoteMalformed
	}
	if len(key) == 0 || !hmac.Equal(got, quoteMAC(key, payload)) {
		return c, errQuoteSignature
	}
	body, err := quoteEncoding.DecodeString(payload)
	if err != nil || json.Unmarshal(body, &c) != nil {
		return c, errQuoteMalformed
	}
	if c.Subject != subject {
		return c, errQuoteSubject
	}
	if now.Unix() > c.ExpiresAt {
		return c, errQuoteExpired
	}
	return c, nil
}

// QuoteKey derives the lock's signing key from the session signing key (a different key from the cookie's).
func QuoteKey(signingKey string) []byte {
	if signingKey == "" {
		return nil
	}
	m := hmac.New(sha256.New, []byte(signingKey))
	m.Write([]byte("checkout-tax-quote-lock"))
	return m.Sum(nil)
}

// taxQuoteInputs is the lock's binding: the cart lines (id, quantity, unit price), the address (id and the place tax is
// worked out for), the delivery option per package and the coupon code. The summary and Place order compute it the same
// way, so a lock is "for these inputs" exactly when the two agree. Delivery fees, the tax rate and stock are not part of
// it: they change on their own, and orders' guard (total shown vs total charged) is what catches them.
type taxQuoteInputs struct {
	AddressID, State, PostalCode, City string
	Coupon                             string
	Lines                              []string
	Delivery                           []string
}

func (i taxQuoteInputs) hash() string {
	lines, delivery := append([]string(nil), i.Lines...), append([]string(nil), i.Delivery...)
	sort.Strings(lines)
	sort.Strings(delivery)
	sum := sha256.Sum256([]byte(strings.Join([]string{i.AddressID, strings.ToUpper(i.State), strings.TrimSpace(i.PostalCode),
		strings.ToLower(strings.TrimSpace(i.City)), strings.ToUpper(strings.TrimSpace(i.Coupon)), strings.Join(lines, ","),
		strings.Join(delivery, ",")}, "|")))
	return hex.EncodeToString(sum[:12])
}

// linesOf lists a cart's selected lines as "id:qty:unitPrice".
func quoteLines(pkgs []domain.CheckoutPackage) []string {
	var out []string
	for _, p := range pkgs {
		for _, l := range p.Lines {
			out = append(out, fmt.Sprintf("%s:%d:%d", l.LineID, l.Quantity, l.PriceMinor))
		}
	}
	return out
}

// quoteDeliveryChoice is the delivery option a package is priced and ordered with. A pre-order package has its own fee
// and no choice that matters.
func quoteDeliveryChoice(p domain.CheckoutPackage, chosen string) string {
	if len(p.Preorder) > 0 {
		return p.PackageID + "=preorder"
	}
	return p.PackageID + "=" + strings.ToLower(strings.TrimSpace(chosen))
}
