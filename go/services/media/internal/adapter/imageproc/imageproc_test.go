package imageproc

import (
	"bytes"
	"encoding/binary"
	"hash/crc32"
	"image"
	"image/color"
	"image/jpeg"
	"image/png"
	"math/bits"
	"testing"

	"golang.org/x/image/draw"
)

// gradient draws a picture with structure (so the dHash has both 0 and 1 bits) and a marker in the top-left corner
// (so rotations are visible).
func gradient(w, h int, seed uint8) *image.RGBA {
	img := image.NewRGBA(image.Rect(0, 0, w, h))
	for y := range h {
		for x := range w {
			v := uint8((x*255/w + int(seed)*y*255/h) % 256) //nolint:gosec // test pattern
			img.Set(x, y, color.RGBA{R: v, G: 255 - v, B: uint8((x + y) % 256), A: 255})
		}
	}
	for y := range h / 10 {
		for x := range w / 10 {
			img.Set(x, y, color.RGBA{R: 255, A: 255})
		}
	}
	return img
}

func encodeJPEG(t *testing.T, img image.Image, q int) []byte {
	t.Helper()
	var buf bytes.Buffer
	if err := jpeg.Encode(&buf, img, &jpeg.Options{Quality: q}); err != nil {
		t.Fatal(err)
	}
	return buf.Bytes()
}

// exifSegment is an APP1 EXIF segment with IFD0 holding an orientation tag and a fake GPS pointer.
func exifSegment(orientation uint16) []byte {
	var tiff bytes.Buffer
	tiff.WriteString("II")
	_ = binary.Write(&tiff, binary.LittleEndian, uint16(42))
	_ = binary.Write(&tiff, binary.LittleEndian, uint32(8))
	_ = binary.Write(&tiff, binary.LittleEndian, uint16(2)) // two entries
	// Orientation (0x0112), SHORT, count 1, value
	_ = binary.Write(&tiff, binary.LittleEndian, []uint16{0x0112, 3})
	_ = binary.Write(&tiff, binary.LittleEndian, uint32(1))
	_ = binary.Write(&tiff, binary.LittleEndian, []uint16{orientation, 0})
	// GPSInfo (0x8825), LONG, count 1, offset
	_ = binary.Write(&tiff, binary.LittleEndian, []uint16{0x8825, 4})
	_ = binary.Write(&tiff, binary.LittleEndian, uint32(1))
	_ = binary.Write(&tiff, binary.LittleEndian, uint32(0))
	_ = binary.Write(&tiff, binary.LittleEndian, uint32(0))
	tiff.WriteString("GPS 6.5244N 3.3792E Canon EOS")
	payload := append([]byte("Exif\x00\x00"), tiff.Bytes()...)
	seg := []byte{0xFF, 0xE1}
	seg = binary.BigEndian.AppendUint16(seg, uint16(len(payload)+2)) //nolint:gosec // small test segment
	return append(seg, payload...)
}

func withSegments(jpg []byte, segs ...[]byte) []byte {
	out := append([]byte{}, jpg[:2]...)
	for _, s := range segs {
		out = append(out, s...)
	}
	return append(out, jpg[2:]...)
}

func comment(text string) []byte {
	seg := []byte{0xFF, 0xFE}
	seg = binary.BigEndian.AppendUint16(seg, uint16(len(text)+2)) //nolint:gosec // small test segment
	return append(seg, text...)
}

func TestJPEGMetadataIsStrippedAndOrientationApplied(t *testing.T) {
	src := gradient(300, 200, 1)
	plain := encodeJPEG(t, src, 90)
	tagged := withSegments(plain, exifSegment(6), comment("shot at 12 Allen Avenue"))
	p := New(40_000_000)

	out, err := p.Process(tagged, "image/jpeg")
	if err != nil {
		t.Fatal(err)
	}
	if !out.Stripped || bytes.Contains(out.Data, []byte("Exif")) || bytes.Contains(out.Data, []byte("GPS")) ||
		bytes.Contains(out.Data, []byte("Allen Avenue")) {
		t.Fatal("EXIF / comment must be removed")
	}
	cfg, err := jpeg.DecodeConfig(bytes.NewReader(out.Data))
	if err != nil {
		t.Fatal(err)
	}
	if cfg.Width != 200 || cfg.Height != 300 {
		t.Fatalf("orientation 6 must rotate 300×200 to 200×300, got %d×%d", cfg.Width, cfg.Height)
	}
	if len(out.Preview) == 0 || out.Hash == 0 {
		t.Fatalf("preview and hash expected: %d bytes, %x", len(out.Preview), out.Hash)
	}

	// Without orientation the scan data is untouched: only the segments go.
	upright, err := p.Process(withSegments(plain, exifSegment(1)), "image/jpeg")
	if err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(upright.Data, plain) {
		t.Fatal("an upright JPEG must be stripped losslessly")
	}
	clean, err := p.Process(plain, "image/jpeg")
	if err != nil || clean.Stripped || !bytes.Equal(clean.Data, plain) {
		t.Fatalf("a clean JPEG stays as it is: stripped=%v err=%v", clean.Stripped, err)
	}
}

