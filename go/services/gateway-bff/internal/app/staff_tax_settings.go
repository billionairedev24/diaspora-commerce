package app

import (
	"context"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

// Tax method and SHADOW provider (category-tax-design.md section 8). The method says who works out the amount; the
// shadow provider is quoted next to it on real orders and never charged. A provider that is not set up cannot be chosen,
// and a provider can only charge once every outcome has a verified code (an unmapped or unverified code blocks the
// line at checkout, it never falls back).

const cutoverRunbook = "docs/runbooks/tax-provider-cutover.md"

var taxMethods = map[string]taxLabel{
	"RULES":      {"Our own rules", "AfroTransact decides the tax itself. Each product's stored tax decision says exempt or taxable, and our state and local rate tables give the rate. No provider is called and nothing costs per order. This is the default."},
	"STRIPE_TAX": {"Stripe Tax", "Stripe Tax works out the tax amount. We still decide what each item is, and send Stripe the code you mapped to that outcome. A line without a verified code is blocked, never guessed."},
	"AVALARA":    {"Avalara", "Avalara works out the tax amount. We still decide what each item is, and send Avalara the code you mapped to that outcome. A line without a verified code is blocked, never guessed."},
}

func taxMethodName(m string) string {
	if l, ok := taxMethods[m]; ok {
		return l.label
	}
	return domain.Humanize(m)
}

// providerConfigured says whether a provider can be chosen. Stripe reports it; Avalara's flag is read when tax-returns
// sends it and counts as not set up until then.
func providerConfigured(settings jobj, provider string) bool {
	switch provider {
	case "STRIPE":
		return boolean(settings, "stripeAvailable")
	case "AVALARA":
		return boolean(settings, "avalaraAvailable")
	}
	return false
}

func notSetUp(provider string) string {
	if provider == "AVALARA" {
		return "Avalara isn't set up yet: its account details aren't configured for this environment."
	}
	return "Stripe Tax isn't set up yet: its key isn't configured, or the registrations aren't done in the Stripe dashboard."
}

func (s *staffTaxImpl) providerStatus(ctx context.Context, sc staffCtx, settings jobj) []jobj {
	out := []jobj{}
	for _, p := range []string{"STRIPE", "AVALARA"} {
		row := jobj{"provider": p, "label": taxProviderLabel(p), "configured": providerConfigured(settings, p)}
		if rows, err := s.providerMap(ctx, sc, p); err == nil {
			row["completeness"] = completeness(rows)
		}
		out = append(out, row)
	}
	return out
}

func (s *staffTaxImpl) Method(ctx context.Context, c *domain.Caller) (any, error) {
	sc, err := s.need(ctx, c, "")
	if err != nil {
		return nil, err
	}
	st, err := s.settings(ctx, sc)
	if err != nil {
		return nil, err
	}
	return s.methodView(ctx, sc, st), nil
}

func (s *staffTaxImpl) methodView(ctx context.Context, sc staffCtx, st jobj) jobj {
	method := firstNonEmpty(jstr(st, "method"), "RULES")
	shadow := jstr(st, "shadowProvider")
	providers := s.providerStatus(ctx, sc, st)
	byProvider := map[string]jobj{}
	for _, p := range providers {
		byProvider[jstr(p, "provider")] = p
	}
	options := []jobj{}
	for _, m := range []string{"RULES", "STRIPE_TAX", "AVALARA"} {
		opt := jobj{"method": m, "label": taxMethods[m].label, "explain": taxMethods[m].explain, "available": true, "blockedReason": "", "current": m == method}
		if p := providerOfMethod(m); p != "" {
			switch comp, _ := byProvider[p]["completeness"].(jobj); {
			case !providerConfigured(st, p):
				opt["available"], opt["blockedReason"] = false, notSetUp(p)
			case comp != nil && !boolean(comp, "complete"):
				opt["available"] = false
				opt["blockedReason"] = strconv.Itoa(intOf(comp["unmapped"])) + " outcomes have no " + taxProviderLabel(p) + " code and " + strconv.Itoa(intOf(comp["unverified"])) +
					" are not verified. Finish the Provider codes first."
			}
		}
		options = append(options, opt)
	}
	shadowOptions := []jobj{{"value": "", "label": "Off", "available": true, "blockedReason": "",
		"explain": "No provider is asked in the background."}}
	for _, p := range []string{"STRIPE", "AVALARA"} {
		opt := jobj{"value": p, "label": taxProviderLabel(p), "available": true, "blockedReason": "",
			"explain": taxProviderLabel(p) + " is asked for the same lines on real orders. The answers are logged and compared. They never change what a customer pays."}
		if !providerConfigured(st, p) {
			opt["available"], opt["blockedReason"] = false, notSetUp(p)
		}
		shadowOptions = append(shadowOptions, opt)
	}
	history := []jobj{}
	for _, h := range objs(st, "history") {
		history = append(history, jobj{"at": jstr(h, "changedAt"), "changedBy": jstr(h, "changedBy"), "fromLabel": taxMethodName(jstr(h, "from")), "toLabel": taxMethodName(jstr(h, "to")),
			"reason": jstr(h, "reason")})
	}
	nameAll(ctx, sc, history, personRef("changedBy", "Changed by"))
	nameAll(ctx, sc, []jobj{st}, personRef("changedBy", "Changed by"))
	view := jobj{"method": method, "methodLabel": taxMethodName(method), "methodExplain": taxMethods[method].explain, "shadowProvider": shadow, "shadowLabel": "Off",
		"options": options, "shadowOptions": shadowOptions, "providers": providers, "history": history, "changedByName": jstr(st, "changedByName"), "changedAt": jstr(st, "changedAt"),
		"activeProvider": activeProvider(st), "runbookPath": cutoverRunbook, "canConfigure": sc.access.Can(permTaxConfigure),
		"draftNotice": "The tax rules are a draft. A Texas tax professional must approve them before they are used for real customers."}
	if shadow != "" {
		view["shadowLabel"] = taxProviderLabel(shadow)
	}
	return view
}

func intOf(v any) int {
	switch x := v.(type) {
	case int:
		return x
	case float64:
		return int(x)
	}
	return 0
}

func (s *staffTaxImpl) ChangeMethod(ctx context.Context, c *domain.Caller, body map[string]any) (any, error) {
	sc, err := s.need(ctx, c, permTaxConfigure)
	if err != nil {
		return nil, err
	}
	method := strings.ToUpper(vs(body, "method"))
	if _, ok := taxMethods[method]; !ok {
		return nil, domain.FieldProblem(domain.CodeValidationFailed, "Validation failed", "method", "Choose who calculates the tax.")
	}
	shadow := strings.ToUpper(vs(body, "shadowProvider"))
	if shadow != "" && !validProvider(shadow) {
		return nil, domain.FieldProblem(domain.CodeValidationFailed, "Validation failed", "shadowProvider", "Choose Stripe, Avalara or Off.")
	}
	if shadow != "" && shadow == providerOfMethod(method) {
		return nil, domain.FieldProblem(domain.CodeValidationFailed, "Validation failed", "shadowProvider",
			taxProviderLabel(shadow)+" is already the one charging. Shadow mode compares another provider with it.")
	}
	reason, err := taxReason(body, "reason")
	if err != nil {
		return nil, err
	}
	st, err := s.settings(ctx, sc)
	if err != nil {
		return nil, err
	}
	if p := providerOfMethod(method); p != "" {
		if !providerConfigured(st, p) {
			return nil, domain.FieldProblem(domain.CodeValidationFailed, "Can't switch", "method", notSetUp(p))
		}
		if firstNonEmpty(jstr(st, "method"), "RULES") != method {
			rows, merr := s.providerMap(ctx, sc, p)
			if merr != nil {
				return nil, merr
			}
			if comp := completeness(rows); !boolean(comp, "complete") {
				return nil, domain.FieldProblem(domain.CodeValidationFailed, "Can't switch", "method",
					strconv.Itoa(intOf(comp["unmapped"]))+" outcomes have no "+taxProviderLabel(p)+" code and "+strconv.Itoa(intOf(comp["unverified"]))+
						" are not verified. Lines without a verified code would be blocked at checkout. Finish the Provider codes first.")
			}
		}
	}
	if shadow != "" && !providerConfigured(st, shadow) {
		return nil, domain.FieldProblem(domain.CodeValidationFailed, "Can't switch", "shadowProvider", notSetUp(shadow))
	}
	up := jobj{"method": method, "reason": reason, "shadowProvider": nil}
	if shadow != "" {
		up["shadowProvider"] = shadow
	}
	var out jobj
	if err := sc.call(ctx, sc.up.TaxReturns, port.StaffCall{Method: http.MethodPut, Path: "/v1/tax/settings", Body: up}, &out); err != nil {
		return nil, taxErr(err)
	}
	return s.methodView(ctx, sc, out), nil
}

// ---------------------------------------------------------------- shadow report

// shadowThresholds decide "ready to cut over?". tax-returns may return its own under `thresholds`; until it does these
// defaults apply and the report says so. The numbers are placeholders to agree with the tax adviser (runbook step 2).
var shadowDefaults = map[string]float64{"minDays": 30, "minLines": 200, "minLinesPerCode": 5, "maxOutcomeDisagreementPct": 0, "maxProviderErrorPct": 1}

var shadowKinds = map[string]string{
	"OUTCOME": "Different outcome (exempt against taxable)", "RATE": "Different rate", "BOTH": "Different outcome and rate",
	"PROVIDER_ERROR": "The provider did not answer", "NO_DECISION": "We had no stored decision",
}

func shadowThresholds(report jobj) (map[string]float64, string) {
	out := map[string]float64{}
	for k, v := range shadowDefaults {
		out[k] = v
	}
	given, _ := report["thresholds"].(map[string]any)
	if len(given) == 0 {
		return out, "DEFAULT"
	}
	for k := range shadowDefaults {
		if f, ok := given[k].(float64); ok {
			out[k] = f
		}
	}
	return out, "TAX_RETURNS"
}

func (s *staffTaxImpl) Shadow(ctx context.Context, c *domain.Caller, q url.Values) (any, error) {
	sc, err := s.need(ctx, c, "")
	if err != nil {
		return nil, err
	}
	st, err := s.settings(ctx, sc)
	if err != nil {
		return nil, err
	}
	provider := strings.ToUpper(strings.TrimSpace(q.Get("provider")))
	if provider == "" {
		provider = firstNonEmpty(jstr(st, "shadowProvider"), activeProvider(st))
	}
	if provider != "" && !validProvider(provider) {
		return nil, domain.ValidationProblem(map[string]string{"provider": "Choose Stripe or Avalara"})
	}
	page, size, err := pageOf(q, 25)
	if err != nil {
		return nil, err
	}
	from, to, err := shadowWindow(q, sc.now)
	if err != nil {
		return nil, err
	}
	if provider == "" {
		return jobj{"provider": "", "from": from, "to": to, "shadowOff": true, "canConfigure": sc.access.Can(permTaxConfigure),
			"message": "Shadow mode is off. Turn it on in Who calculates tax to compare a provider with our own rules on real orders."}, nil
	}
	report, diffs, partial, err := s.collectShadow(ctx, sc, from, to, provider)
	if err != nil {
		return nil, err
	}
	return s.shadowView(ctx, sc, st, report, diffs, partial, provider, from, to, page, size), nil
}

func shadowWindow(q url.Values, now time.Time) (from, to string, err error) {
	today := now.UTC().Truncate(24 * time.Hour)
	toT, fromT := today, today.AddDate(0, 0, -30)
	parse := func(field string, dst *time.Time) error {
		if v := strings.TrimSpace(q.Get(field)); v != "" {
			t, perr := time.Parse(time.DateOnly, v)
			if perr != nil {
				return domain.ValidationProblem(map[string]string{field: "Use a date like 2026-11-09"})
			}
			*dst = t
		}
		return nil
	}
	if err = parse("from", &fromT); err != nil {
		return "", "", err
	}
	if err = parse("to", &toT); err != nil {
		return "", "", err
	}
	if toT.Before(fromT) || toT.Sub(fromT) > 366*24*time.Hour {
		return "", "", domain.ValidationProblem(map[string]string{"to": "The end date must be after the start, within a year"})
	}
	return fromT.Format(time.DateOnly), toT.Format(time.DateOnly), nil
}

const shadowMaxRows = 1000

// collectShadow reads the report and then every difference (up to a cap) so the tallies are exact.
func (s *staffTaxImpl) collectShadow(ctx context.Context, sc staffCtx, from, to, provider string) (jobj, []jobj, bool, error) {
	var report jobj
	diffs := []jobj{}
	for page := 0; ; page++ {
		var r jobj
		q := url.Values{"from": {from}, "to": {to}, "provider": {provider}, "onlyDifferences": {"true"}, "page": {strconv.Itoa(page)}, "size": {strconv.Itoa(taxMaxPage)}}
		if err := sc.get(ctx, sc.up.TaxReturns, "/v1/tax/shadow/report", q, &r); err != nil {
			return nil, nil, false, taxErr(err)
		}
		if page == 0 {
			report = r
		}
		got := objs(r, "differences")
		diffs = append(diffs, got...)
		if len(got) < taxMaxPage || len(diffs) >= shadowMaxRows || int64(num(report, "linesDiffering")) <= int64(len(diffs)) {
			break
		}
	}
	return report, diffs, int64(num(report, "linesDiffering")) > int64(len(diffs)), nil
}

func pct(part, whole float64) float64 {
	if whole <= 0 {
		return 0
	}
	return float64(int(part/whole*10000+0.5)) / 100
}

func categoryOfCode(internal string) string {
	parts := strings.Split(internal, ".")
	if len(parts) >= 2 {
		return strings.ToLower(strings.ReplaceAll(parts[1], "_", "-"))
	}
	return ""
}

func taxMoney(minor float64) string {
	sign := ""
	if minor < 0 {
		sign, minor = "-", -minor
	}
	return sign + "$" + strconv.FormatFloat(float64(int64(minor+0.5))/100, 'f', 2, 64)
}

func sideText(side jobj) string {
	if e := jstr(side, "error"); e != "" {
		return "No answer: " + e
	}
	if jstr(side, "outcome") == "" {
		return "No decision"
	}
	text := taxOutcomeLabel(jstr(side, "outcome"))
	if side["rate"] != nil {
		text += " at " + strconv.FormatFloat(num(side, "rate")*100, 'f', -1, 64) + "%"
	}
	if side["taxMinor"] != nil {
		text += ", tax " + taxMoney(num(side, "taxMinor"))
	}
	return text
}

type tally struct{ lines, differing, abs float64 }

func (s *staffTaxImpl) shadowView(ctx context.Context, sc staffCtx, st, report jobj, diffs []jobj, partial bool, provider, from, to string, page, size int) jobj {
	names := s.categoryNames(ctx, sc)
	outcomes := map[string]string{}
	codeRows, mapErr := s.providerMap(ctx, sc, provider)
	for _, e := range codeRows {
		outcomes[jstr(e, "internalTaxCode")] = jstr(e, "outcome")
	}
	byCode, byCat, byOutcome := []jobj{}, map[string]*tally{}, map[string]*tally{}
	catOrder, outOrder := []string{}, []string{}
	perCodeShort := 0
	th, source := shadowThresholds(report)
	for _, r := range objs(report, "byInternalTaxCode") {
		code := jstr(r, "internalTaxCode")
		cat, out := categoryOfCode(code), firstNonEmpty(outcomes[code], "UNKNOWN")
		lines, differing := num(r, "lines"), num(r, "differing")
		row := jobj{"internalTaxCode": code, "providerCode": jstr(r, "providerCode"), "lines": lines, "differing": differing, "differingPct": pct(differing, lines),
			"absoluteDifferenceMinor": num(r, "absoluteDifferenceMinor"), "categoryName": firstNonEmpty(names[cat], domain.Humanize(cat)), "outcomeLabel": taxOutcomeLabel(out)}
		byCode = append(byCode, row)
		if lines < th["minLinesPerCode"] {
			perCodeShort++
		}
		if byCat[cat] == nil {
			byCat[cat] = &tally{}
			catOrder = append(catOrder, cat)
		}
		if byOutcome[out] == nil {
			byOutcome[out] = &tally{}
			outOrder = append(outOrder, out)
		}
		for _, t := range []*tally{byCat[cat], byOutcome[out]} {
			t.lines += lines
			t.differing += differing
			t.abs += num(r, "absoluteDifferenceMinor")
		}
	}
	groups := func(order []string, m map[string]*tally, label func(string) string) []jobj {
		out := []jobj{}
		for _, k := range order {
			t := m[k]
			out = append(out, jobj{"key": k, "label": label(k), "lines": t.lines, "differing": t.differing, "differingPct": pct(t.differing, t.lines), "absoluteDifferenceMinor": t.abs})
		}
		return out
	}
	kinds, states := map[string]int{}, map[string]*tally{}
	stateOrder := []string{}
	rows := make([]jobj, 0, len(diffs))
	for _, d := range diffs {
		kinds[jstr(d, "kind")]++
		if state := jstr(d, "shipToState"); state != "" {
			if states[state] == nil {
				states[state] = &tally{}
				stateOrder = append(stateOrder, state)
			}
			states[state].differing++
			states[state].abs += absf(num(d, "differenceMinor"))
		}
		rows = append(rows, jobj{"orderId": jstr(d, "orderId"), "lineId": jstr(d, "lineId"), "atin": jstr(d, "atin"), "internalTaxCode": jstr(d, "internalTaxCode"),
			"categoryName": firstNonEmpty(names[categoryOfCode(jstr(d, "internalTaxCode"))], domain.Humanize(categoryOfCode(jstr(d, "internalTaxCode")))),
			"providerCode": jstr(d, "providerCode"), "kind": jstr(d, "kind"), "kindLabel": shadowKinds[jstr(d, "kind")],
			"inHouseText": sideText(asObj(d["inHouse"])), "providerText": sideText(asObj(d["provider"])),
			"differenceMinor": num(d, "differenceMinor"), "differenceText": taxMoney(num(d, "differenceMinor")), "quotedAt": jstr(d, "quotedAt")})
	}
	nameAll(ctx, sc, rows, orderRef("orderId"))
	total := float64(len(rows))
	linesCompared, linesDiffering := num(report, "linesCompared"), num(report, "linesDiffering")
	mapped := jobj(nil)
	if mapErr == nil {
		mapped = completeness(codeRows)
	}
	out := jobj{"provider": provider, "providerLabel": taxProviderLabel(provider), "from": from, "to": to, "linesCompared": linesCompared, "linesDiffering": linesDiffering,
		"differingPct": pct(linesDiffering, linesCompared), "absoluteDifferenceMinor": num(report, "absoluteDifferenceMinor"), "netDifferenceMinor": num(report, "netDifferenceMinor"),
		"absoluteDifferenceText": taxMoney(num(report, "absoluteDifferenceMinor")), "netDifferenceText": taxMoney(num(report, "netDifferenceMinor")),
		"byCode": byCode, "byCategory": groups(catOrder, byCat, func(k string) string { return firstNonEmpty(names[k], domain.Humanize(k)) }),
		"byOutcome": groups(outOrder, byOutcome, taxOutcomeLabelOrUnknown), "byState": groups(stateOrder, states, func(k string) string { return k }),
		"kinds": kindCounts(kinds), "items": slicePage(rows, page, size), "total": len(rows), "page": page, "size": size, "partial": partial, "sampled": int(total),
		"shadowProvider": jstr(st, "shadowProvider"), "canConfigure": sc.access.Can(permTaxConfigure), "runbookPath": cutoverRunbook, "shadowOff": false}
	out["readiness"] = readiness(th, source, from, to, linesCompared, kinds, perCodeShort, len(byCode), mapped, partial)
	return out
}

func absf(f float64) float64 {
	if f < 0 {
		return -f
	}
	return f
}

func asObj(v any) jobj {
	m, _ := v.(map[string]any)
	return m
}

func taxOutcomeLabelOrUnknown(k string) string {
	if k == "UNKNOWN" {
		return "Not in the code map"
	}
	return taxOutcomeLabel(k)
}

func kindCounts(kinds map[string]int) []jobj {
	out := []jobj{}
	for _, k := range []string{"OUTCOME", "BOTH", "RATE", "NO_DECISION", "PROVIDER_ERROR"} {
		out = append(out, jobj{"kind": k, "label": shadowKinds[k], "count": kinds[k]})
	}
	return out
}

func readiness(th map[string]float64, source, from, to string, lines float64, kinds map[string]int, perCodeShort, codesSeen int, mapped jobj, partial bool) jobj {
	fromT, _ := time.Parse(time.DateOnly, from)
	toT, _ := time.Parse(time.DateOnly, to)
	days := int(toT.Sub(fromT).Hours()/24) + 1
	checks := []jobj{}
	add := func(key, title string, ok bool, detail string) {
		checks = append(checks, jobj{"key": key, "title": title, "ok": ok, "detail": detail})
	}
	add("window", "Long enough", float64(days) >= th["minDays"], "Compared "+strconv.Itoa(days)+" days; at least "+strconv.Itoa(int(th["minDays"]))+" needed (a full filing month).")
	add("volume", "Enough real orders", lines >= th["minLines"], strconv.Itoa(int(lines))+" order lines compared; at least "+strconv.Itoa(int(th["minLines"]))+" needed.")
	outcomeDiff := float64(kinds["OUTCOME"] + kinds["BOTH"] + kinds["NO_DECISION"])
	add("outcomes", "Same exempt or taxable answer", pct(outcomeDiff, lines) <= th["maxOutcomeDisagreementPct"] && !partial,
		strconv.Itoa(int(outcomeDiff))+" lines where the provider disagreed on exempt or taxable, or we had no decision. Each must be fixed (re-map, refine the code) or accepted in writing by the tax adviser.")
	add("errors", "Provider answers reliably", pct(float64(kinds["PROVIDER_ERROR"]), lines) <= th["maxProviderErrorPct"],
		strconv.Itoa(kinds["PROVIDER_ERROR"])+" lines the provider did not answer; at most "+strconv.FormatFloat(th["maxProviderErrorPct"], 'f', -1, 64)+"% allowed.")
	add("perCode", "Every outcome seen often enough", perCodeShort == 0, strconv.Itoa(perCodeShort)+" of "+strconv.Itoa(codesSeen)+" outcomes had fewer than "+strconv.Itoa(int(th["minLinesPerCode"]))+" lines.")
	if mapped != nil {
		add("codes", "Every code verified", boolean(mapped, "complete"), strconv.Itoa(intOf(mapped["unmapped"]))+" outcomes have no code and "+strconv.Itoa(intOf(mapped["unverified"]))+" are not verified.")
	}
	failing := 0
	for _, ch := range checks {
		if !boolean(ch, "ok") {
			failing++
		}
	}
	headline, ready := "Ready to cut over: every check below passes. The tax professional still signs off the cutover.", true
	if failing > 0 {
		ready = false
		headline = "Not ready to cut over: " + strconv.Itoa(failing) + " of " + strconv.Itoa(len(checks)) + " checks need work."
	}
	return jobj{"ready": ready, "headline": headline, "checks": checks, "thresholdSource": source,
		"thresholds": jobj{"minDays": th["minDays"], "minLines": th["minLines"], "minLinesPerCode": th["minLinesPerCode"],
			"maxOutcomeDisagreementPct": th["maxOutcomeDisagreementPct"], "maxProviderErrorPct": th["maxProviderErrorPct"]},
		"rateNote": "Rate differences are expected while local rates come from the ZIP table. Each one still has to be explained in the tax adviser's notes."}
}
