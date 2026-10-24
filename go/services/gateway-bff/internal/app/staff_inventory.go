package app

import (
	"context"
	"fmt"
	"net/http"
	"net/url"
	"regexp"
	"strconv"
	"strings"
	"sync"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

// StaffInventoryService is the staff console's Inventory hub and AfroTransact Retail (1P) workspace
// (contracts/openapi/gateway-bff-staff.yaml, tags Inventory hub and Retail (1P); S281). It mirrors the routes of
// inventory-service (/v1/staff/inventory/**) and retail-service (/v1/staff/retail/**) 1:1 with the staff member's
// token, after checking the module level each route needs (View to read, Edit to change, Approve for write-offs,
// over-receipts, PO and invoice approvals). The services check their own permissions again. Flow and Trace compose
// the pipeline from a purchase order to the storefront.
type StaffInventoryService interface {
	// Relay runs one mirrored route; 404 for anything not in the route table.
	Relay(ctx context.Context, c *domain.Caller, r OpsRequest) (any, error)
	// Stream relays a document (inbound labels, a PO's PDF or EDI 850) without buffering it.
	Stream(ctx context.Context, c *domain.Caller, r OpsRequest) (port.StaffStream, error)
	// Flow is the pipeline with what is waiting at each step.
	Flow(ctx context.Context, c *domain.Caller) (domain.InventoryFlow, error)
	// Trace follows one product (atin and / or sku) from its retail item to the storefront.
	Trace(ctx context.Context, c *domain.Caller, atin, sku string) (domain.InventoryTrace, error)
}

// OpsRequest is one mirrored call: Area is "inventory" or "retail", Path the part after /v1/staff/<area>.
type OpsRequest struct {
	Area    string
	Method  string
	Path    string
	Query   url.Values
	Body    map[string]any
	IfMatch string
	Idem    string
}

type staffInventoryImpl struct {
	r      *staffResolver
	retail string
}

var _ StaffInventoryService = (*staffInventoryImpl)(nil)

// NewStaffInventoryService returns the Inventory hub and Retail (1P) use case.
func NewStaffInventoryService(d StaffDeps) StaffInventoryService {
	return &staffInventoryImpl{r: newStaffResolver(d), retail: d.RetailOrgID}
}

const (
	areaInventory = "inventory"
	areaRetail    = "retail"
	retailModule  = "retail"
	invModule     = "inventory"
)

// opsRoute maps a BFF route onto the upstream route it mirrors.
type opsRoute struct {
	area, method string
	pattern      *regexp.Regexp
	names        []string
	target       string
	level        string
	query        []string
	stream       bool
}

// route builds one table entry: {name} segments match ids and codes and are substituted, escaped, into the target.
func route(area, method, path, target, level string, query ...string) opsRoute {
	names := []string{}
	parts := strings.Split(strings.TrimPrefix(path, "/"), "/")
	for i, p := range parts {
		if strings.HasPrefix(p, "{") && strings.HasSuffix(p, "}") {
			names = append(names, strings.Trim(p, "{}"))
			parts[i] = `([A-Za-z0-9._~-]{1,80})`
		} else {
			parts[i] = regexp.QuoteMeta(p)
		}
	}
	return opsRoute{area: area, method: method, pattern: regexp.MustCompile("^/" + strings.Join(parts, "/") + "$"), names: names,
		target: target, level: level, query: query}
}

// streamRoute is a document read at View level (labels, a PO's PDF or EDI 850).
func streamRoute(area, path, target string, query ...string) opsRoute {
	r := route(area, http.MethodGet, path, target, domain.LevelView, query...)
	r.stream = true
	return r
}

var pageQuery = []string{"page", "size"}

func withPage(keys ...string) []string { return append(keys, pageQuery...) }

const (
	opGet  = http.MethodGet
	opPost = http.MethodPost
	opPut  = http.MethodPut
	lvView = domain.LevelView
	lvEdit = domain.LevelEdit
	lvAppr = domain.LevelApprove
)

// opsRoutes is the allow-list of mirrored routes (order matters only for overlapping patterns).
var opsRoutes = []opsRoute{
	// ---- inventory-service
	route(areaInventory, opGet, "/overview", "/v1/overview", lvView, "nodeId", "ownerOrg"),
	route(areaInventory, opGet, "/nodes", "/v1/nodes", lvView),
	route(areaInventory, opGet, "/currencies", "/v1/currencies", lvView),
	route(areaInventory, opGet, "/exchange-rates/current", "/v1/exchange-rates/current", lvView, "asOf"),
	route(areaInventory, opGet, "/inbound", "/v1/inbound/shipments", lvView, withPage("ownerOrg", "nodeId", "status", "source", "sku")...),
	route(areaInventory, opPost, "/inbound", "/v1/inbound/shipments", lvEdit),
	route(areaInventory, opGet, "/inbound/{id}", "/v1/inbound/shipments/{id}", lvView),
	route(areaInventory, opPut, "/inbound/{id}", "/v1/inbound/shipments/{id}", lvEdit),
	route(areaInventory, opPut, "/inbound/{id}/tracking", "/v1/inbound/shipments/{id}/tracking", lvEdit),
	route(areaInventory, opPost, "/inbound/{id}/ship", "/v1/inbound/shipments/{id}/ship", lvEdit),
	route(areaInventory, opPost, "/inbound/{id}/receipts", "/v1/inbound/shipments/{id}/receipts", lvEdit),
	route(areaInventory, opPost, "/inbound/{id}/close", "/v1/inbound/shipments/{id}/close", lvEdit),
	route(areaInventory, opPost, "/inbound/{id}/cancel", "/v1/inbound/shipments/{id}/cancel", lvEdit),
	streamRoute(areaInventory, "/inbound/{id}/labels", "/v1/inbound/shipments/{id}/labels", "kind", "format", "lineNo"),
	route(areaInventory, opGet, "/lots", "/v1/lots", lvView, withPage("sku", "nodeId", "ownerOrg", "status", "lotNo")...),
	route(areaInventory, opGet, "/lots/markdown", "/v1/lots/markdown", lvView, "within"),
	route(areaInventory, opPost, "/lots/expiry-runs", "/v1/lots/expiry-runs", lvEdit),
	route(areaInventory, opPost, "/lots/markdown-runs", "/v1/lots/markdown-runs", lvEdit),
	route(areaInventory, opGet, "/lots/{id}", "/v1/lots/{id}", lvView),
	route(areaInventory, opGet, "/lots/{id}/trace", "/v1/lots/{id}/trace", lvView),
	route(areaInventory, opPost, "/lots/{id}/quarantine", "/v1/lots/{id}/quarantine", lvEdit),
	route(areaInventory, opPost, "/lots/{id}/release", "/v1/lots/{id}/release", lvEdit),
	route(areaInventory, opPost, "/lots/{id}/adjust", "/v1/lots/{id}/adjust", lvEdit),
	route(areaInventory, opPost, "/lots/{id}/transfer", "/v1/lots/{id}/transfer", lvEdit),
	route(areaInventory, opPost, "/lots/{id}/write-off", "/v1/lots/{id}/write-off", lvAppr),
	route(areaInventory, opGet, "/positions", "/v1/positions", lvView, withPage("sku", "nodeId", "ownerOrg", "lowStock")...),
	route(areaInventory, opGet, "/movements", "/v1/movements", lvView, withPage("sku", "nodeId", "lotId", "reference", "ownerOrg", "type")...),
	route(areaInventory, opGet, "/reservations/{orderId}", "/v1/reservations/{orderId}", lvView),
	route(areaInventory, opGet, "/reconciliation/latest", "/v1/reconciliation/runs/latest", lvView),
	route(areaInventory, opPost, "/reconciliation/runs", "/v1/reconciliation/runs", lvEdit),
	route(areaInventory, opGet, "/cycle-counts", "/v1/cycle-counts", lvView, withPage("nodeId", "status", "sku", "ownerOrg")...),
	route(areaInventory, opPost, "/cycle-counts", "/v1/cycle-counts", lvEdit),
	route(areaInventory, opGet, "/cycle-counts/{id}", "/v1/cycle-counts/{id}", lvView),
	route(areaInventory, opPut, "/cycle-counts/{id}/counts", "/v1/cycle-counts/{id}/counts", lvEdit),
	route(areaInventory, opPost, "/cycle-counts/{id}/post", "/v1/cycle-counts/{id}/post", lvEdit),
	route(areaInventory, opPost, "/cycle-counts/{id}/cancel", "/v1/cycle-counts/{id}/cancel", lvEdit),
	// ---- retail-service
	route(areaRetail, opGet, "/items", "/v1/items", lvView, withPage("q", "supplierId", "listing")...),
	route(areaRetail, opPost, "/items", "/v1/items", lvEdit),
	route(areaRetail, opGet, "/items/{id}", "/v1/items/{id}", lvView),
	route(areaRetail, opPut, "/items/{id}", "/v1/items/{id}", lvEdit),
	route(areaRetail, opPut, "/items/{id}/atin", "/v1/items/{id}/atin", lvEdit),
	route(areaRetail, opPost, "/items/{id}/listing", "/v1/items/{id}/listing", lvEdit),
	route(areaRetail, opGet, "/suppliers", "/v1/suppliers", lvView, withPage("q", "status")...),
	route(areaRetail, opPost, "/suppliers", "/v1/suppliers", lvEdit),
	route(areaRetail, opGet, "/suppliers/{id}", "/v1/suppliers/{id}", lvView),
	route(areaRetail, opPut, "/suppliers/{id}", "/v1/suppliers/{id}", lvEdit),
	route(areaRetail, opPost, "/suppliers/{id}/status", "/v1/suppliers/{id}/status", lvAppr),
	route(areaRetail, opGet, "/purchase-orders", "/v1/purchase-orders", lvView, withPage("status", "supplierId")...),
	route(areaRetail, opPost, "/purchase-orders", "/v1/purchase-orders", lvEdit),
	route(areaRetail, opGet, "/purchase-orders/{id}", "/v1/purchase-orders/{id}", lvView),
	route(areaRetail, opPut, "/purchase-orders/{id}/lines", "/v1/purchase-orders/{id}/lines", lvEdit),
	route(areaRetail, opPost, "/purchase-orders/{id}/submit", "/v1/purchase-orders/{id}/submit", lvEdit),
	route(areaRetail, opPost, "/purchase-orders/{id}/approve", "/v1/purchase-orders/{id}/approve", lvAppr),
	route(areaRetail, opPost, "/purchase-orders/{id}/send-back", "/v1/purchase-orders/{id}/send-back", lvAppr),
	route(areaRetail, opPost, "/purchase-orders/{id}/send", "/v1/purchase-orders/{id}/send", lvEdit),
	route(areaRetail, opPost, "/purchase-orders/{id}/confirm", "/v1/purchase-orders/{id}/confirm", lvEdit),
	route(areaRetail, opPost, "/purchase-orders/{id}/close", "/v1/purchase-orders/{id}/close", lvEdit),
	route(areaRetail, opPost, "/purchase-orders/{id}/cancel", "/v1/purchase-orders/{id}/cancel", lvEdit),
	route(areaRetail, opGet, "/purchase-orders/{id}/dispatches", "/v1/purchase-orders/{id}/dispatches", lvView),
	route(areaRetail, opPost, "/purchase-orders/{id}/dispatches/retry", "/v1/purchase-orders/{id}/dispatches/retry", lvEdit),
	streamRoute(areaRetail, "/purchase-orders/{id}/pdf", "/v1/purchase-orders/{id}/pdf"),
	streamRoute(areaRetail, "/purchase-orders/{id}/edi850", "/v1/purchase-orders/{id}/edi850"),
	route(areaRetail, opGet, "/shipments", "/v1/shipments", lvView, withPage("stage", "poId")...),
	route(areaRetail, opPost, "/shipments", "/v1/shipments", lvEdit),
	route(areaRetail, opGet, "/shipments/{id}", "/v1/shipments/{id}", lvView),
	route(areaRetail, opPut, "/shipments/{id}/asn", "/v1/shipments/{id}/asn", lvEdit),
	route(areaRetail, opPost, "/shipments/{id}/documents", "/v1/shipments/{id}/documents", lvEdit),
	route(areaRetail, opPut, "/shipments/{id}/charges", "/v1/shipments/{id}/charges", lvEdit),
	route(areaRetail, opPost, "/shipments/{id}/advance", "/v1/shipments/{id}/advance", lvEdit),
	route(areaRetail, opGet, "/shipments/{id}/landed-cost", "/v1/shipments/{id}/landed-cost", lvView),
	route(areaRetail, opGet, "/receipts", "/v1/receipts", lvView, withPage("status", "poId")...),
	route(areaRetail, opPost, "/receipts", "/v1/receipts", lvEdit),
	route(areaRetail, opGet, "/receipts/{id}", "/v1/receipts/{id}", lvView),
	route(areaRetail, opPut, "/receipts/{id}/lines", "/v1/receipts/{id}/lines", lvEdit),
	route(areaRetail, opPut, "/receipts/{id}/charges", "/v1/receipts/{id}/charges", lvEdit),
	route(areaRetail, opPost, "/receipts/{id}/approve-over-receipt", "/v1/receipts/{id}/approve-over-receipt", lvAppr),
	route(areaRetail, opPost, "/receipts/{id}/validate", "/v1/receipts/{id}/validate", lvView),
	route(areaRetail, opPost, "/receipts/{id}/complete", "/v1/receipts/{id}/complete", lvEdit),
	route(areaRetail, opPost, "/receipts/{id}/claims/{claimRef}/resolve", "/v1/receipts/{id}/claims/{claimRef}/resolve", lvEdit),
	route(areaRetail, opGet, "/invoices", "/v1/invoices", lvView, withPage("status", "poId")...),
	route(areaRetail, opPost, "/invoices", "/v1/invoices", lvEdit),
	route(areaRetail, opGet, "/invoices/{id}", "/v1/invoices/{id}", lvView),
	route(areaRetail, opPost, "/invoices/{id}/approve", "/v1/invoices/{id}/approve", lvAppr),
	route(areaRetail, opPost, "/invoices/{id}/reject", "/v1/invoices/{id}/reject", lvAppr),
	route(areaRetail, opPost, "/invoices/{id}/rematch", "/v1/invoices/{id}/rematch", lvEdit),
	route(areaRetail, opGet, "/gl/accounts", "/v1/gl/accounts", lvView),
	route(areaRetail, opGet, "/gl/journal", "/v1/gl/journal", lvView, withPage("source", "sourceRef")...),
}

// match finds the route and the upstream path for a request.
func match(r OpsRequest) (opsRoute, string, bool) {
	return matchIn(opsRoutes, r)
}

// expand substitutes the captured segments, escaped, into the upstream path.
func expand(rt opsRoute, m []string) string {
	target := rt.target
	for i, name := range rt.names {
		target = strings.Replace(target, "{"+name+"}", url.PathEscape(m[i+1]), 1)
	}
	return target
}

func notMirrored() error {
	return domain.NewProblem(http.StatusNotFound, domain.CodeNotFound, "Not found", "No such inventory or retail route.")
}

func moduleOf(area string) string {
	if area == areaRetail {
		return retailModule
	}
	return invModule
}

func (s *staffInventoryImpl) prepare(ctx context.Context, c *domain.Caller, r OpsRequest) (staffCtx, opsRoute, port.StaffCall, port.StaffUpstream, error) {
	rt, target, ok := match(r)
	if !ok {
		return staffCtx{}, rt, port.StaffCall{}, nil, notMirrored()
	}
	q, err := passQuery(r.Query, rt.query...)
	if err != nil {
		return staffCtx{}, rt, port.StaffCall{}, nil, err
	}
	sc, err := s.r.require(ctx, c, moduleOf(r.Area), rt.level)
	if err != nil {
		return sc, rt, port.StaffCall{}, nil, err
	}
	up := sc.up.Inventory
	if r.Area == areaRetail {
		up = sc.up.Retail
	}
	if up == nil {
		return sc, rt, port.StaffCall{}, nil, domain.UpstreamUnavailable(r.Area)
	}
	call := port.StaffCall{Method: r.Method, Path: target, Query: q, IfMatch: r.IfMatch, IdempotencyKey: r.Idem}
	if r.Method != http.MethodGet {
		if r.Body == nil {
			call.Body = map[string]any{}
		} else {
			call.Body = r.Body
		}
	}
	return sc, rt, call, up, nil
}

func (s *staffInventoryImpl) Relay(ctx context.Context, c *domain.Caller, r OpsRequest) (any, error) {
	sc, rt, call, up, err := s.prepare(ctx, c, r)
	if err != nil {
		return nil, err
	}
	if rt.stream {
		return nil, notMirrored()
	}
	var out any
	if err := sc.call(ctx, up, call, &out); err != nil {
		return nil, err
	}
	if list, ok := out.([]any); ok {
		return map[string]any{"items": list}, nil
	}
	if out == nil {
		return map[string]any{}, nil
	}
	return out, nil
}

func (s *staffInventoryImpl) Stream(ctx context.Context, c *domain.Caller, r OpsRequest) (port.StaffStream, error) {
	sc, rt, call, up, err := s.prepare(ctx, c, r)
	if err != nil {
		return port.StaffStream{}, err
	}
	if !rt.stream {
		return port.StaffStream{}, notMirrored()
	}
	st, ok := up.(port.StaffStreamer)
	if !ok {
		return port.StaffStream{}, domain.UpstreamUnavailable(r.Area)
	}
	call.Accept = "application/pdf, text/plain;q=0.9, */*;q=0.5"
	out, err := st.Stream(ctx, sc.bearer, call)
	if err != nil {
		return port.StaffStream{}, mapErr(up.Name(), err)
	}
	if out.ContentType == "" {
		out.ContentType = "application/octet-stream"
	}
	if out.ContentDisposition == "" {
		out.ContentDisposition = "inline"
	}
	return out, nil
}

// ---------------------------------------------------------------- flow

type flowCount struct {
	up    func(port.StaffUpstreams) port.StaffUpstream
	name  string
	path  string
	query url.Values
}

func (s *staffInventoryImpl) Flow(ctx context.Context, c *domain.Caller) (domain.InventoryFlow, error) {
	sc, err := s.r.require(ctx, c, invModule, domain.LevelView)
	if err != nil {
		return domain.InventoryFlow{}, err
	}
	retail := sc.access.Allows(retailModule, domain.LevelView)
	inv := func(u port.StaffUpstreams) port.StaffUpstream { return u.Inventory }
	ret := func(u port.StaffUpstreams) port.StaffUpstream { return u.Retail }
	one := func(kv ...string) url.Values {
		v := url.Values{"size": {"1"}}
		for i := 0; i+1 < len(kv); i += 2 {
			v.Set(kv[i], kv[i+1])
		}
		return v
	}
	counts := map[string]flowCount{
		"items":     {ret, areaRetail, "/v1/items", one()},
		"draft":     {ret, areaRetail, "/v1/purchase-orders", one("status", "DRAFT")},
		"pending":   {ret, areaRetail, "/v1/purchase-orders", one("status", "PENDING_APPROVAL")},
		"approved":  {ret, areaRetail, "/v1/purchase-orders", one("status", "APPROVED")},
		"sent":      {ret, areaRetail, "/v1/purchase-orders", one("status", "SENT")},
		"confirmed": {ret, areaRetail, "/v1/purchase-orders", one("status", "CONFIRMED")},
		"working":   {inv, areaInventory, "/v1/inbound/shipments", one("status", "WORKING")},
		"shipped":   {inv, areaInventory, "/v1/inbound/shipments", one("status", "SHIPPED")},
		"receiving": {inv, areaInventory, "/v1/inbound/shipments", one("status", "RECEIVING")},
		"grnOpen":   {ret, areaRetail, "/v1/receipts", one("status", "OPEN")},
		"lots":      {inv, areaInventory, "/v1/lots", one("status", "SELLABLE")},
		"live":      {ret, areaRetail, "/v1/items", one("listing", "LIVE")},
		"exception": {ret, areaRetail, "/v1/invoices", one("status", "EXCEPTION")},
	}
	got := map[string]int64{}
	failed := map[string]string{}
	var mu sync.Mutex
	var wg sync.WaitGroup
	for key, fc := range counts {
		if fc.name == areaRetail && !retail {
			failed[key] = "retail (no access)"
			continue
		}
		wg.Add(1)
		go func(key string, fc flowCount) {
			defer wg.Done()
			var page struct {
				Total *int64 `json:"total"`
			}
			err := sc.call(ctx, fc.up(sc.up), port.StaffCall{Path: fc.path, Query: fc.query}, &page)
			mu.Lock()
			defer mu.Unlock()
			if err != nil || page.Total == nil {
				failed[key] = fc.name
				return
			}
			got[key] = *page.Total
		}(key, fc)
	}
	wg.Wait()
	sum := func(label string, keys ...string) (*int64, string, string) {
		var n int64
		for _, k := range keys {
			if why, bad := failed[k]; bad {
				return nil, label, why
			}
			n += got[k]
		}
		return &n, label, ""
	}
	stage := func(key, title, who, what, effect, href string, count *int64, label, unavailable string) domain.FlowStage {
		return domain.FlowStage{Key: key, Title: title, Who: who, What: what, Effect: effect, Count: count, CountLabel: label, Href: href,
			Unavailable: unavailable}
	}
	st := []domain.FlowStage{}
	add := func(key, title, who, what, effect, href string, c *int64, label, why string) {
		st = append(st, stage(key, title, who, what, effect, href, c, label, why))
	}
	n, l, why := sum("retail items", "items")
	add("items", "Create the product", "Category manager",
		"Retail item master: name, supplier, shelf life, storage, price. SKU and GTIN barcode are issued; link the catalog ATIN.",
		"retail.item.created · listing starts as Draft", "/retail?tab=items", n, l, why)
	n, l, why = sum("draft POs", "draft")
	add("po", "Raise a purchase order", "Buyer",
		"Pick the supplier, the destination fulfilment centre and the Incoterm; add lines from retail items in the supplier's currency.",
		"Draft PO · base-currency value at the day's FX rate", "/retail?tab=pos", n, l, why)
	n, l, why = sum("awaiting the next step", "pending", "approved", "sent", "confirmed")
	add("approve", "Approve & send", "Finance (Approve) → Buyer",
		"Approval above the buyer's limit, then send the PO PDF and EDI 850; the supplier confirms quantity, price and ready date.",
		"Pending approval → Approved → Sent → Confirmed", "/retail?tab=pos", n, l, why)
	n, l, why = sum("inbound plans being prepared", "working")
	add("inbound", "Announce the inbound shipment", "Buyer / seller",
		"The ASN (packing list): lines with quantity, case pack, batch, made and expiry dates; box and pallet labels with GS1-128 barcodes.",
		"inventory.inbound.created · units count as inbound once shipped", "/inventory?tab=inbound", n, l, why)
	n, l, why = sum("in transit", "shipped")
	add("transit", "Track in transit", "Logistics",
		"Carrier and tracking number; expected arrival at the fulfilment centre. The storefront can show 'Coming soon'.",
		"inventory.inbound.shipped · inbound units at the FC", "/inventory?tab=inbound", n, l, why)
	n, l, why = sum("at the dock", "receiving", "grnOpen")
	add("receive", "Receive & count at the dock", "Warehouse receiver",
		"Scan each case's GS1-128 label, count good and damaged units, confirm batch, made and expiry, choose the bin; over-receipts need approval.",
		"Validates dates, minimum remaining life, duplicate lots and over / short", "/inventory?tab=receiving", n, l, why)
	n, l, why = sum("sellable lots", "lots")
	add("lots", "Lots go into stock", "System",
		"Each batch becomes its own lot with landed cost per unit. Damaged, short-dated and QC-held units go to quarantine, not to sale.",
		"inventory.lot.created · stock movements logged · positions updated", "/inventory?tab=stock", n, l, why)
	n, l, why = sum("live listings", "live")
	add("storefront", "Sell on the storefront", "System + category manager",
		"Live listings show 'Sold & shipped by AfroTransact'. Available = sellable lots − reserved; orders reserve the earliest expiry first (FEFO).",
		"inventory.availability.changed → offers → search index → product page", "/inventory?tab=flow", n, l, why)
	n, l, why = sum("invoice exceptions", "exception")
	add("invoice", "Match the invoice & pay", "Finance",
		"Three-way match: PO price × received quantity = supplier invoice. Exceptions go to the buyer; closing the PO releases payment.",
		"PO closed · accounts payable cleared", "/retail?tab=invoices", n, l, why)
	return domain.InventoryFlow{Stages: st, AsOf: sc.now}, nil
}

// ---------------------------------------------------------------- trace

var atinPattern = regexp.MustCompile(`^AT[0-9A-Z]{8}$`)
var skuPattern = regexp.MustCompile(`^[A-Z0-9][A-Z0-9._/-]{0,63}$`)

func (s *staffInventoryImpl) Trace(ctx context.Context, c *domain.Caller, atin, sku string) (domain.InventoryTrace, error) {
	atin, sku = strings.ToUpper(strings.TrimSpace(atin)), strings.ToUpper(strings.TrimSpace(sku))
	if atin == "" && sku == "" {
		return domain.InventoryTrace{}, domain.ValidationProblem(map[string]string{"atin": "Give an ATIN or an inventory SKU"})
	}
	if atin != "" && !atinPattern.MatchString(atin) {
		return domain.InventoryTrace{}, domain.ValidationProblem(map[string]string{"atin": "AT + 8 letters or digits"})
	}
	if sku != "" && !skuPattern.MatchString(sku) {
		return domain.InventoryTrace{}, domain.ValidationProblem(map[string]string{"sku": "Letters, digits, . _ / - (max 64)"})
	}
	sc, err := s.r.require(ctx, c, invModule, domain.LevelView)
	if err != nil {
		return domain.InventoryTrace{}, err
	}
	t := domain.InventoryTrace{Atin: atin, Sku: sku, Badges: []string{}}
	step := func(key, title, status, detail, href string) {
		t.Steps = append(t.Steps, domain.TraceStep{Key: key, Title: title, Status: status, Detail: detail, Href: href})
	}

	// 1. Retail item (1P) and its listing.
	var item jobj
	if sc.access.Allows(retailModule, domain.LevelView) {
		var page struct {
			Items []jobj `json:"items"`
		}
		q := url.Values{"q": {firstNonEmpty(atin, sku)}, "size": {"20"}}
		if err := sc.get(ctx, sc.up.Retail, "/v1/items", q, &page); err == nil {
			for _, it := range page.Items {
				if (atin != "" && jstr(it, "atin") == atin) || (sku != "" && jstr(it, "sku") == sku) {
					item = it
					break
				}
			}
		}
	}
	switch {
	case item != nil:
		if sku == "" {
			sku = jstr(item, "sku")
		}
		if atin == "" {
			atin = jstr(item, "atin")
		}
		t.Title = jstr(item, "name")
		status := domain.TraceWaiting
		if jstr(item, "listingStatus") == "LIVE" && atin != "" {
			status = domain.TraceDone
		}
		linked := "not linked to a catalog ATIN"
		if a := jstr(item, "atin"); a != "" {
			linked = "ATIN " + a
		}
		step("item", "Retail item & listing", status, fmt.Sprintf("%s · %s · listing %s", jstr(item, "sku"), linked,
			strings.ToLower(jstr(item, "listingStatus"))), "/retail?tab=items&q="+url.QueryEscape(jstr(item, "sku")))
	default:
		step("item", "Retail item & listing", domain.TraceWaiting, "No AfroTransact Retail item: a seller's product (FBA) or not set up yet.", "")
	}

	// 2. The offer the storefront sells (inventory SKU, availability, badges).
	var offer jobj
	if atin != "" && sc.up.Offers != nil {
		var page struct {
			Items []jobj `json:"items"`
		}
		q := url.Values{"atin": {atin}, "size": {"20"}}
		if err := sc.get(ctx, sc.up.Offers, "/v1/offers", q, &page); err == nil {
			for _, o := range page.Items {
				if jstr(o, "fulfilment") == "AFROTRANSACT" && jstr(o, "status") != "WITHDRAWN" &&
					(sku == "" || jstr(o, "inventorySku") == sku || offer == nil) {
					offer = o
				}
			}
		}
	}
	if sku == "" && offer != nil {
		sku = jstr(offer, "inventorySku")
	}
	t.Sku = sku
	t.Atin = atin

	// 3. Inbound shipments of the SKU.
	if sku != "" {
		var page struct {
			Items []jobj `json:"items"`
			Total int64  `json:"total"`
		}
		if err := sc.get(ctx, sc.up.Inventory, "/v1/inbound/shipments", url.Values{"sku": {sku}, "size": {"5"}}, &page); err != nil {
			step("inbound", "Inbound shipment (ASN)", domain.TraceBroken, "inventory-service unavailable", "")
		} else if len(page.Items) == 0 {
			step("inbound", "Inbound shipment (ASN)", domain.TraceWaiting, "No inbound shipment of "+sku+" yet.", "/inventory?tab=inbound")
		} else {
			last := page.Items[0]
			status := domain.TraceWaiting
			if s := jstr(last, "status"); s == "RECEIVING" || s == "CLOSED" {
				status = domain.TraceDone
			}
			step("inbound", "Inbound shipment (ASN)", status, fmt.Sprintf("%s · %s · %d of %d units received (%d shipments)", jstr(last, "number"),
				strings.ToLower(jstr(last, "status")), int64(num(last, "receivedUnits")), int64(num(last, "expectedUnits")), page.Total),
				"/inventory?tab=inbound&id="+url.QueryEscape(jstr(last, "id")))
		}

		// 4. Lots (FEFO) and 5. available positions.
		var lots struct {
			Items []jobj `json:"items"`
		}
		if err := sc.get(ctx, sc.up.Inventory, "/v1/lots", url.Values{"sku": {sku}, "size": {"50"}}, &lots); err != nil {
			step("lots", "Lots (batches)", domain.TraceBroken, "inventory-service unavailable", "")
		} else {
			sellable, units := 0, int64(0)
			next := ""
			for _, l := range lots.Items {
				if jstr(l, "status") == "SELLABLE" && num(l, "qtyAvailable") > 0 {
					sellable++
					units += int64(num(l, "qtyAvailable"))
					if next == "" {
						next = jstr(l, "lotNo") + " (exp " + firstNonEmpty(jstr(l, "expDate"), "none") + ")"
					}
				}
			}
			if sellable == 0 {
				step("lots", "Lots (batches)", domain.TraceWaiting, "No sellable lot of "+sku+".", "/inventory?tab=stock&q="+url.QueryEscape(sku))
			} else {
				step("lots", "Lots (batches)", domain.TraceDone, fmt.Sprintf("%d sellable lots · %d units · picks next: %s (FEFO)", sellable, units, next),
					"/inventory?tab=stock&q="+url.QueryEscape(sku))
			}
		}
		var avail []jobj
		if err := sc.get(ctx, sc.up.Inventory, "/v1/availability", url.Values{"skus": {sku}}, &avail); err == nil && len(avail) > 0 {
			a := int64(num(avail[0], "available"))
			t.AvailableUnits = &a
			status := domain.TraceDone
			if a <= 0 {
				status = domain.TraceWaiting
			}
			step("positions", "Available to sell", status, fmt.Sprintf("%d available (on hand − reserved) over every fulfilment centre", a), "")
		} else {
			step("positions", "Available to sell", domain.TraceWaiting, "No position for "+sku+".", "")
		}
	}

	// 6. Offer availability.
	switch {
	case atin == "":
		step("offer", "Offer (Fulfilled by AfroTransact)", domain.TraceWaiting, "Link the retail item to a catalog ATIN to sell it.", "")
	case offer == nil:
		step("offer", "Offer (Fulfilled by AfroTransact)", domain.TraceBroken, "No open Fulfilled-by-AfroTransact offer on "+atin+".", "")
	case sku != "" && jstr(offer, "inventorySku") != sku:
		step("offer", "Offer (Fulfilled by AfroTransact)", domain.TraceBroken, fmt.Sprintf("The offer sells inventory SKU %s, not %s: list the retail item to point it here.",
			jstr(offer, "inventorySku"), sku), "/retail?tab=items")
	default:
		status := domain.TraceDone
		if jstr(offer, "status") != "ACTIVE" {
			status = domain.TraceWaiting
		}
		step("offer", "Offer (Fulfilled by AfroTransact)", status, fmt.Sprintf("%s · %s · inventory SKU %s · %s", jstr(offer, "sellerSku"),
			strings.ToLower(jstr(offer, "status")), jstr(offer, "inventorySku"), money(num(offer, "priceMinor"), jstr(offer, "currency"))), "")
	}

	// 7. Search index and 8. the product page (public buy box: what the storefront shows).
	if atin != "" && sc.up.Offers != nil {
		var box struct {
			BuyBox jobj `json:"buyBox"`
		}
		err := sc.get(ctx, sc.up.Offers, "/v1/public/atins/"+url.PathEscape(atin)+"/offers", url.Values{"currency": {"USD"}}, &box)
		switch {
		case err != nil:
			step("pdp", "Product page", domain.TraceBroken, "offers-service unavailable", "")
		case box.BuyBox == nil:
			step("pdp", "Product page", domain.TraceWaiting, "Currently unavailable: no eligible offer (no stock or the listing is paused).", "")
		default:
			w := box.BuyBox
			for _, b := range anyList(w["badges"]) {
				if s, ok := b.(string); ok {
					t.Badges = append(t.Badges, s)
				}
			}
			if d, ok := w["deliveryDays"].(float64); ok {
				dd := int64(d)
				t.DeliveryDays = &dd
			}
			sold := firstNonEmpty(jstr(w, "soldBy"), jstr(w, "seller.name"))
			step("pdp", "Product page", domain.TraceDone, fmt.Sprintf("Buy box: %s · %s · %d available · delivery in %d days · %s", sold,
				strings.ToLower(strings.ReplaceAll(jstr(w, "fulfilment"), "_", " ")), int64(num(w, "availableQty")), int64(num(w, "deliveryDays")),
				strings.Join(t.Badges, ", ")), "")
		}
		t.StorefrontPath = "/p/" + url.PathEscape(firstNonEmpty(jstr(offer, "productAtin"), atin))
		if sc.up.Search != nil && t.Title != "" {
			var res struct {
				Hits []jobj `json:"hits"`
			}
			if err := sc.get(ctx, sc.up.Search, "/v1/search", url.Values{"q": {t.Title}, "size": {"24"}}, &res); err == nil {
				var hit jobj
				for _, it := range res.Hits {
					if jstr(it, "atin") == atin || jstr(it, "atin") == jstr(offer, "productAtin") {
						hit = it
					}
				}
				if hit == nil {
					step("search", "Search index", domain.TraceWaiting, "Not in the search results for “"+t.Title+"” yet (the indexer applies offer events).", "")
				} else {
					step("search", "Search index", domain.TraceDone, fmt.Sprintf("Indexed · %s · %d available", strings.ToLower(strings.ReplaceAll(jstr(hit, "availability"), "_", " ")),
						int64(num(hit, "availableQty"))), "")
				}
			}
		}
	}
	return t, nil
}

func anyList(v any) []any {
	if l, ok := v.([]any); ok {
		return l
	}
	return nil
}

func money(minor float64, currency string) string {
	if currency == "" {
		currency = "USD"
	}
	return currency + " " + strconv.FormatFloat(minor/100, 'f', 2, 64)
}
