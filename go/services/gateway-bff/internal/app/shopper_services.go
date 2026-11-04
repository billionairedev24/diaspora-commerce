package app

import (
	"context"
	"io"
	"net/http"
	"regexp"
	"strings"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

// ServicesService is the services marketplace over bookings: search by ZIP, service detail with live days and
// slots, the provider page (bookings profile + the provider's published storefront page), booking with a deposit
// and the customer's bookings (cancel with the exact refund quote, reschedule, new-time answers, review).
type ServicesService interface {
	Search(ctx context.Context, c *domain.Caller, q, category, zip string) (domain.ServiceSearch, error)
	Service(ctx context.Context, id string) (domain.ServiceDetail, error)
	Days(ctx context.Context, id string, q port.SlotQuery) ([]domain.AvailableDay, error)
	Slots(ctx context.Context, id string, q port.SlotQuery) ([]domain.AvailableSlot, error)
	Quote(ctx context.Context, id string, q port.SlotQuery) (domain.BookingQuote, error)
	Provider(ctx context.Context, orgID string) (domain.ProviderPage, error)
	Book(ctx context.Context, c *domain.Caller, key string, req domain.BookingRequest) (domain.BookingCreated, error)
	Mine(ctx context.Context, c *domain.Caller) ([]domain.CustomerBooking, error)
	Booking(ctx context.Context, c *domain.Caller, id string) (domain.CustomerBooking, error)
	CancellationQuote(ctx context.Context, c *domain.Caller, id string) (domain.CancellationQuote, error)
	Cancel(ctx context.Context, c *domain.Caller, key, id, reason string) (domain.BookingCancelled, error)
	Reschedule(ctx context.Context, c *domain.Caller, key, id, startAt string) (domain.CustomerBooking, error)
	AnswerNewTime(ctx context.Context, c *domain.Caller, key, id string, accept bool) (domain.CustomerBooking, error)
	Review(ctx context.Context, c *domain.Caller, id string, rating int, text string) (domain.BookingReview, error)
	SyncPayment(ctx context.Context, c *domain.Caller, id string) (domain.CustomerBooking, error)
}

type servicesServiceImpl struct{ d ShopperDeps }

var _ ServicesService = (*servicesServiceImpl)(nil)

// NewServicesService returns the ServicesService.
func NewServicesService(d ShopperDeps) ServicesService { return &servicesServiceImpl{d: d} }

var (
	zip5      = regexp.MustCompile(`^\d{5}$`)
	modes     = map[string]bool{"IN_PERSON": true, "AT_HOME": true, "VIRTUAL": true}
	cancelOK  = map[string]bool{"PENDING_PAYMENT": true, "REQUESTED": true, "CONFIRMED": true, "RESCHEDULE_REQUESTED": true, "RESCHEDULE_PROPOSED": true}
	slotIDRe  = regexp.MustCompile(`^[A-Za-z0-9_-]{1,64}$`)
	isoLayout = time.RFC3339
)

// bookingsErr maps bookings' refusals onto the contract.
func bookingsErr(err error) error {
	if p, ok := port.AsUpstreamProblem(err); ok {
		switch {
		case p.Code == "bookings.slot-taken":
			pr := domain.NewProblem(http.StatusConflict, domain.CodeConflict, "That time was just taken",
				firstNonEmpty(p.Detail, "Someone booked that time a moment ago. Choose another time."))
			pr.Errors = map[string]string{"startAt": pr.Detail}
			return pr.WithCause(err)
		case p.Code == "bookings.invalid-zip":
			return domain.FieldProblem(domain.CodeValidationFailed, "Check the ZIP code", "zip", "Enter a 5-digit US ZIP code.").WithCause(err)
		case p.Code == "bookings.payment-rejected":
			return domain.NewProblem(http.StatusPaymentRequired, domain.CodePaymentDeclined, "Payment declined",
				firstNonEmpty(p.Detail, "The deposit couldn't be held. Try another card.")).WithCause(err)
		case p.Status == http.StatusNotFound:
			return domain.NewProblem(http.StatusNotFound, domain.CodeNotFound, "Not found", firstNonEmpty(p.Detail, "We couldn't find that.")).WithCause(err)
		case p.Status == http.StatusConflict:
			return domain.NewProblem(http.StatusConflict, domain.CodeConflict, "Can't do that now",
				firstNonEmpty(p.Detail, "This booking can't be changed any more.")).WithCause(err)
		}
	}
	return mapErr("bookings", err)
}

func policy(p port.UpBkPolicy) domain.ServicePolicy {
	return domain.ServicePolicy{Kind: p.Kind, Label: firstNonEmpty(p.Label, p.Kind), Text: p.Text}
}

func members(in []port.UpBkMember) []domain.ServiceMember {
	out := make([]domain.ServiceMember, 0, len(in))
	for _, m := range in {
		out = append(out, domain.ServiceMember{ID: m.ID, DisplayName: m.DisplayName})
	}
	return out
}

func toService(s port.UpBkService) domain.ServiceDetail {
	return domain.ServiceDetail{ID: s.ID, OrgID: s.OrgID, ProviderName: s.ProviderName, Name: s.Name, Description: s.Description,
		Category: s.Category, DurationMinutes: s.DurationMinutes, PriceMinor: s.PriceMinor, DepositMinor: s.DepositMinor,
		BalanceMinor: s.BalanceMinor, Currency: firstNonEmpty(s.Currency, domain.Currency), Modes: orEmpty(s.Modes),
		AddOns: orEmpty(s.AddOns), Policy: policy(s.Policy), InstantBooking: s.InstantBooking, Team: members(s.Team)}
}

func toBooking(b port.UpBkBooking) domain.CustomerBooking {
	st := strings.ToUpper(b.Status)
	out := domain.CustomerBooking{ID: b.ID, Reference: b.Reference, OrgID: b.OrgID, Status: st, StatusLabel: firstNonEmpty(b.StatusLabel, statusLabel(strings.ToLower(st))),
		StartAt: b.StartAt, EndAt: b.EndAt, ProposedStartAt: optStr(b.ProposedStartAt), Location: b.Location, Notes: b.Notes,
		Service: domain.BookedService{ServiceID: b.Service.ServiceID, Name: b.Service.Name, DurationMinutes: b.Service.DurationMinutes,
			PriceMinor: b.Service.PriceMinor, DepositMinor: b.Service.DepositMinor, BalanceMinor: b.Service.BalanceMinor,
			Currency: firstNonEmpty(b.Service.Currency, domain.Currency), Mode: b.Service.Mode, ProviderName: b.Service.ProviderName,
			MemberName: b.Service.MemberName, AddOns: orEmpty(b.Service.AddOns)},
		Policy:    policy(b.Policy),
		Payment:   domain.BookingPayment{State: b.Payment.State, DepositCapturedMinor: b.Payment.DepositCapturedMinor, RefundedMinor: b.Payment.RefundedMinor},
		CanCancel: cancelOK[st], CanReschedule: st == "CONFIRMED", CanReview: st == "COMPLETED", AwaitingPayment: st == "PENDING_PAYMENT",
	}
	if b.Payment.Balance != nil {
		out.Payment.BalanceState = b.Payment.Balance.State
	}
	return out
}

func toCancellationQuote(q port.UpBkQuote) domain.CancellationQuote {
	return domain.CancellationQuote{RefundSharePercent: q.RefundSharePercent, DepositMinor: q.DepositMinor, RefundMinor: q.RefundMinor,
		KeptMinor: q.KeptMinor, ReleaseHold: q.ReleaseAuthorisation, Policy: q.Policy, Explanation: q.Explanation,
		Currency: firstNonEmpty(q.Currency, domain.Currency)}
}

func (s *servicesServiceImpl) Search(ctx context.Context, c *domain.Caller, q, category, zip string) (domain.ServiceSearch, error) {
	if len(q) > 120 || len(category) > 60 {
		return domain.ServiceSearch{}, domain.ValidationProblem(map[string]string{"q": "At most 120 characters"})
	}
	city := ""
	if zip == "" && s.d.Location != nil {
		loc := s.d.Location.Current(ctx, c)
		zip, city = loc.ZIP, loc.City
	}
	if zip != "" && !zip5.MatchString(zip) {
		return domain.ServiceSearch{}, domain.FieldProblem(domain.CodeValidationFailed, "Check the ZIP code", "zip", "Enter a 5-digit US ZIP code.")
	}
	list, err := s.d.Bookings.Search(ctx, strings.TrimSpace(q), category, zip)
	if err != nil {
		return domain.ServiceSearch{}, bookingsErr(err)
	}
	out := domain.ServiceSearch{ZIP: zip, City: city, Services: make([]domain.ServiceListing, 0, len(list))}
	for _, l := range list {
		out.Services = append(out.Services, domain.ServiceListing{ServiceID: l.ServiceID, OrgID: l.OrgID, Name: l.Name, ProviderName: l.ProviderName,
			Description: l.Description, Category: l.Category, DurationMinutes: l.DurationMinutes, PriceFromMinor: l.PriceFromMinor,
			DepositMinor: l.DepositMinor, Currency: firstNonEmpty(l.Currency, domain.Currency), Modes: orEmpty(l.Modes), Rating: l.Rating,
			RatingCount: l.RatingCount, NextAvailable: optStr(l.NextAvailable), Location: l.Location})
	}
	return out, nil
}

func (s *servicesServiceImpl) Service(ctx context.Context, id string) (domain.ServiceDetail, error) {
	v, err := s.d.Bookings.Service(ctx, id)
	if err != nil {
		return domain.ServiceDetail{}, bookingsErr(err)
	}
	return toService(v), nil
}

func validSlotQuery(q port.SlotQuery) error {
	errs := map[string]string{}
	if q.Days < 0 || q.Days > 62 {
		errs["days"] = "Between 1 and 62"
	}
	if q.Mode != "" && !modes[q.Mode] {
		errs["mode"] = "Choose IN_PERSON, AT_HOME or VIRTUAL"
	}
	for _, a := range q.AddOnIDs {
		if !slotIDRe.MatchString(a) {
			errs["addOnIds"] = "Unknown add-on"
		}
	}
	if len(errs) > 0 {
		return domain.ValidationProblem(errs)
	}
	return nil
}

func (s *servicesServiceImpl) Days(ctx context.Context, id string, q port.SlotQuery) ([]domain.AvailableDay, error) {
	if err := validSlotQuery(q); err != nil {
		return nil, err
	}
	d, err := s.d.Bookings.Days(ctx, id, q)
	return orEmpty(d), wrapBk(err)
}

func (s *servicesServiceImpl) Slots(ctx context.Context, id string, q port.SlotQuery) ([]domain.AvailableSlot, error) {
	if err := validSlotQuery(q); err != nil {
		return nil, err
	}
	if q.Date == "" {
		return nil, domain.ValidationProblem(map[string]string{"date": "Choose a day"})
	}
	out, err := s.d.Bookings.Slots(ctx, id, q)
	for i := range out {
		out[i].MemberIDs = orEmpty(out[i].MemberIDs)
	}
	return orEmpty(out), wrapBk(err)
}

func (s *servicesServiceImpl) Quote(ctx context.Context, id string, q port.SlotQuery) (domain.BookingQuote, error) {
	if err := validSlotQuery(q); err != nil {
		return domain.BookingQuote{}, err
	}
	out, err := s.d.Bookings.Summary(ctx, id, q)
	out.AddOns = orEmpty(out.AddOns)
	return out, wrapBk(err)
}

// Provider is the bookings profile and services of a provider.
func (s *servicesServiceImpl) Provider(ctx context.Context, orgID string) (domain.ProviderPage, error) {
	p, err := s.d.Bookings.Provider(ctx, orgID)
	if err != nil {
		return domain.ProviderPage{}, bookingsErr(err)
	}
	out := domain.ProviderPage{OrgID: p.OrgID, DisplayName: p.DisplayName, TimeZone: p.TimeZone, Location: p.Location, Policy: policy(p.Policy),
		InstantBooking: p.InstantBooking, Team: members(p.Team), Services: make([]domain.ServiceDetail, 0, len(p.Services))}
	for _, sv := range p.Services {
		out.Services = append(out.Services, toService(sv))
	}
	return out, nil
}

func (s *servicesServiceImpl) Book(ctx context.Context, c *domain.Caller, key string, req domain.BookingRequest) (domain.BookingCreated, error) {
	if err := requireCustomer(c); err != nil {
		return domain.BookingCreated{}, err
	}
	errs := map[string]string{}
	if req.ServiceID == "" {
		errs["serviceId"] = "Required"
	}
	if _, err := time.Parse(isoLayout, req.StartAt); err != nil {
		errs["startAt"] = "Choose a time"
	}
	if !modes[req.Mode] {
		errs["mode"] = "Choose IN_PERSON, AT_HOME or VIRTUAL"
	}
	if req.Mode == "AT_HOME" && strings.TrimSpace(req.Location) == "" {
		errs["location"] = "Enter the address for the home visit"
	}
	if len(req.Notes) > 1000 {
		errs["notes"] = "At most 1000 characters"
	}
	if len(req.Location) > 300 {
		errs["location"] = "At most 300 characters"
	}
	if l := len(key); l > 0 && (l < 16 || l > 64) {
		errs["Idempotency-Key"] = "Send a 16–64 character Idempotency-Key header"
	}
	if len(errs) > 0 {
		return domain.BookingCreated{}, domain.ValidationProblem(errs)
	}
	me, err := s.d.Identity.Me(ctx, c.BearerToken)
	if err != nil {
		return domain.BookingCreated{}, mapIdentityErr(err)
	}
	body := port.UpBkCreate{ServiceID: req.ServiceID, MemberID: req.MemberID, StartAt: req.StartAt, Mode: req.Mode, AddOnIDs: req.AddOnIDs,
		Location: strings.TrimSpace(req.Location), Notes: strings.TrimSpace(req.Notes), Channel: "web"}
	if c.App {
		body.Channel = "app"
	}
	body.Customer.Name = strings.TrimSpace(me.FirstName + " " + me.LastName)
	body.Customer.Email, body.Customer.Phone = me.Email, me.Phone
	if body.Customer.Name == "" {
		body.Customer.Name = firstNonEmpty(me.Email, "AfroTransact customer")
	}
	if key == "" {
		key = derivedKey(c, s.d.Clock.now(), "book", req.ServiceID, req.StartAt)
	}
	b, err := s.d.Bookings.Create(ctx, c.BearerToken, key, body)
	if err != nil {
		return domain.BookingCreated{}, bookingsErr(err)
	}
	out := domain.BookingCreated{Booking: toBooking(b)}
	if b.Payment.ClientSecret != "" {
		out.Payment = &domain.DepositPayment{ClientSecret: b.Payment.ClientSecret, PublishableKey: s.d.PublishableKey}
	}
	return out, nil
}

func (s *servicesServiceImpl) Mine(ctx context.Context, c *domain.Caller) ([]domain.CustomerBooking, error) {
	if err := requireCustomer(c); err != nil {
		return nil, err
	}
	list, err := s.d.Bookings.Mine(ctx, c.BearerToken)
	if err != nil {
		return nil, bookingsErr(err)
	}
	out := make([]domain.CustomerBooking, 0, len(list))
	for _, b := range list {
		out = append(out, toBooking(b))
	}
	return out, nil
}

func (s *servicesServiceImpl) Booking(ctx context.Context, c *domain.Caller, id string) (domain.CustomerBooking, error) {
	if err := requireCustomer(c); err != nil {
		return domain.CustomerBooking{}, err
	}
	b, err := s.d.Bookings.Get(ctx, c.BearerToken, id)
	return toBooking(b), wrapBk(err)
}

func (s *servicesServiceImpl) CancellationQuote(ctx context.Context, c *domain.Caller, id string) (domain.CancellationQuote, error) {
	if err := requireCustomer(c); err != nil {
		return domain.CancellationQuote{}, err
	}
	q, err := s.d.Bookings.Quote(ctx, c.BearerToken, id)
	return toCancellationQuote(q), wrapBk(err)
}

func (s *servicesServiceImpl) Cancel(ctx context.Context, c *domain.Caller, key, id, reason string) (domain.BookingCancelled, error) {
	if err := requireCustomer(c); err != nil {
		return domain.BookingCancelled{}, err
	}
	if len(reason) > 500 {
		return domain.BookingCancelled{}, domain.ValidationProblem(map[string]string{"reason": "At most 500 characters"})
	}
	if key == "" {
		key = derivedKey(c, s.d.Clock.now(), "cancel", id)
	}
	b, q, err := s.d.Bookings.Cancel(ctx, c.BearerToken, key, id, strings.TrimSpace(reason))
	if err != nil {
		return domain.BookingCancelled{}, bookingsErr(err)
	}
	return domain.BookingCancelled{Booking: toBooking(b), Applied: toCancellationQuote(q)}, nil
}

func (s *servicesServiceImpl) Reschedule(ctx context.Context, c *domain.Caller, key, id, startAt string) (domain.CustomerBooking, error) {
	if err := requireCustomer(c); err != nil {
		return domain.CustomerBooking{}, err
	}
	if _, err := time.Parse(isoLayout, startAt); err != nil {
		return domain.CustomerBooking{}, domain.ValidationProblem(map[string]string{"startAt": "Choose a new time"})
	}
	if key == "" {
		key = derivedKey(c, s.d.Clock.now(), "reschedule", id, startAt)
	}
	b, err := s.d.Bookings.Reschedule(ctx, c.BearerToken, key, id, startAt)
	return toBooking(b), wrapBk(err)
}

func (s *servicesServiceImpl) AnswerNewTime(ctx context.Context, c *domain.Caller, key, id string, accept bool) (domain.CustomerBooking, error) {
	if err := requireCustomer(c); err != nil {
		return domain.CustomerBooking{}, err
	}
	if key == "" {
		key = derivedKey(c, s.d.Clock.now(), "new-time", id, map[bool]string{true: "accept", false: "decline"}[accept])
	}
	b, err := s.d.Bookings.NewTime(ctx, c.BearerToken, key, id, accept)
	return toBooking(b), wrapBk(err)
}

func (s *servicesServiceImpl) Review(ctx context.Context, c *domain.Caller, id string, rating int, text string) (domain.BookingReview, error) {
	if err := requireCustomer(c); err != nil {
		return domain.BookingReview{}, err
	}
	errs := map[string]string{}
	if rating < 1 || rating > 5 {
		errs["rating"] = "Choose 1 to 5 stars"
	}
	if len(text) > 2000 {
		errs["text"] = "At most 2000 characters"
	}
	if len(errs) > 0 {
		return domain.BookingReview{}, domain.ValidationProblem(errs)
	}
	r, err := s.d.Bookings.Review(ctx, c.BearerToken, id, rating, strings.TrimSpace(text))
	if r.BookingID == "" {
		r.BookingID = id
	}
	return r, wrapBk(err)
}

func (s *servicesServiceImpl) SyncPayment(ctx context.Context, c *domain.Caller, id string) (domain.CustomerBooking, error) {
	if err := requireCustomer(c); err != nil {
		return domain.CustomerBooking{}, err
	}
	b, err := s.d.Bookings.SyncPayment(ctx, c.BearerToken, id)
	return toBooking(b), wrapBk(err)
}

func wrapBk(err error) error {
	if err == nil {
		return nil
	}
	return bookingsErr(err)
}

// ---------------------------------------------------------------- Lola

// LolaService relays Lola turns (ai-gateway SSE) with the shopper's token and deliver-to ZIP.
type LolaService interface {
	Chat(ctx context.Context, c *domain.Caller, req domain.LolaChatRequest) (io.ReadCloser, error)
	History(ctx context.Context, c *domain.Caller, sessionID string) ([]domain.LolaTurn, error)
}

type lolaServiceImpl struct{ d ShopperDeps }

var _ LolaService = (*lolaServiceImpl)(nil)

// NewLolaService returns the LolaService.
func NewLolaService(d ShopperDeps) LolaService { return &lolaServiceImpl{d: d} }

var lolaSessionRe = regexp.MustCompile(`^[A-Za-z0-9_-]{8,64}$`)

func lolaErr(err error) error {
	if p, ok := port.AsUpstreamProblem(err); ok && p.Status == http.StatusTooManyRequests {
		pr := domain.NewProblem(http.StatusTooManyRequests, domain.CodeRateLimited, "Lola needs a break",
			firstNonEmpty(p.Detail, "Lola has answered a lot today. Please try again later."))
		pr.RetryAfter = max(1, p.RetryAfter)
		return pr.WithCause(err)
	}
	return mapErr("ai-gateway", err)
}

func (s *lolaServiceImpl) Chat(ctx context.Context, c *domain.Caller, req domain.LolaChatRequest) (io.ReadCloser, error) {
	if err := requireCustomer(c); err != nil {
		return nil, err
	}
	req.Message = strings.TrimSpace(req.Message)
	errs := map[string]string{}
	if req.Message == "" || len([]rune(req.Message)) > 4000 {
		errs["message"] = "Type a message (up to 4000 characters)"
	}
	if req.SessionID != "" && !lolaSessionRe.MatchString(req.SessionID) {
		errs["sessionId"] = "Unknown conversation"
	}
	if req.ZIP != "" && !zip5.MatchString(req.ZIP) {
		errs["zip"] = "Enter a 5-digit US ZIP code."
	}
	if len(errs) > 0 {
		return nil, domain.ValidationProblem(errs)
	}
	if req.ZIP == "" && s.d.Location != nil {
		req.ZIP = s.d.Location.Current(ctx, c).ZIP
	}
	if req.Language == "" && c.Language != "" {
		req.Language = strings.TrimSpace(strings.Split(strings.Split(c.Language, ",")[0], ";")[0])
		if len(req.Language) > 16 {
			req.Language = ""
		}
	}
	stream, err := s.d.Lola.Chat(ctx, c.BearerToken, req)
	if err != nil {
		return nil, lolaErr(err)
	}
	return stream, nil
}

func (s *lolaServiceImpl) History(ctx context.Context, c *domain.Caller, sessionID string) ([]domain.LolaTurn, error) {
	if err := requireCustomer(c); err != nil {
		return nil, err
	}
	if !lolaSessionRe.MatchString(sessionID) {
		return nil, domain.NewProblem(http.StatusNotFound, domain.CodeNotFound, "Not found", "That conversation doesn't exist.")
	}
	turns, err := s.d.Lola.History(ctx, c.BearerToken, sessionID)
	if err != nil {
		return nil, lolaErr(err)
	}
	return orEmpty(turns), nil
}
