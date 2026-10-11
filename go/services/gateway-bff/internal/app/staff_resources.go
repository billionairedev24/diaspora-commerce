package app

import (
	"context"
	"fmt"
	"net/http"
	"net/url"
	"slices"
	"sort"
	"strconv"
	"strings"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
)

// Server-driven tables and detail sheets. Each resource (staff_resources_*.go) says which upstream lists and reads it,
// how its rows look, which actions it has (with the permission and module level the upstream enforces) and how to
// create or edit a record. Upstream documents are read as generic JSON (jobj) so every field reaches the sheet.

type jobj = map[string]any

// staffQuery is a table request.
type staffQuery struct {
	Q, Status, Cursor string
	Page, Size        int
}

// staffList is one page from an upstream.
type staffList struct {
	items []jobj
	total *int64
	next  *string
}

type staffAction struct {
	key, label, tone string
	// permission is the one the upstream checks; also lists alternatives that are accepted too.
	permission string
	also       []string
	// level is the module level needed (default EDIT).
	level   string
	confirm string
	form    func(ctx context.Context, sc staffCtx, o jobj) *domain.Form
	// when returns why the action is not possible in the record's state ("" when it is).
	when func(sc staffCtx, o jobj) string
	run  func(ctx context.Context, sc staffCtx, o jobj, v jobj) error
}

type staffEdit struct {
	permission string
	form       func(o jobj) domain.Form
	save       func(ctx context.Context, sc staffCtx, o jobj, v jobj, version string) error
}

type staffCreate struct {
	permission string
	form       func(ctx context.Context, sc staffCtx) domain.Form
	create     func(ctx context.Context, sc staffCtx, v jobj) (string, error)
}

type staffResource struct {
	key, module, title string
	columns            []domain.Column
	facets             []domain.Facet
	statusKey          string
	searchPlaceholder  string
	exportable         bool
	list               func(ctx context.Context, sc staffCtx, q staffQuery) (staffList, error)
	get                func(ctx context.Context, sc staffCtx, id string) (jobj, error)
	idOf               func(o jobj) string
	cells              func(o jobj) map[string]any
	titleOf            func(o jobj) string
	subtitleOf         func(o jobj) string
	status             func(o jobj) string
	sections           func(o jobj) []domain.DetailSection
	tables             func(o jobj) []domain.DetailTable
	activity           func(ctx context.Context, sc staffCtx, o jobj) []domain.ActivityEntry
	links              func(o jobj) []domain.RecordLink
	version            func(o jobj) string
	actions            []staffAction
	edit               *staffEdit
	create             *staffCreate
}

func (r *staffResource) id(o jobj) string {
	if r.idOf != nil {
		return r.idOf(o)
	}
	return jstr(o, "id")
}

func (r *staffResource) statusOf(o jobj) string {
	if r.status != nil {
		return r.status(o)
	}
	if r.statusKey == "" {
		return ""
	}
	return jstr(o, r.statusKey)
}

// ---------------------------------------------------------------- generic JSON helpers

// at walks a dotted path ("contacts.supportPhone").
func at(o jobj, path string) any {
	var cur any = o
	for _, k := range strings.Split(path, ".") {
		m, ok := cur.(map[string]any)
		if !ok {
			return nil
		}
		cur = m[k]
	}
	return cur
}

func jstr(o jobj, path string) string {
	switch v := at(o, path).(type) {
	case string:
		return v
	case float64:
		return strconv.FormatFloat(v, 'f', -1, 64)
	case bool:
		return strconv.FormatBool(v)
	default:
		return ""
	}
}

func num(o jobj, path string) float64 {
	switch v := at(o, path).(type) {
	case float64:
		return v
	case string:
		f, _ := strconv.ParseFloat(v, 64)
		return f
	default:
		return 0
	}
}

func boolean(o jobj, path string) bool {
	b, _ := at(o, path).(bool)
	return b
}

