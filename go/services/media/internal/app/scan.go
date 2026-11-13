package app

import (
	"bytes"
	"context"
	"errors"
	"io"

	"github.com/billionairedev24/diaspora-commerce/go/services/media/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/media/internal/port"
)

// ScanMode says what happens to a file when the malware scanner gives no verdict.
type ScanMode string

// Scan modes.
const (
	// ScanRequired is fail-closed: the file stays PENDING and is not served until a scan has passed.
	ScanRequired ScanMode = "required"
	// ScanBestEffort scans, but lets the file through (and logs it) when the scanner is unavailable.
	ScanBestEffort ScanMode = "best-effort"
	// ScanSkip does not scan.
	ScanSkip ScanMode = "skip"
)

// ScanPolicy sets the mode per kind of purpose: Documents for the purposes domain.ScanRequired names (résumés, KYC,
// purchase orders, shipping documents, proofs), Images for the public image purposes (already decode-checked and
// re-encoded). Other purposes (exports) are not scanned.
type ScanPolicy struct {
	Documents ScanMode
	Images    ScanMode
}

// WithScanner adds the malware scan to the step that releases an upload (PENDING to READY).
func WithScanner(sc port.Scanner, policy ScanPolicy) Option {
	return func(s *mediaServiceImpl) {
		s.scanner, s.scanPolicy = sc, policy
		if s.scanPolicy.Documents == "" {
			s.scanPolicy.Documents = ScanRequired
		}
		if s.scanPolicy.Images == "" {
			s.scanPolicy.Images = ScanBestEffort
		}
	}
}

// scanMode is the mode that applies to a purpose (ScanSkip when there is no scanner).
func (s *mediaServiceImpl) scanMode(p domain.Purpose) ScanMode {
	if s.scanner == nil {
		return ScanSkip
	}
	switch {
	case domain.ScanRequired(p):
		return s.scanPolicy.Documents
	case p == domain.PurposeProductImage || p == domain.PurposeStorefront:
		return s.scanPolicy.Images
	default:
		return ScanSkip
	}
}

type scanOutcome int

const (
	scanPassed scanOutcome = iota
	scanInfected
	scanHeld
)

// scan runs the scanner over the stored object (data is the object when the caller already read it). Infected files
// are audited; an unavailable scanner holds the file in required mode and is logged and ignored in best-effort mode.
func (s *mediaServiceImpl) scan(ctx context.Context, st port.ObjectStore, a domain.Asset, data []byte) scanOutcome {
	mode := s.scanMode(a.Purpose)
	if mode == ScanSkip {
		return scanPassed
	}
	var r io.Reader
	size := int64(len(data))
	if data != nil {
		r = bytes.NewReader(data)
	} else {
		body, n, _, err := st.Open(ctx, a.Key, 0)
		if err != nil {
			return s.noVerdict(ctx, a, mode, err)
		}
		defer func() { _ = body.Close() }()
		r, size = body, n
	}
	res, err := s.scanner.Scan(ctx, r, size)
	if err != nil {
		if !errors.Is(err, domain.ErrScannerUnavailable) {
			err = errors.Join(domain.ErrScannerUnavailable, err)
		}
		return s.noVerdict(ctx, a, mode, err)
	}
	if res.Infected {
		s.log.WarnContext(ctx, "audit: malware found, upload rejected and deleted", "audit", true,
			"event", "media.upload.infected", "asset_id", a.ID, "purpose", string(a.Purpose),
			"owner_org", a.OwnerOrg, "uploaded_by", a.UploadedBy, "signature", res.Signature)
		return scanInfected
	}
	return scanPassed
}

func (s *mediaServiceImpl) noVerdict(ctx context.Context, a domain.Asset, mode ScanMode, err error) scanOutcome {
	if mode == ScanRequired {
		s.log.WarnContext(ctx, "audit: malware scan unavailable, file held", "audit", true,
			"event", "media.upload.scan_held", "asset_id", a.ID, "purpose", string(a.Purpose), "error", err)
		return scanHeld
	}
	s.log.WarnContext(ctx, "malware scan unavailable, file released unscanned (best-effort)", "asset_id", a.ID,
		"purpose", string(a.Purpose), "error", err)
	return scanPassed
}