func pngChunk(typ string, data []byte) []byte {
	out := binary.BigEndian.AppendUint32(nil, uint32(len(data))) //nolint:gosec // small test chunk
	out = append(out, typ...)
	out = append(out, data...)
	return binary.BigEndian.AppendUint32(out, crc32.ChecksumIEEE(append([]byte(typ), data...)))
}

func TestPNGTextChunksAreStripped(t *testing.T) {
	var buf bytes.Buffer
	if err := png.Encode(&buf, gradient(64, 64, 2)); err != nil {
		t.Fatal(err)
	}
	raw := buf.Bytes()
	// Insert tEXt and eXIf right after IHDR (8 signature + 25 IHDR bytes).
	tagged := append(append([]byte{}, raw[:33]...), pngChunk("tEXt", []byte("Author\x00Mama Tee, +1 555 0100"))...)
	tagged = append(tagged, pngChunk("eXIf", []byte("MM\x00\x2a"))...)
	tagged = append(tagged, raw[33:]...)

	out, err := New(40_000_000).Process(tagged, "image/png")
	if err != nil {
		t.Fatal(err)
	}
	if !out.Stripped || !bytes.Equal(out.Data, raw) {
		t.Fatal("text and EXIF chunks must be dropped, everything else kept byte for byte")
	}
}

func webpChunk(fourCC string, data []byte) []byte {
	out := append([]byte(fourCC), binary.LittleEndian.AppendUint32(nil, uint32(len(data)))...) //nolint:gosec // test
	out = append(out, data...)
	if len(data)%2 == 1 {
		out = append(out, 0)
	}
	return out
}

func TestWebPExifAndXMPChunksAreStripped(t *testing.T) {
	vp8x := make([]byte, 10)
	vp8x[0] = webpFlagEXIF | webpFlagXMP | 0x10 // + alpha
	image := webpChunk("VP8L", []byte{0x2f, 0, 0, 0, 0})
	chunks := append(append(append(webpChunk("VP8X", vp8x), image...), webpChunk("EXIF", []byte("II*\x00GPS"))...),
		webpChunk("XMP ", []byte("<x:xmpmeta/>"))...)
	file := append([]byte("RIFF"), binary.LittleEndian.AppendUint32(nil, uint32(4+len(chunks)))...) //nolint:gosec // test
	file = append(append(file, "WEBP"...), chunks...)

	out, stripped, err := stripWebP(file)
	if err != nil || !stripped {
		t.Fatalf("stripped=%v err=%v", stripped, err)
	}
	if bytes.Contains(out, []byte("EXIF")) || bytes.Contains(out, []byte("xmpmeta")) {
		t.Fatal("EXIF and XMP chunks must be removed")
	}
	if got := binary.LittleEndian.Uint32(out[4:8]); int(got) != len(out)-8 {
		t.Fatalf("RIFF size %d, file %d", got, len(out))
	}
	if flags := out[20]; flags&(webpFlagEXIF|webpFlagXMP) != 0 || flags&0x10 == 0 {
		t.Fatalf("VP8X flags %08b: EXIF/XMP cleared, alpha kept", flags)
	}
	if !bytes.Contains(out, image) {
		t.Fatal("image data must be kept")
	}
}

func TestDHashFindsTheSamePhotoAcrossReencodingAndResizing(t *testing.T) {
	a := gradient(400, 400, 1)
	small := image.NewRGBA(image.Rect(0, 0, 160, 160))
	draw.ApproxBiLinear.Scale(small, small.Bounds(), a, a.Bounds(), draw.Src, nil)
	reencoded, err := jpeg.Decode(bytes.NewReader(encodeJPEG(t, small, 40)))
	if err != nil {
		t.Fatal(err)
	}
	other := gradient(400, 400, 3)
	same := bits.OnesCount64(DHash(a) ^ DHash(reencoded))
	diff := bits.OnesCount64(DHash(a) ^ DHash(other))
	if same > 6 || diff <= 10 {
		t.Fatalf("same photo distance %d (want ≤ 6), different photo %d (want > 10)", same, diff)
	}
}

func TestTooManyPixelsAndBrokenFilesAreRefused(t *testing.T) {
	if _, err := New(100).Process(encodeJPEG(t, gradient(20, 20, 1), 80), "image/jpeg"); err == nil {
		t.Fatal("over the pixel limit must fail")
	}
	if _, err := New(1_000_000).Process([]byte("\xFF\xD8\xFF\xE1\x00"), "image/jpeg"); err == nil {
		t.Fatal("truncated JPEG must fail")
	}
}
