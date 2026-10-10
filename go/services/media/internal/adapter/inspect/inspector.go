// Package inspect implements port.Inspector: it sniffs the real file type from the bytes (never trusting the
// declared type), decodes image dimensions and samples the border brightness for the white-background check.
package inspect

import (
	"bufio"
	"bytes"
	"fmt"
	"image"
	"image/color"
	"image/jpeg"
	"image/png"
	"io"
	"net/http"

	"golang.org/x/image/webp"

	"github.com/billionairedev24/diaspora-commerce/go/services/media/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/media/internal/port"
)

type inspectorImpl struct {
	maxDecodePixels int
}

var _ port.Inspector = (*inspectorImpl)(nil)

// New returns an Inspector that fully decodes images up to maxDecodePixels (larger images get dimensions only).
func New(maxDecodePixels int) port.Inspector { return &inspectorImpl{maxDecodePixels: maxDecodePixels} }

func (i *inspectorImpl) Inspect(r io.Reader, size int64, _ string) (domain.Inspection, error) {
	br := bufio.NewReaderSize(r, 4096)
	head, _ := br.Peek(512)
	ct := http.DetectContentType(head)
	if bytes.HasPrefix(head, []byte("RIFF")) && len(head) >= 12 && string(head[8:12]) == "WEBP" {
		ct = "image/webp"
	}
	in := domain.Inspection{ContentType: ct, Bytes: size}
	if ct == "application/pdf" || (ct != "image/jpeg" && ct != "image/png" && ct != "image/webp") {
		return in, nil
	}
	data, err := io.ReadAll(br)
	if err != nil {
		return in, fmt.Errorf("read image: %w", err)
	}
	var cfg image.Config
	switch ct {
	case "image/jpeg":
		cfg, err = jpeg.DecodeConfig(bytes.NewReader(data))
	case "image/png":
		cfg, err = png.DecodeConfig(bytes.NewReader(data))
	default:
		cfg, err = webp.DecodeConfig(bytes.NewReader(data))
	}
	if err != nil {
		return in, fmt.Errorf("decode config: %w", err)
	}
	in.Width, in.Height = cfg.Width, cfg.Height
	if cfg.Width*cfg.Height > i.maxDecodePixels {
		in.BorderBrightness = 255 // too large to decode cheaply; do not flag
		return in, nil
	}
	var img image.Image
	switch ct {
	case "image/jpeg":
		img, err = jpeg.Decode(bytes.NewReader(data))
	case "image/png":
		img, err = png.Decode(bytes.NewReader(data))
	default:
		img, err = webp.Decode(bytes.NewReader(data))
	}
	if err != nil {
		return in, fmt.Errorf("decode: %w", err)
	}
	in.BorderBrightness = borderBrightness(img)
	return in, nil
}

// borderBrightness averages luma over a 3% frame around the image, sampling every few pixels.
func borderBrightness(img image.Image) float64 {
	b := img.Bounds()
	w, h := b.Dx(), b.Dy()
	frame := max(1, min(w, h)*3/100)
	step := max(1, min(w, h)/200)
	var sum float64
	var n int
	for y := b.Min.Y; y < b.Max.Y; y += step {
		for x := b.Min.X; x < b.Max.X; x += step {
			if x-b.Min.X >= frame && b.Max.X-x > frame && y-b.Min.Y >= frame && b.Max.Y-y > frame {
				continue
			}
			g := color.GrayModel.Convert(img.At(x, y)).(color.Gray)
			sum += float64(g.Y)
			n++
		}
	}
	if n == 0 {
		return 0
	}
	return sum / float64(n)
}
