// Package domain holds media assets, upload purposes and their rules.
package domain

import (
	"errors"
	"fmt"
	"slices"
	"strings"
	"time"
)

// Purpose decides the storage, limits and visibility of an upload.
type Purpose string

// Upload purposes.
const (
	PurposeProductImage Purpose = "product-image" // public, UploadThing / R2, moderated
	PurposeStorefront   Purpose = "storefront"    // public seller/provider page media
	PurposeProofPhoto   Purpose = "proof-photo"   // driver proof of delivery, private
	PurposeKYCDocument  Purpose = "kyc-document"  // private, object-locked bucket on the primary cloud
	PurposeReturnPhoto  Purpose = "return-photo"  // private

	PurposePurchaseOrder    Purpose = "purchase-order"    // retail PO PDF e-mailed to suppliers, private
	PurposeShippingDocument Purpose = "shipping-document" // inbound shipment papers (ASN, bill of lading …), private
	PurposeCareerResume     Purpose = "career-resume"     // job applicants' résumés, private
	PurposeExport           Purpose = "export"            // staff CSV / XLSX exports, private
)

// Content types beyond images.
const (
	ContentTypePDF  = "application/pdf"
	ContentTypeHEIC = "image/heic"
	ContentTypeCSV  = "text/csv"
	ContentTypeXLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
)

// Service clients (client-credentials token subjects) named in purpose rules.
const (
	ClientRetailService       = "retail-service"
	ClientGatewayBFF          = "gateway-bff"
	ClientDeliveryRunsService = "delivery-runs-service"
)

// PermissionMediaRead is the scope a reader client's token must carry.
const PermissionMediaRead = "media:read"

// ReaderClients are service clients whose own client-credentials tokens, with scope media:read, may read public
// assets (GET /v1/assets/{id}: delivery-runs verifies pre-order creatives and stores their size). They never upload.
func ReaderClients() []string { return []string{ClientDeliveryRunsService} }

// AcceptedServiceClients are the service clients whose own client-credentials tokens media accepts: the clients the
// purpose rules name and the reader clients.
func AcceptedServiceClients() []string {
	out := ServiceClients()
	for _, c := range ReaderClients() {
		if !slices.Contains(out, c) {
			out = append(out, c)
		}
	}
	return out
}

// Rules for a purpose.
type Rules struct {
	MaxBytes     int64
	ContentTypes []string
	Public       bool
	MinEdge      int // minimum width and height for images (0 = no rule)
	// ServiceClients lists the service clients (SERVICE principals by token subject) that may upload and read
	// assets of this purpose. When it is set, other service principals are refused.
	ServiceClients []string
	// StaffOnly refuses human principals other than platform staff (the listed service clients still pass).
	StaffOnly bool
	// LongLinks allows signed download links longer than the default lifetime (up to the configured maximum),
	// for files that travel by e-mail or are downloaded later.
	LongLinks bool
}

// Restricted reports whether the purpose names the service clients it accepts.
func (r Rules) Restricted() bool { return len(r.ServiceClients) > 0 }

// AllowsServiceClient reports whether a SERVICE principal with this subject may use the purpose.
func (r Rules) AllowsServiceClient(subject string) bool {
	return slices.Contains(r.ServiceClients, subject)
}

