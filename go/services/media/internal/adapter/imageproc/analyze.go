package imageproc

import (
	"bytes"
	"fmt"
	"image"
	"image/jpeg"
	"image/png"
	"math"
	"slices"

	"golang.org/x/image/draw"

	"github.com/billionairedev24/diaspora-commerce/go/services/media/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/media/internal/port"
)

// workEdge is the longest edge of the reduced copy the fill, border and blur measurements run on.
const workEdge = 1000

// blurEdge is the longest edge the sharpness is measured at, so the number does not depend on the photo's size.
const blurEdge = 512

// scanLines is how many perpendicular lines per edge look for a frame.
const scanLines = 16

// frameTolerance is how far (per channel) a pixel may differ from the edge pixel and still be part of the band.
const frameTolerance = 14

// jpegQuality is used when snapping or colour conversion makes a JPEG be written again.
const jpegQuality = 92

type analyzerImpl struct {
	maxPixels int
}

var _ port.ImageAnalyzer = (*analyzerImpl)(nil)

// NewAnalyzer returns the ImageAnalyzer: it measures the main image rules on the pixels (white background by sampling
// the outer ring and the corners, how much of the frame the product fills, a frame around the picture, sharpness,
// placeholders) and, for a main image on white, sets the near-white background to pure white. Transparent images are
// flattened onto white and CMYK ones become RGB. Images over maxPixels are refused.
func NewAnalyzer(maxPixels int) port.ImageAnalyzer { return &analyzerImpl{maxPixels: maxPixels} }

func (a *analyzerImpl) Analyze(data []byte, contentType string, snap bool, rules domain.MainRules) (port.AnalyzedImage, error) {
	cfg, err := decodeConfig(data, contentType)
	if err != nil {
		return port.AnalyzedImage{}, fmt.Errorf("read image header: %w", err)
	}
	if cfg.Width*cfg.Height > a.maxPixels {
		return port.AnalyzedImage{}, fmt.Errorf("%w: %d×%d", ErrTooLarge, cfg.Width, cfg.Height)
	}
	src, err := decode(data, contentType)
	if err != nil {
		return port.AnalyzedImage{}, fmt.Errorf("decode: %w", err)
	}
	met := domain.ImageMetrics{Measured: true, FillRatio: -1, Sharpness: -1}
	if _, ok := src.(*image.CMYK); ok {
		met.ConvertedCMYK = true
	}
	hadAlpha := false
	if o, ok := src.(interface{ Opaque() bool }); ok {
		hadAlpha = !o.Opaque()
	}
	full := flatten(src)
	hash := DHash(full) // before anything is changed, so it matches the hash the duplicate check stores
	w, h := full.Bounds().Dx(), full.Bounds().Dy()
	whiteMin := uint8(clamp(rules.WhiteChannelMin, 0, 255)) //nolint:gosec // clamped
	big := rules.SnapMaxPixels > 0 && w*h > rules.SnapMaxPixels
	probe := full
	if big {
		probe = scaleTo(full, rules.SnapMaxPixels)
	}
	pw, ph := probe.Bounds().Dx(), probe.Bounds().Dy()
	ringW := max(1, int(float64(min(pw, ph))*rules.RingFraction+0.5))
	met.RingWhiteRatio, met.CornersWhite = ringStats(probe, ringW, whiteMin)
	whiteOK := met.RingWhiteRatio >= rules.RingPassRatio && met.CornersWhite

	if whiteOK && snap && !big {
		met.SnappedWhite = snapToWhite(full, whiteMin)
	}
	met.FlattenedAlpha = hadAlpha && snap
	work := probe
	if whiteOK && met.SnappedWhite {
		work = full
	}
	work = reduce(work, workEdge)

	band, ratio := frameBand(work, rules)
	met.BorderBand, met.BorderRatio = band, ratio
	if whiteOK {
		box, ok := productBox(work, rules.FillTolerance)
		if !ok {
			met.Placeholder = true
		} else {
			bw, bh := box.Dx(), box.Dy()
			met.FillRatio = max(float64(bw)/float64(work.Bounds().Dx()), float64(bh)/float64(work.Bounds().Dy()))
			met.Sharpness = sharpness(work, box)
		}
	}
	if !met.Placeholder {
		for _, p := range rules.PlaceholderHashList() {
			if domain.HashDistance(hash, p) <= rules.PlaceholderDistance {
				met.Placeholder = true
				break
			}
		}
	}

	out := port.AnalyzedImage{Metrics: met, ContentType: contentType, Width: w, Height: h}
	if !big && (met.SnappedWhite || met.FlattenedAlpha || met.ConvertedCMYK) {
		enc, ct, err := encodeFlattened(full, contentType)
		if err != nil {
			return port.AnalyzedImage{}, err
		}
		out.Data, out.ContentType = enc, ct
	}
	return out, nil
}

