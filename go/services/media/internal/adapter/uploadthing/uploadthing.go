// Package uploadthing implements port.ObjectStore with UploadThing (v7) as the SDK `uploadthing@7` does it:
//
//   - uploads: POST /v7/prepareUpload with the secret key returns a presigned ingest URL; the client PUTs the file
//     there as multipart/form-data (field "file", header Range: bytes=0-). Browser uploads are always private: a
//     public image is released only after moderation, by a server-side upload of the cleaned file (Put);
//   - downloads: files live on the UploadThing CDN, https://<appId>.ufs.sh/f/<key>; private files are read through
//     URLs signed locally with the secret key (HMAC-SHA256 over the URL with an `expires` parameter in epoch
//     milliseconds, appended as `signature=hmac-sha256=<hex>`, at most 7 days) — no API round trip;
//   - deletes: POST /v6/deleteFiles.
package uploadthing

import (
	"bytes"
	"context"
	"crypto/hmac"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"mime/multipart"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/services/media/internal/port"
)

// SDKVersion is sent as x-uploadthing-version (the ingest protocol version the requests follow).
const SDKVersion = "7.7.4"

// Options configure UploadThing.
type Options struct {
	APIKey  string // UPLOADTHING_API_KEY (sk_live_… / sk_test_…), or from UPLOADTHING_TOKEN
	AppID   string // for CDN URLs
	BaseURL string // API, defaults to https://api.uploadthing.com
	// CDNBaseURL overrides https://<appId>.ufs.sh (tests).
	CDNBaseURL string
	Region     string
	// Now is the clock used for signed URLs (tests); defaults to time.Now.
	Now func() time.Time
}

// Token is the decoded UPLOADTHING_TOKEN (base64 JSON issued by the UploadThing dashboard).
type Token struct {
	APIKey  string   `json:"apiKey"`
	AppID   string   `json:"appId"`
	Regions []string `json:"regions"`
}

// ParseToken decodes UPLOADTHING_TOKEN.
func ParseToken(raw string) (Token, error) {
	b, err := base64.StdEncoding.DecodeString(strings.TrimSpace(raw))
	if err != nil {
		b, err = base64.RawStdEncoding.DecodeString(strings.TrimSpace(raw))
	}
	if err != nil {
		return Token{}, fmt.Errorf("uploadthing token: %w", err)
	}
	var t Token
	if err := json.Unmarshal(b, &t); err != nil {
		return Token{}, fmt.Errorf("uploadthing token: %w", err)
	}
	if !strings.HasPrefix(t.APIKey, "sk_") || t.AppID == "" {
		return Token{}, errors.New("uploadthing token: apiKey (sk_…) and appId are required")
	}
	return t, nil
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
	if o.CDNBaseURL == "" {
		o.CDNBaseURL = fmt.Sprintf("https://%s.ufs.sh", o.AppID)
	}
	if o.Now == nil {
		o.Now = time.Now
	}
	return &utImpl{opts: o, client: client}
}

func (u *utImpl) Name() string { return "uploadthing" }

func (u *utImpl) cdn(key string) string {
	return strings.TrimSuffix(u.opts.CDNBaseURL, "/") + "/f/" + url.PathEscape(key)
}

type prepared struct {
	Key string `json:"key"`
	URL string `json:"url"`
}

func (u *utImpl) prepare(ctx context.Context, customID, fileName, contentType string, size int64, acl string,
	ttl time.Duration,
) (prepared, error) {
	var out prepared
	body := map[string]any{
		"fileName": fileName, "fileSize": size, "fileType": contentType, "customId": customID,
		"contentDisposition": "inline", "acl": acl, "expiresIn": int(ttl.Seconds()),
	}
	if u.opts.Region != "" {
		body["region"] = u.opts.Region
	}
	if err := u.post(ctx, "/v7/prepareUpload", body, &out); err != nil {
		return prepared{}, err
	}
	if out.Key == "" || out.URL == "" {
		return prepared{}, errors.New("uploadthing prepareUpload: no key or url")
	}
	return out, nil
}

