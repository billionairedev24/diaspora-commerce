package imageproc_test

import (
	"bytes"
	"image"
	"image/color"
	"image/jpeg"
	"image/png"
	"os"
	"slices"
	"testing"

	"github.com/billionairedev24/diaspora-commerce/go/services/media/internal/adapter/imageproc"
	"github.com/billionairedev24/diaspora-commerce/go/services/media/internal/domain"
)

// product paints a textured product (stripes and noise, so it has detail) into the rectangle of img.
func product(img *image.RGBA, r image.Rectangle) {
	for y := r.Min.Y; y < r.Max.Y; y++ {
		for x := r.Min.X; x < r.Max.X; x++ {
			n := uint8((x*7919 + y*104729) % 41) //nolint:gosec // deterministic noise
			if ((x/14)+(y/22))%2 == 0 {
				img.SetRGBA(x, y, color.RGBA{R: 20 + n, G: 70 + n, B: 150 + n/2, A: 255})
			} else {
				img.SetRGBA(x, y, color.RGBA{R: 235, G: 190 - n, B: 40 + n, A: 255})
			}
		}
	}
}

func fill(img *image.RGBA, c color.RGBA) {
	for i := 0; i < len(img.Pix); i += 4 {
		img.Pix[i], img.Pix[i+1], img.Pix[i+2], img.Pix[i+3] = c.R, c.G, c.B, c.A
	}
}

// packshot is a w×h picture on bg with the product centred, spanning frac of the frame's height and width.
func packshot(w, h int, bg color.RGBA, frac float64) *image.RGBA {
	img := image.NewRGBA(image.Rect(0, 0, w, h))
	fill(img, bg)
	pw, ph := int(float64(w)*frac), int(float64(h)*frac)
	product(img, image.Rect((w-pw)/2, (h-ph)/2, (w+pw)/2, (h+ph)/2))
	return img
}

func asJPEG(t *testing.T, img image.Image) []byte {
	t.Helper()
	var buf bytes.Buffer
	if err := jpeg.Encode(&buf, img, &jpeg.Options{Quality: 90}); err != nil {
		t.Fatal(err)
	}
	return buf.Bytes()
}

func asPNG(t *testing.T, img image.Image) []byte {
	t.Helper()
	var buf bytes.Buffer
	if err := png.Encode(&buf, img); err != nil {
		t.Fatal(err)
	}
	return buf.Bytes()
}

// blur box-blurs the whole image n times (a soft, out-of-focus photo).
func blur(src *image.RGBA, n int) *image.RGBA {
	cur := src
	for range n {
		next := image.NewRGBA(cur.Bounds())
		b := cur.Bounds()
		for y := b.Min.Y; y < b.Max.Y; y++ {
			for x := b.Min.X; x < b.Max.X; x++ {
				var r, g, bl, c int
				for dy := -2; dy <= 2; dy++ {
					for dx := -2; dx <= 2; dx++ {
						xx, yy := x+dx, y+dy
						if xx < b.Min.X || yy < b.Min.Y || xx >= b.Max.X || yy >= b.Max.Y {
							continue
						}
						p := cur.RGBAAt(xx, yy)
						r, g, bl, c = r+int(p.R), g+int(p.G), bl+int(p.B), c+1
					}
				}
				next.SetRGBA(x, y, color.RGBA{R: uint8(r / c), G: uint8(g / c), B: uint8(bl / c), A: 255}) //nolint:gosec // mean of bytes
			}
		}
		cur = next
	}
	return cur
}

func frame(img *image.RGBA, thickness int, c color.RGBA) {
	b := img.Bounds()
	for y := b.Min.Y; y < b.Max.Y; y++ {
		for x := b.Min.X; x < b.Max.X; x++ {
			if x < thickness || y < thickness || x >= b.Max.X-thickness || y >= b.Max.Y-thickness {
				img.SetRGBA(x, y, c)
			}
		}
	}
}

func codes(checks []domain.Check) []string {
	out := make([]string, 0, len(checks))
	for _, c := range checks {
		out = append(out, c.Code)
	}
	return out
}

var (
	pureWhite = color.RGBA{R: 255, G: 255, B: 255, A: 255}
	nearWhite = color.RGBA{R: 252, G: 252, B: 252, A: 255}
	offWhite  = color.RGBA{R: 245, G: 245, B: 245, A: 255}
	cream     = color.RGBA{R: 250, G: 244, B: 228, A: 255}
	black     = color.RGBA{R: 10, G: 10, B: 10, A: 255}
	blue      = color.RGBA{R: 40, G: 90, B: 200, A: 255}
)

