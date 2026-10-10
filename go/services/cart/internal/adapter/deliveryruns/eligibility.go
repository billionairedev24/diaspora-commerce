// Package deliveryruns implements port.PreorderEligibility over delivery-runs'
// GET /v1/service/preorders/eligibility (S272) with a client-credentials token of cart-service
// (scope delivery-runs:reserve) from identity.
package deliveryruns

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"math"
	"net/http"
	"net/url"
	"strings"
	"sync"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/services/cart/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/cart/internal/port"
)

// Default fees when delivery-runs sends none (minor units).
const (
	DefaultDoorFeeMinor   = 799
	DefaultPickupFeeMinor = 0
)

// Options of the adapter.
type Options struct {
	BaseURL      string // delivery-runs
	TokenURL     string // identity /oauth2/token
	ClientID     string
	ClientSecret string
	Scope        string // delivery-runs:reserve
}

type eligibilityImpl struct {
	o      Options
	client *http.Client
	now    func() time.Time

	mu     sync.Mutex
	token  string
	expiry time.Time
}

var _ port.PreorderEligibility = (*eligibilityImpl)(nil)

// New returns the eligibility adapter.
func New(o Options, client *http.Client) port.PreorderEligibility {
	o.BaseURL = strings.TrimSuffix(o.BaseURL, "/")
	if o.Scope == "" {
		o.Scope = "delivery-runs:reserve"
	}
	return &eligibilityImpl{o: o, client: client, now: time.Now}
}

func (e *eligibilityImpl) Eligibility(ctx context.Context, loc domain.Location, atins []string) (map[string]domain.PreorderLine, error) {
	out := map[string]domain.PreorderLine{}
	if len(atins) == 0 {
		return out, nil
	}
	q := url.Values{"atins": {strings.Join(atins, ",")}}
	country := loc.Country
	if country == "" {
		country = "US"
	}
	q.Set("country", country)
	if loc.Region != "" {
		q.Set("region", loc.Region)
	}
	if loc.City != "" {
		q.Set("city", loc.City)
	}
	tok, err := e.bearer(ctx)
	if err != nil {
		return nil, err
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, e.o.BaseURL+"/v1/service/preorders/eligibility?"+q.Encode(), nil)
	if err != nil {
		return nil, fmt.Errorf("request: %w", err)
	}
	req.Header.Set("Authorization", "Bearer "+tok)
	res, err := e.client.Do(req)
	if err != nil {
		return nil, fmt.Errorf("delivery-runs: %w", err)
	}
	defer func() { _ = res.Body.Close() }()
	if res.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("delivery-runs eligibility: status %d", res.StatusCode)
	}
	var raw json.RawMessage
	if err := json.NewDecoder(res.Body).Decode(&raw); err != nil {
		return nil, fmt.Errorf("delivery-runs decode: %w", err)
	}
	entries, err := decodeEntries(raw)
	if err != nil {
		return nil, err
	}
	for _, en := range entries {
		if l, atin, ok := en.line(); ok {
			out[atin] = l
		}
	}
	return out, nil
}

// entry reads delivery-runs' answer tolerantly: window fields flat or under "window"; fees as doorFee /
// doorFeeMinor in minor units.
type entry struct {
	ATIN        string           `json:"atin"`
	VariantATIN string           `json:"variantAtin"`
	Preorder    bool             `json:"preorder"`
	WindowID    string           `json:"windowId"`
	WindowCode  string           `json:"windowCode"`
	WindowName  string           `json:"windowName"`
	Stage       string           `json:"stage"`
	DeliversOn  string           `json:"deliversOn"`
	ClosesAt    string           `json:"closesAt"`
	OpensAt     string           `json:"opensAt"`
	Door        *bool            `json:"door"`
	Pickup      *bool            `json:"pickup"`
	DoorFee     *float64         `json:"doorFee"`
	DoorFeeM    *float64         `json:"doorFeeMinor"`
	PickupFee   *float64         `json:"pickupFee"`
	PickupFeeM  *float64         `json:"pickupFeeMinor"`
	Currency    string           `json:"currency"`
	Allowed     []string         `json:"allowedMethods"`
	PickupWin   string           `json:"pickupWindow"`
	TimeZone    string           `json:"timeZone"`
	Window      *json.RawMessage `json:"window"`
}

func decodeEntries(raw json.RawMessage) ([]entry, error) {
	var list []entry
	if err := json.Unmarshal(raw, &list); err == nil {
		return list, nil
	}
	var wrapped map[string]json.RawMessage
	if err := json.Unmarshal(raw, &wrapped); err != nil {
		return nil, fmt.Errorf("delivery-runs decode: %w", err)
	}
	for _, k := range []string{"items", "lines", "results", "atins", "products"} {
		if v, ok := wrapped[k]; ok {
			if err := json.Unmarshal(v, &list); err == nil {
				return list, nil
			}
		}
	}
	for atin, v := range wrapped {
		var en entry
		if err := json.Unmarshal(v, &en); err == nil {
			if en.ATIN == "" {
				en.ATIN = atin
			}
			list = append(list, en)
		}
	}
	return list, nil
}