func objs(o jobj, path string) []jobj {
	list, _ := at(o, path).([]any)
	out := make([]jobj, 0, len(list))
	for _, it := range list {
		if m, ok := it.(map[string]any); ok {
			out = append(out, m)
		}
	}
	return out
}

func strs(o jobj, path string) []string {
	list, _ := at(o, path).([]any)
	out := make([]string, 0, len(list))
	for _, it := range list {
		if s, ok := it.(string); ok {
			out = append(out, s)
		}
	}
	return out
}

func timeOf(o jobj, path string) time.Time {
	t, _ := time.Parse(time.RFC3339, jstr(o, path))
	return t
}

// cellValue turns a JSON value into a table cell (strings, numbers or null; lists are joined).
func cellValue(v any) any {
	switch x := v.(type) {
	case nil:
		return nil
	case string:
		if x == "" {
			return nil
		}
		return x
	case float64:
		return x
	case bool:
		if x {
			return "Yes"
		}
		return "No"
	case []any:
		parts := []string{}
		for _, it := range x {
			if s, ok := cellValue(it).(string); ok {
				parts = append(parts, s)
			}
		}
		if len(parts) == 0 {
			return nil
		}
		return strings.Join(parts, ", ")
	default:
		return nil
	}
}

func displayValue(v any) string {
	switch c := cellValue(v).(type) {
	case nil:
		return "—"
	case float64:
		return strconv.FormatFloat(c, 'f', -1, 64)
	case string:
		return c
	default:
		return fmt.Sprint(c)
	}
}

func fieldKind(key, statusKey string) string {
	switch {
	case key == statusKey && statusKey != "":
		return "status"
	case strings.HasSuffix(key, "Minor"):
		return "money"
	case strings.HasSuffix(key, "At") || key == "effectiveFrom":
		return "datetime"
	case strings.HasSuffix(key, "Date"):
		return "date"
	case slices.Contains([]string{"id", "atin", "sku", "hash", "prevHash", "lotNo", "reference", "number", "orderNumber", "orderId", "paymentId"}, key):
		return "mono"
	default:
		return "text"
	}
}

func col(key, label, kind string, width int) domain.Column {
	return domain.Column{Key: key, Label: label, Kind: kind, Width: width}
}

// autoSections lays out every field of an upstream document: scalars under "Details", nested objects in their own
// section; lists of objects become tables (autoTables) and are skipped here.
func autoSections(o jobj, statusKey string, skip ...string) []domain.DetailSection {
	keys := make([]string, 0, len(o))
	for k := range o {
		keys = append(keys, k)
	}
	sort.Strings(keys)
	details := domain.DetailSection{Title: "Details", Fields: []domain.DetailField{}}
	var nested []domain.DetailSection
	for _, k := range keys {
		if slices.Contains(skip, k) {
			continue
		}
		switch v := o[k].(type) {
		case map[string]any:
			sec := domain.DetailSection{Title: domain.Humanize(k), Fields: []domain.DetailField{}}
			sub := make([]string, 0, len(v))
			for sk := range v {
				sub = append(sub, sk)
			}
			sort.Strings(sub)
			for _, sk := range sub {
				if _, isMap := v[sk].(map[string]any); isMap {
					continue
				}
				if l, isList := v[sk].([]any); isList && len(l) > 0 {
					if _, objList := l[0].(map[string]any); objList {
						continue
					}
				}
				sec.Fields = append(sec.Fields, domain.DetailField{Label: domain.Humanize(sk), Value: displayValue(v[sk]), Kind: fieldKind(sk, "")})
			}
			if len(sec.Fields) > 0 {
				nested = append(nested, sec)
			}
		case []any:
			if len(v) > 0 {
				if _, objList := v[0].(map[string]any); objList {
					continue
				}
			}
			details.Fields = append(details.Fields, domain.DetailField{Label: domain.Humanize(k), Value: displayValue(v), Kind: "text"})
		default:
			details.Fields = append(details.Fields, domain.DetailField{Label: domain.Humanize(k), Value: displayValue(v), Kind: fieldKind(k, statusKey)})
		}
	}
	return append([]domain.DetailSection{details}, nested...)
}

