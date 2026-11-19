package app_test

import (
	"context"
	"encoding/hex"
	"encoding/json"
	"image/color"
	"os"
	"slices"
	"testing"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/pkg/auth"
	"github.com/billionairedev24/diaspora-commerce/go/pkg/picturerules"
	"github.com/billionairedev24/diaspora-commerce/go/services/media/internal/adapter/imageproc"
	"github.com/billionairedev24/diaspora-commerce/go/services/media/internal/adapter/inspect"
	"github.com/billionairedev24/diaspora-commerce/go/services/media/internal/app"
	"github.com/billionairedev24/diaspora-commerce/go/services/media/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/media/internal/port"
)

// pictureFixture is the image pipeline under one picture-rules profile.
func pictureFixture(t *testing.T, pr picturerules.Rules) imageFixture {
	t.Helper()
	st := &memStore{objects: map[string][]byte{}}
	repo := &memRepo{assets: map[string]domain.Asset{}}
	stores := map[domain.Purpose]port.ObjectStore{domain.PurposeProductImage: st, domain.PurposeStorefront: st}
	ai := &fakeModerator{verdict: allow}
	svc := app.NewMediaService(stores, repo, inspect.New(40_000_000), domain.LinkPolicy{},
		app.WithModeration(imageproc.New(40_000_000), ai, domain.ModerationPolicy{DuplicateDistance: 6}, time.Second),
		app.WithImageRules(domain.DefaultImageRules(), imageproc.NewAnalyzer(40_000_000), &memRules{}),
		app.WithPictureRules(picturerules.Static(pr)))
	return imageFixture{svc: svc, store: st, repo: repo, ai: ai}
}

func (f imageFixture) putAs(t *testing.T, actor auth.Actor, body []byte, fileName, declared string, opts domain.ImageOptions) (domain.Asset, error) {
	t.Helper()
	ctx := context.Background()
	a, up, err := f.svc.RequestImageUpload(ctx, actor, domain.PurposeProductImage, fileName, declared, int64(len(body)), opts)
	if err != nil {
		return domain.Asset{}, err
	}
	f.store.objects[up.Key] = body
	return f.svc.Complete(ctx, actor, a.ID)
}

type sample struct {
	Name string `json:"name"`
	Hex  string `json:"hex"`
	Type string `json:"type"`
}

func vectorSamples(t *testing.T) (images, polyglots, rejected []sample) {
	t.Helper()
	raw, err := os.ReadFile("../../../../../contracts/picture-rules/vectors.json")
	if err != nil {
		t.Fatal(err)
	}
	var v struct {
		Images    []sample `json:"images"`
		Polyglots []sample `json:"polyglots"`
		Rejected  []sample `json:"rejected"`
	}
	if err := json.Unmarshal(raw, &v); err != nil {
		t.Fatal(err)
	}
	return v.Images, v.Polyglots, v.Rejected
}

func decodeHex(t *testing.T, s sample) []byte {
	t.Helper()
	b, err := hex.DecodeString(s.Hex)
	if err != nil {
		t.Fatal(err)
	}
	return b
}

func sampleNamed(t *testing.T, list []sample, name string) []byte {
	t.Helper()
	for _, s := range list {
		if s.Name == name {
			return decodeHex(t, s)
		}
	}
	t.Fatalf("no sample %q", name)
	return nil
}

