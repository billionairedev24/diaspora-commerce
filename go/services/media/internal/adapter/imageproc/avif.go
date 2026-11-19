package imageproc

import (
	"encoding/binary"
	"fmt"
	"slices"

	"github.com/billionairedev24/diaspora-commerce/go/pkg/picturerules"
)

// AVIF has no decoder in the offline module set, so an AVIF is never decoded: its size is read from the header
// (picturerules.Dimensions, the "ispe" box), it gets no perceptual hash and no preview for the AI hook (ProcessedImage.Undecoded),
// and its metadata is removed in place: the Exif and XMP items are overwritten with zeros, so every offset in the file stays
// valid and no byte is moved. A decoder (for example github.com/gen2brain/avif) would lift the first two limits.

type isoBox struct {
	typ        string
	start, end int // the whole box
	body       int // first byte after the box header
}

// isoBoxes lists the boxes in data[from:to].
func isoBoxes(data []byte, from, to int) []isoBox {
	var out []isoBox
	for off := from; off+8 <= to; {
		size := int(binary.BigEndian.Uint32(data[off : off+4]))
		hdr := 8
		switch size {
		case 0:
			size = to - off
		case 1:
			if off+16 > to {
				return out
			}
			size, hdr = int(binary.BigEndian.Uint64(data[off+8:off+16])), 16
		}
		if size < hdr || off+size > to {
			return out
		}
		out = append(out, isoBox{typ: string(data[off+4 : off+8]), start: off, end: off + size, body: off + hdr})
		off += size
	}
	return out
}

type avifItem struct {
	id      uint32
	typ     string
	mime    string
	extents [][2]int // file offset, length
	idat    bool
}

// stripAVIF returns a copy of the file with the Exif and XMP item payloads zeroed. stripped is false when it had none.
func stripAVIF(data []byte) (out []byte, stripped bool, err error) {
	if picturerules.Sniff(data) != picturerules.AVIF {
		return nil, false, errMalformed
	}
	var meta *isoBox
	for _, b := range isoBoxes(data, 0, len(data)) {
		if b.typ == "meta" {
			bb := b
			meta = &bb
			break
		}
	}
	if meta == nil || meta.body+4 > meta.end {
		return data, false, nil
	}
	items := map[uint32]*avifItem{}
	var iloc *isoBox
	for _, b := range isoBoxes(data, meta.body+4, meta.end) {
		switch b.typ {
		case "iinf":
			if err := readIinf(data, b, items); err != nil {
				return nil, false, fmt.Errorf("avif iinf: %w", err)
			}
		case "iloc":
			bb := b
			iloc = &bb
		}
	}
	if iloc == nil {
		return data, false, nil
	}
	if err := readIloc(data, *iloc, items); err != nil {
		return nil, false, fmt.Errorf("avif iloc: %w", err)
	}
	out = slices.Clone(data)
	ids := make([]uint32, 0, len(items))
	for id := range items {
		ids = append(ids, id)
	}
	slices.Sort(ids)
	for _, id := range ids {
		it := items[id]
		if !(it.typ == "Exif" || (it.typ == "mime" && it.mime == "application/rdf+xml")) || it.idat {
			continue
		}
		for _, e := range it.extents {
			if e[0] < 0 || e[1] < 0 || e[0]+e[1] > len(out) {
				return nil, false, errMalformed
			}
			clear(out[e[0] : e[0]+e[1]])
			stripped = stripped || e[1] > 0
		}
	}
	if !stripped {
		return data, false, nil
	}
	return out, true, nil
}

func cstring(data []byte, off, end int) (string, int) {
	for i := off; i < end; i++ {
		if data[i] == 0 {
			return string(data[off:i]), i + 1
		}
	}
	return string(data[off:end]), end
}

func readIinf(data []byte, b isoBox, items map[uint32]*avifItem) error {
	p := b.body
	if p+4 > b.end {
		return errMalformed
	}
	version := data[p]
	p += 4
	if version == 0 {
		p += 2
	} else {
		p += 4
	}
	for _, e := range isoBoxes(data, p, b.end) {
		if e.typ != "infe" || e.body+4 > e.end {
			continue
		}
		v := data[e.body]
		q := e.body + 4
		if v < 2 {
			continue
		}
		var id uint32
		if v == 2 {
			if q+4 > e.end {
				return errMalformed
			}
			id = uint32(binary.BigEndian.Uint16(data[q : q+2]))
			q += 2
		} else {
			if q+6 > e.end {
				return errMalformed
			}
			id = binary.BigEndian.Uint32(data[q : q+4])
			q += 4
		}
		q += 2 // protection index
		if q+4 > e.end {
			return errMalformed
		}
		it := &avifItem{id: id, typ: string(data[q : q+4])}
		q += 4
		_, q = cstring(data, q, e.end) // item name
		if it.typ == "mime" {
			it.mime, _ = cstring(data, q, e.end)
		}
		items[id] = it
	}
	return nil
}

// readIloc reads the item locations (versions 0 to 2); an item that lives in the idat box is marked and left alone.
func readIloc(data []byte, b isoBox, items map[uint32]*avifItem) error {
	p := b.body
	if p+8 > b.end {
		return errMalformed
	}
	version := data[p]
	p += 4
	offSize, lenSize := int(data[p]>>4), int(data[p]&0x0f)
	baseSize, indexSize := int(data[p+1]>>4), 0
	if version == 1 || version == 2 {
		indexSize = int(data[p+1] & 0x0f)
	}
	p += 2
	var count int
	if version < 2 {
		count = int(binary.BigEndian.Uint16(data[p : p+2]))
		p += 2
	} else {
		if p+4 > b.end {
			return errMalformed
		}
		count = int(binary.BigEndian.Uint32(data[p : p+4]))
		p += 4
	}
	read := func(n int) (int, bool) {
		if n == 0 {
			return 0, true
		}
		if n > 8 || p+n > b.end {
			return 0, false
		}
		var v uint64
		for _, c := range data[p : p+n] {
			v = v<<8 | uint64(c)
		}
		p += n
		if v > uint64(len(data)) {
			return int(len(data)) + 1, true // out of range: rejected by the caller's bounds check
		}
		return int(v), true
	}
	for range count {
		var id int
		var ok bool
		if version < 2 {
			id, ok = read(2)
		} else {
			id, ok = read(4)
		}
		if !ok {
			return errMalformed
		}
		method := 0
		if version == 1 || version == 2 {
			var m int
			if m, ok = read(2); !ok {
				return errMalformed
			}
			method = m & 0x0f
		}
		if _, ok = read(2); !ok { // data reference index
			return errMalformed
		}
		base, ok1 := read(baseSize)
		nExt, ok2 := read(2)
		if !ok1 || !ok2 {
			return errMalformed
		}
		it := items[uint32(id)] //nolint:gosec // item ids are 16 or 32 bits
		for range nExt {
			if indexSize > 0 {
				if _, ok = read(indexSize); !ok {
					return errMalformed
				}
			}
			off, ok3 := read(offSize)
			length, ok4 := read(lenSize)
			if !ok3 || !ok4 {
				return errMalformed
			}
			if it != nil {
				it.idat = it.idat || method != 0
				it.extents = append(it.extents, [2]int{base + off, length})
			}
		}
	}
	return nil
}
