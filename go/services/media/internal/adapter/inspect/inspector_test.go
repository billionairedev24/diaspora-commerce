package inspect_test

import (
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