// TestMainImageSampleSet is the table of section 6.3: every sample goes through the analysis and the rules and ends
// with the verdict and codes the spec asks for.
func TestMainImageSampleSet(t *testing.T) {
	rules := domain.DefaultImageRules()
	a := imageproc.NewAnalyzer(40_000_000)
	phone := image.NewRGBA(image.Rect(0, 0, 1600, 1200))
	for y := range 1200 {
		for x := range 1600 {
			n := uint8((x*31 + y*17) % 90) //nolint:gosec // deterministic noise
			phone.SetRGBA(x, y, color.RGBA{R: 110 + n, G: 80 + n/2, B: 50 + n/3, A: 255})
		}
	}
	product(phone, image.Rect(400, 300, 1200, 900))
	wide := packshot(2400, 800, pureWhite, 0.9)

	cases := []struct {
		name    string
		data    []byte
		ct      string
		verdict domain.ImageVerdict
		want    []string // codes that must be present (WARN and above)
		absent  []string // codes that must not be present
	}{
		{"white packshot", asJPEG(t, packshot(1600, 1600, pureWhite, 0.92)), "image/jpeg", domain.VerdictPass, nil, []string{"NOT_WHITE", "FILL_LOW"}},
		{"jpeg noise near white passes", asJPEG(t, packshot(1600, 1600, nearWhite, 0.92)), "image/jpeg", domain.VerdictPass, nil, []string{"NOT_WHITE"}},
		{"off-white 245 is rejected", asJPEG(t, packshot(1600, 1600, offWhite, 0.92)), "image/jpeg", domain.VerdictReject, []string{"NOT_WHITE"}, nil},
		{"cream is rejected", asJPEG(t, packshot(1600, 1600, cream, 0.92)), "image/jpeg", domain.VerdictReject, []string{"NOT_WHITE"}, nil},
		{"phone photo on a table is rejected", asJPEG(t, phone), "image/jpeg", domain.VerdictReject, []string{"NOT_WHITE"}, []string{"BORDER"}},
		{"800 px is too small", asJPEG(t, packshot(800, 800, pureWhite, 0.92)), "image/jpeg", domain.VerdictReject, []string{"MAIN_TOO_SMALL"}, nil},
		{"999 px longest side is too small", asJPEG(t, packshot(999, 999, pureWhite, 0.92)), "image/jpeg", domain.VerdictReject, []string{"MAIN_TOO_SMALL"}, nil},
		{"1000 px longest side passes the zoom rule", asJPEG(t, packshot(1000, 700, pureWhite, 0.92)), "image/jpeg", domain.VerdictPass, nil, []string{"MAIN_TOO_SMALL"}},
		{"thin 1200x300 is too small on the short side", asJPEG(t, packshot(1200, 300, pureWhite, 0.92)), "image/jpeg", domain.VerdictReject, []string{"TOO_SMALL"}, nil},
		{"black frame is a border", asJPEG(t, func() *image.RGBA {
			i := packshot(1600, 1600, pureWhite, 0.8)
			frame(i, 64, black)
			return i
		}()), "image/jpeg", domain.VerdictReject, []string{"BORDER"}, []string{"NOT_WHITE"}},
		{"solid backdrop is a background, not a frame", asJPEG(t, packshot(1600, 1600, blue, 0.5)), "image/jpeg", domain.VerdictReject, []string{"NOT_WHITE"}, []string{"BORDER"}},
		{"product at 70 percent is rejected", asJPEG(t, packshot(1600, 1600, pureWhite, 0.70)), "image/jpeg", domain.VerdictReject, []string{"FILL_LOW"}, nil},
		{"product at 80 percent warns", asJPEG(t, packshot(1600, 1600, pureWhite, 0.80)), "image/jpeg", domain.VerdictWarn, []string{"FILL_OK_NEAR"}, []string{"FILL_LOW"}},
		{"blank white is a placeholder", asJPEG(t, func() *image.RGBA {
			i := image.NewRGBA(image.Rect(0, 0, 1600, 1600))
			fill(i, pureWhite)
			return i
		}()), "image/jpeg", domain.VerdictReject, []string{"PLACEHOLDER"}, nil},
		{"out of focus warns", asJPEG(t, blur(packshot(1200, 1200, pureWhite, 0.92), 6)), "image/jpeg", domain.VerdictWarn, []string{"BLUR"}, nil},
		{"very wide warns", asJPEG(t, wide), "image/jpeg", domain.VerdictWarn, []string{"ASPECT"}, nil},
		{"transparent png is flattened on white", func() []byte {
			img := image.NewNRGBA(image.Rect(0, 0, 1500, 1500))
			p := packshot(1500, 1500, pureWhite, 0.9)
			for y := range 1500 {
				for x := range 1500 {
					if c := p.RGBAAt(x, y); c != pureWhite {
						img.SetNRGBA(x, y, color.NRGBA{R: c.R, G: c.G, B: c.B, A: 255})
					}
				}
			}
			return asPNG(t, img)
		}(), "image/png", domain.VerdictPass, nil, []string{"NOT_WHITE"}},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			got, err := a.Analyze(c.data, c.ct, true, rules.Main)
			if err != nil {
				t.Fatal(err)
			}
			checks := domain.EvaluateImage(rules, domain.ImageFacts{Slot: domain.ImageSlotMain, Width: got.Width, Height: got.Height,
				Metrics: &got.Metrics})
			if v := domain.VerdictOf(checks); v != c.verdict {
				t.Fatalf("verdict %s, want %s: %v metrics %+v", v, c.verdict, codes(checks), got.Metrics)
			}
			have := codes(checks)
			for _, w := range c.want {
				if !slices.Contains(have, w) {
					t.Fatalf("missing %s in %v (metrics %+v)", w, have, got.Metrics)
				}
			}
			for _, w := range c.absent {
				if slices.Contains(have, w) {
					t.Fatalf("unexpected %s in %v (metrics %+v)", w, have, got.Metrics)
				}
			}
		})
	}
}

