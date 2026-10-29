package domain

import (
	"fmt"
	"regexp"
	"strconv"
	"strings"
)

// LibrarySlot is where a library image goes on a listing: the main image, an additional image (PT01–PT08, in that
// order) or the variant swatch. The names follow the convention sellers know from Amazon's image upload.
type LibrarySlot string

// Library slots.
const (
	SlotMain   LibrarySlot = "MAIN"
	SlotSwatch LibrarySlot = "SWCH"
)

// MaxOtherSlots is the number of additional image slots (PT01 … PT08).
const MaxOtherSlots = 8

// OtherSlot returns the additional image slot n (1–8): PT01 … PT08.
func OtherSlot(n int) LibrarySlot { return LibrarySlot(fmt.Sprintf("PT%02d", n)) }

// LibrarySlots lists every slot in listing order (MAIN, PT01 … PT08, SWCH).
func LibrarySlots() []LibrarySlot {
	out := []LibrarySlot{SlotMain}
	for n := 1; n <= MaxOtherSlots; n++ {
		out = append(out, OtherSlot(n))
	}
	return append(out, SlotSwatch)
}

// LibraryName is what a library file name says: the SKU (upper case) and the slot.
type LibraryName struct {
	SKU  string
	Slot LibrarySlot
}

// libraryName splits "<sku><separators><slot>": the SKU is the shortest prefix, so "AWD-M.MAIN" is SKU AWD-M and
// "AWD--PT1" is SKU AWD. Separators are dot, underscore, dash and space; the slot is MAIN, PT1–PT8 (or PT01–PT08),
// SWCH or SWATCH, in any case.
var libraryName = regexp.MustCompile(`(?i)^(.+?)[._\- ]+(MAIN|PT0?[1-8]|SWCH|SWATCH)$`)

// librarySKU is what a matched SKU may contain (the catalog allows letters, digits, dot, dash and underscore).
var librarySKU = regexp.MustCompile(`^[A-Z0-9._-]{1,64}$`)

// imageExtension is stripped before parsing (.jpg, .JPEG, .png, .webp and other image extensions).
var imageExtension = regexp.MustCompile(`(?i)\.(jpe?g|png|webp|gif|avif|heic|heif|tiff?|bmp)$`)

// ParseLibraryName reads the SKU and slot from an image file name: SKU.MAIN.jpg is the main image of SKU,
// SKU.PT01.jpg … SKU.PT08.jpg the additional images in order and SKU.SWCH.jpg the variant swatch. Matching is
// case-insensitive (the SKU comes back upper case) and tolerates "_", "-" or a space instead of the dot
// (sku_main.png, SKU-PT1.webp). Folders in the name are ignored. ok is false when the name has no slot: such images
// can still be used by file name in a bulk upload sheet.
func ParseLibraryName(fileName string) (LibraryName, bool) {
	base := fileName
	if i := strings.LastIndexAny(base, `/\`); i >= 0 {
		base = base[i+1:]
	}
	base = strings.TrimSpace(imageExtension.ReplaceAllString(strings.TrimSpace(base), ""))
	m := libraryName.FindStringSubmatch(base)
	if m == nil {
		return LibraryName{}, false
	}
	sku := strings.ToUpper(strings.TrimSpace(m[1]))
	if !librarySKU.MatchString(sku) {
		return LibraryName{}, false
	}
	switch slot := strings.ToUpper(m[2]); slot {
	case "MAIN":
		return LibraryName{SKU: sku, Slot: SlotMain}, true
	case "SWCH", "SWATCH":
		return LibraryName{SKU: sku, Slot: SlotSwatch}, true
	default:
		n, err := strconv.Atoi(strings.TrimPrefix(slot, "PT"))
		if err != nil {
			return LibraryName{}, false
		}
		return LibraryName{SKU: sku, Slot: OtherSlot(n)}, true
	}
}

// LibraryFileName is a file name as stored for the library: the base name without folders, at most 200 characters.
func LibraryFileName(fileName string) string {
	base := fileName
	if i := strings.LastIndexAny(base, `/\`); i >= 0 {
		base = base[i+1:]
	}
	base = strings.TrimSpace(base)
	if r := []rune(base); len(r) > 200 {
		base = string(r[:200])
	}
	return base
}

// LibraryQuery filters the image library list.
type LibraryQuery struct {
	// Q matches the file name or the SKU (case-insensitive, contains).
	Q string
	// Status is READY (published), FLAGGED (held by moderation) or REJECTED; empty = all.
	Status string
	// Matched filters names with a SKU and slot (true) or without (false); nil = all.
	Matched *bool
	Page    int
	Size    int
}

// LibraryStatus is the status a library image shows: READY when published (quality flags such as a non-white
// background do not stop that), FLAGGED while moderation holds it, REJECTED otherwise.
func LibraryStatus(a Asset) Status {
	switch {
	case a.Status == StatusRejected:
		return StatusRejected
	case a.URL != "" && a.Moderation.Published():
		return StatusReady
	case a.Status == StatusPending:
		return StatusPending
	default:
		return StatusFlagged
	}
}

// LibraryImage is one image of an organisation's library (contract LibraryImage).
type LibraryImage struct {
	AssetID     string       `json:"assetId"`
	FileName    string       `json:"fileName"`
	SKU         *string      `json:"sku"`
	Slot        *LibrarySlot `json:"slot"`
	Status      Status       `json:"status"`
	URL         *string      `json:"url"`
	Width       int          `json:"width"`
	Height      int          `json:"height"`
	Bytes       int64        `json:"bytes"`
	ContentType string       `json:"contentType"`
	Flags       []string     `json:"flags"`
	CreatedAt   string       `json:"createdAt"`
}

// ToLibraryImage shapes an asset for the library API; held and rejected images have no URL.
func ToLibraryImage(a Asset) LibraryImage {
	out := LibraryImage{AssetID: a.ID, FileName: a.FileName, Status: LibraryStatus(a), Width: a.Width,
		Height: a.Height, Bytes: a.Bytes, ContentType: a.ContentType, Flags: a.Flags,
		CreatedAt: a.CreatedAt.UTC().Format("2006-01-02T15:04:05.000Z07:00")}
	if out.Flags == nil {
		out.Flags = []string{}
	}
	if a.SKU != "" {
		sku, slot := a.SKU, a.Slot
		out.SKU, out.Slot = &sku, &slot
	}
	if out.Status == StatusReady {
		u := a.URL
		out.URL = &u
	}
	return out
}
