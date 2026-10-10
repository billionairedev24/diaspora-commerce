package app_test

import (
	"context"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"testing"

	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/app"
	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/domain"
)

var sampleLinks = app.Links{Storefront: "https://afrotransact.com", SellerCenter: "https://seller.afrotransact.com",
	ProviderHub: "https://provider.afrotransact.com"}

// seededTemplates parses the INSERT INTO template … VALUES tuples of every migration and returns the latest row per
// key, locale and channel (each later seed row is the next version of its key). The Postgres integration test checks
// the same against the real migrations; this one runs without Docker.
func seededTemplates(t *testing.T) []domain.Template {
	t.Helper()
	files, err := filepath.Glob(filepath.Join("..", "adapter", "postgres", "migrations", "*.sql"))
	if err != nil || len(files) == 0 {
		t.Fatalf("migrations: %v", err)
	}
	sort.Strings(files)
	latest := map[string]domain.Template{}
	for _, f := range files {
		raw, err := os.ReadFile(f)
		if err != nil {
			t.Fatal(err)
		}
		for _, row := range sqlTuples(string(raw)) {
			if len(row) != 8 {
				t.Fatalf("%s: unexpected template row %q", f, row)
			}
			tpl := domain.Template{Key: row[0], Locale: row[1], Channel: domain.Channel(row[2]), Category: domain.Category(row[4]),
				Subject: row[5], BodyHTML: row[6], BodyText: row[7]}
			id := tpl.Key + "|" + tpl.Locale + "|" + string(tpl.Channel)
			tpl.Version = latest[id].Version + 1
			latest[id] = tpl
		}
	}
	out := make([]domain.Template, 0, len(latest))
	for _, tpl := range latest {
		out = append(out, tpl)
	}
	sort.Slice(out, func(i, j int) bool {
		a, b := out[i], out[j]
		return a.Key+"|"+a.Locale+"|"+string(a.Channel) < b.Key+"|"+b.Locale+"|"+string(b.Channel)
	})
	return out
}

// sqlTuples returns the top-level value tuples of every INSERT INTO template statement: string literals unquoted
// (” → '), other values (numbers, sub-selects) as written.
func sqlTuples(sql string) [][]string {
	var out [][]string
	for {
		i := strings.Index(sql, "INSERT INTO template ")
		if i < 0 {
			return out
		}
		sql = sql[i:]
		v := strings.Index(sql, "VALUES")
		if v < 0 {
			return out
		}
		sql = sql[v+len("VALUES"):]
		var row []string
		var field strings.Builder
		depth, quoted := 0, false
		end := len(sql)
	scan:
		for k := 0; k < len(sql); k++ {
			c := sql[k]
			switch {
			case quoted:
				switch {
				case c == '\'' && k+1 < len(sql) && sql[k+1] == '\'':
					field.WriteByte('\'')
					k++
				case c == '\'':
					quoted = false
					if depth > 1 {
						field.WriteByte(c)
					}
				default:
					field.WriteByte(c)
				}
			case c == '-' && k+1 < len(sql) && sql[k+1] == '-':
				for k < len(sql) && sql[k] != '\n' {
					k++
				}
			case c == '\'':
				quoted = true
				if depth > 1 {
					field.WriteByte(c)
				}
			case c == '(':
				depth++
				if depth > 1 {
					field.WriteByte(c)
				}
			case c == ')':
				depth--
				if depth == 0 {
					row = append(row, strings.TrimSpace(field.String()))
					field.Reset()
					out = append(out, row)
					row = nil
				} else {
					field.WriteByte(c)
				}
			case c == ',' && depth == 1:
				row = append(row, strings.TrimSpace(field.String()))
				field.Reset()
			case c == ';' && depth == 0:
				end = k
				break scan
			case depth >= 1:
				field.WriteByte(c)
			}
		}
		sql = sql[end:]
	}
}