func TestPassingBackgroundIsSnappedToPureWhite(t *testing.T) {
	rules := domain.DefaultImageRules()
	a := imageproc.NewAnalyzer(40_000_000)
	got, err := a.Analyze(asJPEG(t, packshot(1600, 1600, nearWhite, 0.92)), "image/jpeg", true, rules.Main)
	if err != nil {
		t.Fatal(err)
	}
	if !got.Metrics.SnappedWhite || got.Data == nil || got.ContentType != "image/jpeg" {
		t.Fatalf("a passing near-white background must be snapped: %+v", got.Metrics)
	}
	img, err := jpeg.Decode(bytes.NewReader(got.Data))
	if err != nil {
		t.Fatal(err)
	}
	for _, p := range [][2]int{{0, 0}, {1599, 0}, {0, 1599}, {1599, 1599}, {800, 40}, {40, 800}} {
		r, g, b, _ := img.At(p[0], p[1]).RGBA()
		if r>>8 < 254 || g>>8 < 254 || b>>8 < 254 {
			t.Fatalf("pixel %v is not white after snapping: %d %d %d", p, r>>8, g>>8, b>>8)
		}
	}
	// A slot that is not the main image is measured but never changed.
	other, err := a.Analyze(asJPEG(t, packshot(1600, 1600, nearWhite, 0.92)), "image/jpeg", false, rules.Main)
	if err != nil || other.Data != nil || other.Metrics.SnappedWhite {
		t.Fatalf("other slots keep the file: %+v %v", other.Metrics, err)
	}
	// A failing background is never snapped.
	bad, err := a.Analyze(asJPEG(t, packshot(1600, 1600, cream, 0.92)), "image/jpeg", true, rules.Main)
	if err != nil || bad.Data != nil {
		t.Fatalf("a rejected background must not be touched: %v", err)
	}
}

func TestTransparentPNGIsFlattenedOnWhite(t *testing.T) {
	rules := domain.DefaultImageRules()
	a := imageproc.NewAnalyzer(40_000_000)
	img := image.NewNRGBA(image.Rect(0, 0, 1400, 1400))
	p := packshot(1400, 1400, pureWhite, 0.9)
	for y := range 1400 {
		for x := range 1400 {
			if c := p.RGBAAt(x, y); c != pureWhite {
				img.SetNRGBA(x, y, color.NRGBA{R: c.R, G: c.G, B: c.B, A: 255})
			}
		}
	}
	got, err := a.Analyze(asPNG(t, img), "image/png", true, rules.Main)
	if err != nil {
		t.Fatal(err)
	}
	if !got.Metrics.FlattenedAlpha || got.Data == nil || got.ContentType != "image/png" {
		t.Fatalf("transparent main image must be rewritten flat: %+v", got.Metrics)
	}
	out, err := png.Decode(bytes.NewReader(got.Data))
	if err != nil {
		t.Fatal(err)
	}
	if o, ok := out.(interface{ Opaque() bool }); !ok || !o.Opaque() {
		t.Fatal("the rewritten PNG must have no transparency")
	}
	if r, g, b, a := out.At(5, 5).RGBA(); r != 0xffff || g != 0xffff || b != 0xffff || a != 0xffff {
		t.Fatalf("transparent corner must become white, got %x %x %x %x", r, g, b, a)
	}
}

