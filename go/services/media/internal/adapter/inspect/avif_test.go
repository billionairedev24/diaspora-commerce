package inspect_test

import (
	"bytes"
	"encoding/hex"
	"encoding/json"
	"os"
	"testing"

	"github.com/billionairedev24/diaspora-commerce/go/services/media/internal/adapter/inspect"
)

func vectors(t *testing.T) (images, rejected []struct {
	Name   string `json:"name"`
	Hex    string `json:"hex"`
	Type   string `json:"type"`
	Width  int    `json:"width"`
	Height int    `json:"height"`
}) {
	t.Helper()
	raw, err := os.ReadFile("../../../../../../contracts/picture-rules/vectors.json")
	if err != nil {
		t.Fatal(err)
	}
	var v struct {
		Images []struct {
			Name   string `json:"name"`
			Hex    string `json:"hex"`
			Type   string `json:"type"`
			Width  int    `json:"width"`
			Height int    `json:"height"`
		} `json:"images"`
		Rejected []struct {
			Name   string `json:"name"`
			Hex    string `json:"hex"`
			Type   string `json:"type"`
			Width  int    `json:"width"`
			Height int    `json:"height"`
		} `json:"rejected"`
	}
	if err := json.Unmarshal(raw, &v); err != nil {
		t.Fatal(err)
	}
	return v.Images, v.Rejected
}

func TestAvifIsSniffedByItsBrandAndMeasuredFromTheHeader(t *testing.T) {
	images, _ := vectors(t)
	n := 0
	for _, im := range images {
		if im.Type != "image/avif" {
			continue
		}
		n++
		data, _ := hex.DecodeString(im.Hex)
		// The declared type is a lie on purpose: the bytes decide.
		got, err := inspect.New(40_000_000).Inspect(bytes.NewReader(data), int64(len(data)), "image/png")
		if err != nil || got.ContentType != "image/avif" || got.Width != im.Width || got.Height != im.Height {
			t.Errorf("%s: %+v %v", im.Name, got, err)
		}
	}
	if n < 3 {
		t.Fatalf("only %d avif samples", n)
	}
}

func TestNothingElseIsTakenForAnImage(t *testing.T) {
	_, rejected := vectors(t)
	for _, r := range rejected {
		data, _ := hex.DecodeString(r.Hex)
		got, _ := inspect.New(40_000_000).Inspect(bytes.NewReader(data), int64(len(data)), "image/jpeg")
		switch got.ContentType {
		case "image/jpeg", "image/png", "image/webp", "image/avif":
			t.Errorf("%s was taken for %s", r.Name, got.ContentType)
		}
	}
}
