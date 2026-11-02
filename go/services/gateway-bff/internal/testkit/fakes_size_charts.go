package testkit

import (
	"encoding/json"
	"io"
	"net/http"
	"strings"
)

// Size chart fixtures: the fake catalog keeps the seller's charts in memory (like catalog: scoped to the token's
// organisation, unique names ignoring case, optimistic version, retire instead of delete).
const (
	// SizedProductATIN is a live fashion product (Size × Color with seller-written sizes) with a size chart.
	SizedProductATIN = "ATADIRE001"
)

type fakeChart struct {
	body    map[string]any
	retired bool
	version int
}

func (u *Upstreams) sizeChartRoutes(mux *http.ServeMux) {
	var charts []*fakeChart
	seq := 0
	columnsOf := func(b map[string]any) []map[string]any {
		out := []map[string]any{}
		unit, _ := b["unitSystem"].(string)
		cols, _ := b["columns"].([]any)
		for _, c := range cols {
			m, _ := c.(map[string]any)
			label, _ := m["label"].(string)
			key, _ := m["key"].(string)
			if key == "" {
				key = strings.ToLower(strings.ReplaceAll(label, " ", "_"))
			}
			out = append(out, map[string]any{"key": key, "label": label, "unit": unit})
		}
		return out
	}
	rowsOf := func(b map[string]any) []map[string]any {
		out := []map[string]any{}
		rows, _ := b["rows"].([]any)
		for _, r := range rows {
			m, _ := r.(map[string]any)
			values := map[string]any{}
			alt := map[string]any{}
			vs, _ := m["values"].(map[string]any)
			for k, v := range vs {
				vm, _ := v.(map[string]any)
				min, _ := vm["min"].(float64)
				max, ok := vm["max"].(float64)
				if !ok {
					max = min
				}
				values[k] = map[string]any{"min": min, "max": max}
				alt[k] = map[string]any{"min": min / 2.54, "max": max / 2.54}
			}
			out = append(out, map[string]any{"size": m["size"], "values": values, "alternateValues": alt})
		}
		return out
	}
	render := func(id string, c *fakeChart) map[string]any {
		b := c.body
		unit, _ := b["unitSystem"].(string)
		other := "IN"
		if unit == "IN" {
			other = "CM"
		}
		var retiredAt any
		if c.retired {
			retiredAt = "2026-11-02T10:00:00Z"
		}
		return map[string]any{"id": id, "ownerOrgId": SellerOrgID, "name": b["name"], "unitSystem": unit, "alternateUnit": other,
			"productTypeCode": b["productTypeCode"], "categoryId": b["categoryId"], "columns": columnsOf(b), "rows": rowsOf(b),
			"fitNote": b["fitNote"], "howToMeasure": b["howToMeasure"], "retired": c.retired, "retiredAt": retiredAt, "productCount": 0,
			"createdAt": "2026-11-01T10:00:00Z", "updatedAt": "2026-11-02T10:00:00Z", "version": c.version}
	}
	ids := map[*fakeChart]string{}
	find := func(id string) *fakeChart {
		for _, c := range charts {
			if ids[c] == id {
				return c
			}
		}
		return nil
	}
	validate := func(b map[string]any, ownId string) map[string]string {
		errs := map[string]string{}
		name, _ := b["name"].(string)
		if n := len(strings.TrimSpace(name)); n < 2 {
			errs["name"] = "2 to 80 characters"
		}
		for _, c := range charts {
			if ids[c] != ownId && !c.retired && strings.EqualFold(strings.TrimSpace(c.body["name"].(string)), strings.TrimSpace(name)) {
				errs["name"] = "You already have a size chart called " + name
			}
		}
		if rows, _ := b["rows"].([]any); len(rows) == 0 {
			errs["rows"] = "Add at least one size"
		}
		return errs
	}
	save := func(w http.ResponseWriter, r *http.Request, id string, status int) {
		var b map[string]any
		_ = json.NewDecoder(r.Body).Decode(&b)
		u.mu.Lock()
		u.LastSizeChartBody = b
		u.mu.Unlock()
		if errs := validate(b, id); len(errs) > 0 {
			problem(w, 422, "catalog.rule-violation", "The size chart has problems", map[string]any{"fieldErrors": errs})
			return
		}
		u.mu.Lock()
		defer u.mu.Unlock()
		if id == "" {
			seq++
			id = "5c1e0000-0000-4000-8000-00000000010" + string(rune('0'+seq))
			c := &fakeChart{body: b}
			ids[c] = id
			charts = append(charts, c)
			writeJSON(w, status, render(id, c))
			return
		}
		c := find(id)
		if v, ok := b["version"].(float64); ok && int(v) != c.version {
			problem(w, 409, "catalog.size-chart-changed", "This size chart was changed since you opened it", nil)
			return
		}
		c.body, c.version = b, c.version+1
		writeJSON(w, status, render(id, c))
	}
	mux.HandleFunc("GET /v1/size-charts", func(w http.ResponseWriter, r *http.Request) {
		u.mu.Lock()
		defer u.mu.Unlock()
		u.LastSizeChartQuery = r.URL.Query()
		items := []map[string]any{}
		for _, c := range charts {
			if !c.retired || r.URL.Query().Get("includeRetired") == "true" {
				items = append(items, render(ids[c], c))
			}
		}
		writeJSON(w, 200, map[string]any{"items": items, "total": len(items), "page": 0, "size": 25})
	})
	mux.HandleFunc("POST /v1/size-charts", func(w http.ResponseWriter, r *http.Request) { save(w, r, "", 201) })
	mux.HandleFunc("GET /v1/size-charts/suggestions", func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Query().Get("productType") == "grocery" {
			writeJSON(w, 200, map[string]any{"productTypeCode": "grocery", "productTypeLabel": "Packaged food", "sized": false,
				"sizeChartRequired": false, "sizeDimension": "Size", "sizes": []string{}, "sizesAllowCustomValues": false,
				"unitSystem": "CM", "measurements": []any{}})
			return
		}
		writeJSON(w, 200, map[string]any{"productTypeCode": "dress", "productTypeLabel": "Women's dresses", "sized": true,
			"sizeChartRequired": true, "sizeDimension": "Size", "sizes": []string{"XS", "S", "M", "L", "XL"}, "sizesAllowCustomValues": true,
			"unitSystem": "CM", "measurements": []map[string]any{
				{"key": "bust", "label": "Bust", "unit": "CM", "help": "Around the fullest part of the bust"},
				{"key": "waist", "label": "Waist", "unit": "CM", "help": nil}}})
	})
	mux.HandleFunc("GET /v1/size-charts/import-template", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "text/csv; charset=utf-8")
		w.Header().Set("Content-Disposition", `attachment; filename="afrotransact-size-charts-`+r.URL.Query().Get("productType")+`.csv"`)
		_, _ = w.Write([]byte("\xef\xbb\xbf# One row per size.\r\nSize chart name *,Unit,Size *,Bust *,Waist *,Size fit,How to measure\r\n"))
	})
	mux.HandleFunc("POST /v1/size-charts/import", func(w http.ResponseWriter, r *http.Request) {
		if err := r.ParseMultipartForm(1 << 20); err != nil {
			problem(w, 422, "catalog.upload-rejected", "The file cannot be imported", nil)
			return
		}
		f, _, _ := r.FormFile("file")
		data, _ := io.ReadAll(f)
		u.mu.Lock()
		u.LastSizeChartQuery = r.URL.Query()
		u.mu.Unlock()
		dry := r.URL.Query().Get("dryRun") == "true"
		bad := strings.Contains(string(data), "abc")
		report := map[string]any{"applied": !dry, "fileName": "charts.csv", "fileWarnings": []string{}, "chartCount": 1, "created": 1, "updated": 0,
			"unchanged": 0, "failed": 0, "errorRows": 0, "warningRows": 0,
			"charts": []map[string]any{{"name": "Adire dresses", "action": "CREATE", "chartId": nil, "rows": []int{2}, "message": nil}},
			"rows": []map[string]any{{"rowNumber": 2, "chartName": "Adire dresses", "size": "S", "status": "VALID", "messages": []any{}, "cells": []string{"Adire dresses"}}}}
		if bad {
			report["created"], report["failed"], report["errorRows"] = 0, 1, 1
			report["charts"] = []map[string]any{{"name": "Adire dresses", "action": "FAILED", "chartId": nil, "rows": []int{2}, "message": "Fix the rows"}}
			report["rows"] = []map[string]any{{"rowNumber": 2, "chartName": "Adire dresses", "size": "S", "status": "ERROR", "cells": []string{"abc"},
				"messages": []map[string]any{{"column": "m:bust", "columnIndex": 3, "severity": "ERROR", "message": "\"abc\" is not a number or a range like 86-90"}}}}
		}
		writeJSON(w, 200, report)
	})
	mux.HandleFunc("GET /v1/size-charts/{id}", func(w http.ResponseWriter, r *http.Request) {
		u.mu.Lock()
		defer u.mu.Unlock()
		c := find(r.PathValue("id"))
		if c == nil {
			problem(w, 404, "catalog.size-chart-not-found", "Size chart not found", nil)
			return
		}
		writeJSON(w, 200, render(r.PathValue("id"), c))
	})
	mux.HandleFunc("PUT /v1/size-charts/{id}", func(w http.ResponseWriter, r *http.Request) {
		u.mu.Lock()
		missing := find(r.PathValue("id")) == nil
		u.mu.Unlock()
		if missing {
			problem(w, 404, "catalog.size-chart-not-found", "Size chart not found", nil)
			return
		}
		save(w, r, r.PathValue("id"), 200)
	})
	mux.HandleFunc("DELETE /v1/size-charts/{id}", func(w http.ResponseWriter, r *http.Request) {
		u.mu.Lock()
		defer u.mu.Unlock()
		c := find(r.PathValue("id"))
		if c == nil {
			problem(w, 404, "catalog.size-chart-not-found", "Size chart not found", nil)
			return
		}
		c.retired = true
		writeJSON(w, 200, render(r.PathValue("id"), c))
	})
	mux.HandleFunc("POST /v1/size-charts/{id}/duplicate", func(w http.ResponseWriter, r *http.Request) {
		u.mu.Lock()
		defer u.mu.Unlock()
		c := find(r.PathValue("id"))
		if c == nil {
			problem(w, 404, "catalog.size-chart-not-found", "Size chart not found", nil)
			return
		}
		var in map[string]any
		_ = json.NewDecoder(r.Body).Decode(&in)
		b := map[string]any{}
		for k, v := range c.body {
			b[k] = v
		}
		b["name"] = c.body["name"].(string) + " (copy)"
		if n, _ := in["name"].(string); n != "" {
			b["name"] = n
		}
		seq++
		id := "5c1e0000-0000-4000-8000-00000000010" + string(rune('0'+seq))
		cp := &fakeChart{body: b}
		ids[cp] = id
		charts = append(charts, cp)
		writeJSON(w, 201, render(id, cp))
	})
}