func TestMainImageSizeAndAppearanceFollowTheProfile(t *testing.T) {
	cases := []struct {
		name string
		w, h int
		bg   color.RGBA
		want map[picturerules.Profile]domain.Status
	}{
		{"1200 on cream: not white", 1200, 1200, cream, map[picturerules.Profile]domain.Status{
			picturerules.Strict: domain.StatusRejected, picturerules.Startup: domain.StatusReady, picturerules.Testing: domain.StatusReady}},
		{"600 on white: under the zoom rule", 600, 600, white, map[picturerules.Profile]domain.Status{
			picturerules.Strict: domain.StatusRejected, picturerules.Startup: domain.StatusReady, picturerules.Testing: domain.StatusReady}},
		{"300 on white: too small for startup", 300, 300, white, map[picturerules.Profile]domain.Status{
			picturerules.Strict: domain.StatusRejected, picturerules.Startup: domain.StatusRejected, picturerules.Testing: domain.StatusReady}},
		{"1600 on white: fine everywhere", 1600, 1600, white, map[picturerules.Profile]domain.Status{
			picturerules.Strict: domain.StatusReady, picturerules.Startup: domain.StatusReady, picturerules.Testing: domain.StatusReady}},
	}
	for _, c := range cases {
		for _, p := range picturerules.Profiles() {
			f := pictureFixture(t, picturerules.Default(p))
			got, err := f.putAs(t, sellerMember, shot(t, c.w, c.h, c.bg, 0.9), "photo.jpg", "image/jpeg", main())
			if err != nil {
				t.Fatalf("%s %s: %v", c.name, p, err)
			}
			if got.Status != c.want[p] {
				t.Errorf("%s under %s: status %s (checks %v), want %s", c.name, p, got.Status, codesOf(got), c.want[p])
			}
		}
	}
}

func TestStrictProfileIsTodaysBehaviourUnchanged(t *testing.T) {
	// The same file through the plain fixture (no picture rules at all) and through the strict profile gives the same verdict.
	plain := newImageFixture(t, nil, &fakeModerator{verdict: allow})
	strict := pictureFixture(t, picturerules.Default(picturerules.Strict))
	for _, w := range []int{800, 999, 1000, 1600} {
		body := shot(t, w, w, white, 0.92)
		a := plain.put(t, sellerMember, body, main())
		b, err := strict.putAs(t, sellerMember, body, "photo.jpg", "image/jpeg", main())
		if err != nil {
			t.Fatal(err)
		}
		if a.Status != b.Status || !slices.Equal(codesOf(a), codesOf(b)) {
			t.Fatalf("%d px: plain %s %v, strict %s %v", w, a.Status, codesOf(a), b.Status, codesOf(b))
		}
	}
}

func TestAnOverrideUnderStrictChangesOnlyThatKnob(t *testing.T) {
	long := 600
	r := picturerules.Resolve(picturerules.Strict, picturerules.Overrides{MinLongSidePx: &long})
	f := pictureFixture(t, r)
	got, err := f.putAs(t, sellerMember, shot(t, 700, 700, white, 0.92), "photo.jpg", "image/jpeg", main())
	if err != nil || got.Status != domain.StatusReady {
		t.Fatalf("700 px passes with minLongSidePx 600: %+v %v", got, err)
	}
	notWhite, err := f.putAs(t, sellerMember, shot(t, 1200, 1200, cream, 0.92), "photo.jpg", "image/jpeg", main())
	if err != nil || notWhite.Status != domain.StatusRejected {
		t.Fatalf("the background rule stays on under strict: %+v %v", notWhite, err)
	}
}

func TestAvifIsAcceptedUnderEveryProfileWithSizeFromTheHeader(t *testing.T) {
	images, _, _ := vectorSamples(t)
	avif := sampleNamed(t, images, "avif 1024x768")
	want := map[picturerules.Profile]domain.Status{
		// Strict cannot look at the pixels of an AVIF here, so it holds the file for staff; the relaxed profiles publish it.
		picturerules.Strict: domain.StatusFlagged, picturerules.Startup: domain.StatusReady, picturerules.Testing: domain.StatusReady}
	other := domain.ImageOptions{Slot: domain.ImageSlotOther}
	for _, p := range picturerules.Profiles() {
		f := pictureFixture(t, picturerules.Default(p))
		got, err := f.putAs(t, sellerMember, avif, "photo.avif", "image/avif", other)
		if err != nil {
			t.Fatalf("%s: %v", p, err)
		}
		if got.Status != want[p] || got.Width != 1024 || got.Height != 768 || got.ContentType != "image/avif" {
			t.Errorf("%s: %+v (checks %v)", p, got, codesOf(got))
		}
		if f.ai.calls != 0 {
			t.Errorf("%s: an AVIF has no preview to send to the AI hook", p)
		}
	}
}

