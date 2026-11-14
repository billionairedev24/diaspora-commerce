package app

import (
	"context"
	"net/url"
	"slices"
	"strconv"
	"strings"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
)

// The shadow report's side-by-side table: for every order line, our quote next to each provider's, with the provider's
// own state (answered, not configured, timed out, provider error, no code mapped). tax-returns records one row per
// provider per line; here they are folded into one line with a cell per provider. A provider that did not answer is a
// state with a reason that names it, never a $0.00.

const shadowMaxCompareRows = 2000

var shadowStatuses = []string{"all", "differ", "match", "noanswer", "nocode"}

func (s *staffTaxImpl) shadowComparison(ctx context.Context, sc staffCtx, st, view jobj, from, to string, q url.Values, page, size int) error {
	status := strings.ToLower(strings.TrimSpace(q.Get("status")))
	if status == "" {
		status = "all"
	}
	if !slices.Contains(shadowStatuses, status) {
		return domain.ValidationProblem(map[string]string{"status": "Choose all, differ, match, noanswer or nocode"})
	}
	search := strings.ToLower(strings.TrimSpace(q.Get("search")))
	method := firstNonEmpty(jstr(st, "method"), "RULES")
	providers, err := s.shadowProviderCards(ctx, sc, st, method, from, to)
	if err != nil {
		return err
	}
	rows, partial, err := s.collectAllShadowRows(ctx, sc, from, to)
	if err != nil {
		return err
	}
	names := s.categoryNames(ctx, sc)
	lines := pivotShadowLines(rows, providers, names)
	nameAll(ctx, sc, lines, orderRef("orderId"))
	filtered := make([]jobj, 0, len(lines))
	for _, l := range lines {
		if status != "all" && jstr(l, "status") != status {
			continue
		}
		if search != "" && !strings.Contains(strings.ToLower(jstr(l, "searchText")+" "+jstr(l, "orderNumber")), search) {
			continue
		}
		filtered = append(filtered, l)
	}
	for _, l := range filtered {
		delete(l, "searchText")
	}
	view["providers"] = providers
	view["items"] = slicePage(filtered, page, size)
	view["total"], view["page"], view["size"] = len(filtered), page, size
	view["linesInPeriod"] = len(lines)
	view["status"], view["search"] = status, q.Get("search")
	view["comparisonPartial"] = partial
	return nil
}

// shadowProviderCards says per provider whether it is in the comparison and how its recorded lines came out.
func (s *staffTaxImpl) shadowProviderCards(ctx context.Context, sc staffCtx, st jobj, method, from, to string) ([]jobj, error) {
	out := make([]jobj, 0, len(shadowProviders))
	for _, p := range shadowProviders {
		label := taxProviderLabel(p)
		card := jobj{"provider": p, "label": label, "configured": providerConfigured(st, p)}
		switch {
		case !providerConfigured(st, p):
			card["state"], card["stateLabel"] = "NOT_CONFIGURED", "Not configured"
			card["note"] = label + " is not configured, so it is not asked. Set " + providerEnv[p] + " for this environment."
		case providerOfMethod(method) == p:
			card["state"], card["stateLabel"] = "CHARGING", "Charging customers"
			card["note"] = label + " is the method charging customers, so it is not quoted in shadow."
		default:
			card["state"], card["stateLabel"] = "ASKING", "Asked on every real order"
			card["note"] = "Quoted next to ours on every real order; never charged."
		}
		var r jobj
		qv := url.Values{"from": {from}, "to": {to}, "provider": {p}, "onlyDifferences": {"false"}, "page": {"0"}, "size": {"1"}}
		if err := sc.get(ctx, sc.up.TaxReturns, "/v1/tax/shadow/report", qv, &r); err != nil {
			return nil, taxErr(err)
		}
		kinds := map[string]float64{}
		for _, k := range objs(r, "byKind") {
			kinds[jstr(k, "key")] = num(k, "lines")
		}
		answered := kinds["MATCH"] + kinds["OUTCOME"] + kinds["RATE"] + kinds["BOTH"]
		card["lines"], card["answered"], card["matches"] = num(r, "linesCompared"), answered, kinds["MATCH"]
		card["differs"], card["noAnswer"], card["noCode"] = kinds["OUTCOME"]+kinds["RATE"]+kinds["BOTH"], kinds["PROVIDER_ERROR"], kinds["NO_CODE"]
		out = append(out, card)
	}
	return out, nil
}

// collectAllShadowRows reads every recorded row of the period, all providers, up to a cap.
func (s *staffTaxImpl) collectAllShadowRows(ctx context.Context, sc staffCtx, from, to string) ([]jobj, bool, error) {
	all := []jobj{}
	for page := 0; ; page++ {
		var r jobj
		qv := url.Values{"from": {from}, "to": {to}, "onlyDifferences": {"false"}, "page": {strconv.Itoa(page)}, "size": {strconv.Itoa(taxMaxPage)}}
		if err := sc.get(ctx, sc.up.TaxReturns, "/v1/tax/shadow/report", qv, &r); err != nil {
			return nil, false, taxErr(err)
		}
		got := objs(r, "differences")
		all = append(all, got...)
		if len(got) < taxMaxPage {
			return all, false, nil
		}
		if len(all) >= shadowMaxCompareRows {
			return all[:shadowMaxCompareRows], true, nil
		}
	}
}

