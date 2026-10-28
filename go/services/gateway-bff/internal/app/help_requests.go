package app

import (
	"context"
	"net/http"
	"path"
	"slices"
	"strconv"
	"strings"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

// HelpRequestsService is the customer side of disputes. A delivered order has no self-service refund or return: the
// customer describes the problem with photos, and staff resolve it in the claims tooling (trust-ops). Photos go
// straight to media-service through signed uploads; the request maps to a trust-ops claim on an order the customer
// owns, with amount, currency and seller taken from that order, never from the client.
type HelpRequestsService interface {
	EvidenceUpload(ctx context.Context, c *domain.Caller, fileName, contentType string, size int64) (domain.HelpEvidenceUpload, error)
	CompleteEvidence(ctx context.Context, c *domain.Caller, assetID string) (domain.HelpEvidenceAsset, error)
	Create(ctx context.Context, c *domain.Caller, key string, r domain.CreateHelpRequest) (domain.HelpRequest, error)
	// List returns the customer's requests, newest first. cursor is the page number the previous answer returned.
	List(ctx context.Context, c *domain.Caller, cursor string, limit int) (domain.Page[domain.HelpRequest], error)
	Get(ctx context.Context, c *domain.Caller, id string) (domain.HelpRequest, error)
}

// HelpEvidencePurpose is the media purpose for evidence photos: private, images up to 10 MB.
const HelpEvidencePurpose = "return-photo"

const (
	helpReceiptTTL = 24 * time.Hour
	helpListMax    = 50
)

// HelpRequestsDeps are the upstreams of HelpRequestsService.
type HelpRequestsDeps struct {
	Orders   port.CustomerOrdersClient
	Claims   port.HelpRequestsClient
	Media    port.MediaClient
	Receipts port.ReceiptStore
}

type helpRequestsServiceImpl struct{ d HelpRequestsDeps }

var _ HelpRequestsService = (*helpRequestsServiceImpl)(nil)

// NewHelpRequestsService returns the HelpRequestsService.
func NewHelpRequestsService(d HelpRequestsDeps) HelpRequestsService {
	return &helpRequestsServiceImpl{d: d}
}

func helpRequestNotFound(cause error) error {
	return domain.NewProblem(http.StatusNotFound, domain.CodeNotFound, "Request not found", "We couldn't find that help request.").WithCause(cause)
}

func helpRequestErr(err error) error {
	if p, ok := port.AsUpstreamProblem(err); ok {
		switch {
		case p.Status == http.StatusNotFound || p.Status == http.StatusForbidden:
			return helpRequestNotFound(err)
		case p.Status == http.StatusConflict:
			return domain.NewProblem(http.StatusConflict, domain.CodeConflict, "Already reported",
				firstNonEmpty(p.Detail, "You've already reported a problem with this order. We'll update you when we've looked into it.")).WithCause(err)
		case p.Status == http.StatusUnprocessableEntity && len(p.Fields) == 0:
			return domain.FieldProblem(domain.CodeValidationFailed, "Can't send this request", "description",
				firstNonEmpty(p.Detail, "We couldn't accept this request.")).WithCause(err)
		}
	}
	return mapErr("trust-ops", err)
}

func assetKey(c *domain.Caller, assetID string) string {
	return "help-asset:" + c.Subject + ":" + assetID
}

func (s *helpRequestsServiceImpl) EvidenceUpload(ctx context.Context, c *domain.Caller, fileName, contentType string, size int64) (domain.HelpEvidenceUpload, error) {
	if err := requireCustomer(c); err != nil {
		return domain.HelpEvidenceUpload{}, err
	}
	fileName = strings.TrimSpace(path.Base(strings.ReplaceAll(fileName, "\\", "/")))
	contentType = strings.ToLower(strings.TrimSpace(contentType))
	errs := map[string]string{}
	if fileName == "" || fileName == "." || len(fileName) > 200 {
		errs["fileName"] = "Choose a photo"
	}
	if !slices.Contains(domain.HelpEvidenceContentTypes, contentType) {
		errs["contentType"] = "Upload a JPEG, PNG, WebP or HEIC photo"
	}
	if size <= 0 || size > domain.HelpEvidenceMaxBytes {
		errs["size"] = "Each photo must be 10 MB or smaller"
	}
	if len(errs) > 0 {
		return domain.HelpEvidenceUpload{}, domain.ValidationProblem(errs)
	}
	up, err := s.d.Media.RequestUpload(ctx, c.BearerToken, HelpEvidencePurpose, fileName, contentType, size)
	if err != nil {
		return domain.HelpEvidenceUpload{}, mapErr("media", err)
	}
	if err := s.d.Receipts.Remember(ctx, assetKey(c, up.Asset.ID), "pending", helpReceiptTTL); err != nil {
		return domain.HelpEvidenceUpload{}, mapErr("session store", err)
	}
	headers := up.Upload.Headers
	if headers == nil {
		headers = map[string]string{"Content-Type": contentType}
	}
	return domain.HelpEvidenceUpload{AssetID: up.Asset.ID, MaxBytes: domain.HelpEvidenceMaxBytes, Upload: domain.ResumeUploadURL{
		Method: firstNonEmpty(up.Upload.Method, http.MethodPut), URL: up.Upload.URL, Headers: headers, ExpiresAt: up.Upload.ExpiresAt}}, nil
}

func (s *helpRequestsServiceImpl) CompleteEvidence(ctx context.Context, c *domain.Caller, assetID string) (domain.HelpEvidenceAsset, error) {
	if err := requireCustomer(c); err != nil {
		return domain.HelpEvidenceAsset{}, err
	}
	// Only photos this customer asked to upload can be finalised through here.
	if _, ok, err := s.d.Receipts.Recall(ctx, assetKey(c, assetID)); err != nil {
		return domain.HelpEvidenceAsset{}, mapErr("session store", err)
	} else if !ok {
		return domain.HelpEvidenceAsset{}, domain.NewProblem(http.StatusNotFound, domain.CodeNotFound, "Not found", "We couldn't find that upload.")
	}
	a, err := s.d.Media.Complete(ctx, c.BearerToken, assetID)
	if err != nil {
		return domain.HelpEvidenceAsset{}, mapErr("media", err)
	}
	if a.Status != "READY" {
		return domain.HelpEvidenceAsset{}, domain.ValidationProblem(map[string]string{"file": "We couldn't read that photo. Upload a JPEG, PNG, WebP or HEIC image of 10 MB or less."})
	}
	if err := s.d.Receipts.Remember(ctx, assetKey(c, assetID), "ready", helpReceiptTTL); err != nil {
		return domain.HelpEvidenceAsset{}, mapErr("session store", err)
	}
	out := domain.HelpEvidenceAsset{AssetID: a.ID, ContentType: a.ContentType, Status: a.Status}
	if a.Bytes != nil {
		out.Bytes = *a.Bytes
	}
	return out, nil
}

func validateHelpRequest(key string, r domain.CreateHelpRequest) map[string]string {
	errs := map[string]string{}
	if len(key) < 16 || len(key) > 64 {
		errs["Idempotency-Key"] = "Send an Idempotency-Key of 16 to 64 characters"
	}
	if strings.TrimSpace(r.OrderNumber) == "" {
		errs["orderNumber"] = "Choose the order"
	}
	if !slices.Contains(domain.HelpRequestTypes, r.Type) {
		errs["type"] = "Choose what went wrong"
	}
	if n := len([]rune(strings.TrimSpace(r.Description))); n < domain.HelpDescriptionMin || n > domain.HelpDescriptionMax {
		errs["description"] = "Describe the problem in " + strconv.Itoa(domain.HelpDescriptionMin) + " to " + strconv.Itoa(domain.HelpDescriptionMax) + " characters"
	}
	if len(r.EvidenceAssetIDs) > domain.HelpEvidenceMaxPerReq {
		errs["evidenceAssetIds"] = "Add at most " + strconv.Itoa(domain.HelpEvidenceMaxPerReq) + " photos"
	}
	seen := map[string]bool{}
	for _, id := range r.EvidenceAssetIDs {
		if strings.TrimSpace(id) == "" || seen[id] {
			errs["evidenceAssetIds"] = "Each photo can be added once"
		}
		seen[id] = true
	}
	return errs
}

// claimAmount is what the customer can claim: the shipment's items when one is named, otherwise the order's total.
func claimAmount(o port.UpOrder, shipment *port.UpShipment) int64 {
	if shipment != nil {
		var sum int64
		for _, l := range shipment.Lines {
			sum += l.ItemsMinor
		}
		if sum > 0 {
			return sum
		}
	}
	return o.TotalMinor
}

func (s *helpRequestsServiceImpl) Create(ctx context.Context, c *domain.Caller, key string, r domain.CreateHelpRequest) (domain.HelpRequest, error) {
	if err := requireCustomer(c); err != nil {
		return domain.HelpRequest{}, err
	}
	r.OrderNumber = strings.TrimSpace(r.OrderNumber)
	r.Description = strings.TrimSpace(r.Description)
	if errs := validateHelpRequest(key, r); len(errs) > 0 {
		return domain.HelpRequest{}, domain.ValidationProblem(errs)
	}
	// A retry with the same key gives back the request it already created.
	keyed := "help-key:" + c.Subject + ":" + key
	if id, ok, err := s.d.Receipts.Recall(ctx, keyed); err != nil {
		return domain.HelpRequest{}, mapErr("session store", err)
	} else if ok {
		claim, err := s.d.Claims.Get(ctx, c.BearerToken, id)
		if err != nil {
			return domain.HelpRequest{}, helpRequestErr(err)
		}
		return s.toHelpRequest(c, claim)
	}
	// Ownership: orders answers only for the token's customer; anything else is "not found".
	o, err := s.d.Orders.Get(ctx, c.BearerToken, r.OrderNumber)
	if err != nil {
		return domain.HelpRequest{}, ordersErr(err)
	}
	var shipment *port.UpShipment
	if r.ShipmentID != "" {
		for i := range o.Shipments {
			if sh := o.Shipments[i]; sh.ID == r.ShipmentID || sh.ShipmentID == r.ShipmentID {
				shipment = &o.Shipments[i]
			}
		}
		if shipment == nil {
			return domain.HelpRequest{}, domain.ValidationProblem(map[string]string{"shipmentId": "That item isn't part of this order"})
		}
	}
	// Evidence: only finalised photos this customer uploaded here.
	for _, id := range r.EvidenceAssetIDs {
		state, ok, err := s.d.Receipts.Recall(ctx, assetKey(c, id))
		if err != nil {
			return domain.HelpRequest{}, mapErr("session store", err)
		}
		if !ok || state != "ready" {
			return domain.HelpRequest{}, domain.ValidationProblem(map[string]string{"evidenceAssetIds": "One of the photos wasn't uploaded completely. Remove it and add it again."})
		}
	}
	req := port.UpOpenClaim{Type: r.Type, OrderID: o.ID, OrderNumber: o.OrderNumber, AmountMinor: claimAmount(o, shipment),
		Currency: firstNonEmpty(o.Currency, domain.Currency), Description: r.Description, EvidenceAssetIDs: orEmpty(r.EvidenceAssetIDs)}
	if shipment != nil {
		req.ShipmentID = firstNonEmpty(shipment.ShipmentID, shipment.ID)
		req.SellerOrgID = shipment.SellerOrgID
	}
	if req.AmountMinor < 1 {
		return domain.HelpRequest{}, domain.NewProblem(http.StatusConflict, domain.CodeConflict, "Nothing to report on",
			"This order has no charged items to report a problem with.")
	}
	claim, err := s.d.Claims.Open(ctx, c.BearerToken, req)
	if err != nil {
		return domain.HelpRequest{}, helpRequestErr(err)
	}
	if err := s.d.Receipts.Remember(ctx, keyed, claim.ID, helpReceiptTTL); err != nil {
		return domain.HelpRequest{}, mapErr("session store", err)
	}
	return s.toHelpRequest(c, claim)
}

func (s *helpRequestsServiceImpl) List(ctx context.Context, c *domain.Caller, cursor string, limit int) (domain.Page[domain.HelpRequest], error) {
	if err := requireCustomer(c); err != nil {
		return domain.Page[domain.HelpRequest]{}, err
	}
	page := 0
	if cursor != "" {
		n, err := strconv.Atoi(cursor)
		if err != nil || n < 0 {
			return domain.Page[domain.HelpRequest]{}, domain.ValidationProblem(map[string]string{"cursor": "Invalid cursor"})
		}
		page = n
	}
	limit = min(max(limit, 1), helpListMax)
	claims, more, err := s.d.Claims.Mine(ctx, c.BearerToken, page, limit)
	if err != nil {
		return domain.Page[domain.HelpRequest]{}, helpRequestErr(err)
	}
	out := domain.Page[domain.HelpRequest]{Items: make([]domain.HelpRequest, 0, len(claims))}
	for _, k := range claims {
		v, err := s.toHelpRequest(c, k)
		if err != nil {
			continue // not this customer's: never listed
		}
		out.Items = append(out.Items, v)
	}
	if more {
		out.NextCursor = ptr(strconv.Itoa(page + 1))
	}
	return out, nil
}

func (s *helpRequestsServiceImpl) Get(ctx context.Context, c *domain.Caller, id string) (domain.HelpRequest, error) {
	if err := requireCustomer(c); err != nil {
		return domain.HelpRequest{}, err
	}
	claim, err := s.d.Claims.Get(ctx, c.BearerToken, id)
	if err != nil {
		return domain.HelpRequest{}, helpRequestErr(err)
	}
	return s.toHelpRequest(c, claim)
}

var helpStatuses = map[string][2]string{
	"AWAITING_SELLER": {domain.HelpReceived, "We've received your request"},
	"UNDER_REVIEW":    {domain.HelpInReview, "Our team is looking into it"},
	"ESCALATED":       {domain.HelpInReview, "Our team is looking into it"},
	"RESOLVED_REFUND": {domain.HelpResolved, "Resolved"},
	"RESOLVED_DENIED": {domain.HelpClosed, "Reviewed and closed"},
	"WITHDRAWN":       {domain.HelpWithdrawn, "Withdrawn"},
}

// toHelpRequest keeps only what the customer may see. A claim that is not the caller's reads as not found.
func (s *helpRequestsServiceImpl) toHelpRequest(c *domain.Caller, k port.UpClaim) (domain.HelpRequest, error) {
	if k.CustomerID != "" && k.CustomerID != c.Subject {
		return domain.HelpRequest{}, helpRequestNotFound(nil)
	}
	st, ok := helpStatuses[k.Status]
	if !ok {
		st = [2]string{domain.HelpInReview, "Our team is looking into it"}
	}
	out := domain.HelpRequest{ID: k.ID, Reference: k.Reference, OrderNumber: k.OrderNumber, Type: k.Type, Status: st[0], StatusLabel: st[1],
		Description: k.Description, EvidenceCount: len(k.Evidence), ClaimedMinor: k.AmountMinor, Currency: firstNonEmpty(k.Currency, domain.Currency),
		CreatedAt: k.OpenedAt, UpdatedAt: firstNonEmpty(k.UpdatedAt, k.OpenedAt)}
	if st[0] == domain.HelpResolved || st[0] == domain.HelpClosed {
		res := &domain.HelpResolution{Outcome: "NOT_REFUNDED", Note: strings.TrimSpace(k.DecisionNote), DecidedAt: optStr(k.DecidedAt)}
		if k.Outcome == "REFUND" {
			res.Outcome = "REFUNDED"
			res.RefundMinor = k.RefundMinor
		}
		out.Resolution = res
	}
	return out, nil
}