// tableOf renders a list of objects with chosen columns.
func tableOf(title string, items []jobj, columns []domain.Column, idKey string) domain.DetailTable {
	t := domain.DetailTable{Title: title, Columns: columns, Rows: []domain.Row{}}
	for i, it := range items {
		cells := map[string]any{}
		for _, c := range columns {
			cells[c.Key] = cellValue(at(it, c.Key))
		}
		id := jstr(it, idKey)
		if id == "" {
			id = strconv.Itoa(i)
		}
		t.Rows = append(t.Rows, domain.Row{ID: id, Cells: cells, Tone: domain.StatusTone(jstr(it, "status"))})
	}
	return t
}

// pageOf reads an upstream page document ({items,total} or a bare array).
func jpage(raw any) staffList {
	switch v := raw.(type) {
	case []any:
		out := staffList{}
		for _, it := range v {
			if m, ok := it.(map[string]any); ok {
				out.items = append(out.items, m)
			}
		}
		return out
	case map[string]any:
		out := staffList{items: objs(v, "items")}
		if t, ok := v["total"].(float64); ok {
			n := int64(t)
			out.total = &n
		}
		if c, ok := v["nextCursor"].(string); ok && c != "" {
			out.next = &c
		}
		return out
	default:
		return staffList{}
	}
}

// listPaged calls a page-numbered upstream list.
func listPaged(path string, upstream func(staffCtx) staffUp, extra func(q staffQuery, v url.Values)) func(context.Context, staffCtx, staffQuery) (staffList, error) {
	return func(ctx context.Context, sc staffCtx, q staffQuery) (staffList, error) {
		v := url.Values{"page": {strconv.Itoa(q.Page)}, "size": {strconv.Itoa(q.Size)}}
		if q.Status != "" {
			v.Set("status", q.Status)
		}
		if extra != nil {
			extra(q, v)
		}
		var raw any
		if err := sc.get(ctx, upstream(sc), path, v, &raw); err != nil {
			return staffList{}, err
		}
		return jpage(raw), nil
	}
}

// listLimited calls an upstream that returns a bare array capped by `limit`: the BFF asks for the rows up to the
// requested page and slices (cursor = offset).
func listLimited(path string, upstream func(staffCtx) staffUp) func(context.Context, staffCtx, staffQuery) (staffList, error) {
	return func(ctx context.Context, sc staffCtx, q staffQuery) (staffList, error) {
		offset, _ := strconv.Atoi(q.Cursor)
		limit := min(offset+q.Size+1, 500)
		v := url.Values{"limit": {strconv.Itoa(limit)}}
		if q.Status != "" {
			v.Set("status", q.Status)
		}
		var raw any
		if err := sc.get(ctx, upstream(sc), path, v, &raw); err != nil {
			return staffList{}, err
		}
		return sliceList(jpage(raw).items, offset, q.Size), nil
	}
}

// sliceList pages an in-memory list with an offset cursor.
func sliceList(all []jobj, offset, size int) staffList {
	if offset > len(all) {
		offset = len(all)
	}
	end := min(offset+size, len(all))
	out := staffList{items: all[offset:end]}
	if end < len(all) {
		next := strconv.Itoa(end)
		out.next = &next
	}
	return out
}

// listAll calls an upstream that returns everything at once (small reference tables) and pages it in the BFF.
func listAll(load func(ctx context.Context, sc staffCtx, q staffQuery) ([]jobj, error)) func(context.Context, staffCtx, staffQuery) (staffList, error) {
	return func(ctx context.Context, sc staffCtx, q staffQuery) (staffList, error) {
		all, err := load(ctx, sc, q)
		if err != nil {
			return staffList{}, err
		}
		total := int64(len(all))
		out := sliceList(all, q.Page*q.Size, q.Size)
		out.total = &total
		out.next = nil
		return out, nil
	}
}