// flatten draws the image onto white, as 8-bit RGBA with the origin at 0,0 (transparent pixels become white, CMYK
// becomes RGB).
func flatten(src image.Image) *image.RGBA {
	b := src.Bounds()
	dst := image.NewRGBA(image.Rect(0, 0, b.Dx(), b.Dy()))
	draw.Draw(dst, dst.Bounds(), image.White, image.Point{}, draw.Src)
	draw.Draw(dst, dst.Bounds(), src, b.Min, draw.Over)
	return dst
}

// scaleTo reduces the image so it has at most maxPixels pixels.
func scaleTo(src *image.RGBA, maxPixels int) *image.RGBA {
	b := src.Bounds()
	f := math.Sqrt(float64(maxPixels) / float64(b.Dx()*b.Dy()))
	return scale(src, max(1, int(float64(b.Dx())*f)), max(1, int(float64(b.Dy())*f)))
}

// reduce scales the image down to at most edge pixels on its longest side (a smaller image is returned as is).
func reduce(src *image.RGBA, edge int) *image.RGBA {
	b := src.Bounds()
	w, h := b.Dx(), b.Dy()
	longest := max(w, h)
	if longest <= edge {
		return src
	}
	return scale(src, max(1, w*edge/longest), max(1, h*edge/longest))
}

func scale(src *image.RGBA, w, h int) *image.RGBA {
	dst := image.NewRGBA(image.Rect(0, 0, w, h))
	draw.ApproxBiLinear.Scale(dst, dst.Bounds(), src, src.Bounds(), draw.Src, nil)
	return dst
}

func clamp(v, lo, hi int) int { return max(lo, min(hi, v)) }

func white(p []uint8, i int, min uint8) bool {
	return p[i] >= min && p[i+1] >= min && p[i+2] >= min
}

// ringStats samples the outer ring (every second pixel on all four sides) and reports the share of white pixels and
// whether all four corners are white.
func ringStats(img *image.RGBA, ring int, whiteMin uint8) (ratio float64, corners bool) {
	w, h := img.Bounds().Dx(), img.Bounds().Dy()
	var total, hits int
	for y := 0; y < h; y += 2 {
		if y < ring || y >= h-ring {
			for x := 0; x < w; x += 2 {
				total++
				if white(img.Pix, y*img.Stride+x*4, whiteMin) {
					hits++
				}
			}
			continue
		}
		for x := 0; x < ring; x += 2 {
			total++
			if white(img.Pix, y*img.Stride+x*4, whiteMin) {
				hits++
			}
		}
		for x := w - ring; x < w; x += 2 {
			if x < ring {
				continue
			}
			total++
			if white(img.Pix, y*img.Stride+x*4, whiteMin) {
				hits++
			}
		}
	}
	corners = true
	for _, c := range [][2]int{{0, 0}, {w - 1, 0}, {0, h - 1}, {w - 1, h - 1}} {
		for dy := range 2 {
			for dx := range 2 {
				x, y := clamp(c[0]+signOf(c[0], w)*dx, 0, w-1), clamp(c[1]+signOf(c[1], h)*dy, 0, h-1)
				if !white(img.Pix, y*img.Stride+x*4, whiteMin) {
					corners = false
				}
			}
		}
	}
	if total == 0 {
		return 0, false
	}
	return float64(hits) / float64(total), corners
}

// signOf is +1 at the low edge and -1 at the high edge, so a 2×2 block grows inward from a corner.
func signOf(v, size int) int {
	if v >= size-1 && size > 1 {
		return -1
	}
	return 1
}

