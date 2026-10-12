package app

import (
	"context"
	"net/http"
	"regexp"
	"strings"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

// AccountService serves the signed-in customer's account: address book (identity, validated by geo), saved
// payment methods (payments), orders, shipment cancellation and returns (orders).
type AccountService interface {
	Addresses(ctx context.Context, c *domain.Caller) (domain.Page[domain.Address], error)
	CreateAddress(ctx context.Context, c *domain.Caller, in domain.AddressInput) (domain.Address, error)
	UpdateAddress(ctx context.Context, c *domain.Caller, id string, in domain.AddressInput) (domain.Address, error)
	DeleteAddress(ctx context.Context, c *domain.Caller, id string) error
	SetDefaultAddress(ctx context.Context, c *domain.Caller, id string) (domain.Address, error)
	PaymentMethods(ctx context.Context, c *domain.Caller) (domain.Page[domain.PaymentMethod], error)
	SetupIntent(ctx context.Context, c *domain.Caller) (domain.SetupIntent, error)
	DeletePaymentMethod(ctx context.Context, c *domain.Caller, id string) error
	Orders(ctx context.Context, c *domain.Caller, cursor string, limit int) (domain.Page[domain.OrderSummary], error)
	CancelShipment(ctx context.Context, c *domain.Caller, orderNumber, shipmentID string) (domain.OrderSummary, error)
	Returns(ctx context.Context, c *domain.Caller) (domain.Page[domain.ReturnSummary], error)
	StartReturn(ctx context.Context, c *domain.Caller, r domain.StartReturn) (domain.ReturnSummary, error)
	// StartPhoneVerification texts a 6-digit code to the account phone; ConfirmPhoneVerification checks it.
	StartPhoneVerification(ctx context.Context, c *domain.Caller) (domain.PhoneCodeSent, error)
	ConfirmPhoneVerification(ctx context.Context, c *domain.Caller, code string) (domain.PhoneVerification, error)
}

type accountServiceImpl struct {
	identity port.IdentityClient
	geo      port.GeoClient
	payments port.PaymentsClient
	orders   port.OrdersClient
}

var _ AccountService = (*accountServiceImpl)(nil)

// NewAccountService returns the AccountService.
func NewAccountService(identity port.IdentityClient, geo port.GeoClient, payments port.PaymentsClient, orders port.OrdersClient) AccountService {
	return &accountServiceImpl{identity: identity, geo: geo, payments: payments, orders: orders}
}

func requireCustomer(c *domain.Caller) error {
	if !c.Authenticated() {
		return domain.Unauthenticated()
	}
	return nil
}

func toAddress(a port.IdentityAddress) domain.Address {
	return domain.Address{ID: a.ID, Name: a.FullName, Phone: a.Phone, Line1: a.Line1, Line2: a.Line2, City: a.City,
		State: a.State, ZIP: a.PostalCode, IsDefault: a.IsDefault, Verified: a.Verified}
}

func (s *accountServiceImpl) Addresses(ctx context.Context, c *domain.Caller) (domain.Page[domain.Address], error) {
	if err := requireCustomer(c); err != nil {
		return domain.Page[domain.Address]{}, err
	}
	list, err := s.identity.Addresses(ctx, c.BearerToken)
	if err != nil {
		return domain.Page[domain.Address]{}, mapErr("identity", err)
	}
	out := make([]domain.Address, 0, len(list))
	for _, a := range list {
		out = append(out, toAddress(a))
	}
	return domain.Page[domain.Address]{Items: out}, nil
}

var (
	stateRe = regexp.MustCompile(`^[A-Z]{2}$`)
	usZipRe = regexp.MustCompile(`^\d{5}(-\d{4})?$`)
)

func validateAddress(in domain.AddressInput) map[string]string {
	errs := map[string]string{}
	required := map[string]string{"name": in.Name, "phone": in.Phone, "line1": in.Line1, "city": in.City}
	for k, v := range required {
		if strings.TrimSpace(v) == "" {
			errs[k] = "Required"
		}
	}
	if !stateRe.MatchString(in.State) {
		errs["state"] = "Choose a state"
	}
	if !usZipRe.MatchString(in.ZIP) {
		errs["zip"] = "Enter a valid ZIP code"
	}
	return errs
}

// verify checks deliverability with geo. Corrections are suggested, never applied silently; without
// acceptUnverified, anything but VALID is a 422 the form shows next to the street fields.
func (s *accountServiceImpl) verify(ctx context.Context, in domain.AddressInput) (bool, error) {
	v, err := s.geo.Validate(ctx, port.GeoAddress{Line1: in.Line1, Line2: in.Line2, City: in.City, State: in.State, PostalCode: in.ZIP, Country: "US"})
	if err != nil {
		if p, ok := port.AsUpstreamProblem(err); ok && p.Status == http.StatusUnprocessableEntity && !in.AcceptUnverified {
			return false, domain.ValidationProblem(map[string]string{"line1": firstNonEmpty(p.Detail, "Check the address")}).WithCause(err)
		}
		return false, nil // geo unavailable: keep the address, unverified
	}
	if v.Status == "VALID" {
		return true, nil
	}
	if in.AcceptUnverified {
		return false, nil
	}
	msg := "We couldn’t verify this address. Check it or keep it as entered."
	if len(v.Messages) > 0 {
		msg = v.Messages[0]
	}
	field := "line1"
	if v.Status == "MISSING_UNIT" {
		field = "line2"
	}
	if v.Status == "CORRECTED" && v.Standard != nil {
		msg = "Did you mean " + strings.TrimSpace(v.Standard.Line1+" "+v.Standard.Line2) + ", " + v.Standard.City + ", " + v.Standard.State + " " + v.Standard.PostalCode + "?"
	}
	return false, domain.ValidationProblem(map[string]string{field: msg})
}

var identityFields = map[string]string{"fullName": "name", "postalCode": "zip", "line1": "line1", "line2": "line2",
	"city": "city", "state": "state", "phone": "phone"}

func addressErr(err error) error {
	p, ok := port.AsUpstreamProblem(err)
	if !ok {
		return mapErr("identity", err)
	}
	switch {
	case p.Status == http.StatusNotFound || p.Code == "identity.address-not-found":
		return domain.NewProblem(http.StatusNotFound, domain.CodeAddressNotFound, "Address not found", "That address no longer exists.").WithCause(err)
	case p.Status == http.StatusUnprocessableEntity || p.Status == http.StatusBadRequest:
		fields := map[string]string{}
		for k, v := range p.Fields {
			if mk, ok := identityFields[k]; ok {
				fields[mk] = v
			} else {
				fields[k] = v
			}
		}
		if len(fields) == 0 {
			fields["_"] = firstNonEmpty(p.Detail, "Check the address")
		}
		return domain.ValidationProblem(fields).WithCause(err)
	}
	return mapErr("identity", err)
}

func (s *accountServiceImpl) save(ctx context.Context, c *domain.Caller, id string, in domain.AddressInput) (domain.Address, error) {
	if err := requireCustomer(c); err != nil {
		return domain.Address{}, err
	}
	if errs := validateAddress(in); len(errs) > 0 {
		return domain.Address{}, domain.ValidationProblem(errs)
	}
	verified, err := s.verify(ctx, in)
	if err != nil {
		return domain.Address{}, err
	}
	body := port.IdentityAddress{FullName: strings.TrimSpace(in.Name), Phone: in.Phone, Line1: in.Line1, Line2: in.Line2, City: in.City,
		State: in.State, PostalCode: in.ZIP, Country: "US", Verified: verified, MakeDefault: in.IsDefault}
	var saved port.IdentityAddress
	if id == "" {
		saved, err = s.identity.CreateAddress(ctx, c.BearerToken, body)
	} else {
		saved, err = s.identity.UpdateAddress(ctx, c.BearerToken, id, body)
		if err == nil && in.IsDefault && !saved.IsDefault {
			saved, err = s.identity.DefaultAddress(ctx, c.BearerToken, id)
		}
	}
	if err != nil {
		return domain.Address{}, addressErr(err)
	}
	return toAddress(saved), nil
}

func (s *accountServiceImpl) CreateAddress(ctx context.Context, c *domain.Caller, in domain.AddressInput) (domain.Address, error) {
	return s.save(ctx, c, "", in)
}

func (s *accountServiceImpl) UpdateAddress(ctx context.Context, c *domain.Caller, id string, in domain.AddressInput) (domain.Address, error) {
	return s.save(ctx, c, id, in)
}

func (s *accountServiceImpl) DeleteAddress(ctx context.Context, c *domain.Caller, id string) error {
	if err := requireCustomer(c); err != nil {
		return err
	}
	if err := s.identity.DeleteAddress(ctx, c.BearerToken, id); err != nil {
		return addressErr(err)
	}
	return nil
}

func (s *accountServiceImpl) SetDefaultAddress(ctx context.Context, c *domain.Caller, id string) (domain.Address, error) {
	if err := requireCustomer(c); err != nil {
		return domain.Address{}, err
	}
	a, err := s.identity.DefaultAddress(ctx, c.BearerToken, id)
	if err != nil {
		return domain.Address{}, addressErr(err)
	}
	return toAddress(a), nil
}

func (s *accountServiceImpl) PaymentMethods(ctx context.Context, c *domain.Caller) (domain.Page[domain.PaymentMethod], error) {
	if err := requireCustomer(c); err != nil {
		return domain.Page[domain.PaymentMethod]{}, err
	}
	wallet, err := s.payments.PaymentMethods(ctx, c.BearerToken)
	if err != nil {
		return domain.Page[domain.PaymentMethod]{}, mapErr("payments", err)
	}
	out := make([]domain.PaymentMethod, 0, len(wallet.Items))
	for _, m := range wallet.Items {
		out = append(out, toPaymentMethod(m))
	}
	return domain.Page[domain.PaymentMethod]{Items: out}, nil
}

func (s *accountServiceImpl) SetupIntent(ctx context.Context, c *domain.Caller) (domain.SetupIntent, error) {
	if err := requireCustomer(c); err != nil {
		return domain.SetupIntent{}, err
	}
	si, err := s.payments.SetupIntent(ctx, c.BearerToken)
	if err != nil {
		return si, mapErr("payments", err)
	}
	return si, nil
}

func (s *accountServiceImpl) DeletePaymentMethod(ctx context.Context, c *domain.Caller, id string) error {
	if err := requireCustomer(c); err != nil {
		return err
	}
	if err := s.payments.DeletePaymentMethod(ctx, c.BearerToken, id); err != nil {
		if p, ok := port.AsUpstreamProblem(err); ok && p.Status == http.StatusNotFound {
			return domain.NewProblem(http.StatusNotFound, domain.CodePaymentNotFound, "Payment method not found", "That payment method no longer exists.").WithCause(err)
		}
		return mapErr("payments", err)
	}
	return nil
}

func (s *accountServiceImpl) Orders(ctx context.Context, c *domain.Caller, cursor string, limit int) (domain.Page[domain.OrderSummary], error) {
	if err := requireCustomer(c); err != nil {
		return domain.Page[domain.OrderSummary]{}, err
	}
	page, err := s.orders.List(ctx, c.BearerToken, cursor, limit)
	if err != nil {
		return page, mapErr("orders", err)
	}
	page.Items = orEmpty(page.Items)
	for i := range page.Items {
		page.Items[i].Shipments = orEmpty(page.Items[i].Shipments)
	}
	return page, nil
}

func (s *accountServiceImpl) CancelShipment(ctx context.Context, c *domain.Caller, orderNumber, shipmentID string) (domain.OrderSummary, error) {
	if err := requireCustomer(c); err != nil {
		return domain.OrderSummary{}, err
	}
	o, err := s.orders.CancelShipment(ctx, c.BearerToken, orderNumber, shipmentID)
	if err != nil {
		if p, ok := port.AsUpstreamProblem(err); ok && p.Status == http.StatusNotFound {
			return o, domain.NewProblem(http.StatusNotFound, domain.CodeOrderNotFound, "Order not found", "We couldn't find that shipment.").WithCause(err)
		}
		return o, mapErr("orders", err)
	}
	o.Shipments = orEmpty(o.Shipments)
	return o, nil
}

func (s *accountServiceImpl) Returns(ctx context.Context, c *domain.Caller) (domain.Page[domain.ReturnSummary], error) {
	if err := requireCustomer(c); err != nil {
		return domain.Page[domain.ReturnSummary]{}, err
	}
	page, err := s.orders.Returns(ctx, c.BearerToken)
	if err != nil {
		return page, mapErr("orders", err)
	}
	page.Items = orEmpty(page.Items)
	return page, nil
}

var returnReasons = map[string]bool{"DAMAGED": true, "WRONG_ITEM": true, "NOT_AS_DESCRIBED": true, "NO_LONGER_NEEDED": true}

func (s *accountServiceImpl) StartReturn(ctx context.Context, c *domain.Caller, r domain.StartReturn) (domain.ReturnSummary, error) {
	if err := requireCustomer(c); err != nil {
		return domain.ReturnSummary{}, err
	}
	errs := map[string]string{}
	if strings.TrimSpace(r.OrderNumber) == "" {
		errs["orderNumber"] = "Required"
	}
	if !atinRe.MatchString(r.ATIN) {
		errs["atin"] = "Invalid ATIN"
	}
	if !returnReasons[r.Reason] {
		errs["reason"] = "Choose a reason"
	}
	if len(errs) > 0 {
		return domain.ReturnSummary{}, domain.ValidationProblem(errs)
	}
	out, err := s.orders.StartReturn(ctx, c.BearerToken, r)
	if err != nil {
		return out, mapErr("orders", err)
	}
	return out, nil
}

// ---------------------------------------------------------------- phone verification (S234 / S236)

var phoneCodeRe = regexp.MustCompile(`^\d{6}$`)

func phoneErr(err error) error {
	if p, ok := port.AsUpstreamProblem(err); ok {
		switch p.Code {
		case "identity.phone-missing":
			return domain.FieldProblem(domain.CodePhoneMissing, "No phone number", "phone", "Add a mobile number to your profile first.").WithCause(err)
		case "identity.phone-already-verified":
			return domain.NewProblem(http.StatusConflict, domain.CodeConflict, "Already verified", "Your phone number is already verified.").WithCause(err)
		case "identity.phone-changed":
			return domain.NewProblem(http.StatusGone, domain.CodeChallengeExpired, "Phone changed",
				"Your phone number changed after the code was sent. Send a new code.").WithCause(err)
		}
	}
	return mapIdentityErr(err)
}

func (s *accountServiceImpl) StartPhoneVerification(ctx context.Context, c *domain.Caller) (domain.PhoneCodeSent, error) {
	if err := requireCustomer(c); err != nil {
		return domain.PhoneCodeSent{}, err
	}
	sent, err := s.identity.StartPhoneVerification(ctx, c.BearerToken)
	if err != nil {
		return domain.PhoneCodeSent{}, phoneErr(err)
	}
	return domain.PhoneCodeSent(sent), nil
}

func (s *accountServiceImpl) ConfirmPhoneVerification(ctx context.Context, c *domain.Caller, code string) (domain.PhoneVerification, error) {
	if err := requireCustomer(c); err != nil {
		return domain.PhoneVerification{}, err
	}
	code = strings.TrimSpace(code)
	if !phoneCodeRe.MatchString(code) {
		return domain.PhoneVerification{}, domain.FieldProblem(domain.CodeValidationFailed, "Validation failed", "code", "Enter the 6-digit code")
	}
	v, err := s.identity.ConfirmPhoneVerification(ctx, c.BearerToken, code)
	if err != nil {
		return domain.PhoneVerification{}, phoneErr(err)
	}
	return domain.PhoneVerification{PhoneVerified: v.PhoneVerified, VerifiedAt: v.VerifiedAt}, nil
}