// sizedProductChart is the chart the public page of SizedProductATIN shows.
func sizedProductChart() map[string]any {
	col := func(k, l string) map[string]any { return map[string]any{"key": k, "label": l, "unit": "CM"} }
	row := func(size string, bust, waist [2]float64) map[string]any {
		v := func(r [2]float64) map[string]any { return map[string]any{"min": r[0], "max": r[1]} }
		alt := func(r [2]float64) map[string]any { return map[string]any{"min": r[0] / 2.54, "max": r[1] / 2.54} }
		return map[string]any{"size": size, "values": map[string]any{"bust": v(bust), "waist": v(waist)},
			"alternateValues": map[string]any{"bust": alt(bust), "waist": alt(waist)}}
	}
	return map[string]any{"name": "Adire dresses", "unitSystem": "CM", "alternateUnit": "IN",
		"columns": []map[string]any{col("bust", "Bust"), col("waist", "Waist")},
		"rows": []map[string]any{row("S", [2]float64{84, 88}, [2]float64{66, 70}), row("UK 12", [2]float64{89, 93}, [2]float64{71, 75}),
			row("Free size", [2]float64{90, 110}, [2]float64{72, 90})},
		"fitNote": "RUNS_SMALL", "howToMeasure": "Measure over light clothing."}
}
