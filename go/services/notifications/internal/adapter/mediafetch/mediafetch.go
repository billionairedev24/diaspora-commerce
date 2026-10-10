// Package mediafetch implements port.AttachmentFetcher: it downloads email attachments from signed media links,
// only from allow-listed hosts (the media base URL's host plus configured storage hosts) and only up to a size limit.
// Signed URLs carry their own authorisation, so no credentials are sent.
package mediafetch

import (
	"context"
	"errors"
	"fmt"
	"io"
	"mime"
	"net/http"
	"net/url"
	"path"
	"strings"

	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/port"
)

// DefaultMaxBytes is the default size limit per attachment (10 MiB).
const DefaultMaxBytes = 10 << 20

const maxRedirects = 3

// Config configures the fetcher.
type Config struct {
	// MediaBaseURL is media-service's base URL; its host is always allowed.
	MediaBaseURL string
	// AllowedHosts are further hosts signed links may point at (object storage, CDN): "host", "host:port",
	// "*.example.com" for any subdomain of example.com, or "host/path/prefix/" for only URLs under that path on the
	// host (media-hosted links on the shared API host, which redirect to object storage).
	AllowedHosts []string
	// MaxBytes is the size limit per file (DefaultMaxBytes when 0).
	MaxBytes int64
}

type fetcherImpl struct {
	allowed  []string
	maxBytes int64
	client   *http.Client
}

var _ port.AttachmentFetcher = (*fetcherImpl)(nil)

// New returns the fetcher. client's transport and timeout are used; redirects are re-checked against the allow-list.
func New(cfg Config, client *http.Client) (port.AttachmentFetcher, error) {
	var allowed []string
	if cfg.MediaBaseURL != "" {
		u, err := url.Parse(cfg.MediaBaseURL)
		if err != nil || u.Host == "" {
			return nil, fmt.Errorf("media base url %q: not an absolute URL", cfg.MediaBaseURL)
		}
		allowed = append(allowed, strings.ToLower(u.Host))
	}
	for _, h := range cfg.AllowedHosts {
		if h = strings.ToLower(strings.TrimSpace(h)); h != "" {
			allowed = append(allowed, h)
		}
	}
	if len(allowed) == 0 {
		return nil, errors.New("no allowed attachment hosts: set the media base URL or allowed hosts")
	}
	if cfg.MaxBytes <= 0 {
		cfg.MaxBytes = DefaultMaxBytes
	}
	f := &fetcherImpl{allowed: allowed, maxBytes: cfg.MaxBytes}
	c := *client
	c.CheckRedirect = func(req *http.Request, via []*http.Request) error {
		if len(via) >= maxRedirects {
			return fmt.Errorf("%w: too many redirects", domain.ErrAttachmentRejected)
		}
		return f.check(req.URL)
	}
	f.client = &c
	return f, nil
}

// check rejects URLs that may not be fetched: other schemes than http/https, user info, hosts not allow-listed
// ("host" entries match any port, "host:port" entries only that port, "host/prefix" entries only that path prefix).
func (f *fetcherImpl) check(u *url.URL) error {
	if u.Scheme != "https" && u.Scheme != "http" {
		return fmt.Errorf("%w: scheme %q", domain.ErrAttachmentRejected, u.Scheme)
	}
	if u.User != nil {
		return fmt.Errorf("%w: credentials in URL", domain.ErrAttachmentRejected)
	}
	host, hostPort := strings.ToLower(u.Hostname()), strings.ToLower(u.Host)
	for _, a := range f.allowed {
		if h, prefix, ok := strings.Cut(a, "/"); ok {
			p := path.Clean("/" + u.EscapedPath())
			if (hostPort == h || host == h) && strings.HasPrefix(p+"/", "/"+prefix) && !strings.Contains(u.EscapedPath(), "..") {
				return nil
			}
			continue
		}
		switch {
		case strings.HasPrefix(a, "*."):
			if strings.HasSuffix(host, a[1:]) && len(host) > len(a)-1 {
				return nil
			}
		case strings.Contains(a, ":"):
			if hostPort == a {
				return nil
			}
		default:
			if host == a {
				return nil
			}
		}
	}
	return fmt.Errorf("%w: host %q is not allow-listed", domain.ErrAttachmentRejected, u.Host)
}

func (f *fetcherImpl) Fetch(ctx context.Context, ref domain.AttachmentRef) (domain.Attachment, error) {
	u, err := url.Parse(ref.URL)
	if err != nil || !u.IsAbs() {
		return domain.Attachment{}, fmt.Errorf("%w: invalid URL", domain.ErrAttachmentRejected)
	}
	if err := f.check(u); err != nil {
		return domain.Attachment{}, err
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, u.String(), nil)
	if err != nil {
		return domain.Attachment{}, fmt.Errorf("build request: %w", err)
	}
	resp, err := f.client.Do(req) //nolint:gosec // G704: the host is checked against the configured allow-list
	if err != nil {
		return domain.Attachment{}, fmt.Errorf("fetch %s: %w", ref.FileName, err)
	}
	defer func() { _ = resp.Body.Close() }()
	if resp.StatusCode != http.StatusOK {
		_, _ = io.Copy(io.Discard, io.LimitReader(resp.Body, 4096))
		return domain.Attachment{}, fmt.Errorf("fetch %s: status %d", ref.FileName, resp.StatusCode)
	}
	if resp.ContentLength > f.maxBytes {
		return domain.Attachment{}, fmt.Errorf("%w: %d bytes over the %d-byte limit", domain.ErrAttachmentRejected,
			resp.ContentLength, f.maxBytes)
	}
	content, err := io.ReadAll(io.LimitReader(resp.Body, f.maxBytes+1))
	if err != nil {
		return domain.Attachment{}, fmt.Errorf("read %s: %w", ref.FileName, err)
	}
	if int64(len(content)) > f.maxBytes {
		return domain.Attachment{}, fmt.Errorf("%w: over the %d-byte limit", domain.ErrAttachmentRejected, f.maxBytes)
	}
	return domain.Attachment{FileName: domain.SafeFileName(ref.FileName),
		ContentType: contentType(ref.ContentType, resp.Header.Get("Content-Type")), Content: content}, nil
}

// contentType prefers the producer's declared type, then the response's, then application/octet-stream.
func contentType(declared, served string) string {
	for _, c := range []string{declared, served} {
		if mt, _, err := mime.ParseMediaType(c); err == nil && strings.Contains(mt, "/") {
			return mt
		}
	}
	return "application/octet-stream"
}
