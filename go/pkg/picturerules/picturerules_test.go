package picturerules_test

import (
	"context"
	"encoding/hex"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"slices"
	"sync/atomic"
	"testing"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/pkg/picturerules"
)

type vectors struct {
	Profiles    map[string]picturerules.Knobs `json:"profiles"`
	EnvDefaults map[string]string             `json:"envDefaults"`
	Images      []struct {
		Name   string `json:"name"`
		Hex    string `json:"hex"`
		Type   string `json:"type"`
		Width  int    `json:"width"`
		Height int    `json:"height"`
	} `json:"images"`
	Rejected []struct {
		Name string `json:"name"`
		Hex  string `json:"hex"`
	} `json:"rejected"`
	Polyglots []struct {
		Name string `json:"name"`
		Hex  string `json:"hex"`
	} `json:"polyglots"`
	URLs []struct {
		URL    string `json:"url"`
		Class  string `json:"class"`
		Expect map[string]struct {
			PhotoOK          bool `json:"photoOK"`
			ShowOnStorefront bool `json:"showOnStorefront"`
			Matchable        bool `json:"matchable"`
		} `json:"expect"`
	} `json:"urls"`
	Overrides []struct {
		Name       string                 `json:"name"`
		Profile    string                 `json:"profile"`
		Overrides  picturerules.Overrides `json:"overrides"`
		Overridden []string               `json:"overridden"`
		Knobs      picturerules.Knobs     `json:"knobs"`
	} `json:"overrides"`
	Invalid []struct {
		Name  string             `json:"name"`
		Knobs picturerules.Knobs `json:"knobs"`
	} `json:"invalid"`
}

func load(t *testing.T) vectors {
	t.Helper()
	raw, err := os.ReadFile("../../../contracts/picture-rules/vectors.json")
	if err != nil {
		t.Fatal(err)
	}
	var v vectors
	if err := json.Unmarshal(raw, &v); err != nil {
		t.Fatal(err)
	}
	return v
}

func bytesOf(t *testing.T, h string) []byte {
	t.Helper()
	b, err := hex.DecodeString(h)
	if err != nil {
		t.Fatal(err)
	}
	return b
}

func TestProfileKnobsMatchGoldenVectors(t *testing.T) {
	v := load(t)
	for name, want := range v.Profiles {
		p, ok := picturerules.ParseProfile(name)
		if !ok {
			t.Fatalf("profile %q unknown", name)
		}
		if got := picturerules.ProfileKnobs(p); got != want {
			t.Errorf("%s: knobs %+v, want %+v", name, got, want)
		}
		if msg := picturerules.ProfileKnobs(p).Problem(); msg != "" {
			t.Errorf("%s: own knobs invalid: %s", name, msg)
		}
	}
	if len(v.Profiles) != 3 {
		t.Fatalf("want 3 profiles, got %d", len(v.Profiles))
	}
}

// Matrix: every url against every profile for photo ok, storefront and matching.
func TestUrlMatrixPerProfile(t *testing.T) {
	v := load(t)
	for _, c := range v.URLs {
		for name, want := range c.Expect {
			p, _ := picturerules.ParseProfile(name)
			r := picturerules.Default(p)
			if got := r.PhotoOK(c.URL); got != want.PhotoOK {
				t.Errorf("%s %q PhotoOK=%v want %v", name, c.URL, got, want.PhotoOK)
			}
			if got := r.ShowOnStorefront(c.URL); got != want.ShowOnStorefront {
				t.Errorf("%s %q ShowOnStorefront=%v want %v", name, c.URL, got, want.ShowOnStorefront)
			}
			if got := r.MatchableWithPhoto(c.URL); got != want.Matchable {
				t.Errorf("%s %q Matchable=%v want %v", name, c.URL, got, want.Matchable)
			}
		}
		if got := picturerules.IsRealPhoto(c.URL); got != (c.Class == "real") {
			t.Errorf("IsRealPhoto(%q)=%v want class %s", c.URL, got, c.Class)
		}
	}
}

func TestNoProfileEverAcceptsVectorOrInlineImages(t *testing.T) {
	for _, p := range picturerules.Profiles() {
		r := picturerules.Default(p)
		for _, u := range []string{"https://x/y.svg", "/y.SVG", "data:image/svg+xml;base64,PHN2Zz4=", "https://x/favicon.ico"} {
			if r.PhotoOK(u) {
				t.Errorf("%s accepts %q as a photo", p, u)
			}
		}
	}
}

