// Package urlfetch implements port.URLFetcher: https downloads of public files with an SSRF guard (every dialled
// address must be a public unicast IP), a redirect limit, a timeout and a size limit.
package urlfetch

import (
	"context"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/netip"
	"net/url"
	"path"
	"strings"
	"syscall"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/services/media/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/media/internal/port"
)

// MaxRedirects bounds the redirects followed for one fetch.
const MaxRedirects = 3

type fetcherImpl struct {
	client    *http.Client
	allowHTTP bool
}

var _ port.URLFetcher = (*fetcherImpl)(nil)

// Options configure the fetcher.
type Options struct {
	// Timeout of the whole download (default 20s).
	Timeout time.Duration
	// AllowAddress decides whether an IP may be dialled (default: public unicast only). Tests allow loopback.
	AllowAddress func(netip.Addr) bool
	// AllowHTTP permits http:// URLs (tests only).
	AllowHTTP bool
}

// New returns a fetcher that only dials public addresses.
func New(o Options) port.URLFetcher {
	if o.Timeout <= 0 {
		o.Timeout = 20 * time.Second
	}
	allow := o.AllowAddress
	if allow == nil {
		allow = PublicAddress
	}
	dialer := &net.Dialer{Timeout: 5 * time.Second, Control: func(_, address string, _ syscall.RawConn) error {
		host, _, err := net.SplitHostPort(address)
		if err != nil {
			return fmt.Errorf("%w: %w", domain.ErrRejected, err)
		}
		ip, err := netip.ParseAddr(host)
		if err != nil || !allow(ip.Unmap()) {
			return fmt.Errorf("%w: the link points to a private or reserved address", domain.ErrRejected)
		}
		return nil
	}}
	transport := &http.Transport{DialContext: dialer.DialContext, TLSHandshakeTimeout: 5 * time.Second,
		ResponseHeaderTimeout: 10 * time.Second, MaxIdleConns: 20, IdleConnTimeout: 30 * time.Second,
		ForceAttemptHTTP2: true}
	allowHTTP := o.AllowHTTP
	client := &http.Client{Timeout: o.Timeout, Transport: transport,
		CheckRedirect: func(req *http.Request, via []*http.Request) error {
			if len(via) >= MaxRedirects {
				return fmt.Errorf("%w: too many redirects", domain.ErrRejected)
			}
			if req.URL.Scheme != "https" && !allowHTTP {
				return fmt.Errorf("%w: redirected to a non-https link", domain.ErrRejected)
			}
			return nil
		}}
	return &fetcherImpl{client: client, allowHTTP: allowHTTP}
}

// PublicAddress reports whether ip is a public unicast address (not loopback, private, link-local, multicast,
// unspecified, CGNAT or documentation ranges).
func PublicAddress(ip netip.Addr) bool {
	if !ip.IsValid() || ip.IsLoopback() || ip.IsPrivate() || ip.IsLinkLocalUnicast() || ip.IsLinkLocalMulticast() ||
		ip.IsInterfaceLocalMulticast() || ip.IsMulticast() || ip.IsUnspecified() {
		return false
	}
	for _, p := range reserved {
		if p.Contains(ip) {
			return false
		}
	}
	return true
}

var reserved = []netip.Prefix{
	netip.MustParsePrefix("100.64.0.0/10"), netip.MustParsePrefix("192.0.0.0/24"),
	netip.MustParsePrefix("192.0.2.0/24"), netip.MustParsePrefix("198.18.0.0/15"),
	netip.MustParsePrefix("198.51.100.0/24"), netip.MustParsePrefix("203.0.113.0/24"),
	netip.MustParsePrefix("240.0.0.0/4"), netip.MustParsePrefix("2001:db8::/32"),
	netip.MustParsePrefix("64:ff9b::/96"),
}

func (f *fetcherImpl) Fetch(ctx context.Context, rawURL string, maxBytes int64) (port.FetchedFile, error) {
	u, err := url.Parse(strings.TrimSpace(rawURL))
	if err != nil || u.Host == "" {
		return port.FetchedFile{}, fmt.Errorf("%w: not a valid link", domain.ErrRejected)
	}
	if u.Scheme != "https" && (u.Scheme != "http" || !f.allowHTTP) {
		return port.FetchedFile{}, fmt.Errorf("%w: use an https link", domain.ErrRejected)
	}
	if u.User != nil {
		return port.FetchedFile{}, fmt.Errorf("%w: links with a user name are not allowed", domain.ErrRejected)
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, u.String(), nil)
	if err != nil {
		return port.FetchedFile{}, fmt.Errorf("%w: %w", domain.ErrRejected, err)
	}
	req.Header.Set("User-Agent", "AfroTransact-Media/1.0 (product image import)")
	req.Header.Set("Accept", "image/jpeg, image/png, image/webp")
	res, err := f.client.Do(req) //nolint:gosec // G704: the dialer refuses every non-public address (SSRF guard)
	if err != nil {
		if errors.Is(err, domain.ErrRejected) {
			return port.FetchedFile{}, fmt.Errorf("fetch image: %w", err)
		}
		return port.FetchedFile{}, fmt.Errorf("%w: the link could not be reached", domain.ErrRejected)
	}
	defer func() { _ = res.Body.Close() }()
	if res.StatusCode != http.StatusOK {
		return port.FetchedFile{}, fmt.Errorf("%w: the link answered %s", domain.ErrRejected, res.Status)
	}
	if res.ContentLength > maxBytes {
		return port.FetchedFile{}, fmt.Errorf("%w: larger than %d MB", domain.ErrRejected, maxBytes>>20)
	}
	data, err := io.ReadAll(io.LimitReader(res.Body, maxBytes+1))
	if err != nil {
		return port.FetchedFile{}, fmt.Errorf("%w: the download was interrupted", domain.ErrRejected)
	}
	if int64(len(data)) > maxBytes {
		return port.FetchedFile{}, fmt.Errorf("%w: larger than %d MB", domain.ErrRejected, maxBytes>>20)
	}
	name := path.Base(res.Request.URL.Path)
	if name == "." || name == "/" {
		name = ""
	}
	ct := strings.TrimSpace(strings.Split(res.Header.Get("Content-Type"), ";")[0])
	return port.FetchedFile{Data: data, ContentType: ct, FileName: name}, nil
}
