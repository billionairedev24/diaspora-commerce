package imageproc

import (
	"bytes"
	"encoding/binary"
	"errors"
	"hash/crc32"
)

var errMalformed = errors.New("malformed image")

// stripJPEG removes metadata segments from a JPEG without re-encoding: APP1 (EXIF, XMP), APP13 (Photoshop / IPTC),
// COM and every other APPn except APP0 (JFIF / JFXX), APP14 (Adobe colour transform) and APP2 ICC profiles. It
// returns the EXIF orientation found (1 when none) and whether anything was removed. Scan data after SOS is copied
// verbatim.
func stripJPEG(data []byte) (out []byte, orientation int, stripped bool, err error) {
	if len(data) < 4 || data[0] != 0xFF || data[1] != 0xD8 {
		return nil, 1, false, errMalformed
	}
	orientation = 1
	var b bytes.Buffer
	b.Grow(len(data))
	b.Write(data[:2])
	i := 2
	for i < len(data) {
		if data[i] != 0xFF {
			return nil, 1, false, errMalformed
		}
		// Fill bytes: any number of 0xFF before a marker.
		for i+1 < len(data) && data[i+1] == 0xFF {
			i++
		}
		if i+1 >= len(data) {
			return nil, 1, false, errMalformed
		}
		marker := data[i+1]
		switch {
		case marker == 0xD8 || marker == 0x01 || (marker >= 0xD0 && marker <= 0xD7):
			b.Write(data[i : i+2])
			i += 2
			continue
		case marker == 0xD9: // EOI
			b.Write(data[i:])
			return b.Bytes(), orientation, stripped, nil
		}
		if i+4 > len(data) {
			return nil, 1, false, errMalformed
		}
		segLen := int(binary.BigEndian.Uint16(data[i+2 : i+4]))
		end := i + 2 + segLen
		if segLen < 2 || end > len(data) {
			return nil, 1, false, errMalformed
		}
		payload := data[i+4 : end]
		if marker == 0xDA { // SOS: the rest is entropy-coded data (and possibly more segments we keep as-is)
			b.Write(data[i:])
			return b.Bytes(), orientation, stripped, nil
		}
		if dropJPEGSegment(marker, payload) {
			if marker == 0xE1 && bytes.HasPrefix(payload, []byte("Exif\x00\x00")) {
				if o := exifOrientation(payload[6:]); o >= 1 && o <= 8 {
					orientation = o
				}
			}
			stripped = true
			i = end
			continue
		}
		b.Write(data[i:end])
		i = end
	}
	return nil, 1, false, errMalformed
}

func dropJPEGSegment(marker byte, payload []byte) bool {
	switch {
	case marker == 0xFE: // COM
		return true
	case marker == 0xE0 || marker == 0xEE: // APP0 JFIF, APP14 Adobe
		return false
	case marker == 0xE2:
		return !bytes.HasPrefix(payload, []byte("ICC_PROFILE\x00"))
	case marker >= 0xE1 && marker <= 0xEF:
		return true
	default:
		return false
	}
}

// exifOrientation reads tag 0x0112 of IFD0 from a TIFF structure (the EXIF payload after "Exif\0\0").
func exifOrientation(tiff []byte) int {
	if len(tiff) < 8 {
		return 0
	}
	var bo binary.ByteOrder
	switch string(tiff[:2]) {
	case "II":
		bo = binary.LittleEndian
	case "MM":
		bo = binary.BigEndian
	default:
		return 0
	}
	off := int(bo.Uint32(tiff[4:8]))
	if off < 8 || off+2 > len(tiff) {
		return 0
	}
	n := int(bo.Uint16(tiff[off : off+2]))
	for k := range n {
		e := off + 2 + k*12
		if e+12 > len(tiff) {
			return 0
		}
		if bo.Uint16(tiff[e:e+2]) == 0x0112 {
			return int(bo.Uint16(tiff[e+8 : e+10]))
		}
	}
	return 0
}

var pngSignature = []byte("\x89PNG\r\n\x1a\n")

// stripPNG drops the metadata chunks eXIf, tEXt, zTXt, iTXt and tIME; every other chunk (with its CRC) is kept.
func stripPNG(data []byte) (out []byte, stripped bool, err error) {
	if !bytes.HasPrefix(data, pngSignature) {
		return nil, false, errMalformed
	}
	var b bytes.Buffer
	b.Grow(len(data))
	b.Write(pngSignature)
	i := len(pngSignature)
	for i < len(data) {
		if i+8 > len(data) {
			return nil, false, errMalformed
		}
		n := int(binary.BigEndian.Uint32(data[i : i+4]))
		typ := string(data[i+4 : i+8])
		end := i + 12 + n
		if n < 0 || end > len(data) {
			return nil, false, errMalformed
		}
		if crc32.ChecksumIEEE(data[i+4:i+8+n]) != binary.BigEndian.Uint32(data[i+8+n:end]) {
			return nil, false, errMalformed
		}
		switch typ {
		case "eXIf", "tEXt", "zTXt", "iTXt", "tIME":
			stripped = true
		default:
			b.Write(data[i:end])
		}
		i = end
		if typ == "IEND" {
			return b.Bytes(), stripped, nil
		}
	}
	return nil, false, errMalformed
}

// VP8X feature flags.
const (
	webpFlagEXIF = 0x08
	webpFlagXMP  = 0x04
)

// stripWebP drops the EXIF and "XMP " chunks of a WebP (RIFF) file, clears their VP8X flags and fixes the RIFF size.
func stripWebP(data []byte) (out []byte, stripped bool, err error) {
	if len(data) < 12 || string(data[:4]) != "RIFF" || string(data[8:12]) != "WEBP" {
		return nil, false, errMalformed
	}
	var body bytes.Buffer
	body.Grow(len(data))
	vp8x := -1
	i := 12
	for i < len(data) {
		if i+8 > len(data) {
			return nil, false, errMalformed
		}
		fourCC := string(data[i : i+4])
		n := int(binary.LittleEndian.Uint32(data[i+4 : i+8]))
		end := i + 8 + n + n%2
		if n < 0 || i+8+n > len(data) {
			return nil, false, errMalformed
		}
		end = min(end, len(data))
		switch fourCC {
		case "EXIF", "XMP ":
			stripped = true
		default:
			if fourCC == "VP8X" {
				vp8x = body.Len()
			}
			body.Write(data[i:end])
		}
		i = end
	}
	if !stripped {
		return data, false, nil
	}
	chunks := body.Bytes()
	if vp8x >= 0 && vp8x+8 < len(chunks) {
		chunks[vp8x+8] &^= webpFlagEXIF | webpFlagXMP
	}
	out = make([]byte, 0, 12+len(chunks))
	out = append(out, "RIFF"...)
	out = binary.LittleEndian.AppendUint32(out, uint32(4+len(chunks))) //nolint:gosec // bounded by the 8 MB upload limit
	out = append(out, "WEBP"...)
	out = append(out, chunks...)
	return out, true, nil
}