func TestAvifSmallerThanTheProfileMinimumIsRejected(t *testing.T) {
	images, _, _ := vectorSamples(t)
	small := sampleNamed(t, images, "avis sequence 400x400")
	f := pictureFixture(t, picturerules.Default(picturerules.Startup))
	got, err := f.putAs(t, sellerMember, small, "photo.avif", "image/avif", main())
	if err != nil || got.Status != domain.StatusRejected || !slices.Contains(codesOf(got), "MAIN_TOO_SMALL") {
		t.Fatalf("%+v %v", got, err)
	}
}

func TestSpoofedAndPolyglotFilesAreRefusedUnderEveryProfile(t *testing.T) {
	_, polyglots, rejected := vectorSamples(t)
	for _, p := range picturerules.Profiles() {
		f := pictureFixture(t, picturerules.Default(p))
		for _, s := range append(slices.Clone(polyglots), rejected...) {
			if s.Hex == "" {
				continue
			}
			for _, declared := range []string{"image/png", "image/jpeg", "image/webp", "image/avif"} {
				got, err := f.putAs(t, sellerMember, decodeHex(t, s), "photo.png", declared, domain.ImageOptions{Slot: domain.ImageSlotOther})
				if err == nil && got.Status != domain.StatusRejected {
					t.Errorf("%s: %q declared %s was accepted: %+v", p, s.Name, declared, got)
				}
			}
		}
	}
}

func TestAnExtensionNeverDecidesTheType(t *testing.T) {
	images, _, _ := vectorSamples(t)
	f := pictureFixture(t, picturerules.Default(picturerules.Testing))
	// A real WebP uploaded as "photo.png" with a png header is still judged by its bytes: the declared type must be allowed, then
	// the bytes decide what is stored.
	webp := sampleNamed(t, images, "webp extended 1000x700")
	if got, err := f.putAs(t, sellerMember, webp, "photo.png", "image/png", domain.ImageOptions{Slot: domain.ImageSlotOther}); err == nil &&
		got.Status != domain.StatusRejected && got.ContentType != "image/webp" {
		t.Fatalf("stored as %s", got.ContentType)
	}
}

func TestTestingProfileLetsAnImageWithAPlaceholderHashThrough(t *testing.T) {
	// Placeholders are a main-image appearance rejection under strict; allowPlaceholders lifts it.
	r := picturerules.Default(picturerules.Startup)
	r.Knobs.AllowPlaceholders = true
	r.Overridden = []string{"allowPlaceholders"}
	rules := domain.ApplyPictureRules(domain.DefaultImageRules(), r)
	if !rules.AllowPlaceholders {
		t.Fatal("allowPlaceholders must reach the checks")
	}
	checks := domain.EvaluateImage(rules, domain.ImageFacts{Slot: domain.ImageSlotMain, Width: 900, Height: 900,
		Metrics: &domain.ImageMetrics{Measured: true, Placeholder: true}})
	if domain.VerdictOf(checks) == domain.VerdictReject {
		t.Fatalf("placeholder refused although allowed: %+v", checks)
	}
	strict := domain.EvaluateImage(domain.ApplyPictureRules(domain.DefaultImageRules(), picturerules.Default(picturerules.Strict)),
		domain.ImageFacts{Slot: domain.ImageSlotMain, Width: 1200, Height: 1200,
			Metrics: &domain.ImageMetrics{Measured: true, Placeholder: true}})
	if domain.VerdictOf(strict) != domain.VerdictReject {
		t.Fatalf("strict refuses a placeholder: %+v", strict)
	}
}
