// Package imageproc implements port.ImageProcessor: it removes metadata (EXIF with GPS and camera details, XMP,
// IPTC, comments and text chunks) from JPEG, PNG and WebP images losslessly, re-encoding a JPEG only when its EXIF
// orientation must be applied to the pixels, and computes a 64-bit difference hash (dHash) for duplicate detection
// plus a small JPEG preview for the AI moderation hook.
package imageproc

import (
	"bytes"
	"errors"
	"fmt"
	"image"
	"image/color"
	"image/jpeg"
	"image/png"

	"golang.org/x/image/draw"
	"golang.org/x/image/webp"

	"github.com/billionairedev24/diaspora-commerce/go/services/media/internal/port"
)

// PreviewEdge is the longest edge of the preview sent to the AI moderation hook.
const PreviewEdge = 768

// orientedQuality is the JPEG quality used when an EXIF orientation is baked into the pixels.
const orientedQuality = 92

type processorImpl struct {
	maxPixels int
}

var _ port.ImageProcessor = (*processorImpl)(nil)

// New returns an ImageProcessor that refuses images over maxPixels (decoding cost).
func New(maxPixels int) port.ImageProcessor { return &processorImpl{maxPixels: maxPixels} }

// ErrTooLarge is returned for images with more pixels than the processor decodes.
var ErrTooLarge = errors.New("image has too many pixels")

func (p *processorImpl) Process(data []byte, contentType string) (port.ProcessedImage, error) {
	cfg, err := decodeConfig(data, contentType)
	if err != nil {
		return port.ProcessedImage{}, fmt.Errorf("read image header: %w", err)
	}
	if cfg.Width*cfg.Height > p.maxPixels {
		return port.ProcessedImage{}, fmt.Errorf("%w: %d×%d", ErrTooLarge, cfg.Width, cfg.Height)
	}
	var out port.ProcessedImage
	orientation := 1
	switch contentType {
	case "image/jpeg":
		out.Data, orientation, out.Stripped, err = stripJPEG(data)
	case "image/png":
		out.Data, out.Stripped, err = stripPNG(data)
	case "image/webp":
		out.Data, out.Stripped, err = stripWebP(data)
	default:
		return port.ProcessedImage{}, fmt.Errorf("unsupported image type %s", contentType)
	}
	if err != nil {
		return port.ProcessedImage{}, fmt.Errorf("strip metadata: %w", err)
	}
	img, err := decode(out.Data, contentType)
	if err != nil {
		return port.ProcessedImage{}, fmt.Errorf("decode: %w", err)
	}
	if orientation > 1 {
		img = orient(img, orientation)
		var buf bytes.Buffer
		if err := jpeg.Encode(&buf, img, &jpeg.Options{Quality: orientedQuality}); err != nil {
			return port.ProcessedImage{}, fmt.Errorf("encode oriented image: %w", err)
		}
		out.Data = buf.Bytes()
	}
	out.Width, out.Height = img.Bounds().Dx(), img.Bounds().Dy()
	out.Hash = DHash(img)
	out.Preview, err = preview(img)
	if err != nil {
		return port.ProcessedImage{}, err
	}
	return out, nil
}

func decodeConfig(data []byte, contentType string) (image.Config, error) {
	r := bytes.NewReader(data)
	var (
		cfg image.Config
		err error
	)
	switch contentType {
	case "image/jpeg":
		cfg, err = jpeg.DecodeConfig(r)
	case "image/png":
		cfg, err = png.DecodeConfig(r)
	default:
		cfg, err = webp.DecodeConfig(r)
	}
	if err != nil {
		return image.Config{}, fmt.Errorf("decode config: %w", err)
	}
	return cfg, nil
}

func decode(data []byte, contentType string) (image.Image, error) {
	r := bytes.NewReader(data)
	var (
		img image.Image
		err error
	)
	switch contentType {
	case "image/jpeg":
		img, err = jpeg.Decode(r)
	case "image/png":
		img, err = png.Decode(r)
	default:
		img, err = webp.Decode(r)
	}
	if err != nil {
		return nil, fmt.Errorf("decode %s: %w", contentType, err)
	}
	return img, nil
}

// orient applies an EXIF orientation (2–8) so the pixels display upright without the tag.
func orient(src image.Image, o int) image.Image {
	b := src.Bounds()
	w, h := b.Dx(), b.Dy()
	if o >= 5 {
		w, h = h, w
	}
	dst := image.NewRGBA(image.Rect(0, 0, w, h))
	sw, sh := b.Dx(), b.Dy()
	for y := range sh {
		for x := range sw {
			var dx, dy int
			switch o {
			case 2:
				dx, dy = sw-1-x, y
			case 3:
				dx, dy = sw-1-x, sh-1-y
			case 4:
				dx, dy = x, sh-1-y
			case 5:
				dx, dy = y, x
			case 6:
				dx, dy = sh-1-y, x
			case 7:
				dx, dy = sh-1-y, sw-1-x
			case 8:
				dx, dy = y, sw-1-x
			default:
				dx, dy = x, y
			}
			dst.Set(dx, dy, src.At(b.Min.X+x, b.Min.Y+y))
		}
	}
	return dst
}

// DHash is the 64-bit difference hash: the image reduced to 9×8 grey cells (area averages), one bit per pair of
// horizontal neighbours (left brighter than right). Re-encoding, resizing and mild edits keep most bits.
func DHash(img image.Image) uint64 {
	const cols, rows = 9, 8
	b := img.Bounds()
	w, h := b.Dx(), b.Dy()
	var cells [rows][cols]float64
	for r := range rows {
		y0, y1 := b.Min.Y+r*h/rows, b.Min.Y+(r+1)*h/rows
		y1 = max(y1, y0+1)
		for c := range cols {
			x0, x1 := b.Min.X+c*w/cols, b.Min.X+(c+1)*w/cols
			x1 = max(x1, x0+1)
			stepX, stepY := max(1, (x1-x0)/8), max(1, (y1-y0)/8)
			var sum float64
			var n int
			for y := y0; y < y1; y += stepY {
				for x := x0; x < x1; x += stepX {
					sum += luma(img.At(x, y))
					n++
				}
			}
			cells[r][c] = sum / float64(n)
		}
	}
	var hash uint64
	for r := range rows {
		for c := range cols - 1 {
			hash <<= 1
			if cells[r][c] > cells[r][c+1] {
				hash |= 1
			}
		}
	}
	return hash
}

// luma of a colour composited on white (transparent pixels count as white background).
func luma(c color.Color) float64 {
	r, g, bl, a := c.RGBA()
	white := float64(0xffff - a)
	return 0.299*(float64(r)+white) + 0.587*(float64(g)+white) + 0.114*(float64(bl)+white)
}

// preview scales the image to at most PreviewEdge on its longest side, flattened on white, as a JPEG.
func preview(img image.Image) ([]byte, error) {
	b := img.Bounds()
	w, h := b.Dx(), b.Dy()
	if longest := max(w, h); longest > PreviewEdge {
		w, h = max(1, w*PreviewEdge/longest), max(1, h*PreviewEdge/longest)
	}
	dst := image.NewRGBA(image.Rect(0, 0, w, h))
	draw.Draw(dst, dst.Bounds(), image.White, image.Point{}, draw.Src)
	draw.ApproxBiLinear.Scale(dst, dst.Bounds(), img, b, draw.Over, nil)
	var buf bytes.Buffer
	if err := jpeg.Encode(&buf, dst, &jpeg.Options{Quality: 80}); err != nil {
		return nil, fmt.Errorf("encode preview: %w", err)
	}
	return buf.Bytes(), nil
}
