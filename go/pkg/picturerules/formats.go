package picturerules

import (
	"bytes"
	"encoding/binary"
	"errors"
	"strings"
)

// Image content types accepted under every profile.
const (
	JPEG = "image/jpeg"
	PNG  = "image/png"
	WebP = "image/webp"
	AVIF = "image/avif"
)

// Formats lists the accepted image content types. Not a knob: SVG, HTML, scripts, executables and every other type are
// refused under every profile.
func Formats() []string { return []string{JPEG, PNG, WebP, AVIF} }

// Extension returns the stored-file extension of an accepted content type to the file extension used for stored files.
func Extension(contentType string) string {
	switch contentType {
	case JPEG:
		return ".jpg"
	case PNG:
		return ".png"
	case WebP:
		return ".webp"
	case AVIF:
		return ".avif"
	default:
		return ""
	}
}

// Sniff returns the accepted image type of the bytes, decided by magic bytes only (never by file name or declared type),
// or "" for anything else. WebP is RIFF....WEBP, AVIF is an ISO base media file whose ftyp brand is avif or avis.
func Sniff(data []byte) string {
	switch {
	case len(data) >= 3 && data[0] == 0xFF && data[1] == 0xD8 && data[2] == 0xFF:
		return JPEG
	case len(data) >= 8 && string(data[:8]) == "\x89PNG\r\n\x1a\n":
		return PNG
	case len(data) >= 12 && string(data[:4]) == "RIFF" && string(data[8:12]) == "WEBP":
		return WebP
	case isAVIF(data):
		return AVIF
	default:
		return ""
	}
}

// isAVIF reads the leading ftyp box: major brand and compatible brands. HEIC brands are not AVIF.
func isAVIF(data []byte) bool {
	if len(data) < 16 || string(data[4:8]) != "ftyp" {
		return false
	}
	size := int(binary.BigEndian.Uint32(data[:4]))
	if size < 16 || size > len(data) {
		size = min(len(data), 64)
	}
	major := string(data[8:12])
	brands := []string{major}
	for off := 16; off+4 <= size; off += 4 {
		brands = append(brands, string(data[off:off+4]))
	}
	avif := false
	for _, b := range brands {
		switch b {
		case "avif", "avis":
			avif = true
		case "heic", "heix", "hevc", "hevx", "heim", "heis":
			return false
		}
	}
	return avif
}

var activeMarkers = [][]byte{[]byte("<script"), []byte("<html"), []byte("<iframe"), []byte("<svg"), []byte("<!doctype"),
	[]byte("javascript:"), []byte("<?php"), []byte("onerror="), []byte("<object"), []byte("<embed")}

// HasActiveContent reports whether the bytes carry markup or script anywhere (a polyglot: an image whose header is valid
// and whose body is HTML, SVG or script). Such a file is refused whatever its type, under every profile. ELF, PE and
// shell-script headers are caught too, because the sniffer already refuses them as non-images.
func HasActiveContent(data []byte) bool {
	low := bytes.ToLower(data)
	for _, m := range activeMarkers {
		if bytes.Contains(low, m) {
			return true
		}
	}
	return bytes.HasPrefix(data, []byte("MZ")) || bytes.HasPrefix(data, []byte("\x7fELF")) || bytes.HasPrefix(data, []byte("#!"))
}

// ErrNotImage is returned for bytes that are not an accepted image.
var ErrNotImage = errors.New("not an accepted image")

// Dimensions reads the pixel size of an accepted image from its headers; nothing is decoded, so it is cheap and works
// for AVIF where no decoder is available. The type must be the one Sniff returned.
func Dimensions(data []byte, contentType string) (width, height int, err error) {
	switch contentType {
	case PNG:
		if len(data) >= 24 && string(data[12:16]) == "IHDR" {
			return int(binary.BigEndian.Uint32(data[16:20])), int(binary.BigEndian.Uint32(data[20:24])), nil
		}
	case JPEG:
		if w, h, ok := jpegDims(data); ok {
			return w, h, nil
		}
	case WebP:
		if w, h, ok := webpDims(data); ok {
			return w, h, nil
		}
	case AVIF:
		if w, h, ok := avifDims(data); ok {
			return w, h, nil
		}
	}
	return 0, 0, ErrNotImage
}

