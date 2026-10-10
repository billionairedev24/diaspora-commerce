package upstreams

import (
	"context"
	"fmt"
	"net/http"
	"net/url"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/adapter/httpclient"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

// mediaExportsImpl stores staff exports in media-service's object store (contracts/openapi/media.yaml): POST /v1/uploads
// for a direct upload URL, PUT the file to storage, POST /v1/assets/{id}/complete, then GET /v1/assets/{id} for a
// fresh signed URL on every download (media signs for 5 minutes; the BFF enforces the 24-hour link lifetime).
//
// Gap: media's Purpose enum has no "export" and its contentType enum allows images and PDF only, so until media
// accepts text/csv and XLSX exports this store answers 503 and the BFF's own file store (exportfiles) is the default.
type mediaExportsImpl struct {
	c       httpclient.Client
	storage *http.Client
}

var _ port.ExportBlobStore = (*mediaExportsImpl)(nil)

// NewMediaExports returns the media-backed export store; storage is the client for the direct upload (nil = default).
func NewMediaExports(c httpclient.Client, storage *http.Client) port.ExportBlobStore {
	if storage == nil {
		storage = &http.Client{Timeout: 5 * time.Minute}
	}
	return &mediaExportsImpl{c: c, storage: storage}
}

type mediaUpload struct {
	Asset struct {
		ID string `json:"id"`
	} `json:"asset"`
	Upload struct {
		URL     string            `json:"url"`
		Method  string            `json:"method"`
		Headers map[string]string `json:"headers"`
	} `json:"upload"`
}

func (m *mediaExportsImpl) Put(ctx context.Context, bearer string, f port.ExportFile) (string, error) {
	var up mediaUpload
	body := map[string]any{"purpose": "export", "fileName": f.FileName, "contentType": f.ContentType, "size": f.Size}
	if _, err := call(ctx, m.c, httpclient.Request{Method: http.MethodPost, Path: "/v1/uploads", JSON: body, Bearer: bearer}, &up); err != nil {
		if p, ok := port.AsUpstreamProblem(err); ok && p.Status == http.StatusUnprocessableEntity {
			// media does not accept export files yet (purpose / content type).
			return "", fmt.Errorf("media exports: %w", domain.ErrUpstreamUnavailable)
		}
		return "", err
	}
	if up.Upload.URL == "" || up.Asset.ID == "" {
		return "", fmt.Errorf("media exports: no upload url: %w", domain.ErrUpstreamUnavailable)
	}
	method := up.Upload.Method
	if method == "" {
		method = http.MethodPut
	}
	req, err := http.NewRequestWithContext(ctx, method, up.Upload.URL, f.Body)
	if err != nil {
		return "", fmt.Errorf("media exports: upload request: %w", err)
	}
	req.ContentLength = f.Size
	req.Header.Set("Content-Type", f.ContentType)
	for k, v := range up.Upload.Headers {
		req.Header.Set(k, v)
	}
	res, err := m.storage.Do(req) //nolint:gosec // the URL is media-service's signed upload URL, not user input
	if err != nil {
		return "", fmt.Errorf("media exports: upload: %w: %w", domain.ErrUpstreamUnavailable, err)
	}
	_ = res.Body.Close()
	if res.StatusCode >= 300 {
		return "", fmt.Errorf("media exports: upload status %d: %w", res.StatusCode, domain.ErrUpstreamUnavailable)
	}
	if _, err := call(ctx, m.c, httpclient.Request{Method: http.MethodPost, Path: "/v1/assets/" + url.PathEscape(up.Asset.ID) + "/complete", Bearer: bearer}, nil); err != nil {
		return "", err
	}
	return up.Asset.ID, nil
}

func (m *mediaExportsImpl) SignedURL(ctx context.Context, bearer, ref, _ string, _ time.Time) (string, error) {
	var asset struct {
		URL string `json:"url"`
	}
	if _, err := call(ctx, m.c, httpclient.Request{Path: "/v1/assets/" + url.PathEscape(ref), Bearer: bearer}, &asset); err != nil {
		return "", err
	}
	if asset.URL == "" {
		return "", fmt.Errorf("media exports: asset %s has no signed url: %w", ref, domain.ErrUpstreamUnavailable)
	}
	return asset.URL, nil
}
