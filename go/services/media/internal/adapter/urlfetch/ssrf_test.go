package urlfetch_test

import (
	"context"
	"errors"
	"net"
	"net/http"
	"net/http/httptest"
	"net/netip"
	"strings"
	"sync"
	"testing"

	"github.com/billionairedev24/diaspora-commerce/go/services/media/internal/adapter/urlfetch"
	"github.com/billionairedev24/diaspora-commerce/go/services/media/internal/domain"
)

// The SSRF table of S396: addresses, link shapes, DNS rebinding and redirects. Nothing here needs the network: names are
// answered by a fake resolver and connections go to a recording dialler or a local test server.

func TestPublicAddressTable(t *testing.T) {
	blocked := []string{
		// IPv4: loopback, private, link-local and cloud metadata, this-network, CGNAT, protocol assignments, test nets, reserved
		"127.0.0.1", "127.1.2.3", "10.0.0.1", "172.16.0.1", "172.31.255.255", "192.168.0.1", "169.254.169.254", "169.254.170.2",
		"100.100.100.200", "100.64.0.1", "192.0.0.192", "0.0.0.0", "0.1.2.3", "192.0.2.5", "198.51.100.7", "203.0.113.9",
		"198.18.0.1", "192.88.99.1", "224.0.0.1", "239.255.255.250", "240.0.0.1", "255.255.255.255",
		// IPv6: loopback, unspecified, link-local, unique-local (incl. the AWS IPv6 metadata address), site-local, multicast
		"::1", "::", "fe80::1", "fd00:ec2::254", "fc00::1", "fec0::1", "ff02::1", "ff05::2",
		// IPv4 inside IPv6: mapped, compatible, NAT64, 6to4, Teredo, discard, documentation
		"::ffff:127.0.0.1", "::ffff:7f00:1", "::ffff:169.254.169.254", "::ffff:a00:1", "::127.0.0.1", "::7f00:1",
		"64:ff9b::7f00:1", "64:ff9b::a9fe:a9fe", "64:ff9b:1::1", "2002:7f00:1::1", "2002:a9fe:a9fe::1", "2001::1",
		"2001:0:4136:e378:8000:63bf:3fff:fdd2", "100::1", "2001:db8::1",
	}
	for _, s := range blocked {
		if urlfetch.PublicAddress(netip.MustParseAddr(s)) {
			t.Errorf("%s must not be public", s)
		}
	}
	for _, s := range []string{"8.8.8.8", "151.101.1.140", "104.16.0.1", "93.184.216.34", "2606:4700:4700::1111", "2a00:1450:4001::200e", "::ffff:8.8.8.8"} {
		if !urlfetch.PublicAddress(netip.MustParseAddr(s)) {
			t.Errorf("%s is public", s)
		}
	}
}

func TestIPLiteralHostsInEverySpelling(t *testing.T) {
	for _, h := range []string{"127.0.0.1", "8.8.8.8", "[::1]", "::1", "[2606:4700::1111]", "[fe80::1%eth0]", "2130706433", "0x7f000001",
		"0x7f.0.0.1", "0177.0.0.1", "127.1", "10.1", "169.254.169.254.", "[::ffff:127.0.0.1]"} {
		if !urlfetch.IPLiteral(h) {
			t.Errorf("%q is an IP literal", h)
		}
	}
	for _, h := range []string{"example.com", "cdn.example.co.uk", "img.shop.com.", "123.example.com", "a-b.example.org", "localhost", "xn--bcher-kva.example"} {
		if urlfetch.IPLiteral(h) {
			t.Errorf("%q is a name", h)
		}
	}
}

// fake is a name server and a network in one: it answers from a table, counts lookups, and records what was dialled.
type fake struct {
	mu      sync.Mutex
	answers map[string][][]string // host -> answers per lookup, the last one repeats
	lookups map[string]int
	dialled []string
}

func (f *fake) LookupNetIP(_ context.Context, _, host string) ([]netip.Addr, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	n := f.lookups[host]
	f.lookups[host] = n + 1
	seq, ok := f.answers[host]
	if !ok {
		return nil, &net.DNSError{Err: "no such host", Name: host, IsNotFound: true}
	}
	if n >= len(seq) {
		n = len(seq) - 1
	}
	var out []netip.Addr
	for _, s := range seq[n] {
		out = append(out, netip.MustParseAddr(s))
	}
	return out, nil
}

