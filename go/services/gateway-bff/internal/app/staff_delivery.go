package app

import (
	"context"
	"net/http"
	"net/url"
	"regexp"
	"slices"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

// ---------------------------------------------------------------- Delivery module (docs/engineering/delivery-fulfilment.md)
//
// AfroTransact staff run AfroTransact-delivered shipments end to end without the driver app: the queue, take or assign by
// name, picked up, out for delivery, delivered with proof (photo uploaded through media-service, purpose proof-photo, a
// private bucket served by short-lived signed links), failed with a reason and reattempt, and an audited proof
// correction. The module level (View to read, Edit to act) and delivery:read / delivery:manage are checked here, and
// orders-service checks them again and owns every rule (state machine, idempotency, optimistic lock, audit).

// StaffDeliveryService serves /v1/staff/deliveries.
type StaffDeliveryService interface {
	List(ctx context.Context, c *domain.Caller, q DeliveryQuery) (domain.DeliveryList, error)
	Couriers(ctx context.Context, c *domain.Caller) ([]domain.DeliveryCourier, error)
	Detail(ctx context.Context, c *domain.Caller, shipmentID string) (domain.DeliveryDetail, error)
	Take(ctx context.Context, c *domain.Caller, shipmentID, idem string, req domain.DeliveryActionRequest) (domain.DeliveryDetail, error)
	Assign(ctx context.Context, c *domain.Caller, shipmentID, idem string, req domain.DeliveryAssignRequest) (domain.DeliveryDetail, error)
	PickedUp(ctx context.Context, c *domain.Caller, shipmentID, idem string, req domain.DeliveryActionRequest) (domain.DeliveryDetail, error)
	OutForDelivery(ctx context.Context, c *domain.Caller, shipmentID, idem string, req domain.DeliveryActionRequest) (domain.DeliveryDetail, error)
	Delivered(ctx context.Context, c *domain.Caller, shipmentID, idem string, req domain.DeliveryProofRequest) (domain.DeliveryDetail, error)
	Failed(ctx context.Context, c *domain.Caller, shipmentID, idem string, req domain.DeliveryFailedRequest) (domain.DeliveryDetail, error)
	CorrectProof(ctx context.Context, c *domain.Caller, shipmentID, idem string, req domain.DeliveryProofRequest) (domain.DeliveryDetail, error)
	Upload(ctx context.Context, c *domain.Caller, req domain.DeliveryUploadRequest) (domain.DeliveryUploadTicket, error)
}

// DeliveryQuery is the queue's filters, search, sort and page as the console sends them.
type DeliveryQuery struct {
	Status, Mode, Text, Assignee, City, Seller, Sort string
	Desc                                             bool
	Page, Size                                       int
}

type staffDeliveryImpl struct {
	r *staffResolver
	// origins caches origin cities for a few minutes: the seller's city and the fulfilment centre's change rarely.
	mu      sync.Mutex
	origins map[string]originCity
	clock   func() time.Time
}

type originCity struct {
	city string
	at   time.Time
}

var _ StaffDeliveryService = (*staffDeliveryImpl)(nil)

// NewStaffDeliveryService returns the Delivery module use cases.
func NewStaffDeliveryService(d StaffDeps) StaffDeliveryService {
	r := newStaffResolver(d)
	return &staffDeliveryImpl{r: r, origins: map[string]originCity{}, clock: r.d.Clock}
}

const (
	proofPurpose   = "proof-photo"
	maxProofPhotos = 5
	maxProofBytes  = 10 << 20
	originTTL      = 10 * time.Minute
)

var (
	proofTypes = []string{"image/jpeg", "image/png", "image/webp"}
	assetIDRe  = regexp.MustCompile(`^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$`)
)

func deliveryNotFound() *domain.Problem {
	return domain.NewProblem(http.StatusNotFound, domain.CodeNotFound, "Not found", "No such delivery.")
}

func validDeliveryID(id string) error {
	if id == "" || len(id) > 64 || strings.ContainsAny(id, "/?#\\") {
		return deliveryNotFound()
	}
	return nil
}

// reader resolves the staff member for a read: Delivery module View and delivery:read (or manage).
func (s *staffDeliveryImpl) reader(ctx context.Context, c *domain.Caller) (staffCtx, error) {
	sc, err := s.r.require(ctx, c, domain.DeliveryModule, domain.LevelView)
	if err != nil {
		return sc, err
	}
	if !sc.access.CanAny(domain.PermDeliveryRead, domain.PermDeliveryManage) {
		return sc, needsPermission(domain.PermDeliveryRead)
	}
	return sc, nil
}

// operator resolves the staff member for an action: Delivery module Edit and delivery:manage.
func (s *staffDeliveryImpl) operator(ctx context.Context, c *domain.Caller, idem string) (staffCtx, error) {
	sc, err := s.r.require(ctx, c, domain.DeliveryModule, domain.LevelEdit)
	if err != nil {
		return sc, err
	}
	if !sc.access.Can(domain.PermDeliveryManage) {
		return sc, needsPermission(domain.PermDeliveryManage)
	}
	sc.idem = strings.TrimSpace(idem)
	if len(sc.idem) > 120 {
		return sc, domain.ValidationProblem(map[string]string{"Idempotency-Key": "Too long"})
	}
	return sc, nil
}

func (s *staffDeliveryImpl) List(ctx context.Context, c *domain.Caller, q DeliveryQuery) (domain.DeliveryList, error) {
	if len(q.Text) > 120 || len(q.City) > 80 || len(q.Seller) > 120 || len(q.Status) > 200 {
		return domain.DeliveryList{}, domain.ValidationProblem(map[string]string{"q": "Too long"})
	}
	sc, err := s.reader(ctx, c)
	if err != nil {
		return domain.DeliveryList{}, err
	}
	v := url.Values{"page": {strconv.Itoa(max(q.Page, 0))}, "size": {strconv.Itoa(min(max(q.Size, 1), 100))}}
	set := func(k, val string) {
		if val = strings.TrimSpace(val); val != "" {
			v.Set(k, val)
		}
	}
	set("status", q.Status)
	set("mode", q.Mode)
	set("q", q.Text)
	set("city", q.City)
	set("seller", q.Seller)
	set("sort", q.Sort)
	if q.Desc {
		v.Set("desc", "true")
	}
	switch a := strings.TrimSpace(q.Assignee); a {
	case "":
	case "me":
		v.Set("assignee", sc.me.ID)
	default:
		v.Set("assignee", a)
	}
	var up jobj
	if err := sc.get(ctx, sc.up.Orders, "/v1/deliveries", v, &up); err != nil {
		return domain.DeliveryList{}, err
	}
	out := domain.DeliveryList{Items: []domain.DeliveryRow{}, Page: int(num(up, "page")), Size: int(num(up, "size")), Total: int64(num(up, "total")),
		Counts: map[string]int64{}, CanManage: sc.access.Can(domain.PermDeliveryManage) && sc.access.Allows(domain.DeliveryModule, domain.LevelEdit)}
	if m, ok := up["counts"].(map[string]any); ok {
		for k, n := range m {
			if f, ok := n.(float64); ok {
				out.Counts[k] = int64(f)
			}
		}
	}
	for _, r := range objs(up, "items") {
		out.Items = append(out.Items, s.row(ctx, sc, r))
	}
	return out, nil
}

// row maps one orders row. The origin city is read best effort and cached: a shipment never fails to list for want of it.
func (s *staffDeliveryImpl) row(ctx context.Context, sc staffCtx, r jobj) domain.DeliveryRow {
	return domain.DeliveryRow{ShipmentID: jstr(r, "shipmentId"), OrderNumber: jstr(r, "orderNumber"), ShipmentLabel: jstr(r, "shipmentLabel"),
		Mode: jstr(r, "mode"), Status: strings.ToLower(jstr(r, "status")), SellerName: jstr(r, "sellerName"),
		OriginCity:   s.originOf(ctx, sc, jstr(r, "pickupLocationId"), jstr(r, "nodeId")),
		CustomerName: jstr(r, "customerName"), CustomerCity: jstr(r, "customerCity"), CustomerState: jstr(r, "customerState"),
		CustomerPostalCode: jstr(r, "customerPostalCode"), Units: int(num(r, "units")), Perishable: boolean(r, "perishable"),
		PromisedBy: strPtr(jstr(r, "promisedBy")), AssigneeName: strPtr(jstr(r, "assigneeName")),
		AssigneeMe: jstr(r, "assigneeId") != "" && jstr(r, "assigneeId") == sc.me.ID, Attempts: int(num(r, "attempts")),
		Version: int64(num(r, "version")), ProofAttached: boolean(r, "proofAttached"), UpdatedAt: strPtr(jstr(r, "updatedAt")),
		DeliveredAt: strPtr(jstr(r, "deliveredAt"))}
}

func (s *staffDeliveryImpl) originOf(ctx context.Context, sc staffCtx, locationID, nodeID string) string {
	key, path, up, field := "", "", sc.up.Seller, "city"
	switch {
	case locationID != "":
		key, path = "loc:"+locationID, "/v1/ship-from-locations/"+url.PathEscape(locationID)
	case nodeID != "":
		key, path, up = "node:"+nodeID, "/v1/centres", sc.up.Fulfilment
	default:
		return ""
	}
	s.mu.Lock()
	if o, ok := s.origins[key]; ok && s.clock().Sub(o.at) < originTTL {
		s.mu.Unlock()
		return o.city
	}
	s.mu.Unlock()
	city := ""
	if up != nil {
		if nodeID != "" && locationID == "" {
			var centres []jobj
			if err := sc.get(ctx, up, path, nil, &centres); err == nil {
				for _, ce := range centres {
					if strings.EqualFold(jstr(ce, "id"), nodeID) {
						city = jstr(ce, field)
					}
				}
			}
		} else {
			var a jobj
			if err := sc.get(ctx, up, path, nil, &a); err == nil {
				city = jstr(a, field)
			}
		}
	}
	s.mu.Lock()
	if len(s.origins) > 500 {
		s.origins = map[string]originCity{}
	}
	s.origins[key] = originCity{city: city, at: s.clock()}
	s.mu.Unlock()
	return city
}

func (s *staffDeliveryImpl) Couriers(ctx context.Context, c *domain.Caller) ([]domain.DeliveryCourier, error) {
	sc, err := s.reader(ctx, c)
	if err != nil {
		return nil, err
	}
	var up []jobj
	if err := sc.get(ctx, sc.up.Orders, "/v1/deliveries/couriers", nil, &up); err != nil {
		return nil, err
	}
	out := []domain.DeliveryCourier{}
	for _, p := range up {
		out = append(out, domain.DeliveryCourier{ID: jstr(p, "id"), Name: firstNonEmpty(jstr(p, "name"), "Staff member"), Me: jstr(p, "id") == sc.me.ID})
	}
	return out, nil
}

func (s *staffDeliveryImpl) Detail(ctx context.Context, c *domain.Caller, shipmentID string) (domain.DeliveryDetail, error) {
	if err := validDeliveryID(shipmentID); err != nil {
		return domain.DeliveryDetail{}, err
	}
	sc, err := s.reader(ctx, c)
	if err != nil {
		return domain.DeliveryDetail{}, err
	}
	var up jobj
	if err := sc.get(ctx, sc.up.Orders, "/v1/deliveries/"+url.PathEscape(shipmentID), nil, &up); err != nil {
		return domain.DeliveryDetail{}, err
	}
	return s.detail(ctx, sc, up), nil
}

func (s *staffDeliveryImpl) detail(ctx context.Context, sc staffCtx, up jobj) domain.DeliveryDetail {
	d := domain.DeliveryDetail{Row: s.row(ctx, sc, jobj(objOf(up, "row"))), Items: []domain.DeliveryItem{}, Audit: []domain.DeliveryAuditLine{},
		NextStatuses: strs(up, "nextStatuses"), CanManage: boolean(up, "canManage") && sc.access.Allows(domain.DeliveryModule, domain.LevelEdit)}
	if !d.CanManage {
		d.NextStatuses = []string{}
	}
	dest := objOf(up, "destination")
	d.Destination = domain.DeliveryDestination{Name: jstr(dest, "name"), Line1: strPtr(jstr(dest, "line1")), Line2: strPtr(jstr(dest, "line2")),
		City: jstr(dest, "city"), State: jstr(dest, "state"), PostalCode: strPtr(jstr(dest, "postalCode")), Phone: strPtr(jstr(dest, "phone")),
		Instructions: strPtr(jstr(dest, "instructions")), HandToCustomer: boolean(dest, "handToCustomer")}
	for _, it := range objs(up, "items") {
		d.Items = append(d.Items, domain.DeliveryItem{Title: jstr(it, "title"), Quantity: int(num(it, "quantity"))})
	}
	job := objOf(up, "job")
	d.Job = domain.DeliveryJob{AssigneeName: strPtr(jstr(job, "assigneeName")), AssignedAt: strPtr(jstr(job, "assignedAt")), AssignedBy: strPtr(jstr(job, "assignedByName")),
		Attempts: int(num(job, "attempts")), LastFailure: strPtr(jstr(job, "lastFailure")), Version: int64(num(job, "version"))}
	if p := objOf(up, "proof"); len(p) > 0 {
		ids := strs(p, "photoAssetIds")
		pv := &domain.DeliveryProofView{PhotoAssetIDs: ids, PhotoURLs: make([]string, len(ids)), RecipientName: strPtr(jstr(p, "recipientName")), Note: strPtr(jstr(p, "note")),
			DeliveredAt: jstr(p, "deliveredAt"), RecordedByName: firstNonEmpty(jstr(p, "recordedByName"), "Staff member"),
			CorrectedAt: strPtr(jstr(p, "correctedAt")), CorrectedByName: strPtr(jstr(p, "correctedByName")), Corrections: int(num(p, "corrections")),
			Version: int64(num(p, "version")), Latitude: optNum(p, "latitude"), Longitude: optNum(p, "longitude")}
		if sc.up.Proof != nil && len(ids) > 0 {
			urls := sc.up.Proof.URLs(ctx, ids...)
			for i, id := range ids {
				pv.PhotoURLs[i] = urls[id]
			}
		}
		d.Proof = pv
	}
	for _, a := range objs(up, "audit") {
		note := firstNonEmpty(jstr(a, "detail.reason"), jstr(a, "detail.note"))
		d.Audit = append(d.Audit, domain.DeliveryAuditLine{Action: jstr(a, "action"), FromStatus: strings.ToLower(jstr(a, "fromStatus")),
			ToStatus: strings.ToLower(jstr(a, "toStatus")), ActorName: firstNonEmpty(jstr(a, "actorName"), "Staff member"), Note: note, At: jstr(a, "at")})
	}
	return d
}

// objOf is the nested object at key (empty when absent).
func objOf(o jobj, key string) jobj {
	if m, ok := o[key].(map[string]any); ok && m != nil {
		return m
	}
	return jobj{}
}

// act runs one action on orders and answers the delivery as the console should now show it.
func (s *staffDeliveryImpl) act(ctx context.Context, sc staffCtx, shipmentID, method, path string, body jobj) (domain.DeliveryDetail, error) {
	var up jobj
	if err := sc.call(ctx, sc.up.Orders, port.StaffCall{Method: method, Path: "/v1/deliveries/" + url.PathEscape(shipmentID) + path, Body: body, IdempotencyKey: sc.idem}, &up); err != nil {
		return domain.DeliveryDetail{}, deliveryErr(err)
	}
	return s.detail(ctx, sc, up), nil
}

// deliveryErr words the desk's refusals for staff; unknown ones pass through.
func deliveryErr(err error) error {
	p, ok := domain.AsProblem(err)
	if !ok {
		return err
	}
	if p.Status == http.StatusConflict {
		return domain.NewProblem(http.StatusConflict, domain.CodeConflict, "Can't do that now", firstNonEmpty(p.Detail, "Someone else changed this delivery. Reload and try again.")).WithCause(err)
	}
	return err
}

func versioned(body jobj, v *int64, note string) jobj {
	if v != nil {
		body["version"] = *v
	}
	if n := strings.TrimSpace(note); n != "" {
		body["note"] = n
	}
	return body
}

func checkNote(note string) error {
	if len([]rune(note)) > 500 {
		return domain.ValidationProblem(map[string]string{"note": "Up to 500 characters"})
	}
	return nil
}

func (s *staffDeliveryImpl) simple(ctx context.Context, c *domain.Caller, shipmentID, idem, path string, req domain.DeliveryActionRequest) (domain.DeliveryDetail, error) {
	if err := validDeliveryID(shipmentID); err != nil {
		return domain.DeliveryDetail{}, err
	}
	if err := checkNote(req.Note); err != nil {
		return domain.DeliveryDetail{}, err
	}
	sc, err := s.operator(ctx, c, idem)
	if err != nil {
		return domain.DeliveryDetail{}, err
	}
	return s.act(ctx, sc, shipmentID, http.MethodPost, path, versioned(jobj{}, req.Version, req.Note))
}

func (s *staffDeliveryImpl) Take(ctx context.Context, c *domain.Caller, id, idem string, req domain.DeliveryActionRequest) (domain.DeliveryDetail, error) {
	return s.simple(ctx, c, id, idem, "/take", req)
}

func (s *staffDeliveryImpl) PickedUp(ctx context.Context, c *domain.Caller, id, idem string, req domain.DeliveryActionRequest) (domain.DeliveryDetail, error) {
	return s.simple(ctx, c, id, idem, "/picked-up", req)
}

func (s *staffDeliveryImpl) OutForDelivery(ctx context.Context, c *domain.Caller, id, idem string, req domain.DeliveryActionRequest) (domain.DeliveryDetail, error) {
	return s.simple(ctx, c, id, idem, "/out-for-delivery", req)
}

func (s *staffDeliveryImpl) Assign(ctx context.Context, c *domain.Caller, id, idem string, req domain.DeliveryAssignRequest) (domain.DeliveryDetail, error) {
	if err := validDeliveryID(id); err != nil {
		return domain.DeliveryDetail{}, err
	}
	who := strings.TrimSpace(req.AssigneeID)
	if who == "" || len(who) > 120 {
		return domain.DeliveryDetail{}, domain.ValidationProblem(map[string]string{"assigneeId": "Choose who delivers this"})
	}
	if err := checkNote(req.Note); err != nil {
		return domain.DeliveryDetail{}, err
	}
	sc, err := s.operator(ctx, c, idem)
	if err != nil {
		return domain.DeliveryDetail{}, err
	}
	return s.act(ctx, sc, id, http.MethodPost, "/assign", versioned(jobj{"assigneeId": who}, req.Version, req.Note))
}

func (s *staffDeliveryImpl) Failed(ctx context.Context, c *domain.Caller, id, idem string, req domain.DeliveryFailedRequest) (domain.DeliveryDetail, error) {
	if err := validDeliveryID(id); err != nil {
		return domain.DeliveryDetail{}, err
	}
	reason := strings.TrimSpace(req.Reason)
	if n := len([]rune(reason)); n < 3 || n > 500 {
		return domain.DeliveryDetail{}, domain.ValidationProblem(map[string]string{"reason": "Say why it failed (3 to 500 characters)"})
	}
	sc, err := s.operator(ctx, c, idem)
	if err != nil {
		return domain.DeliveryDetail{}, err
	}
	return s.act(ctx, sc, id, http.MethodPost, "/failed", versioned(jobj{"reason": reason}, req.Version, ""))
}

// proofBody validates the proof the console sent (the same limits orders enforces) and checks each photo with media: it
// must be a proof photo asset, an image of an allowed type and size, uploaded and not rejected.
func (s *staffDeliveryImpl) proofBody(ctx context.Context, sc staffCtx, req domain.DeliveryProofRequest) (jobj, error) {
	errs := map[string]string{}
	ids := []string{}
	for _, id := range req.PhotoAssetIDs {
		id = strings.ToLower(strings.TrimSpace(id))
		if !assetIDRe.MatchString(id) {
			errs["photoAssetIds"] = "Attach a photo taken for this delivery"
			break
		}
		if !slices.Contains(ids, id) {
			ids = append(ids, id)
		}
	}
	switch {
	case errs["photoAssetIds"] != "":
	case len(ids) == 0:
		errs["photoAssetIds"] = "Attach at least one photo as proof of delivery"
	case len(ids) > maxProofPhotos:
		errs["photoAssetIds"] = "Attach at most " + strconv.Itoa(maxProofPhotos) + " photos"
	}
	name, note := strings.TrimSpace(req.RecipientName), strings.TrimSpace(req.Note)
	if len([]rune(name)) > 120 {
		errs["recipientName"] = "Up to 120 characters"
	}
	if len([]rune(note)) > 500 {
		errs["note"] = "Up to 500 characters"
	}
	if (req.Latitude == nil) != (req.Longitude == nil) {
		errs["latitude"] = "Give both latitude and longitude, or neither"
	} else if req.Latitude != nil && (*req.Latitude < -90 || *req.Latitude > 90 || *req.Longitude < -180 || *req.Longitude > 180) {
		errs["latitude"] = "That position is not on the map"
	}
	if len(errs) > 0 {
		return nil, domain.ValidationProblem(errs)
	}
	for _, id := range ids {
		if msg := s.checkPhoto(ctx, sc, id); msg != "" {
			return nil, domain.ValidationProblem(map[string]string{"photoAssetIds": msg})
		}
	}
	body := jobj{"photoAssetIds": ids}
	if name != "" {
		body["recipientName"] = name
	}
	if note != "" {
		body["note"] = note
	}
	if req.Latitude != nil && req.Longitude != nil {
		body["latitude"], body["longitude"] = *req.Latitude, *req.Longitude
	}
	return body, nil
}

// checkPhoto answers "" when media confirms the asset is a usable proof photo, else what to tell the staff member.
func (s *staffDeliveryImpl) checkPhoto(ctx context.Context, sc staffCtx, id string) string {
	if sc.up.Media == nil {
		return "Photos can't be checked right now. Try again in a minute."
	}
	// Finalise the direct upload (media detects the type and size); an already complete asset answers an error we ignore.
	_ = sc.call(ctx, sc.up.Media, port.StaffCall{Method: http.MethodPost, Path: "/v1/assets/" + url.PathEscape(id) + "/complete"}, nil)
	var a jobj
	if err := sc.get(ctx, sc.up.Media, "/v1/assets/"+url.PathEscape(id), nil, &a); err != nil {
		return "That photo was not found. Take it again."
	}
	switch {
	case jstr(a, "purpose") != proofPurpose:
		return "That file is not a delivery proof photo"
	case !slices.Contains(proofTypes, strings.ToLower(jstr(a, "contentType"))):
		return "Use a JPEG, PNG or WebP photo"
	case int64(num(a, "bytes")) > maxProofBytes:
		return "Photos up to 10 MB"
	case strings.EqualFold(jstr(a, "status"), "REJECTED"):
		return "That photo was rejected. Take it again."
	case strings.EqualFold(jstr(a, "status"), "PENDING"):
		return "That photo has not finished uploading"
	}
	return ""
}

func (s *staffDeliveryImpl) Delivered(ctx context.Context, c *domain.Caller, id, idem string, req domain.DeliveryProofRequest) (domain.DeliveryDetail, error) {
	if err := validDeliveryID(id); err != nil {
		return domain.DeliveryDetail{}, err
	}
	sc, err := s.operator(ctx, c, idem)
	if err != nil {
		return domain.DeliveryDetail{}, err
	}
	body, err := s.proofBody(ctx, sc, req)
	if err != nil {
		return domain.DeliveryDetail{}, err
	}
	return s.act(ctx, sc, id, http.MethodPost, "/delivered", versioned(body, req.Version, ""))
}

func (s *staffDeliveryImpl) CorrectProof(ctx context.Context, c *domain.Caller, id, idem string, req domain.DeliveryProofRequest) (domain.DeliveryDetail, error) {
	if err := validDeliveryID(id); err != nil {
		return domain.DeliveryDetail{}, err
	}
	reason := strings.TrimSpace(req.Reason)
	if n := len([]rune(reason)); n < 3 || n > 500 {
		return domain.DeliveryDetail{}, domain.ValidationProblem(map[string]string{"reason": "Say why you correct the proof (3 to 500 characters)"})
	}
	sc, err := s.operator(ctx, c, idem)
	if err != nil {
		return domain.DeliveryDetail{}, err
	}
	body, err := s.proofBody(ctx, sc, req)
	if err != nil {
		return domain.DeliveryDetail{}, err
	}
	body["reason"] = reason
	return s.act(ctx, sc, id, http.MethodPut, "/proof", versioned(body, req.Version, ""))
}

// Upload signs a direct upload of a proof photo (media purpose proof-photo: private, signed links, JPEG/PNG/WebP up to 10 MB).
func (s *staffDeliveryImpl) Upload(ctx context.Context, c *domain.Caller, req domain.DeliveryUploadRequest) (domain.DeliveryUploadTicket, error) {
	errs := map[string]string{}
	if n := strings.TrimSpace(req.FileName); n == "" || len(n) > 200 || strings.ContainsAny(n, "/\\") {
		errs["fileName"] = "Choose a photo"
	}
	if !slices.Contains(proofTypes, strings.ToLower(req.ContentType)) {
		errs["contentType"] = "Use a JPEG, PNG or WebP photo"
	}
	if req.Size <= 0 || req.Size > maxProofBytes {
		errs["size"] = "Photos up to 10 MB"
	}
	if len(errs) > 0 {
		return domain.DeliveryUploadTicket{}, domain.ValidationProblem(errs)
	}
	sc, err := s.operator(ctx, c, "")
	if err != nil {
		return domain.DeliveryUploadTicket{}, err
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
	if err := sc.call(ctx, sc.up.Media, port.StaffCall{Method: http.MethodPost, Path: "/v1/uploads", Body: jobj{"purpose": proofPurpose,
		"fileName": strings.TrimSpace(req.FileName), "contentType": strings.ToLower(req.ContentType), "size": req.Size}}, &up); err != nil {
		return domain.DeliveryUploadTicket{}, err
	}
	if up.Asset.ID == "" || up.Upload.URL == "" {
		return domain.DeliveryUploadTicket{}, domain.UpstreamUnavailable("media")
	}
	method := strings.ToUpper(firstNonEmpty(up.Upload.Method, http.MethodPut))
	if method != http.MethodPost {
		method = http.MethodPut
	}
	headers := up.Upload.Headers
	if headers == nil {
		headers = map[string]string{}
	}
	return domain.DeliveryUploadTicket{AssetID: up.Asset.ID, Upload: domain.DeliveryUploadSpec{Method: method, URL: up.Upload.URL, Headers: headers}}, nil
}
