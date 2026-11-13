// Package urlfetch implements port.URLFetcher: https downloads of public files with an SSRF guard (S396).
//
// The guard, in the order a request meets it:
//   - the link must be https on port 443 (or no port), without a user name, and its host must be a DNS name: IP
//     literals in any spelling (dotted, decimal, octal, hex, bracketed IPv6) are refused before any lookup;
//   - the name is resolved once; when any answer is not a public unicast address the whole fetch is refused (a name
//     that mixes public and private answers is a rebinding attempt), and the connection is made to the address that
//     was checked, never to a second lookup (DNS pinning); the dialler re-checks the address it really connects to;
//   - every redirect target goes through the same checks (https, 443, no IP literal, resolve once, pin), at most
//     MaxRedirects of them;
//   - the whole download has a time limit and a byte limit.
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
	anyPort   bool
}

var _ port.URLFetcher = (*fetcherImpl)(nil)

// Resolver looks a host name up. *net.Resolver satisfies it.
type Resolver interface {
	LookupNetIP(ctx context.Context, network, host string) ([]netip.Addr, error)
}

// Options configure the fetcher.
type Options struct {
	// Timeout of the whole download (default 20s).
	Timeout time.Duration
	// AllowAddress decides whether an IP may be dialled (default: public unicast only). Tests allow loopback.
	AllowAddress func(netip.Addr) bool
	// AllowHTTP permits http:// URLs (tests only).
	AllowHTTP bool
	// AnyPort permits ports other than 443 and IP-literal hosts (tests only).
	AnyPort bool
	// Resolver answers DNS lookups (default net.DefaultResolver). Tests inject one that plays a rebinding name server.
	Resolver Resolver
	// Dial connects to an already checked address (default: a net.Dialer with a 5 s timeout that re-checks the address it
	// connects to). Tests record the address or point it at a local server.
	Dial func(ctx context.Context, network, address string) (net.Conn, error)
}

var errPrivate = fmt.Errorf("%w: the link points to a private or reserved address", domain.ErrRejected)

// New returns a fetcher that only dials public addresses.
func New(o Options) port.URLFetcher {
	if o.Timeout <= 0 {
		o.Timeout = 20 * time.Second
	}
	allow := o.AllowAddress
	if allow == nil {
		allow = PublicAddress
	}
	resolver := o.Resolver
	if resolver == nil {
		resolver = net.DefaultResolver
	}
	dial := o.Dial
	if dial == nil {
		dialer := &net.Dialer{Timeout: 5 * time.Second, Control: func(_, address string, _ syscall.RawConn) error {
			host, _, err := net.SplitHostPort(address)
			if err != nil {
				return fmt.Errorf("%w: %w", domain.ErrRejected, err)
			}
			ip, err := netip.ParseAddr(host)
			if err != nil || !allow(ip.Unmap()) {
				return errPrivate
			}
			return nil
		}}
		dial = dialer.DialContext
	}
	f := &fetcherImpl{allowHTTP: o.AllowHTTP, anyPort: o.AnyPort}
	// The dial function is the pin: it resolves the host once, refuses the fetch when any answer is not allowed, and
	// connects to an address that was checked. Nothing resolves the name a second time.
	pinned := func(ctx context.Context, network, address string) (net.Conn, error) {
		host, port, err := net.SplitHostPort(address)
		if err != nil {
			return nil, fmt.Errorf("%w: %w", domain.ErrRejected, err)
		}
		addrs, err := resolver.LookupNetIP(ctx, "ip", host)
		if err != nil || len(addrs) == 0 {
			return nil, fmt.Errorf("%w: the link could not be reached", domain.ErrRejected)
		}
		for _, a := range addrs {
			if !allow(a.Unmap()) {
				return nil, errPrivate
			}
		}
		var last error
		for _, a := range addrs {
			c, derr := dial(ctx, network, net.JoinHostPort(a.Unmap().String(), port))
			if derr == nil {
				return c, nil
			}
			last = derr
		}
		return nil, last
	}
	transport := &http.Transport{DialContext: pinned, TLSHandshakeTimeout: 5 * time.Second,
		ResponseHeaderTimeout: 10 * time.Second, MaxIdleConns: 20, IdleConnTimeout: 30 * time.Second,
		ForceAttemptHTTP2: true}
	f.client = &http.Client{Timeout: o.Timeout, Transport: transport,
		CheckRedirect: func(req *http.Request, via []*http.Request) error {
			if len(via) >= MaxRedirects {
				return fmt.Errorf("%w: too many redirects", domain.ErrRejected)
			}
			return f.checkURL(req.URL)
		}}
	return f
}