// snapToWhite sets the near-white pixels connected to the border to pure white (255) and reports whether anything
// changed. Near-white pixels inside the product (highlights, white labels) are not touched.
func snapToWhite(img *image.RGBA, whiteMin uint8) bool {
	w, h := img.Bounds().Dx(), img.Bounds().Dy()
	seen := make([]bool, w*h)
	stack := make([]int32, 0, 2*(w+h))
	push := func(x, y int) {
		i := y*w + x
		if seen[i] || !white(img.Pix, y*img.Stride+x*4, whiteMin) {
			return
		}
		seen[i] = true
		stack = append(stack, int32(i)) //nolint:gosec // w*h is bounded by SnapMaxPixels
	}
	for x := range w {
		push(x, 0)
		push(x, h-1)
	}
	for y := range h {
		push(0, y)
		push(w-1, y)
	}
	changed := false
	for len(stack) > 0 {
		i := int(stack[len(stack)-1])
		stack = stack[:len(stack)-1]
		x, y := i%w, i/w
		o := y*img.Stride + x*4
		if img.Pix[o] != 255 || img.Pix[o+1] != 255 || img.Pix[o+2] != 255 {
			img.Pix[o], img.Pix[o+1], img.Pix[o+2] = 255, 255, 255
			changed = true
		}
		if x > 0 {
			push(x-1, y)
		}
		if x < w-1 {
			push(x+1, y)
		}
		if y > 0 {
			push(x, y-1)
		}
		if y < h-1 {
			push(x, y+1)
		}
	}
	return changed
}

// productBox is the bounding box of the pixels farther than tol (in any channel) from white after a 3×3 blur; ok is
// false when there is none (a blank image).
func productBox(img *image.RGBA, tol int) (image.Rectangle, bool) {
	w, h := img.Bounds().Dx(), img.Bounds().Dy()
	dist := make([]uint8, w*h)
	for y := range h {
		for x := range w {
			o := y*img.Stride + x*4
			dist[y*w+x] = 255 - min(img.Pix[o], img.Pix[o+1], img.Pix[o+2])
		}
	}
	box := image.Rectangle{Min: image.Pt(w, h), Max: image.Pt(0, 0)}
	found := false
	for y := range h {
		for x := range w {
			sum, n := 0, 0
			for dy := -1; dy <= 1; dy++ {
				for dx := -1; dx <= 1; dx++ {
					xx, yy := x+dx, y+dy
					if xx < 0 || yy < 0 || xx >= w || yy >= h {
						continue
					}
					sum += int(dist[yy*w+xx])
					n++
				}
			}
			if sum/n > tol {
				found = true
				box.Min.X, box.Min.Y = min(box.Min.X, x), min(box.Min.Y, y)
				box.Max.X, box.Max.Y = max(box.Max.X, x+1), max(box.Max.Y, y+1)
			}
		}
	}
	return box, found
}

// sharpness is the variance of the Laplacian of the grey product area, at a fixed working size.
func sharpness(img *image.RGBA, box image.Rectangle) float64 {
	crop := image.NewRGBA(image.Rect(0, 0, box.Dx(), box.Dy()))
	draw.Draw(crop, crop.Bounds(), img, box.Min, draw.Src)
	crop = reduce(crop, blurEdge)
	w, h := crop.Bounds().Dx(), crop.Bounds().Dy()
	if w < 3 || h < 3 {
		return -1
	}
	grey := make([]float64, w*h)
	for y := range h {
		for x := range w {
			o := y*crop.Stride + x*4
			grey[y*w+x] = 0.299*float64(crop.Pix[o]) + 0.587*float64(crop.Pix[o+1]) + 0.114*float64(crop.Pix[o+2])
		}
	}
	var sum, sumSq float64
	n := 0
	for y := 1; y < h-1; y++ {
		for x := 1; x < w-1; x++ {
			l := grey[(y-1)*w+x] + grey[(y+1)*w+x] + grey[y*w+x-1] + grey[y*w+x+1] - 4*grey[y*w+x]
			sum += l
			sumSq += l * l
			n++
		}
	}
	mean := sum / float64(n)
	return sumSq/float64(n) - mean*mean
}

