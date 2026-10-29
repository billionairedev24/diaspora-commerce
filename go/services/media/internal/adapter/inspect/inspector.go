// Package inspect implements port.Inspector: it sniffs the real file type from the bytes (never trusting the
// declared type), decodes image dimensions and samples the border brightness for the white-background check.
// Plain text declared as CSV is reported as CSV, and ZIP archives that hold an Office Open XML workbook as XLSX.
package inspect

import (
	"bufio"
	"bytes"
	"encoding/binary"
	"fmt"
	"image"
	"image/color"
	"image/jpeg"
	"image/png"
	"io"
	"net/http"
	"strings"

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

func (i *inspectorImpl) Inspect(r io.Reader, size int64, declaredType string) (domain.Inspection, error) {
	br := bufio.NewReaderSize(r, 4096)
	head, _ := br.Peek(512)
	ct := http.DetectContentType(head)
	if bytes.HasPrefix(head, []byte("RIFF")) && len(head) >= 12 && string(head[8:12]) == "WEBP" {
		ct = "image/webp"
	}
	if isHEIC(head) {
		// No dimensions: the standard library cannot decode HEIC, so only the type and size rules apply.
		return domain.Inspection{ContentType: domain.ContentTypeHEIC, Bytes: size}, nil
	}
	in := domain.Inspection{ContentType: ct, Bytes: size}
	switch {
	case strings.HasPrefix(ct, "text/plain") && declaredType == domain.ContentTypeCSV:
		// Bytes cannot tell CSV from other plain text; the sniffer has ruled out HTML, scripts and binaries.
		in.ContentType = domain.ContentTypeCSV
		return in, nil
	case ct == "application/zip":
		rest, err := io.ReadAll(br)
		if err != nil {
			return in, fmt.Errorf("read archive: %w", err)
		}
		if isSpreadsheetML(rest) {
			in.ContentType = domain.ContentTypeXLSX
		}
		return in, nil
	case ct != "image/jpeg" && ct != "image/png" && ct != "image/webp":
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

// isHEIC reports whether the head starts with an ISO base media "ftyp" box of a HEIF image brand.
func isHEIC(head []byte) bool {
	if len(head) < 12 || string(head[4:8]) != "ftyp" {
		return false
	}
	switch string(head[8:12]) {
	case "heic", "heix", "hevc", "hevx", "heim", "heis", "mif1", "msf1":
		return true
	}
	return false
}

// zipLocalHeader starts every member of a ZIP archive.
var zipLocalHeader = []byte("PK\x03\x04")

// isSpreadsheetML reports whether a ZIP archive is an Office Open XML workbook: it must carry the package's
// [Content_Types].xml and the workbook part xl/workbook.xml. Member names are read from the local file headers, so
// the archive's central directory (at its end, possibly past what was read) is not needed.
func isSpreadsheetML(data []byte) bool {
	var contentTypes, workbook bool
	for off := 0; ; {
		i := bytes.Index(data[off:], zipLocalHeader)
		if i < 0 {
			break
		}
		h := off + i
		const fixed = 30 // local file header size before the name
		if h+fixed > len(data) {
			break
		}
		nameLen := int(binary.LittleEndian.Uint16(data[h+26 : h+28]))
		if h+fixed+nameLen > len(data) {
			break
		}
		switch string(data[h+fixed : h+fixed+nameLen]) {
		case "[Content_Types].xml":
			contentTypes = true
		case "xl/workbook.xml":
			workbook = true
		}
		if contentTypes && workbook {
			return true
		}
		off = h + len(zipLocalHeader)
	}
	return false
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