func (f *fake) Dial(_ context.Context, _, address string) (net.Conn, error) {
	f.mu.Lock()
	f.dialled = append(f.dialled, address)
	f.mu.Unlock()
	return nil, errors.New("connection refused by the test network")
}

func newFake(answers map[string][][]string) *fake {
	return &fake{answers: answers, lookups: map[string]int{}}
}

func rejected(t *testing.T, err error, want string) {
	t.Helper()
	if !errors.Is(err, domain.ErrRejected) {
		t.Fatalf("want a rejection, got %v", err)
	}
	if want != "" && !strings.Contains(err.Error(), want) {
		t.Fatalf("want %q in %q", want, err)
	}
}

func TestLinkShapesRefusedBeforeAnyLookup(t *testing.T) {
	fk := newFake(map[string][][]string{"img.example.com": {{"93.184.216.34"}}})
	f := urlfetch.New(urlfetch.Options{Resolver: fk, Dial: fk.Dial})
	cases := []struct{ name, link, want string }{
		{"http", "http://img.example.com/a.jpg", "https"},
		{"ftp", "ftp://img.example.com/a.jpg", "https"},
		{"file", "file:///etc/passwd", "valid link"},
		{"user info", "https://user:pw@img.example.com/a.jpg", "user name"},
		{"port 8443", "https://img.example.com:8443/a.jpg", "443"},
		{"port 80", "https://img.example.com:80/a.jpg", "443"},
		{"port 22", "https://img.example.com:22/a.jpg", "443"},
		{"IPv4 literal public", "https://8.8.8.8/a.jpg", "IP address"},
		{"IPv4 literal metadata", "https://169.254.169.254/latest/meta-data/", "IP address"},
		{"IPv6 literal loopback", "https://[::1]/a.jpg", "IP address"},
		{"IPv6 literal mapped", "https://[::ffff:7f00:1]/a.jpg", "IP address"},
		{"decimal literal", "https://2130706433/a.jpg", "IP address"},
		{"hex literal", "https://0x7f000001/a.jpg", "IP address"},
		{"octal literal", "https://0177.0.0.1/a.jpg", "IP address"},
		{"short literal", "https://127.1/a.jpg", "IP address"},
		{"literal with port 443", "https://127.0.0.1:443/a.jpg", "IP address"},
		{"empty host", "https:///a.jpg", "valid link"},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			_, err := f.Fetch(context.Background(), c.link, 1024)
			rejected(t, err, c.want)
		})
	}
	if len(fk.dialled) != 0 || len(fk.lookups) != 0 {
		t.Fatalf("a refused link must not touch the network: dialled=%v lookups=%v", fk.dialled, fk.lookups)
	}
}

func TestNamesThatResolveToPrivateAddressesAreRefused(t *testing.T) {
	fk := newFake(map[string][][]string{
		"loopback.example.com": {{"127.0.0.1"}},
		"metadata.example.com": {{"169.254.169.254"}},
		"cgnat.example.com":    {{"100.100.100.200"}},
		"private.example.com":  {{"10.1.2.3"}},
		"v6local.example.com":  {{"::1"}},
		"v6meta.example.com":   {{"fd00:ec2::254"}},
		"mapped.example.com":   {{"::ffff:127.0.0.1"}},
		"nat64.example.com":    {{"64:ff9b::7f00:1"}},
		"mixed.example.com":    {{"93.184.216.34", "10.0.0.5"}}, // one bad answer refuses the whole name
	})
	f := urlfetch.New(urlfetch.Options{Resolver: fk, Dial: fk.Dial})
	for host := range fk.answers {
		_, err := f.Fetch(context.Background(), "https://"+host+"/a.jpg", 1024)
		rejected(t, err, "private or reserved")
	}
	if len(fk.dialled) != 0 {
		t.Fatalf("no connection may be made to a refused name: %v", fk.dialled)
	}
}

