// Package uploadthing implements port.ObjectStore with UploadThing's REST API (v7): the server asks UploadThing
// for a presigned upload URL with the secret key, the browser uploads directly, and files are served from the
// UploadThing CDN (https://<appId>.ufs.sh/f/<key>). Requests follow the documented v7 endpoints
// (/v7/prepareUpload, /v6/deleteFiles, /v6/requestFileAccess); verify against the live API when rotating keys.
package uploadthing

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"strings"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/services/media/internal/port"
)

// Options configure UploadThing.
type Options struct {
	APIKey  string // UPLOADTHING_API_KEY (sk_live_… / sk_test_…)
	AppID   string // for CDN URLs
	BaseURL string // defaults to https://api.uploadthing.com
	Region  string
}

type utImpl struct {
	opts   Options
	client *http.Client
}

var _ port.ObjectStore = (*utImpl)(nil)

// New returns the UploadThing store.
func New(o Options, client *http.Client) port.ObjectStore {
	if o.BaseURL == "" {
		o.BaseURL = "https://api.uploadthing.com"
	}
	return &utImpl{opts: o, client: client}
}

func (u *utImpl) Name() string { return "uploadthing" }

func (u *utImpl) cdn(key string) string {
	return fmt.Sprintf("https://%s.ufs.sh/f/%s", u.opts.AppID, key)
}

func (u *utImpl) PrepareUpload(ctx context.Context, key, fileName, contentType string, size int64, ttl time.Duration) (port.Upload, error) {
	var out struct {
		Key string `json:"key"`
		URL string `json:"url"`
	}
	err := u.post(ctx, "/v7/prepareUpload", map[string]any{
		"fileName": fileName, "fileSize": size, "fileType": contentType, "customId": key,
		"contentDisposition": "inline", "acl": "public-read", "expiresIn": int(ttl.Seconds()),
	}, &out)
	if err != nil {
		return port.Upload{}, err
	}
	return port.Upload{Method: http.MethodPut, URL: out.URL, Headers: map[string]string{"Content-Type": contentType},
		Key: out.Key, PublicURL: u.cdn(out.Key), ExpiresAt: time.Now().Add(ttl)}, nil
}

func (u *utImpl) Open(ctx context.Context, key string, n int64) (io.ReadCloser, int64, string, error) {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, u.cdn(key), nil)
	if err != nil {
		return nil, 0, "", fmt.Errorf("request: %w", err)
	}
	if n > 0 {
		req.Header.Set("Range", fmt.Sprintf("bytes=0-%d", n-1))
	}
	res, err := u.client.Do(req)
	if err != nil {
		return nil, 0, "", fmt.Errorf("uploadthing get: %w", err)
	}
	if res.StatusCode != http.StatusOK && res.StatusCode != http.StatusPartialContent {
		_ = res.Body.Close()
		return nil, 0, "", fmt.Errorf("uploadthing get %s: status %d", key, res.StatusCode)
	}
	size := res.ContentLength
	if cr := res.Header.Get("Content-Range"); cr != "" {
		if i := strings.LastIndexByte(cr, '/'); i >= 0 {
			_, _ = fmt.Sscan(cr[i+1:], &size)
		}
	}
	return res.Body, size, res.Header.Get("Content-Type"), nil
}

func (u *utImpl) Delete(ctx context.Context, key string) error {
	return u.post(ctx, "/v6/deleteFiles", map[string]any{"fileKeys": []string{key}}, nil)
}

// maxFileAccessTTL is the longest expiresIn UploadThing accepts for requestFileAccess (7 days).
const maxFileAccessTTL = 7 * 24 * time.Hour

func (u *utImpl) MaxSignedURLTTL() time.Duration { return maxFileAccessTTL }

func (u *utImpl) SignedGetURL(ctx context.Context, key string, ttl time.Duration) (string, error) {
	var out struct {
		URL string `json:"url"`
	}
	if err := u.post(ctx, "/v6/requestFileAccess", map[string]any{"fileKey": key, "expiresIn": int(ttl.Seconds())}, &out); err != nil {
		return "", err
	}
	return out.URL, nil
}

func (u *utImpl) post(ctx context.Context, path string, body, out any) error {
	buf, err := json.Marshal(body)
	if err != nil {
		return fmt.Errorf("encode: %w", err)
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, strings.TrimSuffix(u.opts.BaseURL, "/")+path, bytes.NewReader(buf))
	if err != nil {
		return fmt.Errorf("request: %w", err)
	}
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("X-Uploadthing-Api-Key", u.opts.APIKey)
	res, err := u.client.Do(req)
	if err != nil {
		return fmt.Errorf("uploadthing %s: %w", path, err)
	}
	defer func() { _ = res.Body.Close() }()
	if res.StatusCode/100 != 2 {
		msg, _ := io.ReadAll(io.LimitReader(res.Body, 1024))
		return fmt.Errorf("uploadthing %s: status %d: %s", path, res.StatusCode, msg)
	}
	if out == nil {
		return nil
	}
	if err := json.NewDecoder(res.Body).Decode(out); err != nil {
		return fmt.Errorf("uploadthing decode: %w", err)
	}
	return nil
}