func TestEverySeededTemplateRendersWithSampleData(t *testing.T) {
	samples, err := app.NewTemplateSamples(context.Background(), sampleLinks)
	if err != nil {
		t.Fatal(err)
	}
	seeded := seededTemplates(t)
	if len(seeded) < 94 {
		t.Fatalf("parsed %d seeded templates, want at least 94", len(seeded))
	}
	renderer := app.NewRenderer()
	keys := map[string]bool{}
	for _, tpl := range seeded {
		keys[tpl.Key] = true
		s, ok := samples.Sample(tpl.Key)
		if !ok {
			t.Errorf("%s: no sample data (add an event fixture, or an entry in app.SampleData)", tpl.Key)
			continue
		}
		r, err := renderer.Render(tpl, s.Data)
		if err != nil {
			t.Errorf("%s/%s v%d: %v", tpl.Key, tpl.Channel, tpl.Version, err)
			continue
		}
		if strings.Contains(r.Subject+r.Text+r.HTML, "<no value>") {
			t.Errorf("%s/%s: <no value> in output", tpl.Key, tpl.Channel)
		}
		if strings.TrimSpace(r.Text) == "" {
			t.Errorf("%s/%s: empty text", tpl.Key, tpl.Channel)
		}
		if tpl.Channel == domain.ChannelEmail && (r.Subject == "" || r.HTML == "") {
			t.Errorf("%s/email: subject %q, html %d bytes", tpl.Key, r.Subject, len(r.HTML))
		}
		if s.Area == app.AreaOther {
			t.Errorf("%s: no area (extend app.TemplateArea)", tpl.Key)
		}
	}
	for _, s := range samples.All() {
		if !keys[s.Key] {
			t.Errorf("sample for %s, which has no seeded template", s.Key)
		}
	}
}

func TestSamplesComeFromTheRealIngest(t *testing.T) {
	samples, err := app.NewTemplateSamples(context.Background(), sampleLinks)
	if err != nil {
		t.Fatal(err)
	}
	want := map[string]map[string]any{
		"order.confirmed":                 {"total": "$48.00", "estimatedDelivery": "by Wed, Oct 14", "orderUrl": "https://afrotransact.com/orders", "firstName": "Ada"},
		"org.invitation":                  {"inviterName": "A teammate", "programName": "Seller Center"},
		"seller.payouts.paused.suspended": {"reasonText": "prohibited items"},
		"stock.back_in_stock":             {"productUrl": "https://afrotransact.com/p/ATOGBO0500", "firstName": "Ada"},
		"retail.purchase_order.sent":      {"documentExpiresOn": "Oct 24, 2026", "paymentTerms": ""},
		"identity.verification":           {"code": "123456"},
	}
	for key, fields := range want {
		s, ok := samples.Sample(key)
		if !ok {
			t.Fatalf("%s: no sample", key)
		}
		for k, v := range fields {
			if s.Data[k] != v {
				t.Errorf("%s.%s = %v, want %v", key, k, s.Data[k], v)
			}
		}
	}
	s, _ := samples.Sample("shipment.out_for_delivery")
	if s.Source != app.SampleFromEvent || len(s.EventTypes) != 1 || s.EventTypes[0] != "orders.shipment.status_changed" || s.Area != app.AreaOrders {
		t.Errorf("%+v", s)
	}
	if s, _ := samples.Sample("retail.purchase_order.sent"); s.Source != app.SampleFromRequest || s.EventTypes[0] != app.GenericEventType {
		t.Errorf("%+v", s)
	}
	if s, _ := samples.Sample("booking.reminder"); s.Source != app.SampleFromMap || len(s.EventTypes) != 0 {
		t.Errorf("%+v", s)
	}
	if s, ok := samples.Sample("nope.unknown"); ok || s.Source != app.SampleNone || s.Data == nil {
		t.Errorf("%+v", s)
	}
	// Callers get copies: editing one sample must not change the next.
	a, _ := samples.Sample("order.confirmed")
	a.Data["total"] = "changed"
	if b, _ := samples.Sample("order.confirmed"); b.Data["total"] != "$48.00" {
		t.Error("sample data is shared between callers")
	}
}

func TestEveryEventRuleHasASampleFixture(t *testing.T) {
	evs := app.SampleEvents()
	for typ, rules := range app.DefaultEventRules {
		if len(evs[typ]) < len(rules) {
			t.Errorf("%s: add a fixture per rule to app.SampleEvents", typ)
		}
	}
	for key := range app.RequestedTemplates {
		if _, ok := app.SampleRequests()[key]; !ok {
			t.Errorf("%s: add a producer request to app.SampleRequests", key)
		}
	}
}