func TestOverridesMatchGoldenVectors(t *testing.T) {
	for _, c := range load(t).Overrides {
		p, _ := picturerules.ParseProfile(c.Profile)
		got := picturerules.Resolve(p, c.Overrides)
		if got.Knobs != c.Knobs {
			t.Errorf("%s: knobs %+v want %+v", c.Name, got.Knobs, c.Knobs)
		}
		if !slices.Equal(got.Overridden, c.Overridden) {
			t.Errorf("%s: overridden %v want %v", c.Name, got.Overridden, c.Overridden)
		}
		if got.Strict() {
			t.Errorf("%s: an overridden set of rules is not the strict standard", c.Name)
		}
	}
	if !picturerules.Default(picturerules.Strict).Strict() || picturerules.Default(picturerules.Startup).Strict() {
		t.Fatal("only unmodified strict is the standard")
	}
}

func TestStrictAppliesOnlyOverriddenKnobs(t *testing.T) {
	r := picturerules.Default(picturerules.Strict)
	if r.Applies("minImages") {
		t.Fatal("strict without overrides must leave media's own configuration alone")
	}
	one := 1
	r = picturerules.Resolve(picturerules.Strict, picturerules.Overrides{MinImages: &one})
	if !r.Applies("minImages") || r.Applies("maxMegabytes") {
		t.Fatal("strict applies exactly the overridden knobs")
	}
	if !picturerules.Default(picturerules.Startup).Applies("maxMegabytes") {
		t.Fatal("non strict applies every knob")
	}
}

func TestInvalidKnobsAreRefused(t *testing.T) {
	for _, c := range load(t).Invalid {
		if c.Knobs.Problem() == "" {
			t.Errorf("%s: want a problem", c.Name)
		}
	}
}

func TestEnvDefaults(t *testing.T) {
	for env, want := range load(t).EnvDefaults {
		if got := picturerules.EnvDefaultProfile(env); string(got) != want {
			t.Errorf("%s: %s want %s", env, got, want)
		}
	}
	if got := picturerules.FromEnv("prod", "strict").Profile; got != picturerules.Strict {
		t.Fatalf("explicit profile wins, got %s", got)
	}
	if got := picturerules.FromEnv("prod", "nonsense").Profile; got != picturerules.Startup {
		t.Fatalf("unknown profile falls back to the environment default, got %s", got)
	}
}

func TestSniffAndDimensionsOfEveryFormat(t *testing.T) {
	for _, im := range load(t).Images {
		data := bytesOf(t, im.Hex)
		if got := picturerules.Sniff(data); got != im.Type {
			t.Errorf("%s: sniffed %q want %q", im.Name, got, im.Type)
			continue
		}
		w, h, err := picturerules.Dimensions(data, im.Type)
		if err != nil || w != im.Width || h != im.Height {
			t.Errorf("%s: %dx%d (%v) want %dx%d", im.Name, w, h, err, im.Width, im.Height)
		}
		if picturerules.HasActiveContent(data) {
			t.Errorf("%s: a clean image is flagged as active content", im.Name)
		}
		if picturerules.Extension(im.Type) == "" {
			t.Errorf("%s: no extension for %s", im.Name, im.Type)
		}
	}
	if got := picturerules.Formats(); !slices.Equal(got, []string{"image/jpeg", "image/png", "image/webp", "image/avif"}) {
		t.Fatalf("formats %v", got)
	}
}

func TestSpoofedFilesAreRejected(t *testing.T) {
	v := load(t)
	for _, r := range v.Rejected {
		if got := picturerules.Sniff(bytesOf(t, r.Hex)); got != "" {
			t.Errorf("%s: sniffed as %s", r.Name, got)
		}
	}
	for _, p := range v.Polyglots {
		data := bytesOf(t, p.Hex)
		if picturerules.Sniff(data) == "" {
			t.Errorf("%s: the polyglot must have a valid image header for the test to mean something", p.Name)
		}
		if !picturerules.HasActiveContent(data) {
			t.Errorf("%s: polyglot not detected", p.Name)
		}
	}
}