func (en entry) line() (domain.PreorderLine, string, bool) {
	atin := en.ATIN
	if atin == "" {
		atin = en.VariantATIN
	}
	if atin == "" {
		return domain.PreorderLine{}, "", false
	}
	if en.Window != nil {
		var w struct {
			ID         string   `json:"id"`
			Code       string   `json:"code"`
			Name       string   `json:"name"`
			Stage      string   `json:"stage"`
			DeliversOn string   `json:"deliversOn"`
			ClosesAt   string   `json:"closesAt"`
			OpensAt    string   `json:"opensAt"`
			Door       *bool    `json:"door"`
			Pickup     *bool    `json:"pickup"`
			DoorFee    *float64 `json:"doorFee"`
			PickupFee  *float64 `json:"pickupFee"`
			Currency   string   `json:"currency"`
			Allowed    []string `json:"allowedMethods"`
			PickupWin  string   `json:"pickupWindow"`
			TimeZone   string   `json:"timeZone"`
		}
		if err := json.Unmarshal(*en.Window, &w); err == nil {
			en.WindowID = first(en.WindowID, w.ID)
			en.WindowCode = first(en.WindowCode, w.Code)
			en.WindowName = first(en.WindowName, w.Name)
			en.Stage = first(en.Stage, w.Stage)
			en.DeliversOn = first(en.DeliversOn, w.DeliversOn)
			en.ClosesAt = first(en.ClosesAt, w.ClosesAt)
			en.OpensAt = first(en.OpensAt, w.OpensAt)
			en.Currency = first(en.Currency, w.Currency)
			en.PickupWin = first(en.PickupWin, w.PickupWin)
			en.TimeZone = first(en.TimeZone, w.TimeZone)
			if en.Door == nil {
				en.Door = w.Door
			}
			if en.Pickup == nil {
				en.Pickup = w.Pickup
			}
			if en.DoorFee == nil {
				en.DoorFee = w.DoorFee
			}
			if en.PickupFee == nil {
				en.PickupFee = w.PickupFee
			}
			if len(en.Allowed) == 0 {
				en.Allowed = w.Allowed
			}
		}
	}
	return domain.PreorderLine{
		Preorder: en.Preorder && en.WindowID != "", WindowID: en.WindowID, WindowCode: en.WindowCode,
		WindowName: en.WindowName, Stage: strings.ToUpper(en.Stage), DeliversOn: en.DeliversOn,
		ClosesAt: en.ClosesAt, OpensAt: en.OpensAt, Door: en.Door == nil || *en.Door,
		Pickup: en.Pickup != nil && *en.Pickup, DoorFeeMinor: minor(en.DoorFeeM, en.DoorFee, DefaultDoorFeeMinor),
		PickupFeeMinor: minor(en.PickupFeeM, en.PickupFee, DefaultPickupFeeMinor), Currency: first(en.Currency, "USD"),
		AllowedMethods: en.Allowed, PickupWindow: en.PickupWin, TimeZone: en.TimeZone,
	}, atin, true
}

func minor(explicit, fee *float64, fallback int64) int64 {
	switch {
	case explicit != nil:
		return int64(math.Round(*explicit))
	case fee != nil:
		return int64(math.Round(*fee))
	default:
		return fallback
	}
}

func first(a, b string) string {
	if a != "" {
		return a
	}
	return b
}

// bearer returns a cached client-credentials token (refreshed 30 s before expiry).
func (e *eligibilityImpl) bearer(ctx context.Context) (string, error) {
	e.mu.Lock()
	defer e.mu.Unlock()
	if e.token != "" && e.now().Before(e.expiry) {
		return e.token, nil
	}
	form := url.Values{"grant_type": {"client_credentials"}, "scope": {e.o.Scope}}
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, e.o.TokenURL, strings.NewReader(form.Encode()))
	if err != nil {
		return "", fmt.Errorf("token request: %w", err)
	}
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	req.SetBasicAuth(e.o.ClientID, e.o.ClientSecret)
	res, err := e.client.Do(req)
	if err != nil {
		return "", fmt.Errorf("identity: %w", err)
	}
	defer func() { _ = res.Body.Close() }()
	if res.StatusCode != http.StatusOK {
		return "", fmt.Errorf("identity token: status %d", res.StatusCode)
	}
	var tr struct {
		AccessToken string `json:"access_token"`
		ExpiresIn   int    `json:"expires_in"`
	}
	if err := json.NewDecoder(res.Body).Decode(&tr); err != nil {
		return "", fmt.Errorf("identity decode: %w", err)
	}
	if tr.AccessToken == "" {
		return "", errors.New("identity: client-credentials response without access_token")
	}
	e.token = tr.AccessToken
	e.expiry = e.now().Add(time.Duration(max(tr.ExpiresIn, 60))*time.Second - 30*time.Second)
	return e.token, nil
}
