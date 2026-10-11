package app

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"net/http"
	"net/url"
	"slices"
	"strconv"
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
	// SetActive sets one window's own on/off switch ({active}); the master switch is never touched.
	SetActive(ctx context.Context, c *domain.Caller, id, ifMatch string, body map[string]any) (any, error)
	// SetActiveMany switches several windows ({ids, active}), each on its own.
	SetActiveMany(ctx context.Context, c *domain.Caller, body map[string]any) (any, error)
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
	DeleteArea(ctx context.Context, c *domain.Caller, key string) (any, error)
	// Region is one region's coverage for the in-place editor (ETag = its version).
	Region(ctx context.Context, c *domain.Caller, country, region string) (any, error)
	// UpdateRegion replaces one region's "Every city" row and cities ({everyCity, cities[{city, on}]}, If-Match).
	UpdateRegion(ctx context.Context, c *domain.Caller, country, region, ifMatch string, body map[string]any) (any, error)
	Places(ctx context.Context, c *domain.Caller, q url.Values) (any, error)
	// Products are the window's products (the late-add item list).
	Products(ctx context.Context, c *domain.Caller, id string) (any, error)
	// VerifyAddress checks a new late-add address with geo.
	VerifyAddress(ctx context.Context, c *domain.Caller, a PreorderAddressInput) (domain.AddressVerification, error)
	// RequestUpload signs a direct media upload for a creative.
	RequestUpload(ctx context.Context, c *domain.Caller, in PreorderUploadInput) (domain.PreorderUploadTicket, error)
	// Customers is the late-add customer picker: identity's people search with each customer's saved addresses.
	Customers(ctx context.Context, c *domain.Caller, q, windowID string) (domain.LateAddCustomers, error)
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

// maxBulkSwitch is how many windows one bulk switch may name (delivery-runs' limit).
const maxBulkSwitch = 100

func switchOf(body map[string]any) (bool, error) {
	on, ok := body["active"].(bool)
	if !ok {
		return false, domain.ValidationProblem(map[string]string{"active": "Send true (on) or false (off)"})
	}
	return on, nil
}

func (s *staffPreordersImpl) SetActive(ctx context.Context, c *domain.Caller, id, ifMatch string, body map[string]any) (any, error) {
	if p := domain.ValidPreorderID(id); p != nil {
		return nil, p
	}
	on, err := switchOf(body)
	if err != nil {
		return nil, err
	}
	sc, can, err := s.need(ctx, c, poManage)
	if err != nil {
		return nil, err
	}
	out, err := sc.send(ctx, http.MethodPatch, windowPath(id), ifMatch, map[string]any{"active": on})
	if err != nil {
		return nil, err
	}
	return withCan(out, can), nil
}