func jpegDims(d []byte) (w, h int, ok bool) {
	for i := 2; i+9 < len(d); {
		if d[i] != 0xFF {
			i++
			continue
		}
		m := d[i+1]
		if m == 0xFF {
			i++
			continue
		}
		if m == 0xD8 || m == 0x01 || (m >= 0xD0 && m <= 0xD7) {
			i += 2
			continue
		}
		seg := int(binary.BigEndian.Uint16(d[i+2 : i+4]))
		if (m >= 0xC0 && m <= 0xCF) && m != 0xC4 && m != 0xC8 && m != 0xCC {
			return int(binary.BigEndian.Uint16(d[i+7 : i+9])), int(binary.BigEndian.Uint16(d[i+5 : i+7])), true
		}
		i += 2 + seg
	}
	return 0, 0, false
}

func webpDims(d []byte) (w, h int, ok bool) {
	if len(d) < 25 {
		return 0, 0, false
	}
	switch string(d[12:16]) {
	case "VP8 ":
		if len(d) >= 30 && d[23] == 0x9d && d[24] == 0x01 && d[25] == 0x2a {
			return int(binary.LittleEndian.Uint16(d[26:28]) & 0x3fff), int(binary.LittleEndian.Uint16(d[28:30]) & 0x3fff), true
		}
	case "VP8L":
		if d[20] == 0x2f {
			b := binary.LittleEndian.Uint32(d[21:25])
			return int(b&0x3fff) + 1, int((b>>14)&0x3fff) + 1, true
		}
	case "VP8X":
		if len(d) < 30 {
			return 0, 0, false
		}
		w = int(uint32(d[24])|uint32(d[25])<<8|uint32(d[26])<<16) + 1
		h = int(uint32(d[27])|uint32(d[28])<<8|uint32(d[29])<<16) + 1
		return w, h, true
	}
	return 0, 0, false
}

// avifDims walks meta > iprp > ipco and returns the largest ispe (image spatial extents): the primary image is the largest
// item in practice, thumbnails and auxiliary images are smaller. Rotation does not matter: the rules use the long and
// short side.
func avifDims(d []byte) (w, h int, ok bool) {
	best := 0
	var walk func(b []byte, depth int)
	walk = func(b []byte, depth int) {
		for off := 0; off+8 <= len(b); {
			size := int(binary.BigEndian.Uint32(b[off : off+4]))
			typ := string(b[off+4 : off+8])
			hdr := 8
			if size == 1 && off+16 <= len(b) {
				size, hdr = int(binary.BigEndian.Uint64(b[off+8:off+16])), 16
			} else if size == 0 {
				size = len(b) - off
			}
			if size < hdr || off+size > len(b) {
				return
			}
			body := b[off+hdr : off+size]
			switch typ {
			case "meta":
				if len(body) > 4 && depth < 4 {
					walk(body[4:], depth+1) // full box: version and flags
				}
			case "iprp", "ipco":
				if depth < 4 {
					walk(body, depth+1)
				}
			case "ispe":
				if len(body) >= 12 {
					bw, bh := int(binary.BigEndian.Uint32(body[4:8])), int(binary.BigEndian.Uint32(body[8:12]))
					if bw > 0 && bh > 0 && bw*bh > best {
						best, w, h = bw*bh, bw, bh
					}
				}
			}
			off += size
		}
	}
	walk(d, 0)
	return w, h, best > 0
}

// ExtensionOK reports whether a file name's extension is an accepted image extension. A name without an extension is
// accepted (CDN addresses often have none): the bytes decide.
func ExtensionOK(name string) bool {
	low := strings.ToLower(name)
	if i := strings.IndexAny(low, "?#"); i >= 0 {
		low = low[:i]
	}
	dot := strings.LastIndex(low, ".")
	if dot < 0 || dot < strings.LastIndex(low, "/") {
		return true
	}
	switch low[dot:] {
	case ".jpg", ".jpeg", ".png", ".webp", ".avif":
		return true
	}
	return false
}
