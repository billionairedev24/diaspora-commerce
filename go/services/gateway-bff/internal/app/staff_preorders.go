package app

import (
	"context"
	"net/http"
	"net/url"
	"slices"
	"strings"
	"sync"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

// StaffPreordersService is the staff console's Pre-orders module (contracts/openapi/gateway-bff-staff.yaml, tag
// Pre-orders; S270–S275). It mirrors delivery-runs' staff API 1:1 with the staff member's token: windows (list, CRUD,
// publish, pause, resume), creatives, orders, summary, CSV exports (streamed), late orders, platform settings
// (including the payment setup), coverage areas and place search. The BFF checks the module level first:
// View to read, Edit (preorders:manage) to change windows and areas, Full to change settings, preorders:export to
// export, Approve/Edit/Full with preorders:late-add to add late orders. delivery-runs checks its permissions again.
type StaffPreordersService interface {
	List(ctx context.Context, c *domain.Caller, q url.Values) (any, error)
	Create(ctx context.Context, c *domain.Caller, body map[string]any, idem string) (any, error)
	Get(ctx context.Context, c *domain.Caller, id string) (any, error)
	Update(ctx context.Context, c *domain.Caller, id, ifMatch string, body map[string]any) (any, error)
	// Act runs publish, pause or resume.
	Act(ctx context.Context, c *domain.Caller, id, action string) (any, error)
	PutCreative(ctx context.Context, c *domain.Caller, id, slot string, body map[string]any) (any, error)
	DeleteCreative(ctx context.Context, c *domain.Caller, id, slot string) (any, error)
	Orders(ctx context.Context, c *domain.Caller, id string, q url.Values) (any, error)
	Summary(ctx context.Context, c *domain.Caller, id string) (any, error)
	// Export streams one of the window's CSV files (orders, orders-filtered, picking-list, advertorial-report).
	Export(ctx context.Context, c *domain.Caller, id, file string, q url.Values) (port.StaffStream, error)
	LateOrder(ctx context.Context, c *domain.Caller, id string, body map[string]any, idem string) (any, error)
	Settings(ctx context.Context, c *domain.Caller) (any, error)
	UpdateSettings(ctx context.Context, c *domain.Caller, ifMatch string, body map[string]any) (any, error)
	Areas(ctx context.Context, c *domain.Caller, q url.Values) (any, error)
	AddArea(ctx context.Context, c *domain.Caller, body map[string]any) (any, error)
	ToggleAreas(ctx context.Context, c *domain.Caller, body map[string]any) (any, error)
	DeleteArea(ctx context.Context, c *domain.Caller, key string) error
	Places(ctx context.Context, c *domain.Caller, q string) (any, error)
	// Customers is the late-add customer picker: identity's people search with each customer's saved addresses.
	Customers(ctx context.Context, c *domain.Caller, q string) (domain.LateAddCustomers, error)
}

type staffPreordersImpl struct{ r *staffResolver }

var _ StaffPreordersService = (*staffPreordersImpl)(nil)

// NewStaffPreordersService returns the Pre-orders module use case.
func NewStaffPreordersService(d StaffDeps) StaffPreordersService {
	return &staffPreordersImpl{r: newStaffResolver(d)}
}

// Abilities a pre-order route needs.
const (
	poView     = "view"
	poManage   = "manage"
	poSettings = "settings"
	poExport   = "export"
	poLateAdd  = "lateAdd"
)

const (
	preordersPath      = "/v1/preorders"
	preorderSettings   = "/v1/preorder-settings"
	preorderAreas      = "/v1/preorder-areas"
	customerSearchSize = "10"
)

// need resolves the staff member and checks the ability.
func (s *staffPreordersImpl) need(ctx context.Context, c *domain.Caller, ability string) (staffCtx, domain.PreorderCan, error) {
	sc, err := s.r.require(ctx, c, domain.PreorderModule, domain.LevelView)
	if err != nil {
		return sc, domain.PreorderCan{}, err
	}
	can := domain.PreorderPermissions(sc.access)
	switch ability {
	case poManage:
		if !can.Manage {
			return sc, can, denied(sc.access, domain.LevelEdit, domain.PermPreordersManage)
		}
	case poSettings:
		if !can.Settings {
			return sc, can, denied(sc.access, domain.LevelFull, domain.PermPreordersManage)
		}
	case poExport:
		if !can.Export {
			return sc, can, needsPermission(domain.PermPreordersExport)
		}
	case poLateAdd:
		if !can.LateAdd {
			return sc, can, denied(sc.access, domain.LevelEdit, domain.PermPreordersLateAdd)
		}
	}
	if sc.up.DeliveryRuns == nil {
		return sc, can, domain.UpstreamUnavailable("delivery-runs")
	}
	return sc, can, nil
}

// denied names what is missing: the module level or the permission.
func denied(a domain.StaffAccess, level, permission string) error {
	if !a.Allows(domain.PreorderModule, level) {
		return staffModuleDenied(domain.PreorderModule, level)
	}
	return needsPermission(permission)
}

// withCan relays an upstream object with the staff member's abilities; arrays become {items}.
func withCan(v any, can domain.PreorderCan) any {
	switch t := v.(type) {
	case map[string]any:
		t["can"] = can
		return t
	case []any:
		return map[string]any{"items": t, "can": can}
	case nil:
		return map[string]any{"can": can}
	default:
		return v
	}
}

// items relays an upstream list (array or page object) as an object.
func items(v any) any {
	switch t := v.(type) {
	case []any:
		return map[string]any{"items": t}
	case nil:
		return map[string]any{"items": []any{}}
	default:
		return v
	}
}

// passQuery copies the allowed query parameters (each at most 120 characters).
func passQuery(in url.Values, keys ...string) (url.Values, error) {
	out := url.Values{}
	for _, k := range keys {
		v := strings.TrimSpace(in.Get(k))
		if v == "" {
			continue
		}
		if len(v) > 120 {
			return nil, domain.ValidationProblem(map[string]string{k: "Too long"})
		}
		out.Set(k, v)
	}
	return out, nil
}

func windowPath(id string, rest ...string) string {
	return preordersPath + "/" + url.PathEscape(id) + strings.Join(rest, "")
}

func (s staffCtx) send(ctx context.Context, method, path, ifMatch string, body any) (any, error) {
	var out any
	err := s.call(ctx, s.up.DeliveryRuns, port.StaffCall{Method: method, Path: path, Body: body, IdempotencyKey: s.idem, IfMatch: ifMatch}, &out)
	return out, err
}

func (s *staffPreordersImpl) List(ctx context.Context, c *domain.Caller, q url.Values) (any, error) {
	pq, err := passQuery(q, "status", "q", "page", "size")
	if err != nil {
		return nil, err
	}
	sc, can, err := s.need(ctx, c, poView)
	if err != nil {
		return nil, err
	}
	var out any
	if err := sc.get(ctx, sc.up.DeliveryRuns, preordersPath, pq, &out); err != nil {
		return nil, err
	}
	return withCan(out, can), nil
}

func (s *staffPreordersImpl) Create(ctx context.Context, c *domain.Caller, body map[string]any, idem string) (any, error) {
	sc, can, err := s.need(ctx, c, poManage)
	if err != nil {
		return nil, err
	}
	sc.idem = idem
	out, err := sc.send(ctx, http.MethodPost, preordersPath, "", body)
	if err != nil {
		return nil, err
	}
	return withCan(out, can), nil
}

func (s *staffPreordersImpl) Get(ctx context.Context, c *domain.Caller, id string) (any, error) {
	if p := domain.ValidPreorderID(id); p != nil {
		return nil, p
	}
	sc, can, err := s.need(ctx, c, poView)
	if err != nil {
		return nil, err
	}
	var out any
	if err := sc.get(ctx, sc.up.DeliveryRuns, windowPath(id), nil, &out); err != nil {
		return nil, err
	}
	return withCan(out, can), nil
}

func (s *staffPreordersImpl) Update(ctx context.Context, c *domain.Caller, id, ifMatch string, body map[string]any) (any, error) {
	if p := domain.ValidPreorderID(id); p != nil {
		return nil, p
	}
	sc, can, err := s.need(ctx, c, poManage)
	if err != nil {
		return nil, err
	}
	out, err := sc.send(ctx, http.MethodPut, windowPath(id), ifMatch, body)
	if err != nil {
		return nil, err
	}
	return withCan(out, can), nil
}

// preorderActions are the lifecycle actions of a window.
var preorderActions = []string{"publish", "pause", "resume"}

func (s *staffPreordersImpl) Act(ctx context.Context, c *domain.Caller, id, action string) (any, error) {
	if p := domain.ValidPreorderID(id); p != nil {
		return nil, p
	}
	if !slices.Contains(preorderActions, action) {
		return nil, domain.NewProblem(http.StatusNotFound, domain.CodeNotFound, "Not found", "No such action.")
	}
	sc, can, err := s.need(ctx, c, poManage)
	if err != nil {
		return nil, err
	}
	out, err := sc.send(ctx, http.MethodPost, windowPath(id, "/", action), "", nil)
	if err != nil {
		return nil, err
	}
	return withCan(out, can), nil
}

func creativeSlot(slot string) (string, error) {
	s := strings.ToUpper(slot)
	if !slices.Contains(domain.PreorderSlots, s) {
		return "", domain.NewProblem(http.StatusNotFound, domain.CodeNotFound, "Not found", "No such creative slot.")
	}
	return s, nil
}

func (s *staffPreordersImpl) PutCreative(ctx context.Context, c *domain.Caller, id, slot string, body map[string]any) (any, error) {
	if p := domain.ValidPreorderID(id); p != nil {
		return nil, p
	}
	sl, err := creativeSlot(slot)
	if err != nil {
		return nil, err
	}
	asset, _ := body["assetId"].(string)
	if a := strings.TrimSpace(asset); a == "" || len(a) > 64 {
		return nil, domain.ValidationProblem(map[string]string{"assetId": "Upload the creative first"})
	}
	sc, can, err := s.need(ctx, c, poManage)
	if err != nil {
		return nil, err
	}
	out, err := sc.send(ctx, http.MethodPut, windowPath(id, "/creatives/", sl), "", map[string]any{"assetId": strings.TrimSpace(asset)})
	if err != nil {
		return nil, err
	}
	return withCan(out, can), nil
}

func (s *staffPreordersImpl) DeleteCreative(ctx context.Context, c *domain.Caller, id, slot string) (any, error) {
	if p := domain.ValidPreorderID(id); p != nil {
		return nil, p
	}
	sl, err := creativeSlot(slot)
	if err != nil {
		return nil, err
	}
	sc, can, err := s.need(ctx, c, poManage)
	if err != nil {
		return nil, err
	}
	out, err := sc.send(ctx, http.MethodDelete, windowPath(id, "/creatives/", sl), "", nil)
	if err != nil {
		return nil, err
	}
	return withCan(out, can), nil
}

func (s *staffPreordersImpl) Orders(ctx context.Context, c *domain.Caller, id string, q url.Values) (any, error) {
	if p := domain.ValidPreorderID(id); p != nil {
		return nil, p
	}
	pq, err := passQuery(q, "status", "channel", "q", "page", "size")
	if err != nil {
		return nil, err
	}
	sc, _, err := s.need(ctx, c, poView)
	if err != nil {
		return nil, err
	}
	var out any
	if err := sc.get(ctx, sc.up.DeliveryRuns, windowPath(id, "/orders"), pq, &out); err != nil {
		return nil, err
	}
	return items(out), nil
}

func (s *staffPreordersImpl) Summary(ctx context.Context, c *domain.Caller, id string) (any, error) {
	if p := domain.ValidPreorderID(id); p != nil {
		return nil, p
	}
	sc, _, err := s.need(ctx, c, poView)
	if err != nil {
		return nil, err
	}
	var out any
	if err := sc.get(ctx, sc.up.DeliveryRuns, windowPath(id, "/summary"), nil, &out); err != nil {
		return nil, err
	}
	if out == nil {
		out = map[string]any{}
	}
	return out, nil
}

func (s *staffPreordersImpl) Export(ctx context.Context, c *domain.Caller, id, file string, q url.Values) (port.StaffStream, error) {
	if p := domain.ValidPreorderID(id); p != nil {
		return port.StaffStream{}, p
	}
	kind, ok := strings.CutSuffix(file, ".csv")
	if !ok || !slices.Contains(domain.PreorderExports, kind) {
		return port.StaffStream{}, domain.NewProblem(http.StatusNotFound, domain.CodeNotFound, "Not found", "No such export.")
	}
	var pq url.Values
	if kind == "orders-filtered" {
		var err error
		if pq, err = passQuery(q, "status", "channel", "q"); err != nil {
			return port.StaffStream{}, err
		}
	}
	sc, _, err := s.need(ctx, c, poExport)
	if err != nil {
		return port.StaffStream{}, err
	}
	st, ok := sc.up.DeliveryRuns.(port.StaffStreamer)
	if !ok {
		return port.StaffStream{}, domain.UpstreamUnavailable("delivery-runs")
	}
	out, err := st.Stream(ctx, sc.bearer, port.StaffCall{Path: windowPath(id, "/exports/", file), Query: pq})
	if err != nil {
		return port.StaffStream{}, mapErr(sc.up.DeliveryRuns.Name(), err)
	}
	if out.ContentType == "" {
		out.ContentType = "text/csv; charset=utf-8"
	}
	if out.ContentDisposition == "" {
		out.ContentDisposition = `attachment; filename="preorder-` + id + "-" + file + `"`
	}
	return out, nil
}

// latePayments are the late-add payment choices.
var latePayments = []string{"SAVED_CARD", "PAYMENT_LINK"}

func (s *staffPreordersImpl) LateOrder(ctx context.Context, c *domain.Caller, id string, body map[string]any, idem string) (any, error) {
	if p := domain.ValidPreorderID(id); p != nil {
		return nil, p
	}
	errs := map[string]string{}
	if cid, _ := body["customerId"].(string); strings.TrimSpace(cid) == "" {
		errs["customerId"] = "Choose the customer"
	}
	if ok, _ := body["phoneConfirmed"].(bool); !ok {
		errs["phoneConfirmed"] = "Confirm the phone number with the customer"
	}
	if lines, _ := body["items"].([]any); len(lines) == 0 {
		errs["items"] = "Add at least one item"
	}
	if p, _ := body["payment"].(string); !slices.Contains(latePayments, p) {
		errs["payment"] = "Charge the saved card or send a payment link"
	}
	if r, _ := body["reason"].(string); strings.TrimSpace(r) == "" || len(r) > 120 {
		errs["reason"] = "Say why the order is late"
	}
	_, hasAddr := body["addressId"].(string)
	_, hasNew := body["newAddress"].(map[string]any)
	if !hasAddr && !hasNew {
		errs["addressId"] = "Choose a saved address or enter a new one"
	}
	if len(errs) > 0 {
		return nil, domain.ValidationProblem(errs)
	}
	sc, _, err := s.need(ctx, c, poLateAdd)
	if err != nil {
		return nil, err
	}
	sc.idem = idem
	return sc.send(ctx, http.MethodPost, windowPath(id, "/late-orders"), "", body)
}

func (s *staffPreordersImpl) Settings(ctx context.Context, c *domain.Caller) (any, error) {
	sc, can, err := s.need(ctx, c, poView)
	if err != nil {
		return nil, err
	}
	var out any
	if err := sc.get(ctx, sc.up.DeliveryRuns, preorderSettings, nil, &out); err != nil {
		return nil, err
	}
	return withCan(out, can), nil
}

func (s *staffPreordersImpl) UpdateSettings(ctx context.Context, c *domain.Caller, ifMatch string, body map[string]any) (any, error) {
	sc, can, err := s.need(ctx, c, poSettings)
	if err != nil {
		return nil, err
	}
	out, err := sc.send(ctx, http.MethodPut, preorderSettings, ifMatch, body)
	if err != nil {
		return nil, err
	}
	return withCan(out, can), nil
}

func (s *staffPreordersImpl) Areas(ctx context.Context, c *domain.Caller, q url.Values) (any, error) {
	pq, err := passQuery(q, "country", "region", "q", "page", "size")
	if err != nil {
		return nil, err
	}
	sc, can, err := s.need(ctx, c, poView)
	if err != nil {
		return nil, err
	}
	var out any
	if err := sc.get(ctx, sc.up.DeliveryRuns, preorderAreas, pq, &out); err != nil {
		return nil, err
	}
	return withCan(out, can), nil
}

func areaKeyOf(body map[string]any) (string, error) {
	key, _ := body["key"].(string)
	key = strings.TrimSpace(key)
	if !domain.ValidAreaKey(key) {
		return "", domain.ValidationProblem(map[string]string{"key": "Use country/region or country/region/city, e.g. US/TX/Lubbock"})
	}
	return key, nil
}

func (s *staffPreordersImpl) AddArea(ctx context.Context, c *domain.Caller, body map[string]any) (any, error) {
	key, err := areaKeyOf(body)
	if err != nil {
		return nil, err
	}
	sc, can, err := s.need(ctx, c, poManage)
	if err != nil {
		return nil, err
	}
	out, err := sc.send(ctx, http.MethodPost, preorderAreas, "", map[string]any{"key": key})
	if err != nil {
		return nil, err
	}
	return withCan(out, can), nil
}

func (s *staffPreordersImpl) ToggleAreas(ctx context.Context, c *domain.Caller, body map[string]any) (any, error) {
	raw, _ := body["keys"].([]any)
	on, okOn := body["on"].(bool)
	keys := make([]string, 0, len(raw))
	for _, k := range raw {
		ks, _ := k.(string)
		if !domain.ValidAreaKey(ks) {
			return nil, domain.ValidationProblem(map[string]string{"keys": "Use country/region or country/region/city keys"})
		}
		keys = append(keys, ks)
	}
	if len(keys) == 0 || len(keys) > 500 || !okOn {
		return nil, domain.ValidationProblem(map[string]string{"keys": "Choose 1 to 500 areas and on or off"})
	}
	sc, can, err := s.need(ctx, c, poManage)
	if err != nil {
		return nil, err
	}
	out, err := sc.send(ctx, http.MethodPatch, preorderAreas, "", map[string]any{"keys": keys, "on": on})
	if err != nil {
		return nil, err
	}
	return withCan(out, can), nil
}

func (s *staffPreordersImpl) DeleteArea(ctx context.Context, c *domain.Caller, key string) error {
	if !domain.ValidAreaKey(key) {
		return domain.ValidationProblem(map[string]string{"key": "Use country/region or country/region/city, e.g. US/TX/Lubbock"})
	}
	sc, _, err := s.need(ctx, c, poManage)
	if err != nil {
		return err
	}
	_, err = sc.send(ctx, http.MethodDelete, preorderAreas+"/"+url.PathEscape(key), "", nil)
	return err
}

func (s *staffPreordersImpl) Places(ctx context.Context, c *domain.Caller, q string) (any, error) {
	q = strings.TrimSpace(q)
	if len([]rune(q)) < 2 || len(q) > 80 {
		return nil, domain.ValidationProblem(map[string]string{"q": "Type 2 to 80 characters"})
	}
	sc, _, err := s.need(ctx, c, poView)
	if err != nil {
		return nil, err
	}
	var out any
	if err := sc.get(ctx, sc.up.DeliveryRuns, "/v1/places/search", url.Values{"q": {q}}, &out); err != nil {
		return nil, err
	}
	return items(out), nil
}

func (s *staffPreordersImpl) Customers(ctx context.Context, c *domain.Caller, q string) (domain.LateAddCustomers, error) {
	q = strings.TrimSpace(q)
	if len([]rune(q)) < 2 || len(q) > 120 {
		return domain.LateAddCustomers{}, domain.ValidationProblem(map[string]string{"q": "Type 2 to 120 characters"})
	}
	sc, _, err := s.need(ctx, c, poLateAdd)
	if err != nil {
		return domain.LateAddCustomers{}, err
	}
	var page jobj
	if err := sc.get(ctx, sc.up.Identity, "/v1/admin/users", url.Values{"q": {q}, "size": {customerSearchSize}}, &page); err != nil {
		return domain.LateAddCustomers{}, err
	}
	out := domain.LateAddCustomers{Items: []domain.LateAddCustomer{}}
	for _, u := range objs(page, "items") {
		if pt := jstr(u, "principalType"); pt != "" && !strings.EqualFold(pt, "CUSTOMER") {
			continue
		}
		out.Items = append(out.Items, domain.LateAddCustomer{ID: jstr(u, "id"), Name: jstr(u, "name"), Email: jstr(u, "email"),
			Phone: strPtr(firstNonEmpty(jstr(u, "phone"), jstr(u, "phoneNumber"))), Enabled: boolean(u, "enabled"),
			Addresses: []domain.LateAddAddress{}})
	}
	var wg sync.WaitGroup
	for i := range out.Items {
		wg.Add(1)
		go func() {
			defer wg.Done()
			out.Items[i].Addresses, out.Items[i].AddressesUnavailable = s.addresses(ctx, sc, out.Items[i].ID)
		}()
	}
	wg.Wait()
	return out, nil
}

// addresses reads a customer's saved addresses from identity (GET /v1/admin/users/{id}/addresses, users:read);
// best effort: unavailable gives none and the flag.
func (s *staffPreordersImpl) addresses(ctx context.Context, sc staffCtx, userID string) ([]domain.LateAddAddress, bool) {
	out := []domain.LateAddAddress{}
	if userID == "" {
		return out, true
	}
	var raw any
	if err := sc.get(ctx, sc.up.Identity, "/v1/admin/users/"+url.PathEscape(userID)+"/addresses", nil, &raw); err != nil {
		return out, true
	}
	var list []jobj
	switch t := raw.(type) {
	case []any:
		for _, x := range t {
			if m, ok := x.(map[string]any); ok {
				list = append(list, m)
			}
		}
	case map[string]any:
		list = objs(t, "items")
	}
	for _, a := range list {
		out = append(out, domain.LateAddAddress{ID: jstr(a, "id"), Label: strPtr(jstr(a, "label")), Line1: jstr(a, "line1"),
			Line2: strPtr(jstr(a, "line2")), City: jstr(a, "city"), Region: firstNonEmpty(jstr(a, "state"), jstr(a, "region")),
			PostalCode: firstNonEmpty(jstr(a, "postalCode"), jstr(a, "zip")), Country: firstNonEmpty(jstr(a, "country"), "US"),
			Default: boolean(a, "default") || boolean(a, "isDefault")})
	}
	return out, false
}