func TestDNSRebindingResolvesOnceAndConnectsToTheCheckedAddress(t *testing.T) {
	// First answer is public, every later answer is the metadata address: a fetcher that resolves twice (once to check,
	// once to connect) would be led to 169.254.169.254.
	fk := newFake(map[string][][]string{"rebind.example.com": {{"93.184.216.34"}, {"169.254.169.254"}}})
	f := urlfetch.New(urlfetch.Options{Resolver: fk, Dial: fk.Dial})
	_, err := f.Fetch(context.Background(), "https://rebind.example.com/a.jpg", 1024)
	if !errors.Is(err, domain.ErrRejected) {
		t.Fatalf("the test network refuses the connection, so the fetch fails: %v", err)
	}
	if fk.lookups["rebind.example.com"] != 1 {
		t.Fatalf("the name must be resolved exactly once, got %d lookups", fk.lookups["rebind.example.com"])
	}
	if len(fk.dialled) != 1 || fk.dialled[0] != "93.184.216.34:443" {
		t.Fatalf("the connection must go to the checked address on 443, got %v", fk.dialled)
	}
}

func TestRedirectsAreCheckedAtEveryHop(t *testing.T) {
	var target string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		http.Redirect(w, r, target, http.StatusFound)
	}))
	t.Cleanup(srv.Close)
	// The first hop is a public name served by the local test server; every redirect target is then judged on its own.
	host, port, _ := net.SplitHostPort(strings.TrimPrefix(srv.URL, "http://"))
	fk := newFake(map[string][][]string{
		"start.example.com":  {{"93.184.216.34"}},
		"inner.example.com":  {{"10.0.0.9"}},
		"public.example.com": {{"93.184.216.35"}},
	})
	dial := func(ctx context.Context, network, address string) (net.Conn, error) {
		fk.mu.Lock()
		fk.dialled = append(fk.dialled, address)
		fk.mu.Unlock()
		return (&net.Dialer{}).DialContext(ctx, network, net.JoinHostPort(host, port)) // the "public" address is the local server
	}
	f := urlfetch.New(urlfetch.Options{Resolver: fk, Dial: dial, AllowHTTP: true, AnyPort: false})

	// An https first hop cannot be served by a plain test server, so the redirect cases use http through AllowHTTP and the
	// first hop on a name; the hop under test is the redirect target.
	cases := []struct{ name, target, want string }{
		{"to a private name", "http://inner.example.com/a.jpg", "private or reserved"},
		{"to an IP literal", "http://127.0.0.1/a.jpg", "IP address"},
		{"to metadata literal", "http://169.254.169.254/latest/meta-data/", "IP address"},
		{"to another port", "http://public.example.com:8080/a.jpg", "443"},
		{"to a user name link", "http://u:p@public.example.com/a.jpg", "user name"},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			target = c.target
			_, err := f.Fetch(context.Background(), "http://start.example.com/start", 1024)
			rejected(t, err, c.want)
		})
	}
}

func TestTooManyRedirectsAndLimits(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/loop":
			http.Redirect(w, r, "/loop", http.StatusFound)
		case "/big":
			_, _ = w.Write(make([]byte, 4096))
		default:
			w.Header().Set("Content-Type", "image/png")
			_, _ = w.Write([]byte{0x89, 'P', 'N', 'G'})
		}
	}))
	t.Cleanup(srv.Close)
	f := urlfetch.New(urlfetch.Options{AllowHTTP: true, AnyPort: true, AllowAddress: func(netip.Addr) bool { return true }})
	_, err := f.Fetch(context.Background(), srv.URL+"/loop", 1024)
	rejected(t, err, "")
	_, err = f.Fetch(context.Background(), srv.URL+"/big", 1024)
	rejected(t, err, "larger than")
	got, err := f.Fetch(context.Background(), srv.URL+"/ok", 1024)
	if err != nil || len(got.Data) != 4 {
		t.Fatalf("a small file within the limits is fetched: %v %v", got, err)
	}
}

func TestTimeLimitStopsASlowLink(t *testing.T) {
	release := make(chan struct{})
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		<-release
	}))
	t.Cleanup(func() { close(release); srv.Close() })
	f := urlfetch.New(urlfetch.Options{AllowHTTP: true, AnyPort: true, Timeout: 150e6, AllowAddress: func(netip.Addr) bool { return true }})
	_, err := f.Fetch(context.Background(), srv.URL+"/slow", 1024)
	rejected(t, err, "could not be reached")
}