// checkURL applies the rules that need no network: scheme, user name, port and an IP-literal host.
func (f *fetcherImpl) checkURL(u *url.URL) error {
	if u.Host == "" {
		return fmt.Errorf("%w: not a valid link", domain.ErrRejected)
	}
	if u.Scheme != "https" && (u.Scheme != "http" || !f.allowHTTP) {
		return fmt.Errorf("%w: use an https link", domain.ErrRejected)
	}
	if u.User != nil {
		return fmt.Errorf("%w: links with a user name are not allowed", domain.ErrRejected)
	}
	if p := u.Port(); p != "" && p != "443" && !f.anyPort {
		return fmt.Errorf("%w: only the standard https port (443) is allowed", domain.ErrRejected)
	}
	if IPLiteral(u.Hostname()) && !f.anyPort {
		return fmt.Errorf("%w: use a link with a web address, not an IP address", domain.ErrRejected)
	}
	return nil
}

// IPLiteral reports whether a host is an IP address in any spelling: a dotted or IPv6 address (with or without
// brackets or a zone), or the single-number, octal and hex forms older resolvers still read as IPv4. A real host
// name never ends in a numeric label, so that is the test.
func IPLiteral(host string) bool {
	h := strings.Trim(strings.TrimSpace(host), "[]")
	if h == "" {
		return false
	}
	if _, err := netip.ParseAddr(h); err == nil {
		return true
	}
	if strings.Contains(h, ":") {
		return true // an IPv6 form the parser did not accept is still not a name
	}
	h = strings.TrimSuffix(h, ".")
	last := h[strings.LastIndex(h, ".")+1:]
	if last == "" {
		return false
	}
	if strings.HasPrefix(strings.ToLower(last), "0x") {
		return true
	}
	for _, c := range last {
		if c < '0' || c > '9' {
			return false
		}
	}
	return true
}

// PublicAddress reports whether ip is a public unicast address (not loopback, private, link-local, multicast,
// unspecified, CGNAT, documentation or the reserved ranges below).
func PublicAddress(ip netip.Addr) bool {
	ip = ip.Unmap()
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

// reserved are the ranges that are not private in the RFC 1918 sense but never hold a public web server: this-network,
// CGNAT (also some clouds' metadata, 100.100.100.200), protocol assignments (192.0.0.192), documentation, benchmarking,
// 6to4 relays, reserved and broadcast, and the IPv6 forms that carry an IPv4 address inside (IPv4-compatible, NAT64,
// 6to4, Teredo), site-local and the discard block.
var reserved = []netip.Prefix{
	netip.MustParsePrefix("0.0.0.0/8"), netip.MustParsePrefix("100.64.0.0/10"), netip.MustParsePrefix("192.0.0.0/24"),
	netip.MustParsePrefix("192.0.2.0/24"), netip.MustParsePrefix("192.88.99.0/24"), netip.MustParsePrefix("198.18.0.0/15"),
	netip.MustParsePrefix("198.51.100.0/24"), netip.MustParsePrefix("203.0.113.0/24"),
	netip.MustParsePrefix("240.0.0.0/4"), netip.MustParsePrefix("::/96"), netip.MustParsePrefix("100::/64"),
	netip.MustParsePrefix("2001::/32"), netip.MustParsePrefix("2001:db8::/32"), netip.MustParsePrefix("2002::/16"),
	netip.MustParsePrefix("64:ff9b::/96"), netip.MustParsePrefix("64:ff9b:1::/48"), netip.MustParsePrefix("fec0::/10"),
}

func (f *fetcherImpl) Fetch(ctx context.Context, rawURL string, maxBytes int64) (port.FetchedFile, error) {
	u, err := url.Parse(strings.TrimSpace(rawURL))
	if err != nil {
		return port.FetchedFile{}, fmt.Errorf("%w: not a valid link", domain.ErrRejected)
	}
	if err := f.checkURL(u); err != nil {
		return port.FetchedFile{}, err
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
