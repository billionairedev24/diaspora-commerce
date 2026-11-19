package imageproc

import (
	"bytes"
	"encoding/binary"
	"testing"

	"github.com/billionairedev24/diaspora-commerce/go/pkg/picturerules"
)

func box(typ string, body ...[]byte) []byte {
	payload := bytes.Join(body, nil)
	out := binary.BigEndian.AppendUint32(nil, uint32(8+len(payload))) //nolint:gosec // test sizes
	out = append(out, typ...)
	return append(out, payload...)
}

func full(version byte, rest ...[]byte) []byte {
	return append([]byte{version, 0, 0, 0}, bytes.Join(rest, nil)...)
}

func u16(v int) []byte { return binary.BigEndian.AppendUint16(nil, uint16(v)) } //nolint:gosec // test values
func u32(v int) []byte { return binary.BigEndian.AppendUint32(nil, uint32(v)) } //nolint:gosec // test values

// avifWithExif builds an AVIF with an Exif item (id 1) and an XMP mime item (id 3) and an image item (id 2). The items' bytes sit
// in mdat; the iloc offsets are absolute, as in real files.
func avifWithExif(w, h int, exif, xmp, pixels []byte) []byte {
	build := func(base int) []byte {
		infe := func(id int, typ, name, mime string) []byte {
			body := full(2, u16(id), u16(0), []byte(typ), []byte(name+"\x00"))
			if mime != "" {
				body = append(body, []byte(mime+"\x00")...)
			}
			return box("infe", body)
		}
		iinf := box("iinf", full(0, u16(3), infe(1, "Exif", "", ""), infe(2, "av01", "", ""), infe(3, "mime", "", "application/rdf+xml")))
		item := func(id, off, length int) []byte {
			return bytes.Join([][]byte{u16(id), u16(0), u16(1), u32(off), u32(length)}, nil)
		}
		iloc := box("iloc", full(0, []byte{0x44, 0x00}, u16(3), item(1, base, len(exif)), item(2, base+len(exif), len(pixels)),
			item(3, base+len(exif)+len(pixels), len(xmp))))
		ispe := box("ispe", full(0, u32(w), u32(h)))
		iprp := box("iprp", box("ipco", ispe))
		return bytes.Join([][]byte{
			box("ftyp", []byte("avif"), u32(0), []byte("avifmif1")),
			box("meta", full(0, iinf, iloc, iprp)),
		}, nil)
	}
	head := build(0)
	base := len(head) + 8
	head = build(base)
	return bytes.Join([][]byte{head, box("mdat", exif, pixels, xmp)}, nil)
}

func TestAvifMetadataIsZeroedInPlaceAndTheRestIsUntouched(t *testing.T) {
	exif := []byte("\x00\x00\x00\x00Exif\x00\x00GPSLatitude=6.5244N")
	xmp := []byte("<x:xmpmeta>photographer</x:xmpmeta>")
	pixels := []byte("PIXELDATA-1234567890")
	src := avifWithExif(1600, 1200, exif, xmp, pixels)
	if picturerules.Sniff(src) != picturerules.AVIF {
		t.Fatal("the test file must sniff as AVIF")
	}

	out, stripped, err := stripAVIF(src)
	if err != nil || !stripped {
		t.Fatalf("stripped=%v err=%v", stripped, err)
	}
	if len(out) != len(src) {
		t.Fatalf("size changed %d -> %d: offsets would break", len(src), len(out))
	}
	if bytes.Contains(out, []byte("GPSLatitude")) || bytes.Contains(out, []byte("photographer")) {
		t.Fatal("metadata still present")
	}
	if !bytes.Contains(out, pixels) {
		t.Fatal("the image data must stay")
	}
	if !bytes.Contains(src, []byte("GPSLatitude")) {
		t.Fatal("the input must not be modified")
	}
	w, h, err := picturerules.Dimensions(out, picturerules.AVIF)
	if err != nil || w != 1600 || h != 1200 {
		t.Fatalf("%dx%d %v", w, h, err)
	}
}

func TestAvifWithoutMetadataIsReturnedAsIs(t *testing.T) {
	src := avifWithExif(800, 800, nil, nil, []byte("PIXELS"))
	out, stripped, err := stripAVIF(src)
	if err != nil || stripped || !bytes.Equal(out, src) {
		t.Fatalf("stripped=%v err=%v", stripped, err)
	}
}

func TestAvifProcessGivesHeaderSizeNoHashAndNoPreview(t *testing.T) {
	src := avifWithExif(1024, 768, []byte("\x00\x00\x00\x00Exif\x00\x00secret"), nil, []byte("PIXELS"))
	got, err := New(40_000_000).Process(src, picturerules.AVIF)
	if err != nil {
		t.Fatal(err)
	}
	if !got.Undecoded || got.Width != 1024 || got.Height != 768 || got.Hash != 0 || got.Preview != nil || !got.Stripped {
		t.Fatalf("%+v", got)
	}
}

func TestAvifOverThePixelLimitIsRefused(t *testing.T) {
	src := avifWithExif(9000, 9000, nil, nil, []byte("PIXELS"))
	if _, err := New(1_000_000).Process(src, picturerules.AVIF); err == nil {
		t.Fatal("too many pixels must be refused")
	}
}

func TestAMalformedAvifIsAnErrorNotAPanic(t *testing.T) {
	src := avifWithExif(800, 800, []byte("exif-bytes-here"), nil, []byte("PIXELS"))
	for cut := 12; cut < len(src); cut += 7 {
		_, _, _ = stripAVIF(src[:cut])
	}
	bad := append([]byte(nil), src...)
	for i := 40; i < 120 && i < len(bad); i++ {
		bad[i] = 0xFF
	}
	_, _, _ = stripAVIF(bad)
	if _, _, err := stripAVIF([]byte("not an avif at all")); err == nil {
		t.Fatal("non AVIF input must be an error")
	}
}