func (s *staffPreordersImpl) SetActiveMany(ctx context.Context, c *domain.Caller, body map[string]any) (any, error) {
	on, err := switchOf(body)
	if err != nil {
		return nil, err
	}
	raw, _ := body["ids"].([]any)
	ids := make([]string, 0, len(raw))
	for _, v := range raw {
		id, _ := v.(string)
		if domain.ValidPreorderID(id) != nil {
			return nil, domain.ValidationProblem(map[string]string{"ids": "Unknown pre-order id"})
		}
		ids = append(ids, id)
	}
	if len(ids) == 0 || len(ids) > maxBulkSwitch {
		return nil, domain.ValidationProblem(map[string]string{"ids": "Choose 1 to 100 pre-orders"})
	}
	sc, can, err := s.need(ctx, c, poManage)
	if err != nil {
		return nil, err
	}
	out, err := sc.send(ctx, http.MethodPatch, preordersPath, "", map[string]any{"ids": ids, "active": on})
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
	if sc.up.Media != nil {
		// Finalise the direct upload (media detects type and size); already-complete assets answer an error we ignore,
		// delivery-runs verifies the asset either way.
		_ = sc.call(ctx, sc.up.Media, port.StaffCall{Method: http.MethodPost, Path: "/v1/assets/" + url.PathEscape(strings.TrimSpace(asset)) + "/complete"}, nil)
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
	sort, bad := domain.ParseSort(q["sort"], preorderOrderColumn)
	if bad != nil {
		return nil, bad
	}
	sc, _, err := s.need(ctx, c, poView)
	if err != nil {
		return nil, err
	}
	search := strings.TrimSpace(q.Get("q"))
	if search == "" && len(sort) == 0 {
		var out any
		if err := sc.get(ctx, sc.up.DeliveryRuns, windowPath(id, "/orders"), pq, &out); err != nil {
			return nil, err
		}
		return items(out), nil
	}
	// delivery-runs searches order, customer, phone, address and ZIP only and sorts newest first: to match every
	// column the table shows and sort by any of them, the BFF reads the window's orders and pages them itself.
	objs, rows, partial, err := scanPreorderOrders(ctx, sc, id, pq.Get("status"), pq.Get("channel"), domain.TableScanMaxRows)
	if err != nil {
		return nil, err
	}
	rows = domain.FilterRows(rows, PreorderOrderColumns, domain.SearchTerms(search), nil)
	domain.SortRows(rows, PreorderOrderColumns, sort)
	page, _ := strconv.Atoi(pq.Get("page"))
	size, _ := strconv.Atoi(pq.Get("size"))
	if size <= 0 || size > 100 {
		size = 25
	}
	pageRows, _ := domain.PageRows(rows, page, size)
	out := make([]any, 0, len(pageRows))
	for _, r := range pageRows {
		out = append(out, objs[r.ID])
	}
	return map[string]any{"items": out, "total": len(rows), "page": page, "size": size, "sort": domain.SortStrings(sort), "partial": partial}, nil
}

// PreorderOrderColumns are what the pre-order orders table shows (and what its search, sort and export see).
var PreorderOrderColumns = []domain.Column{
	{Key: "number", Label: "Order", Kind: "mono"}, {Key: "customerName", Label: "Customer", Kind: "strong"}, {Key: "phone", Label: "Phone", Kind: "text"},
	{Key: "email", Label: "Email", Kind: "text"}, {Key: "addressLine", Label: "Address", Kind: "text"}, {Key: "city", Label: "City", Kind: "text"},
	{Key: "zip", Label: "ZIP", Kind: "mono"}, {Key: "deliveryNote", Label: "Delivery note", Kind: "text"}, {Key: "placedAt", Label: "Placed", Kind: "datetime"},
	{Key: "channel", Label: "Channel", Kind: "status"}, {Key: "trackingTag", Label: "Tracking tag", Kind: "mono"}, {Key: "units", Label: "Units", Kind: "number"},
	{Key: "fulfilment", Label: "Fulfil", Kind: "status"}, {Key: "total", Label: "Total", Kind: "money"}, {Key: "status", Label: "Status", Kind: "status"},
}

func preorderOrderColumn(k string) bool {
	return slices.ContainsFunc(PreorderOrderColumns, func(c domain.Column) bool { return c.Key == k })
}

// scanPreorderOrders reads up to limit orders of a window (status and channel narrow them upstream).
func scanPreorderOrders(ctx context.Context, sc staffCtx, id, status, channel string, limit int) (map[string]jobj, []domain.Row, bool, error) {
	objsByID := map[string]jobj{}
	rows := []domain.Row{}
	for page := 0; ; page++ {
		v := url.Values{"page": {strconv.Itoa(page)}, "size": {"100"}}
		if status != "" {
			v.Set("status", status)
		}
		if channel != "" {
			v.Set("channel", channel)
		}
		var raw any
		if err := sc.get(ctx, sc.up.DeliveryRuns, windowPath(id, "/orders"), v, &raw); err != nil {
			return nil, nil, false, err
		}
		list := jpage(raw)
		for _, o := range list.items {
			if len(rows) == limit {
				return objsByID, rows, true, nil
			}
			oid := firstNonEmpty(jstr(o, "orderId"), jstr(o, "number"))
			cells := map[string]any{}
			for _, c := range PreorderOrderColumns {
				cells[c.Key] = cellValue(at(o, c.Key))
			}
			objsByID[oid] = o
			rows = append(rows, domain.Row{ID: oid, Cells: cells})
		}
		if len(list.items) < 100 || (list.total != nil && int64((page+1)*100) >= *list.total) {
			return objsByID, rows, false, nil
		}
	}
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
	if aid, _ := body["addressId"].(string); strings.TrimSpace(aid) != "" {
		cid, _ := body["customerId"].(string)
		if err := s.fillAddressSnapshot(ctx, sc, body, cid, aid); err != nil {
			return nil, err
		}
	}
	sc.idem = idem
	return sc.send(ctx, http.MethodPost, windowPath(id, "/late-orders"), "", body)
}

// lateAddressFields maps delivery-runs' late-order address snapshot (PreorderLateAddress) to identity's saved
// address (Address) field names.
var lateAddressFields = [][2]string{
	{"fullName", "fullName"}, {"phone", "phone"}, {"line1", "line1"}, {"line2", "line2"}, {"city", "city"},
	{"region", "state"}, {"zip", "postalCode"}, {"country", "country"}, {"instructions", "instructions"},
}

// snapshotComplete is true when the console already sent what delivery-runs needs with a saved address id.
func snapshotComplete(a jobj) bool {
	for _, k := range []string{"line1", "city", "region", "zip"} {
		if strings.TrimSpace(jstr(a, k)) == "" {
			return false
		}
	}
	return true
}

// fillAddressSnapshot completes body.address (street, city, state, postal code…) from the customer's saved address in
// identity: delivery-runs answers 422 delivery-runs.address-details-required when a saved address id comes without
// it. Fields the console sent win. A saved address that is not the customer's is a 422; identity being unavailable
// leaves the body as it is (delivery-runs then names what is missing).
func (s *staffPreordersImpl) fillAddressSnapshot(ctx context.Context, sc staffCtx, body map[string]any, customerID, addressID string) error {
	snap, _ := body["address"].(map[string]any)
	if snap == nil {
		snap = map[string]any{}
	}
	if snapshotComplete(snap) {
		return nil
	}
	saved, found, known := s.savedAddress(ctx, sc, customerID, addressID)
	if !known {
		return nil // identity unavailable: relay as is (delivery-runs names what is missing)
	}
	if !found {
		return domain.ValidationProblem(map[string]string{"addressId": "That saved address is not on the customer's account; choose another or enter a new one"})
	}
	for _, f := range lateAddressFields {
		if strings.TrimSpace(jstr(snap, f[0])) != "" {
			continue
		}
		v := jstr(saved, f[1])
		if f[0] == "region" {
			v = firstNonEmpty(v, jstr(saved, "region"))
		}
		if f[0] == "zip" {
			v = firstNonEmpty(v, jstr(saved, "zip"))
		}
		if v != "" {
			snap[f[0]] = v
		}
	}
	body["address"] = snap
	return nil
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

func (s *staffPreordersImpl) DeleteArea(ctx context.Context, c *domain.Caller, key string) (any, error) {
	if !domain.ValidAreaKey(key) {
		return nil, domain.ValidationProblem(map[string]string{"key": "Use country/region or country/region/city, e.g. US/TX/Lubbock"})
	}
	sc, can, err := s.need(ctx, c, poManage)
	if err != nil {
		return nil, err
	}
	out, err := sc.send(ctx, http.MethodDelete, preorderAreas+"/"+url.PathEscape(key), "", nil)
	if err != nil {
		return nil, err
	}
	return withCan(out, can), nil
}

func regionPath(country, region string) (string, error) {
	country, region = strings.ToUpper(strings.TrimSpace(country)), strings.ToUpper(strings.TrimSpace(region))
	if !domain.ValidAreaKey(country + "/" + region) {
		return "", domain.NewProblem(http.StatusNotFound, domain.CodeNotFound, "Not found", "No such state, province or region.")
	}
	return preorderAreas + "/regions/" + url.PathEscape(country) + "/" + url.PathEscape(region), nil
}

func (s *staffPreordersImpl) Region(ctx context.Context, c *domain.Caller, country, region string) (any, error) {
	path, err := regionPath(country, region)
	if err != nil {
		return nil, err
	}
	sc, can, err := s.need(ctx, c, poView)
	if err != nil {
		return nil, err
	}
	var out any
	if err := sc.get(ctx, sc.up.DeliveryRuns, path, nil, &out); err != nil {
		return nil, err
	}
	return withCan(out, can), nil
}

// maxRegionCities is how many cities one region edit may list (delivery-runs' limit).
const maxRegionCities = 2000

// regionBody validates a region edit: everyCity true / false / null and cities [{city, on}].
func regionBody(country, region string, body map[string]any) (map[string]any, error) {
	out := map[string]any{"everyCity": nil}
	switch v := body["everyCity"].(type) {
	case nil:
	case bool:
		out["everyCity"] = v
	default:
		return nil, domain.ValidationProblem(map[string]string{"everyCity": "true, false or null"})
	}
	raw, ok := body["cities"].([]any)
	if !ok && body["cities"] != nil {
		return nil, domain.ValidationProblem(map[string]string{"cities": "A list of {city, on}"})
	}
	if len(raw) > maxRegionCities {
		return nil, domain.ValidationProblem(map[string]string{"cities": "At most 2000 cities; cover the whole region instead"})
	}
	cities := make([]map[string]any, 0, len(raw))
	for _, v := range raw {
		m, _ := v.(map[string]any)
		name, _ := m["city"].(string)
		on, okOn := m["on"].(bool)
		name = strings.TrimSpace(name)
		if !okOn || !domain.ValidAreaKey(country+"/"+region+"/"+name) {
			return nil, domain.ValidationProblem(map[string]string{"cities": "Each city needs a name (no '/', at most 80 characters) and on or off"})
		}
		cities = append(cities, map[string]any{"city": name, "on": on})
	}
	out["cities"] = cities
	return out, nil
}

func (s *staffPreordersImpl) UpdateRegion(ctx context.Context, c *domain.Caller, country, region, ifMatch string, body map[string]any) (any, error) {
	path, err := regionPath(country, region)
	if err != nil {
		return nil, err
	}
	b, err := regionBody(strings.ToUpper(strings.TrimSpace(country)), strings.ToUpper(strings.TrimSpace(region)), body)
	if err != nil {
		return nil, err
	}
	sc, can, err := s.need(ctx, c, poManage)
	if err != nil {
		return nil, err
	}
	out, err := sc.send(ctx, http.MethodPut, path, ifMatch, b)
	if err != nil {
		return nil, err
	}
	return withCan(out, can), nil
}

func (s *staffPreordersImpl) Places(ctx context.Context, c *domain.Caller, q url.Values) (any, error) {
	pq, err := passQuery(q, "country", "region", "q")
	if err != nil {
		return nil, err
	}
	sc, _, err := s.need(ctx, c, poView)
	if err != nil {
		return nil, err
	}
	var out any
	if err := sc.get(ctx, sc.up.DeliveryRuns, "/v1/places/search", pq, &out); err != nil {
		return nil, err
	}
	return items(out), nil
}

func (s *staffPreordersImpl) Products(ctx context.Context, c *domain.Caller, id string) (any, error) {
	if p := domain.ValidPreorderID(id); p != nil {
		return nil, p
	}
	sc, _, err := s.need(ctx, c, poView)
	if err != nil {
		return nil, err
	}
	var out any
	if err := sc.get(ctx, sc.up.DeliveryRuns, windowPath(id, "/products"), nil, &out); err != nil {
		return nil, err
	}
	return items(out), nil
}

// VerifyAddress checks a new late-add address with geo (Google Places, Nominatim fallback). placeId is geo's id
// when it has one, else a stable reference of the standardised address that delivery-runs re-verifies.
func (s *staffPreordersImpl) VerifyAddress(ctx context.Context, c *domain.Caller, a PreorderAddressInput) (domain.AddressVerification, error) {
	errs := map[string]string{}
	if l := len(strings.TrimSpace(a.Line1)); l == 0 || l > 200 {
		errs["line1"] = "Enter the street address"
	}
	if z := strings.TrimSpace(a.ZIP); z == "" || len(z) > 12 {
		errs["zip"] = "Enter the ZIP or postal code"
	}
	if len(a.City) > 80 || len(a.Region) > 3 || len(a.Country) > 2 {
		errs["city"] = "Check the city, region and country"
	}
	if len(errs) > 0 {
		return domain.AddressVerification{}, domain.ValidationProblem(errs)
	}
	sc, _, err := s.need(ctx, c, poLateAdd)
	if err != nil {
		return domain.AddressVerification{}, err
	}
	country := strings.ToUpper(firstNonEmpty(strings.TrimSpace(a.Country), "US"))
	var v jobj
	if err := sc.call(ctx, sc.up.Geo, port.StaffCall{Method: http.MethodPost, Path: "/v1/addresses/validate", Body: jobj{
		"line1": strings.TrimSpace(a.Line1), "city": strings.TrimSpace(a.City), "state": strings.ToUpper(strings.TrimSpace(a.Region)),
		"postalCode": strings.TrimSpace(a.ZIP), "country": country}}, &v); err != nil {
		return domain.AddressVerification{}, err
	}
	switch strings.ToUpper(jstr(v, "status")) {
	case "VALID", "CORRECTED":
	default:
		return domain.AddressVerification{Verified: false}, nil
	}
	std, _ := v["standardized"].(map[string]any)
	if std == nil {
		std = jobj{"line1": a.Line1, "city": a.City, "state": a.Region, "postalCode": a.ZIP}
	}
	formatted := strings.TrimSpace(jstr(std, "line1") + ", " + jstr(std, "city") + ", " + jstr(std, "state") + " " + jstr(std, "postalCode"))
	placeID := firstNonEmpty(jstr(v, "placeId"), jstr(std, "placeId"))
	if placeID == "" {
		sum := sha256.Sum256([]byte(strings.ToLower(formatted + "|" + country)))
		placeID = "geo:" + hex.EncodeToString(sum[:12])
	}
	return domain.AddressVerification{Verified: true, PlaceID: &placeID, Formatted: &formatted}, nil
}

// PreorderAddressInput is POST /v1/staff/preorders/address-verify.
type PreorderAddressInput struct {
	Line1   string `json:"line1"`
	ZIP     string `json:"zip"`
	City    string `json:"city"`
	Region  string `json:"region"`
	Country string `json:"country"`
}

// PreorderUploadInput is POST /v1/staff/preorders/uploads.
type PreorderUploadInput struct {
	FileName    string `json:"fileName"`
	ContentType string `json:"contentType"`
	Size        int64  `json:"size"`
	Width       int    `json:"width"`
	Height      int    `json:"height"`
}

var creativeTypes = []string{"image/jpeg", "image/png", "image/webp"}

// maxCreativeBytes caps an advertorial creative (a 1920×640 hero or 1080×1920 story fits easily).
const maxCreativeBytes = 10 << 20

// RequestUpload signs a direct media upload for a creative (media purpose storefront); the console PUTs the file and
// then sets the slot, where delivery-runs verifies the asset with media-service and stores its size.
func (s *staffPreordersImpl) RequestUpload(ctx context.Context, c *domain.Caller, in PreorderUploadInput) (domain.PreorderUploadTicket, error) {
	errs := map[string]string{}
	if n := strings.TrimSpace(in.FileName); n == "" || len(n) > 200 || strings.ContainsAny(n, "/\\") {
		errs["fileName"] = "Choose an image file"
	}
	if !slices.Contains(creativeTypes, strings.ToLower(in.ContentType)) {
		errs["contentType"] = "Use a JPEG, PNG or WebP image"
	}
	if in.Size <= 0 || in.Size > maxCreativeBytes {
		errs["size"] = "Images up to 10 MB"
	}
	if in.Width < 0 || in.Height < 0 || in.Width > 10000 || in.Height > 10000 {
		errs["width"] = "Check the image size"
	}
	if len(errs) > 0 {
		return domain.PreorderUploadTicket{}, domain.ValidationProblem(errs)
	}
	sc, _, err := s.need(ctx, c, poManage)
	if err != nil {
		return domain.PreorderUploadTicket{}, err
	}
	var up struct {
		Asset struct {
			ID string `json:"id"`
		} `json:"asset"`
		Upload struct {
			URL     string            `json:"url"`
			Method  string            `json:"method"`
			Headers map[string]string `json:"headers"`
		} `json:"upload"`
	}
	if err := sc.call(ctx, sc.up.Media, port.StaffCall{Method: http.MethodPost, Path: "/v1/uploads", Body: jobj{"purpose": "storefront",
		"fileName": strings.TrimSpace(in.FileName), "contentType": strings.ToLower(in.ContentType), "size": in.Size}}, &up); err != nil {
		return domain.PreorderUploadTicket{}, err
	}
	if up.Asset.ID == "" || up.Upload.URL == "" {
		return domain.PreorderUploadTicket{}, domain.UpstreamUnavailable("media")
	}
	method := strings.ToUpper(firstNonEmpty(up.Upload.Method, http.MethodPut))
	if method != http.MethodPost {
		method = http.MethodPut
	}
	headers := up.Upload.Headers
	if headers == nil {
		headers = map[string]string{}
	}
	return domain.PreorderUploadTicket{AssetID: up.Asset.ID, Upload: domain.PreorderUploadSpec{Method: method, URL: up.Upload.URL, Headers: headers}}, nil
}

// Customers is the late-add customer picker. Without a query (fewer than 2 characters) it answers no one.
func (s *staffPreordersImpl) Customers(ctx context.Context, c *domain.Caller, q, windowID string) (domain.LateAddCustomers, error) {
	q = strings.TrimSpace(q)
	if len(q) > 120 {
		return domain.LateAddCustomers{}, domain.ValidationProblem(map[string]string{"q": "Type at most 120 characters"})
	}
	if windowID != "" {
		if p := domain.ValidPreorderID(windowID); p != nil {
			return domain.LateAddCustomers{}, p
		}
	}
	sc, _, err := s.need(ctx, c, poLateAdd)
	if err != nil {
		return domain.LateAddCustomers{}, err
	}
	out := domain.LateAddCustomers{Items: []domain.LateAddCustomer{}}
	if len([]rune(q)) < 2 {
		return out, nil
	}
	var page jobj
	if err := sc.get(ctx, sc.up.Identity, "/v1/admin/users", url.Values{"q": {q}, "size": {customerSearchSize}}, &page); err != nil {
		return domain.LateAddCustomers{}, err
	}
	for _, u := range objs(page, "items") {
		if pt := jstr(u, "principalType"); pt != "" && !strings.EqualFold(pt, "CUSTOMER") {
			continue
		}
		out.Items = append(out.Items, domain.LateAddCustomer{ID: jstr(u, "id"), Name: jstr(u, "name"), Email: jstr(u, "email"),
			Phone: firstNonEmpty(jstr(u, "phone"), jstr(u, "phoneNumber")), Country: "US", Addresses: []domain.LateAddAddress{}})
	}
	var wg sync.WaitGroup
	for i := range out.Items {
		wg.Add(1)
		go func() {
			defer wg.Done()
			cu := &out.Items[i]
			var country string
			cu.Addresses, country, cu.AddressesUnavailable = s.addresses(ctx, sc, cu.ID)
			if len(cu.Addresses) > 0 {
				cu.City, cu.Region = cu.Addresses[0].City, cu.Addresses[0].Region
			}
			if country != "" {
				cu.Country = country
			}
		}()
	}
	wg.Wait()
	return out, nil
}

// addresses reads a customer's saved addresses from identity (GET /v1/admin/users/{id}/addresses, users:read),
// default first, and the default's country; best effort: unavailable gives none and the flag.
func (s *staffPreordersImpl) addresses(ctx context.Context, sc staffCtx, userID string) ([]domain.LateAddAddress, string, bool) {
	out := []domain.LateAddAddress{}
	list, err := s.savedAddresses(ctx, sc, userID)
	if err != nil {
		return out, "", true
	}
	country := ""
	for _, a := range list {
		addr := domain.LateAddAddress{ID: jstr(a, "id"), Label: firstNonEmpty(jstr(a, "label"), "Address"), FullName: jstr(a, "fullName"),
			Phone: jstr(a, "phone"), Line1: jstr(a, "line1"), Line2: jstr(a, "line2"), City: jstr(a, "city"),
			Region: firstNonEmpty(jstr(a, "state"), jstr(a, "region")), ZIP: firstNonEmpty(jstr(a, "postalCode"), jstr(a, "zip")),
			Country: jstr(a, "country"), Instructions: jstr(a, "instructions")}
		if boolean(a, "default") || boolean(a, "isDefault") {
			out = append([]domain.LateAddAddress{addr}, out...)
			country = jstr(a, "country")
			continue
		}
		out = append(out, addr)
	}
	return out, country, false
}

// savedAddress finds one of the customer's saved addresses; known is false when identity could not list them.
func (s *staffPreordersImpl) savedAddress(ctx context.Context, sc staffCtx, customerID, addressID string) (jobj, bool, bool) {
	list, err := s.savedAddresses(ctx, sc, customerID)
	if err != nil {
		return nil, false, false
	}
	i := slices.IndexFunc(list, func(a jobj) bool { return jstr(a, "id") == addressID })
	if i < 0 {
		return nil, false, true
	}
	return list[i], true, true
}

// savedAddresses reads a customer's saved addresses from identity (GET /v1/admin/users/{id}/addresses, users:read)
// as raw objects (array or {items}).
func (s *staffPreordersImpl) savedAddresses(ctx context.Context, sc staffCtx, userID string) ([]jobj, error) {
	if strings.TrimSpace(userID) == "" {
		return nil, domain.ValidationProblem(map[string]string{"customerId": "Choose the customer"})
	}
	var raw any
	if err := sc.get(ctx, sc.up.Identity, "/v1/admin/users/"+url.PathEscape(userID)+"/addresses", nil, &raw); err != nil {
		return nil, err
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
	return list, nil
}