// pivotShadowLines folds the rows of one order line (one per provider) into a line with our quote and a cell per provider.
func pivotShadowLines(rows []jobj, providers []jobj, names map[string]string) []jobj {
	type group struct {
		line  jobj
		cells map[string]jobj
	}
	order := []string{}
	groups := map[string]*group{}
	for _, d := range rows {
		key := firstNonEmpty(jstr(d, "orderRef"), jstr(d, "orderId")) + "|" + jstr(d, "lineId")
		g := groups[key]
		if g == nil {
			code := jstr(d, "internalTaxCode")
			cat := firstNonEmpty(names[categoryOfCode(code)], domain.Humanize(categoryOfCode(code)))
			if code == "" {
				cat = firstNonEmpty(names[jstr(d, "categoryCode")], domain.Humanize(jstr(d, "categoryCode")))
			}
			g = &group{line: jobj{"orderId": jstr(d, "orderId"), "lineId": jstr(d, "lineId"), "atin": jstr(d, "atin"), "internalTaxCode": code,
				"categoryName": cat, "quotedAt": jstr(d, "quotedAt"), "inHouse": ourCell(asObj(d["inHouse"]), jstr(d, "kind") == "NO_DECISION")}, cells: map[string]jobj{}}
			groups[key] = g
			order = append(order, key)
		}
		g.cells[jstr(d, "shadowProvider")] = providerCell(d)
	}
	out := make([]jobj, 0, len(order))
	for _, key := range order {
		g := groups[key]
		quotes := []jobj{}
		differs, noAnswer, noCode, matches := false, false, false, 0
		for _, p := range providers {
			id := jstr(p, "provider")
			cell, ok := g.cells[id]
			if !ok {
				cell = absentCell(p)
			}
			quotes = append(quotes, cell)
			switch jstr(cell, "state") {
			case "ANSWERED":
				if jstr(cell, "kind") == "MATCH" {
					matches++
				} else {
					differs = true
				}
			case "NO_CODE":
				noCode = true
			case "NOT_CONFIGURED", "TIMEOUT", "ERROR":
				noAnswer = true
			}
		}
		status := "match"
		switch {
		case differs:
			status = "differ"
		case noAnswer:
			status = "noanswer"
		case noCode:
			status = "nocode"
		case matches == 0:
			status = "none"
		}
		g.line["quotes"], g.line["differs"], g.line["status"] = quotes, differs, status
		g.line["searchText"] = strings.Join([]string{jstr(g.line, "categoryName"), jstr(g.line, "internalTaxCode"), jstr(g.line, "atin"), jstr(g.line, "lineId")}, " ")
		out = append(out, g.line)
	}
	return out
}

func ourCell(side jobj, noDecision bool) jobj {
	cell := jobj{"state": "ANSWERED", "stateLabel": shadowStates["ANSWERED"], "text": sideText(side), "taxMinor": nil, "taxText": ""}
	if noDecision || jstr(side, "outcome") == "" {
		cell["state"], cell["stateLabel"], cell["text"] = "NO_DECISION", shadowStates["NO_DECISION"], "No stored decision for this line"
		return cell
	}
	if side["taxMinor"] != nil {
		cell["taxMinor"], cell["taxText"] = num(side, "taxMinor"), taxMoney(num(side, "taxMinor"))
	}
	return cell
}

// providerCell is what one provider said about the line.
func providerCell(d jobj) jobj {
	id, kind := jstr(d, "shadowProvider"), jstr(d, "kind")
	label := taxProviderLabel(id)
	cell := jobj{"provider": id, "label": label, "kind": kind, "providerCode": jstr(d, "providerCode"), "taxMinor": nil, "taxText": "", "differenceMinor": nil, "differenceText": "", "reason": ""}
	side := asObj(d["provider"])
	switch kind {
	case "MATCH", "OUTCOME", "RATE", "BOTH":
		cell["state"], cell["stateLabel"], cell["text"] = "ANSWERED", shadowStates["ANSWERED"], sideText(side)
		cell["taxMinor"], cell["taxText"] = num(side, "taxMinor"), taxMoney(num(side, "taxMinor"))
		cell["differenceMinor"], cell["differenceText"] = num(d, "differenceMinor"), taxMoney(num(d, "differenceMinor"))
	case "PROVIDER_ERROR":
		state := firstNonEmpty(jstr(d, "failure"), "ERROR")
		reason := firstNonEmpty(jstr(side, "error"), label+" did not answer")
		cell["state"], cell["stateLabel"], cell["reason"] = state, shadowStates[state], reason
		cell["text"] = reason
	case "NO_CODE":
		cell["state"], cell["stateLabel"] = "NO_CODE", shadowStates["NO_CODE"]
		cell["reason"] = firstNonEmpty(jstr(side, "error"), "No "+label+" code is mapped for this outcome")
		cell["text"] = cell["reason"]
	default:
		cell["state"], cell["stateLabel"] = "NO_DECISION", shadowStates["NO_DECISION"]
		cell["text"] = "We had no stored decision, so there is nothing to compare"
	}
	return cell
}

// absentCell fills a provider column for a line it has no row for, saying why.
func absentCell(card jobj) jobj {
	id, label := jstr(card, "provider"), jstr(card, "label")
	cell := jobj{"provider": id, "label": label, "kind": "", "providerCode": "", "taxMinor": nil, "taxText": "", "differenceMinor": nil, "differenceText": ""}
	if jstr(card, "state") == "NOT_CONFIGURED" {
		cell["state"], cell["stateLabel"], cell["reason"] = "NOT_CONFIGURED", shadowStates["NOT_CONFIGURED"], label+" is not configured: set "+providerEnvShort[id]+"."
	} else {
		cell["state"], cell["stateLabel"], cell["reason"] = "NOT_ASKED", shadowStates["NOT_ASKED"], jstr(card, "note")
	}
	cell["text"] = cell["reason"]
	return cell
}