// PrepareUpload issues a private upload: public images are published by Put once moderation passes.
func (u *utImpl) PrepareUpload(ctx context.Context, key, fileName, contentType string, size int64, ttl time.Duration) (port.Upload, error) {
	p, err := u.prepare(ctx, customID(key), fileName, contentType, size, "private", ttl)
	if err != nil {
		return port.Upload{}, err
	}
	return port.Upload{Method: http.MethodPut, URL: p.URL, FormField: "file",
		Headers: map[string]string{"Range": "bytes=0-", "x-uploadthing-version": SDKVersion},
		Key:     p.Key, ExpiresAt: u.opts.Now().Add(ttl)}, nil
}

// customID keeps the customId within UploadThing's 128 characters (keys are <purpose>/<owner>/<yyyy/mm>/<uuid>.ext).
func customID(key string) string {
	if len(key) <= 128 {
		return key
	}
	return key[len(key)-128:]
}

func (u *utImpl) Put(ctx context.Context, key, fileName, contentType string, data []byte, public bool) (string, string, error) {
	acl := "private"
	if public {
		acl = "public-read"
	}
	p, err := u.prepare(ctx, customID(key), fileName, contentType, int64(len(data)), acl, 10*time.Minute)
	if err != nil {
		return "", "", err
	}
	var body bytes.Buffer
	mw := multipart.NewWriter(&body)
	fw, err := mw.CreateFormFile("file", fileName)
	if err != nil {
		return "", "", fmt.Errorf("multipart: %w", err)
	}
	if _, err := fw.Write(data); err != nil {
		return "", "", fmt.Errorf("multipart: %w", err)
	}
	if err := mw.Close(); err != nil {
		return "", "", fmt.Errorf("multipart: %w", err)
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodPut, p.URL, &body)
	if err != nil {
		return "", "", fmt.Errorf("request: %w", err)
	}
	req.Header.Set("Content-Type", mw.FormDataContentType())
	req.Header.Set("Range", "bytes=0-")
	req.Header.Set("x-uploadthing-version", SDKVersion)
	res, err := u.client.Do(req)
	if err != nil {
		return "", "", fmt.Errorf("uploadthing ingest: %w", err)
	}
	defer func() { _ = res.Body.Close() }()
	if res.StatusCode/100 != 2 {
		msg, _ := io.ReadAll(io.LimitReader(res.Body, 1024))
		return "", "", fmt.Errorf("uploadthing ingest: status %d: %s", res.StatusCode, msg)
	}
	_, _ = io.Copy(io.Discard, io.LimitReader(res.Body, 64<<10))
	publicURL := ""
	if public {
		publicURL = u.cdn(p.Key)
	}
	return p.Key, publicURL, nil
}

func (u *utImpl) Open(ctx context.Context, key string, n int64) (io.ReadCloser, int64, string, error) {
	signed, err := u.SignedGetURL(ctx, key, 5*time.Minute)
	if err != nil {
		return nil, 0, "", err
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, signed, nil)
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

// maxFileAccessTTL is the longest signed URL lifetime UploadThing accepts (7 days).
const maxFileAccessTTL = 7 * 24 * time.Hour

func (u *utImpl) MaxSignedURLTTL() time.Duration { return maxFileAccessTTL }

// SignedGetURL signs a CDN URL locally, the way the SDK's UTApi.generateSignedURL does.
func (u *utImpl) SignedGetURL(_ context.Context, key string, ttl time.Duration) (string, error) {
	if key == "" {
		return "", errors.New("empty key")
	}
	ttl = min(ttl, maxFileAccessTTL)
	expires := u.opts.Now().Add(ttl).UnixMilli()
	unsigned := u.cdn(key) + "?expires=" + strconv.FormatInt(expires, 10)
	h := hmac.New(sha256.New, []byte(u.opts.APIKey))
	_, _ = h.Write([]byte(unsigned))
	// URLSearchParams encodes "=" in the value as %3D.
	return unsigned + "&signature=hmac-sha256%3D" + hex.EncodeToString(h.Sum(nil)), nil
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
	req.Header.Set("x-uploadthing-version", SDKVersion)
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