func getByPath(prefix string, upstream func(staffCtx) staffUp) func(context.Context, staffCtx, string) (jobj, error) {
	return func(ctx context.Context, sc staffCtx, id string) (jobj, error) {
		var o jobj
		if err := sc.get(ctx, upstream(sc), prefix+url.PathEscape(id), nil, &o); err != nil {
			return nil, err
		}
		return o, nil
	}
}

// findIn reads a record from a list-only upstream.
func findIn(list func(context.Context, staffCtx, staffQuery) (staffList, error), key string) func(context.Context, staffCtx, string) (jobj, error) {
	return func(ctx context.Context, sc staffCtx, id string) (jobj, error) {
		page, err := list(ctx, sc, staffQuery{Size: 500})
		if err != nil {
			return nil, err
		}
		for _, it := range page.items {
			if jstr(it, key) == id {
				return it, nil
			}
		}
		return nil, domain.NewProblem(http.StatusNotFound, domain.CodeNotFound, "Not found", "We couldn't find that.")
	}
}

// inState builds a `when` that allows the action in the listed statuses only.
func inState(statuses ...string) func(staffCtx, jobj) string {
	return func(_ staffCtx, o jobj) string {
		return stateReason(firstNonEmpty(jstr(o, "status"), jstr(o, "state")), statuses...)
	}
}

func stateReason(status string, ok ...string) string {
	if slices.Contains(ok, strings.ToUpper(status)) {
		return ""
	}
	return "Not possible while " + strings.ToLower(domain.Humanize(status)) + "."
}

func floatPtr(f float64) *float64 { return &f }

// Form helpers.
func ff(key, label, typ string, required bool) domain.FormField {
	return domain.FormField{Key: key, Label: label, Type: typ, Required: required}
}

func withMax(f domain.FormField, n int) domain.FormField { f.MaxLength = n; return f }

func withValue(f domain.FormField, v any) domain.FormField { f.Value = v; return f }

func withOptions(f domain.FormField, o []domain.FieldOption) domain.FormField {
	f.Options = o
	return f
}

func withPattern(f domain.FormField, pattern, help string) domain.FormField {
	f.Pattern, f.Help = pattern, help
	return f
}

// withRange limits a number field to 0 … hi.
func withRange(f domain.FormField, hi float64) domain.FormField {
	f.Min, f.Max = floatPtr(0), floatPtr(hi)
	return f
}

func noteForm(title, submit string, required bool) func(context.Context, staffCtx, jobj) *domain.Form {
	return func(context.Context, staffCtx, jobj) *domain.Form {
		return &domain.Form{Title: title, SubmitLabel: submit, Fields: []domain.FormField{withMax(ff("note", "Note", "textarea", required), 2000)}}
	}
}

func reasonForm(title, submit string) func(context.Context, staffCtx, jobj) *domain.Form {
	return func(context.Context, staffCtx, jobj) *domain.Form {
		return &domain.Form{Title: title, SubmitLabel: submit, Fields: []domain.FormField{withMax(ff("reason", "Reason", "textarea", true), 1000)}}
	}
}

func vs(v jobj, k string) string {
	s, _ := v[k].(string)
	return strings.TrimSpace(s)
}

func vn(v jobj, k string) float64 {
	n, _ := v[k].(float64)
	return n
}

func vb(v jobj, k string) bool {
	b, _ := v[k].(bool)
	return b
}

func vlist(v jobj, k string) []string {
	list, _ := v[k].([]any)
	out := []string{}
	for _, it := range list {
		if s, ok := it.(string); ok {
			out = append(out, s)
		}
	}
	return out
}

// omitEmpty drops empty strings from a request body.
func omitEmpty(m jobj) jobj {
	for k, v := range m {
		if s, ok := v.(string); ok && s == "" {
			delete(m, k)
		}
	}
	return m
}
