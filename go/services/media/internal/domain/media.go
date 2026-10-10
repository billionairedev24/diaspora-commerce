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
)

// Rules for a purpose.
type Rules struct {
	MaxBytes     int64
	ContentTypes []string
	Public       bool
	MinEdge      int // minimum width and height for images (0 = no rule)
}

// RulesFor returns the limits for a purpose.
func RulesFor(p Purpose) (Rules, error) {
	images := []string{"image/jpeg", "image/png", "image/webp"}
	switch p {
	case PurposeProductImage:
		return Rules{MaxBytes: 8 << 20, ContentTypes: images, Public: true, MinEdge: 500}, nil
	case PurposeStorefront:
		return Rules{MaxBytes: 8 << 20, ContentTypes: images, Public: true}, nil
	case PurposeProofPhoto, PurposeReturnPhoto:
		return Rules{MaxBytes: 10 << 20, ContentTypes: images}, nil
	case PurposeKYCDocument:
		return Rules{MaxBytes: 15 << 20, ContentTypes: append(images, "application/pdf")}, nil
	default:
		return Rules{}, fmt.Errorf("%w: unknown purpose %q", ErrRejected, p)
	}
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
}

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
	for _, f := range flags {
		if !strings.HasPrefix(f, "eligible") {
			return StatusFlagged, flags
		}
	}
	return StatusReady, flags
}
