package app

import (
	"encoding/json"
	"strconv"
	"strings"
	"time"
)

// Links are the public base URLs templates link to (config links.*).
type Links struct {
	Storefront   string
	SellerCenter string
	ProviderHub  string
}

// field helpers over decoded JSON payloads (numbers are json.Number: the ingest decodes with UseNumber).

func str(d map[string]any, k string) string {
	switch v := d[k].(type) {
	case string:
		return v
	case json.Number:
		return v.String()
	default:
		return ""
	}
}

func boolean(d map[string]any, k string) bool {
	b, _ := d[k].(bool)
	return b
}

func integer(d map[string]any, k string) (int64, bool) {
	switch v := d[k].(type) {
	case json.Number:
		if n, err := v.Int64(); err == nil {
			return n, true
		}
		if f, err := v.Float64(); err == nil {
			return int64(f), true
		}
	case float64:
		return int64(v), true
	case int64:
		return v, true
	case int:
		return int64(v), true
	}
	return 0, false
}

func stringList(d map[string]any, k string) []string {
	raw, _ := d[k].([]any)
	out := make([]string, 0, len(raw))
	for _, x := range raw {
		if s, ok := x.(string); ok && s != "" {
			out = append(out, s)
		}
	}
	return out
}

func objects(d map[string]any, k string) []map[string]any {
	raw, _ := d[k].([]any)
	out := make([]map[string]any, 0, len(raw))
	for _, x := range raw {
		if m, ok := x.(map[string]any); ok {
			out = append(out, m)
		}
	}
	return out
}

var currencySymbols = map[string]string{"USD": "$", "CAD": "CA$", "GBP": "£", "EUR": "€", "NGN": "₦", "GHS": "GH₵", "KES": "KSh"}

// money formats minor units: money(4200, "USD") = "$42.00", money(123456, "GBP") = "£1,234.56".
func money(minor int64, currency string) string {
	currency = strings.ToUpper(currency)
	neg := minor < 0
	if neg {
		minor = -minor
	}
	whole := strconv.FormatInt(minor/100, 10)
	var b strings.Builder
	for i, r := range whole {
		if i > 0 && (len(whole)-i)%3 == 0 {
			b.WriteByte(',')
		}
		b.WriteRune(r)
	}
	cents := minor % 100
	amount := b.String() + "." + strconv.FormatInt(cents/10, 10) + strconv.FormatInt(cents%10, 10)
	sym, ok := currencySymbols[currency]
	if !ok {
		sym = currency + " "
	}
	if neg {
		return "-" + sym + amount
	}
	return sym + amount
}

// moneyField formats d[minorKey] in d["currency"] (USD when absent); "" when the amount is missing.
func moneyField(d map[string]any, minorKey string) string {
	n, ok := integer(d, minorKey)
	if !ok {
		return ""
	}
	cur := str(d, "currency")
	if cur == "" {
		cur = "USD"
	}
	return money(n, cur)
}

// friendlyDate renders an RFC 3339 timestamp or a YYYY-MM-DD date as "Wed, Oct 14"; "" when unparseable.
func friendlyDate(s string) string {
	if s == "" {
		return ""
	}
	if t, err := time.Parse(time.RFC3339, s); err == nil {
		return t.UTC().Format("Mon, Jan 2")
	}
	if t, err := time.Parse(time.DateOnly, s); err == nil {
		return t.Format("Mon, Jan 2")
	}
	return ""
}

// humanList joins items for copy: "a", "a and b", "a, b and c". Machine keys (snake_case, dotted) are made readable.
func humanList(items []string) string {
	clean := make([]string, 0, len(items))
	for _, it := range items {
		it = strings.NewReplacer("_", " ", ".", " ").Replace(it)
		if it = strings.TrimSpace(it); it != "" {
			clean = append(clean, it)
		}
	}
	switch len(clean) {
	case 0:
		return ""
	case 1:
		return clean[0]
	default:
		return strings.Join(clean[:len(clean)-1], ", ") + " and " + clean[len(clean)-1]
	}
}

// firstWord returns the first word of a full name ("Ada Obi" → "Ada").
func firstWord(name string) string {
	f := strings.Fields(name)
	if len(f) == 0 {
		return ""
	}
	return f[0]
}

func join(base, path string) string { return strings.TrimRight(base, "/") + path }
