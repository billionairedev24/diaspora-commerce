package domain_test

import (
	"testing"

	"github.com/billionairedev24/diaspora-commerce/go/services/media/internal/domain"
)

func TestParseLibraryNameReadsTheSKUAndSlot(t *testing.T) {
	cases := []struct {
		name string
		sku  string
		slot domain.LibrarySlot
	}{
		{"AWD-M.MAIN.jpg", "AWD-M", domain.SlotMain},
		{"awd-m.main.JPG", "AWD-M", domain.SlotMain},
		{"AWD-M_MAIN.png", "AWD-M", domain.SlotMain},
		{"AWD-M-MAIN.webp", "AWD-M", domain.SlotMain},
		{"AWD-M MAIN.jpeg", "AWD-M", domain.SlotMain},
		{"AWD-M.PT01.jpg", "AWD-M", domain.OtherSlot(1)},
		{"AWD-M.pt1.jpg", "AWD-M", domain.OtherSlot(1)},
		{"AWD-M-PT08.jpg", "AWD-M", domain.OtherSlot(8)},
		{"AWD-M_pt05.jpg", "AWD-M", domain.OtherSlot(5)},
		{"AWD-M.SWCH.jpg", "AWD-M", domain.SlotSwatch},
		{"AWD-M.swatch.png", "AWD-M", domain.SlotSwatch},
		{"photos/summer/SUYA.1.MAIN.jpg", "SUYA.1", domain.SlotMain},
		{`C:\pics\SUYA_8OZ.PT02.jpg`, "SUYA_8OZ", domain.OtherSlot(2)},
		{"AWD--MAIN.jpg", "AWD", domain.SlotMain},
		{"  SKU1.MAIN.jpg  ", "SKU1", domain.SlotMain},
		{"SKU1.MAIN", "SKU1", domain.SlotMain},
	}
	for _, c := range cases {
		got, ok := domain.ParseLibraryName(c.name)
		if !ok || got.SKU != c.sku || got.Slot != c.slot {
			t.Errorf("ParseLibraryName(%q) = %+v, %v; want %s %s", c.name, got, ok, c.sku, c.slot)
		}
	}
}

func TestParseLibraryNameRefusesNamesWithoutASlot(t *testing.T) {
	for _, name := range []string{
		"front.jpg",        // no slot
		"AWD-M.jpg",        // a SKU alone is not matched: add .MAIN
		"AWD-M.PT09.jpg",   // only PT01–PT08
		"AWD-M.PT00.jpg",   // idem
		"MAIN.jpg",         // no SKU
		".MAIN.jpg",        // idem
		"AWD M.MAIN.jpg",   // a space inside the SKU
		"AWD/M.MAIN.jpg",   // only the base name counts: "M.MAIN" -> SKU M is fine, checked below
		"AWD-M.MAINLY.jpg", // not a slot
		"",
	} {
		got, ok := domain.ParseLibraryName(name)
		if name == "AWD/M.MAIN.jpg" {
			if !ok || got.SKU != "M" {
				t.Errorf("folders must be ignored: %q = %+v %v", name, got, ok)
			}
			continue
		}
		if ok {
			t.Errorf("ParseLibraryName(%q) = %+v; want no match", name, got)
		}
	}
}

func TestLibrarySlotsAreInListingOrder(t *testing.T) {
	got := domain.LibrarySlots()
	if len(got) != 10 || got[0] != domain.SlotMain || got[1] != "PT01" || got[8] != "PT08" || got[9] != domain.SlotSwatch {
		t.Fatalf("slots = %v", got)
	}
}

func TestLibraryStatusSeparatesHeldFromQualityFlags(t *testing.T) {
	published := domain.Asset{Status: domain.StatusFlagged, URL: "https://cdn/x.jpg",
		Moderation: &domain.Moderation{Verdict: domain.VerdictPassed}, Flags: []string{"background is not white"}}
	held := domain.Asset{Status: domain.StatusFlagged, Moderation: &domain.Moderation{Verdict: domain.VerdictHeld}}
	rejected := domain.Asset{Status: domain.StatusRejected}
	if s := domain.LibraryStatus(published); s != domain.StatusReady {
		t.Errorf("published image with a quality flag = %s", s)
	}
	if s := domain.LibraryStatus(held); s != domain.StatusFlagged {
		t.Errorf("held image = %s", s)
	}
	if s := domain.LibraryStatus(rejected); s != domain.StatusRejected {
		t.Errorf("rejected image = %s", s)
	}
	img := domain.ToLibraryImage(held)
	if img.URL != nil || img.SKU != nil || img.Flags == nil {
		t.Errorf("held image must have no URL, no SKU and empty flags: %+v", img)
	}
	withSKU := published
	withSKU.SKU, withSKU.Slot = "AWD-M", domain.SlotMain
	if img := domain.ToLibraryImage(withSKU); img.URL == nil || *img.SKU != "AWD-M" || *img.Slot != domain.SlotMain {
		t.Errorf("published library image = %+v", img)
	}
}

func TestLibraryFileNameDropsFoldersAndCaps(t *testing.T) {
	if got := domain.LibraryFileName("a/b/c/SKU.MAIN.jpg"); got != "SKU.MAIN.jpg" {
		t.Errorf("got %q", got)
	}
	long := make([]rune, 260)
	for i := range long {
		long[i] = 'x'
	}
	if got := domain.LibraryFileName(string(long)); len([]rune(got)) != 200 {
		t.Errorf("len = %d", len([]rune(got)))
	}
}
