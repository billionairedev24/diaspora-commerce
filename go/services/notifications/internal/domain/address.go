package domain

import "strings"

// NormalizePhone strips formatting from a phone number and ensures a leading "+" (E.164).
func NormalizePhone(p string) string {
	var b strings.Builder
	for _, r := range strings.TrimSpace(p) {
		if r >= '0' && r <= '9' {
			b.WriteRune(r)
		}
	}
	if b.Len() == 0 {
		return ""
	}
	return "+" + b.String()
}

// NormalizeEmail lower-cases and trims an email address.
func NormalizeEmail(e string) string { return strings.ToLower(strings.TrimSpace(e)) }

// NormalizeAddress normalizes an address for its channel.
func NormalizeAddress(ch Channel, addr string) string {
	switch ch {
	case ChannelEmail:
		return NormalizeEmail(addr)
	case ChannelSMS, ChannelChat:
		return NormalizePhone(addr)
	case ChannelPush:
		return strings.TrimSpace(addr)
	default:
		return addr
	}
}
