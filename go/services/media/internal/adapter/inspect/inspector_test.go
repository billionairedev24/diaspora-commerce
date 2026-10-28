package inspect_test

import (
	"archive/zip"
	"bytes"
	"image"
	"image/color"
	"image/jpeg"
	"image/png"
	"testing"

	"github.com/billionairedev24/diaspora-commerce/go/services/media/internal/adapter/inspect"
)

func picture(w, h int, bg color.Color) *image.RGBA {
	img := image.NewRGBA(image.Rect(0, 0, w, h))
	for y := range h {
		for x := range w {
			img.Set(x, y, bg)
		}
	}
	for y := h / 3; y < 2*h/3; y++ { // a dark product in the middle
		for x := w / 3; x < 2*w/3; x++ {
			img.Set(x, y, color.RGBA{R: 120, G: 40, B: 20, A: 255})
		}
	}
	return img
}

func TestInspectsRealTypeSizeAndBackground(t *testing.T) {
	var white, dark bytes.Buffer
	if err := jpeg.Encode(&white, picture(1200, 1100, color.White), nil); err != nil {
		t.Fatal(err)
	}
	if err := png.Encode(&dark, picture(800, 600, color.Black)); err != nil {
		t.Fatal(err)
	}
	ins := inspect.New(40_000_000)

	in, err := ins.Inspect(bytes.NewReader(white.Bytes()), int64(white.Len()), "image/png") // declared type is wrong
	if err != nil {
		t.Fatal(err)
	}
	if in.ContentType != "image/jpeg" || in.Width != 1200 || in.Height != 1100 || in.BorderBrightness < 240 {
		t.Fatalf("white jpeg: %+v", in)
	}
	in, err = ins.Inspect(bytes.NewReader(dark.Bytes()), int64(dark.Len()), "image/png")
	if err != nil {
		t.Fatal(err)
	}
	if in.ContentType != "image/png" || in.BorderBrightness > 10 {
		t.Fatalf("dark png: %+v", in)
	}
	in, err = ins.Inspect(bytes.NewReader([]byte("<html><script>alert(1)</script>")), 31, "image/jpeg")
	if err != nil {
		t.Fatal(err)
	}
	if in.ContentType == "image/jpeg" || in.Width != 0 {
		t.Fatalf("html disguised as jpeg must not pass as an image: %+v", in)
	}
}

func zipOf(t *testing.T, names ...string) []byte {
	t.Helper()
	var buf bytes.Buffer
	zw := zip.NewWriter(&buf)
	for _, n := range names {
		w, err := zw.Create(n)
		if err != nil {
			t.Fatal(err)
		}
		if _, err := w.Write(bytes.Repeat([]byte("<row/>"), 200)); err != nil {
			t.Fatal(err)
		}
	}
	if err := zw.Close(); err != nil {
		t.Fatal(err)
	}
	return buf.Bytes()
}

func TestInspectsSpreadsheetsAndCSV(t *testing.T) {
	ins := inspect.New(40_000_000)
	const xlsxType = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
	cases := []struct {
		name     string
		body     []byte
		declared string
		want     string
	}{
		{"xlsx", zipOf(t, "[Content_Types].xml", "_rels/.rels", "xl/workbook.xml", "xl/worksheets/sheet1.xml"), xlsxType, xlsxType},
		{"xlsx with the workbook part first", zipOf(t, "xl/workbook.xml", "docProps/app.xml", "[Content_Types].xml"), xlsxType, xlsxType},
		{"docx is not a workbook", zipOf(t, "[Content_Types].xml", "word/document.xml"), xlsxType, "application/zip"},
		{"plain zip", zipOf(t, "orders.csv"), xlsxType, "application/zip"},
		{"csv", []byte("\xef\xbb\xbfsku,qty,price\nGARI-2LB,12,8.99\n"), "text/csv", "text/csv"},
		{"text not declared as csv", []byte("sku,qty\n"), "application/pdf", "text/plain; charset=utf-8"},
		{"html declared as csv", []byte("<html><body>x</body></html>"), "text/csv", "text/html; charset=utf-8"},
		{"heic photo", append([]byte("\x00\x00\x00\x18ftypheic\x00\x00\x00\x00"), make([]byte, 64)...), "image/heic", "image/heic"},
		{"mp4 is not heic", append([]byte("\x00\x00\x00\x18ftypmp42\x00\x00\x00\x00"), make([]byte, 64)...), "image/heic", "video/mp4"},
		{"binary declared as csv", []byte{0x00, 0x01, 0x02, 0xff, 0xfe, 0x00}, "text/csv", "application/octet-stream"},
	}
	for _, c := range cases {
		in, err := ins.Inspect(bytes.NewReader(c.body), int64(len(c.body)), c.declared)
		if err != nil {
			t.Fatalf("%s: %v", c.name, err)
		}
		if in.ContentType != c.want || in.Bytes != int64(len(c.body)) {
			t.Fatalf("%s: got %+v, want %s", c.name, in, c.want)
		}
	}
}