func TestCMYKJPEGBecomesRGB(t *testing.T) {
	data, err := os.ReadFile("testdata/cmyk-packshot.jpg")
	if err != nil {
		t.Fatal(err)
	}
	rules := domain.DefaultImageRules()
	a := imageproc.NewAnalyzer(40_000_000)
	got, err := a.Analyze(data, "image/jpeg", true, rules.Main)
	if err != nil {
		t.Fatal(err)
	}
	if !got.Metrics.ConvertedCMYK || got.Data == nil {
		t.Fatalf("CMYK must be converted: %+v", got.Metrics)
	}
	out, err := jpeg.Decode(bytes.NewReader(got.Data))
	if err != nil {
		t.Fatal(err)
	}
	if _, cmyk := out.(*image.CMYK); cmyk {
		t.Fatal("the rewritten JPEG must be RGB")
	}
	checks := domain.EvaluateImage(rules, domain.ImageFacts{Slot: domain.ImageSlotMain, Width: got.Width, Height: got.Height,
		Metrics: &got.Metrics})
	if v := domain.VerdictOf(checks); v == domain.VerdictReject {
		t.Fatalf("a CMYK packshot on white must pass: %v (%+v)", codes(checks), got.Metrics)
	}
}

func TestPlaceholderHashList(t *testing.T) {
	rules := domain.DefaultImageRules()
	a := imageproc.NewAnalyzer(40_000_000)
	// A grey "no image available" card: a product-like box would pass, so the hash list decides.
	card := packshot(1600, 1600, pureWhite, 0.9)
	data := asJPEG(t, card)
	first, err := a.Analyze(data, "image/jpeg", false, rules.Main)
	if err != nil || first.Metrics.Placeholder {
		t.Fatalf("not a placeholder before it is listed: %v %+v", err, first.Metrics)
	}
	proc := imageproc.New(40_000_000)
	pi, err := proc.Process(data, "image/jpeg")
	if err != nil {
		t.Fatal(err)
	}
	rules.Main.PlaceholderHashes = []string{formatHash(pi.Hash)}
	again, err := a.Analyze(data, "image/jpeg", false, rules.Main)
	if err != nil || !again.Metrics.Placeholder {
		t.Fatalf("a listed picture is a placeholder: %v %+v", err, again.Metrics)
	}
}

func formatHash(h uint64) string {
	const digits = "0123456789abcdef"
	out := make([]byte, 16)
	for i := 15; i >= 0; i-- {
		out[i] = digits[h&0xf]
		h >>= 4
	}
	return string(out)
}

func TestLargeImagesAreMeasuredButNotRewritten(t *testing.T) {
	rules := domain.DefaultImageRules()
	rules.Main.SnapMaxPixels = 1_000_000
	a := imageproc.NewAnalyzer(40_000_000)
	got, err := a.Analyze(asJPEG(t, packshot(1600, 1600, nearWhite, 0.92)), "image/jpeg", true, rules.Main)
	if err != nil {
		t.Fatal(err)
	}
	if got.Data != nil || got.Metrics.RingWhiteRatio < 0.95 || got.Metrics.FillRatio < 0.85 {
		t.Fatalf("a large image is measured on a reduced copy and left as it is: %+v", got.Metrics)
	}
}

func TestAnalyzerRefusesHugeAndBrokenImages(t *testing.T) {
	rules := domain.DefaultImageRules()
	a := imageproc.NewAnalyzer(1_000_000)
	if _, err := a.Analyze(asJPEG(t, packshot(1600, 1600, pureWhite, 0.9)), "image/jpeg", true, rules.Main); err == nil {
		t.Fatal("images over the pixel cap are refused")
	}
	if _, err := a.Analyze([]byte("not an image"), "image/jpeg", true, rules.Main); err == nil {
		t.Fatal("broken images are refused")
	}
	// Zero-size and tiny images must not crash the measurements.
	for _, size := range []int{1, 2, 7} {
		tiny := asJPEG(t, packshot(size, size, pureWhite, 0.5))
		if _, err := a.Analyze(tiny, "image/jpeg", true, rules.Main); err != nil {
			t.Fatalf("%d px: %v", size, err)
		}
	}
}