// RulesFor returns the limits for a purpose.
func RulesFor(p Purpose) (Rules, error) {
	images := []string{"image/jpeg", "image/png", "image/webp"}
	switch p {
	case PurposeProductImage:
		return Rules{MaxBytes: 8 << 20, ContentTypes: images, Public: true, MinEdge: 500}, nil
	case PurposeStorefront:
		return Rules{MaxBytes: 8 << 20, ContentTypes: images, Public: true}, nil
	case PurposeProofPhoto:
		return Rules{MaxBytes: 10 << 20, ContentTypes: images}, nil
	case PurposeReturnPhoto:
		// Evidence photos of a help request come straight from phones, which shoot HEIC.
		return Rules{MaxBytes: 10 << 20, ContentTypes: append(slices.Clone(images), ContentTypeHEIC)}, nil
	case PurposeKYCDocument:
		return Rules{MaxBytes: 15 << 20, ContentTypes: append(images, ContentTypePDF)}, nil
	case PurposePurchaseOrder:
		return Rules{MaxBytes: 10 << 20, ContentTypes: []string{ContentTypePDF}, ServiceClients: documentClients(),
			StaffOnly: true, LongLinks: true}, nil
	case PurposeShippingDocument:
		// Suppliers' papers arrive as PDFs or as scans / photos.
		return Rules{MaxBytes: 15 << 20, ContentTypes: append([]string{ContentTypePDF}, images...),
			ServiceClients: documentClients(), StaffOnly: true, LongLinks: true}, nil
	case PurposeCareerResume:
		// Applicants upload signed in (their own token) or as guests through the BFF's client token.
		return Rules{MaxBytes: 5 << 20, ContentTypes: []string{ContentTypePDF}, ServiceClients: documentClients(),
			LongLinks: true}, nil
	case PurposeExport:
		return Rules{MaxBytes: 50 << 20, ContentTypes: []string{ContentTypeCSV, ContentTypeXLSX},
			ServiceClients: documentClients(), StaffOnly: true, LongLinks: true}, nil
	default:
		return Rules{}, fmt.Errorf("%w: unknown purpose %q", ErrRejected, p)
	}
}

// documentClients are the service clients accepted for the document and export purposes.
func documentClients() []string { return []string{ClientRetailService, ClientGatewayBFF} }

// Purposes lists every upload purpose.
func Purposes() []Purpose {
	return []Purpose{PurposeProductImage, PurposeStorefront, PurposeProofPhoto, PurposeReturnPhoto, PurposeKYCDocument,
		PurposePurchaseOrder, PurposeShippingDocument, PurposeCareerResume, PurposeExport}
}

// ServiceClients returns every service client named by a purpose rule (their own client-credentials tokens are
// accepted by media).
func ServiceClients() []string {
	var out []string
	for _, p := range Purposes() {
		r, _ := RulesFor(p)
		for _, c := range r.ServiceClients {
			if !slices.Contains(out, c) {
				out = append(out, c)
			}
		}
	}
	return out
}

// Status of an asset.
type Status string

// Asset statuses.
const (
	StatusPending  Status = "PENDING"  // upload URL issued
	StatusReady    Status = "READY"    // uploaded, inspected, passed checks
	StatusFlagged  Status = "FLAGGED"  // uploaded but failed a moderation heuristic; needs review
	StatusRejected Status = "REJECTED" // wrong type/size; object deleted
)

// Asset is one uploaded file.
type Asset struct {
	ID          string    `json:"id"`
	OwnerOrg    string    `json:"ownerOrg"`
	UploadedBy  string    `json:"uploadedBy"`
	Purpose     Purpose   `json:"purpose"`
	Provider    string    `json:"provider"`
	Key         string    `json:"key"`
	URL         string    `json:"url,omitempty"`
	FileName    string    `json:"fileName"`
	ContentType string    `json:"contentType"`
	Bytes       int64     `json:"bytes"`
	Width       int       `json:"width,omitempty"`
	Height      int       `json:"height,omitempty"`
	Status      Status    `json:"status"`
	Flags       []string  `json:"flags"`
	CreatedAt   time.Time `json:"createdAt"`
	// URLExpiresAt is when a signed download URL in URL stops working (private assets only).
	URLExpiresAt *time.Time `json:"urlExpiresAt,omitempty"`
	// Moderation is set for public images once completed: the checks that gate publication.
	Moderation *Moderation `json:"moderation,omitempty"`
	// PHash is the 64-bit perceptual (difference) hash of an image, for duplicate detection (never serialised).
	PHash *uint64 `json:"-"`
	// Version guards updates (optimistic locking): an update of a stale copy fails with ErrConflict.
	Version int `json:"-"`
}

// ErrConflict is returned when an asset changed since it was read (concurrent completion or review).
var ErrConflict = errors.New("asset changed concurrently")