func TestExtensionDoesNotDecide(t *testing.T) {
	// A PNG named .svg is still sniffed as PNG: the bytes decide the type; the URL rule rejects the .svg address.
	png := bytesOf(t, load(t).Images[0].Hex)
	if picturerules.Sniff(png) != picturerules.PNG {
		t.Fatal("png")
	}
	for name, want := range map[string]bool{"a.jpg": true, "a.JPEG": true, "a.webp": true, "a.avif": true, "a.png?x=1": true, "a": true,
		"a.svg": false, "a.gif": false, "a.html": false, "a.heic": false, "/dir.v2/a": true} {
		if got := picturerules.ExtensionOK(name); got != want {
			t.Errorf("ExtensionOK(%q)=%v want %v", name, got, want)
		}
	}
}

func TestSummaryStatesTheActiveRule(t *testing.T) {
	cases := map[picturerules.Profile][]string{
		picturerules.Startup: {"at least 1 photo", "at least 500 px"},
		picturerules.Strict:  {"at least 1 photo", "at least 1000 px", "plain white background", "AVIF"},
		picturerules.Testing: {"photos are optional"},
	}
	for p, wants := range cases {
		s := picturerules.Default(p).Summary()
		for _, w := range wants {
			if !contains(s, w) {
				t.Errorf("%s summary %q lacks %q", p, s, w)
			}
		}
	}
}

func contains(s, sub string) bool {
	return len(sub) == 0 || (len(s) >= len(sub) && indexOf(s, sub) >= 0)
}
func indexOf(s, sub string) int {
	for i := 0; i+len(sub) <= len(s); i++ {
		if s[i:i+len(sub)] == sub {
			return i
		}
	}
	return -1
}

func TestClientCachesFallsBackAndInvalidates(t *testing.T) {
	var calls atomic.Int32
	var profile atomic.Value
	profile.Store("startup")
	down := atomic.Bool{}
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		calls.Add(1)
		if down.Load() || r.URL.Path != "/v1/public/picture-rules" {
			http.Error(w, "x", http.StatusServiceUnavailable)
			return
		}
		p, _ := picturerules.ParseProfile(profile.Load().(string))
		_ = json.NewEncoder(w).Encode(picturerules.Default(p))
	}))
	defer srv.Close()
	src := picturerules.NewClient(srv.URL, srv.Client(), time.Hour, picturerules.Default(picturerules.Testing))
	ctx := context.Background()
	if got := src.Rules(ctx).Profile; got != picturerules.Startup {
		t.Fatalf("first read %s", got)
	}
	profile.Store("strict")
	if got := src.Rules(ctx).Profile; got != picturerules.Startup || calls.Load() != 1 {
		t.Fatalf("cached read %s after %d calls", got, calls.Load())
	}
	src.Invalidate()
	if got := src.Rules(ctx).Profile; got != picturerules.Strict {
		t.Fatalf("after invalidate %s", got)
	}
	down.Store(true)
	src.Invalidate()
	if got := src.Rules(ctx).Profile; got != picturerules.Strict {
		t.Fatalf("catalog down keeps the last good rules, got %s", got)
	}
}

func TestClientUsesEnvironmentDefaultUntilFirstRead(t *testing.T) {
	src := picturerules.NewClient("http://127.0.0.1:1", &http.Client{Timeout: 200 * time.Millisecond}, time.Second,
		picturerules.FromEnv("uat", ""))
	if got := src.Rules(context.Background()).Profile; got != picturerules.Startup {
		t.Fatalf("fallback %s", got)
	}
}

func TestParseRefusesUnknownProfileAndBadKnobs(t *testing.T) {
	if _, err := picturerules.Parse([]byte(`{"profile":"relaxed","knobs":{}}`)); err == nil {
		t.Fatal("unknown profile")
	}
	if _, err := picturerules.Parse([]byte(`{"profile":"startup","knobs":{"minImages":1}}`)); err == nil {
		t.Fatal("bad knobs")
	}
	r, err := picturerules.Parse([]byte(`{"profile":"testing","knobs":` + mustJSON(picturerules.ProfileKnobs(picturerules.Testing)) + `}`))
	if err != nil || r.Profile != picturerules.Testing || len(r.Formats) != 4 {
		t.Fatalf("%+v %v", r, err)
	}
}

func mustJSON(v any) string {
	b, _ := json.Marshal(v)
	return string(b)
}