// frameBand reports whether a uniform non-white band, thicker than BorderMinRatio and thinner than BorderMaxRatio of
// the shorter side, runs along three or more edges, and its median thickness as a share of the shorter side.
func frameBand(img *image.RGBA, rules domain.MainRules) (bool, float64) {
	w, h := img.Bounds().Dx(), img.Bounds().Dy()
	short := min(w, h)
	minRun := max(2, int(float64(short)*rules.BorderMinRatio+0.5))
	maxRun := int(float64(short) * rules.BorderMaxRatio)
	if maxRun < minRun || short < 8 {
		return false, 0
	}
	edges := 0
	var thicknesses []int
	for edge := range 4 {
		var runs []int
		for k := range scanLines {
			frac := (float64(k) + 0.5) / scanLines
			run := runFromEdge(img, edge, frac, maxRun+1)
			c := pixelAtEdge(img, edge, frac)
			if !white(c[:], 0, 245) && run >= minRun && run <= maxRun && whiteInside(img, edge, frac, run) {
				runs = append(runs, run)
			}
		}
		if len(runs) >= scanLines*3/4 {
			edges++
			slices.Sort(runs)
			thicknesses = append(thicknesses, runs[len(runs)/2])
		}
	}
	if edges < 3 {
		return false, 0
	}
	slices.Sort(thicknesses)
	return true, float64(thicknesses[len(thicknesses)/2]) / float64(short)
}

// whiteInside reports whether the picture just inside the band is white: a frame sits around a white packshot, while
// a coloured margin that runs into the product is a background.
func whiteInside(img *image.RGBA, edge int, frac float64, run int) bool {
	for d := run + 1; d <= run+3; d++ {
		x, y := edgePoint(img, edge, frac, d)
		if !white(img.Pix, y*img.Stride+x*4, 240) {
			return false
		}
	}
	return true
}

// pixelAtEdge is the edge pixel of a scan line: edges 0 top, 1 bottom, 2 left, 3 right; frac is the position along it.
func pixelAtEdge(img *image.RGBA, edge int, frac float64) [4]uint8 {
	x, y := edgePoint(img, edge, frac, 0)
	o := y*img.Stride + x*4
	return [4]uint8{img.Pix[o], img.Pix[o+1], img.Pix[o+2], img.Pix[o+3]}
}

func edgePoint(img *image.RGBA, edge int, frac float64, depth int) (x, y int) {
	w, h := img.Bounds().Dx(), img.Bounds().Dy()
	switch edge {
	case 0:
		return clamp(int(frac*float64(w)), 0, w-1), clamp(depth, 0, h-1)
	case 1:
		return clamp(int(frac*float64(w)), 0, w-1), clamp(h-1-depth, 0, h-1)
	case 2:
		return clamp(depth, 0, w-1), clamp(int(frac*float64(h)), 0, h-1)
	default:
		return clamp(w-1-depth, 0, w-1), clamp(int(frac*float64(h)), 0, h-1)
	}
}

// runFromEdge counts the pixels from the edge inward that stay within frameTolerance of the edge pixel (at most limit).
func runFromEdge(img *image.RGBA, edge int, frac float64, limit int) int {
	ref := pixelAtEdge(img, edge, frac)
	run := 0
	for d := range limit {
		x, y := edgePoint(img, edge, frac, d)
		o := y*img.Stride + x*4
		if absDiff(img.Pix[o], ref[0]) > frameTolerance || absDiff(img.Pix[o+1], ref[1]) > frameTolerance ||
			absDiff(img.Pix[o+2], ref[2]) > frameTolerance {
			break
		}
		run++
	}
	return run
}

func absDiff(a, b uint8) int {
	if a > b {
		return int(a - b)
	}
	return int(b - a)
}

// encodeFlattened writes the flattened image: JPEG stays JPEG, PNG stays PNG and WebP (no encoder) becomes PNG.
func encodeFlattened(img *image.RGBA, contentType string) ([]byte, string, error) {
	var buf bytes.Buffer
	if contentType == "image/jpeg" {
		if err := jpeg.Encode(&buf, img, &jpeg.Options{Quality: jpegQuality}); err != nil {
			return nil, "", fmt.Errorf("encode jpeg: %w", err)
		}
		return buf.Bytes(), contentType, nil
	}
	if err := png.Encode(&buf, img); err != nil {
		return nil, "", fmt.Errorf("encode png: %w", err)
	}
	return buf.Bytes(), "image/png", nil
}