// QuarantinePrefix starts the object key of a public image until moderation releases it: the public store serves
// only the purpose prefixes, never quarantine/ (bucket policy), and UploadThing keeps such files private.
const QuarantinePrefix = "quarantine/"

// ErrRejected marks uploads that break the purpose rules.
var ErrRejected = errors.New("upload rejected")

// CheckDeclared validates what the client says it will upload, before issuing a URL.
func CheckDeclared(p Purpose, fileName, contentType string, size int64) error {
	r, err := RulesFor(p)
	if err != nil {
		return err
	}
	if strings.TrimSpace(fileName) == "" || len(fileName) > 200 {
		return fmt.Errorf("%w: file name is required (max 200 characters)", ErrRejected)
	}
	if !slices.Contains(r.ContentTypes, contentType) {
		return fmt.Errorf("%w: %s is not allowed; use %s", ErrRejected, contentType, strings.Join(r.ContentTypes, ", "))
	}
	if size <= 0 || size > r.MaxBytes {
		return fmt.Errorf("%w: file must be 1 byte to %d MB", ErrRejected, r.MaxBytes>>20)
	}
	return nil
}

// Inspection is what the service learns by reading the uploaded object.
type Inspection struct {
	ContentType string
	Bytes       int64
	Width       int
	Height      int
	// BorderBrightness is the mean brightness (0–255) of the outer 3% of pixels; product main images
	// should sit on white (≥ 235).
	BorderBrightness float64
}

// Judge applies the purpose rules to an inspection and returns the final status and moderation flags.
func Judge(p Purpose, in Inspection) (Status, []string) {
	r, err := RulesFor(p)
	if err != nil {
		return StatusRejected, []string{err.Error()}
	}
	if !slices.Contains(r.ContentTypes, in.ContentType) {
		return StatusRejected, []string{"actual file type " + in.ContentType + " is not allowed"}
	}
	if in.Bytes > r.MaxBytes {
		return StatusRejected, []string{"file is larger than the limit"}
	}
	flags := []string{}
	if r.MinEdge > 0 && (in.Width < r.MinEdge || in.Height < r.MinEdge) {
		flags = append(flags, fmt.Sprintf("image is %d×%d; at least %d×%d is required", in.Width, in.Height, r.MinEdge, r.MinEdge))
	}
	if p == PurposeProductImage && in.Width > 0 && in.BorderBrightness < 235 {
		flags = append(flags, "background is not white (needed for a main image)")
	}
	if p == PurposeProductImage && in.Width >= 1000 && in.Height >= 1000 {
		flags = append(flags, "eligible as main image")
	}
	return StatusForFlags(flags), flags
}

// StatusForFlags is FLAGGED when any flag needs attention and READY when the only flags are informational
// ("eligible as main image").
func StatusForFlags(flags []string) Status {
	for _, f := range flags {
		if !strings.HasPrefix(f, "eligible") {
			return StatusFlagged
		}
	}
	return StatusReady
}

// DefaultLinkTTL is how long a signed download link lasts unless the caller asks otherwise.
const DefaultLinkTTL = 5 * time.Minute

// DefaultMaxLongLinkTTL is the default ceiling for purposes with long-lived links (14 days).
const DefaultMaxLongLinkTTL = 14 * 24 * time.Hour

// LinkPolicy bounds the lifetime of signed download links.
type LinkPolicy struct {
	Default time.Duration // used when the caller asks for nothing; also the ceiling for purposes without LongLinks
	MaxLong time.Duration // ceiling for purposes with LongLinks
}

// TTL returns the lifetime of a signed link: the requested one (0 = the default), capped at the default for
// ordinary purposes, at MaxLong for purposes with long links, and at what the store can sign (storeMax, 0 = no
// limit).
func (lp LinkPolicy) TTL(r Rules, requested, storeMax time.Duration) time.Duration {
	ceiling := lp.Default
	if r.LongLinks {
		ceiling = max(ceiling, lp.MaxLong)
	}
	ttl := lp.Default
	if requested > 0 {
		ttl = requested
	}
	ttl = min(ttl, ceiling)
	if storeMax > 0 {
		ttl = min(ttl, storeMax)
	}
	return ttl
}
