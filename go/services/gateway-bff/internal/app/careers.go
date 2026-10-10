package app

import (
	"context"
	"net/http"
	"path"
	"strings"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

// CareersService signs résumé uploads for the careers form through media-service (direct, signed upload) and
// finalises them; the asset id then goes into the application as resumeAssetId. Applicants are usually guests, so
// the BFF uploads with its own client-credentials token unless the shopper is signed in.
type CareersService interface {
	ResumeUpload(ctx context.Context, c *domain.Caller, fileName, contentType string, size int64) (domain.ResumeUpload, error)
	CompleteResume(ctx context.Context, c *domain.Caller, assetID string) (domain.ResumeAsset, error)
}

// ResumePurpose is the media purpose used for résumés: private storage that accepts PDF. (Media has no dedicated
// careers purpose yet; kyc-document is the private, PDF-capable one.)
const ResumePurpose = "kyc-document"

type careersServiceImpl struct {
	media  port.MediaClient
	tokens port.ServiceTokens
}

var _ CareersService = (*careersServiceImpl)(nil)

// NewCareersService returns the CareersService.
func NewCareersService(media port.MediaClient, tokens port.ServiceTokens) CareersService {
	return &careersServiceImpl{media: media, tokens: tokens}
}

func (s *careersServiceImpl) bearer(ctx context.Context, c *domain.Caller) (string, error) {
	if c.Authenticated() {
		return c.BearerToken, nil
	}
	t, err := s.tokens.Token(ctx, "")
	if err != nil {
		return "", mapErr("identity", err)
	}
	return t, nil
}

func (s *careersServiceImpl) ResumeUpload(ctx context.Context, c *domain.Caller, fileName, contentType string, size int64) (domain.ResumeUpload, error) {
	fileName = strings.TrimSpace(path.Base(strings.ReplaceAll(fileName, "\\", "/")))
	errs := map[string]string{}
	if fileName == "" || fileName == "." || len(fileName) > 200 {
		errs["fileName"] = "Choose a file"
	}
	if !strings.EqualFold(contentType, domain.ResumeContentType) || !strings.HasSuffix(strings.ToLower(fileName), ".pdf") {
		errs["contentType"] = "Upload your résumé as a PDF"
	}
	if size <= 0 || size > domain.ResumeMaxBytes {
		errs["size"] = "Your résumé must be 5 MB or smaller"
	}
	if len(errs) > 0 {
		return domain.ResumeUpload{}, domain.ValidationProblem(errs)
	}
	bearer, err := s.bearer(ctx, c)
	if err != nil {
		return domain.ResumeUpload{}, err
	}
	up, err := s.media.RequestUpload(ctx, bearer, ResumePurpose, fileName, domain.ResumeContentType, size)
	if err != nil {
		return domain.ResumeUpload{}, mapErr("media", err)
	}
	headers := up.Upload.Headers
	if headers == nil {
		headers = map[string]string{"Content-Type": domain.ResumeContentType}
	}
	return domain.ResumeUpload{AssetID: up.Asset.ID, MaxBytes: domain.ResumeMaxBytes, Upload: domain.ResumeUploadURL{
		Method: firstNonEmpty(up.Upload.Method, http.MethodPut), URL: up.Upload.URL, Headers: headers, ExpiresAt: up.Upload.ExpiresAt}}, nil
}

func (s *careersServiceImpl) CompleteResume(ctx context.Context, c *domain.Caller, assetID string) (domain.ResumeAsset, error) {
	bearer, err := s.bearer(ctx, c)
	if err != nil {
		return domain.ResumeAsset{}, err
	}
	a, err := s.media.Complete(ctx, bearer, assetID)
	if err != nil {
		return domain.ResumeAsset{}, mapErr("media", err)
	}
	if a.Status != "READY" {
		return domain.ResumeAsset{}, domain.ValidationProblem(map[string]string{"file": "We couldn't read that file. Upload a PDF of 5 MB or less."})
	}
	out := domain.ResumeAsset{AssetID: a.ID, ContentType: a.ContentType, Status: a.Status}
	if a.Bytes != nil {
		out.Bytes = *a.Bytes
	}
	return out, nil
}
