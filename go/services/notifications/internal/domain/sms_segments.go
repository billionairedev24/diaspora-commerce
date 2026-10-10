package domain

import "unicode/utf16"

// SMS encodings.
const (
	EncodingGSM7 = "GSM-7"
	EncodingUCS2 = "UCS-2"
)

// gsm7Basic is the GSM 03.38 default alphabet (one septet each; ESC excluded).
const gsm7Basic = "@£$¥èéùìòÇ\nØø\rÅåΔ_ΦΓΛΩΠΨΣΘΞÆæßÉ !\"#¤%&'()*+,-./0123456789:;<=>?" +
	"¡ABCDEFGHIJKLMNOPQRSTUVWXYZÄÖÑÜ§¿abcdefghijklmnopqrstuvwxyzäöñüà"

// gsm7Extension is the GSM 03.38 extension table (ESC + char: two septets each).
const gsm7Extension = "^{}\\[~]|€\f"

var gsm7Units = func() map[rune]int {
	m := map[rune]int{}
	for _, r := range gsm7Basic {
		m[r] = 1
	}
	for _, r := range gsm7Extension {
		m[r] = 2
	}
	return m
}()

// SmsSegments describes how an SMS body is encoded and split.
type SmsSegments struct {
	Encoding string `json:"encoding"`
	// Units are septets for GSM-7 (extension characters count two) or UTF-16 code units for UCS-2.
	Units    int `json:"units"`
	Segments int `json:"segments"`
	// PerSegment is the capacity of one segment of a multi-part message (153 GSM-7, 67 UCS-2), or of the single
	// segment (160 / 70) when the message fits in one.
	PerSegment int `json:"perSegment"`
}

// CountSmsSegments returns the encoding and segment count of body: GSM-7 when every character is in the GSM 03.38
// alphabet (160 septets in one segment, 153 per part when concatenated), else UCS-2 (70 / 67 code units).
func CountSmsSegments(body string) SmsSegments {
	septets, gsm := 0, true
	for _, r := range body {
		n, ok := gsm7Units[r]
		if !ok {
			gsm = false
			break
		}
		septets += n
	}
	if gsm {
		return split(EncodingGSM7, septets, 160, 153)
	}
	return split(EncodingUCS2, len(utf16.Encode([]rune(body))), 70, 67)
}

func split(encoding string, units, single, multi int) SmsSegments {
	switch {
	case units == 0:
		return SmsSegments{Encoding: encoding, PerSegment: single}
	case units <= single:
		return SmsSegments{Encoding: encoding, Units: units, Segments: 1, PerSegment: single}
	default:
		return SmsSegments{Encoding: encoding, Units: units, Segments: (units + multi - 1) / multi, PerSegment: multi}
	}
}
