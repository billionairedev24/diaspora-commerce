package app

import (
	"context"
	"encoding/csv"
	"net/http"
	"net/url"
	"regexp"
	"strconv"
	"strings"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

// Provider code maps (category-tax-design.md section 3.4): for each internal outcome code, the code a provider uses for
// it. The map is per outcome, never per category, because one provider code can carry different outcomes. A mapping
// counts only once a person has verified it; an unmapped or unverified code for the provider in use blocks the line at
// checkout, so the completeness meter counts exactly those.

var (
	stripeCodeRe  = regexp.MustCompile(`^txcd_[0-9]{8}$`)
	avalaraCodeRe = regexp.MustCompile(`^[A-Za-z0-9._-]{1,25}$`)
)

const (
	maxImportRows  = 500
	maxImportBytes = 256 * 1024
)

// validProviderCode returns "" when the code looks like one of that provider's, else what is wrong.
func validProviderCode(provider, code string) string {
	switch provider {
	case "STRIPE":
		if !stripeCodeRe.MatchString(code) {
			return "A Stripe Tax code looks like txcd_40040016."
		}
	case "AVALARA":
		if !avalaraCodeRe.MatchString(code) {
			return "An Avalara tax code is letters and digits, up to 25 characters, e.g. PF050099."
		}
	default:
		return "Choose Stripe or Avalara."
	}
	return ""
}

func validProvider(p string) bool { return p == "STRIPE" || p == "AVALARA" }

// codeState classifies one map row.
func codeState(e jobj) string {
	switch {
	case jstr(e, "outcome") == "BLOCKED":
		return "NOT_NEEDED"
	case jstr(e, "providerCode") == "":
		return "UNMAPPED"
	case !boolean(e, "verified"):
		return "UNVERIFIED"
	}
	return "VERIFIED"
}

var codeStateLabels = map[string]string{
	"NOT_NEEDED": "Not needed (never sold)", "UNMAPPED": "No code yet", "UNVERIFIED": "Code set, not verified", "VERIFIED": "Verified",
}

// outcomeName turns AT.NUTS_SEEDS.PLAIN_MULTI_SERVING_2_5OZ_PLUS into "Plain multi serving 2 5oz plus".
func outcomeName(code string) string {
	i := strings.LastIndex(code, ".")
	return domain.Humanize(strings.ToLower(code[i+1:]))
}

func (s *staffTaxImpl) providerMap(ctx context.Context, sc staffCtx, provider string) ([]jobj, error) {
	var raw any
	if err := sc.get(ctx, sc.up.TaxReturns, "/v1/tax/provider-code-map", url.Values{"provider": {provider}}, &raw); err != nil {
		return nil, taxErr(err)
	}
	rows := jpage(raw).items
	names := s.categoryNames(ctx, sc)
	for _, e := range rows {
		if jstr(e, "provider") == "" {
			e["provider"] = provider
		}
		st := codeState(e)
		e["state"], e["stateLabel"] = st, codeStateLabels[st]
		e["outcomeLabel"] = taxOutcomeLabel(jstr(e, "outcome"))
		e["outcomeName"] = outcomeName(jstr(e, "internalTaxCode"))
		e["categoryName"] = firstNonEmpty(names[jstr(e, "categoryCode")], domain.Humanize(jstr(e, "categoryCode")))
		e["verified"] = boolean(e, "verified")
		if e["providerCode"] == nil {
			e["providerCode"] = ""
		}
	}
	return rows, nil
}

// completeness counts the outcomes a provider needs a verified code for.
func completeness(rows []jobj) jobj {
	needed, mapped, verified := 0, 0, 0
	for _, e := range rows {
		switch jstr(e, "state") {
		case "NOT_NEEDED":
			continue
		case "VERIFIED":
			verified++
			mapped++
		case "UNVERIFIED":
			mapped++
		}
		needed++
	}
	pct := 100
	if needed > 0 {
		pct = verified * 100 / needed
	}
	return jobj{"needed": needed, "mapped": mapped, "verified": verified, "unmapped": needed - mapped, "unverified": mapped - verified, "percent": pct,
		"complete": needed > 0 && verified == needed}
}

func providerOfMethod(method string) string {
	switch method {
	case "STRIPE_TAX":
		return "STRIPE"
	case "AVALARA":
		return "AVALARA"
	}
	return ""
}

// activeProvider is the provider whose codes matter now: the one charging, else the one in SHADOW.
func activeProvider(settings jobj) string {
	if p := providerOfMethod(jstr(settings, "method")); p != "" {
		return p
	}
	return jstr(settings, "shadowProvider")
}

func (s *staffTaxImpl) settings(ctx context.Context, sc staffCtx) (jobj, error) {
	var st jobj
	if err := sc.get(ctx, sc.up.TaxReturns, "/v1/tax/settings", nil, &st); err != nil {
		return nil, taxErr(err)
	}
	return st, nil
}

func (s *staffTaxImpl) CodeMap(ctx context.Context, c *domain.Caller, q url.Values) (any, error) {
	sc, err := s.need(ctx, c, "")
	if err != nil {
		return nil, err
	}
	active := ""
	if st, serr := s.settings(ctx, sc); serr == nil {
		active = activeProvider(st)
	}
	provider := strings.ToUpper(strings.TrimSpace(q.Get("provider")))
	if provider == "" {
		provider = firstNonEmpty(active, "STRIPE")
	}
	if !validProvider(provider) {
		return nil, domain.ValidationProblem(map[string]string{"provider": "Choose Stripe or Avalara"})
	}
	page, size, err := pageOf(q, 50)
	if err != nil {
		return nil, err
	}
	all, err := s.providerMap(ctx, sc, provider)
	if err != nil {
		return nil, err
	}
	state, cat := strings.ToUpper(q.Get("state")), strings.TrimSpace(q.Get("categoryCode"))
	if q.Get("unmappedOnly") == "true" {
		state = "UNMAPPED"
	}
	terms := strings.Fields(strings.ToLower(q.Get("q")))
	rows := make([]jobj, 0, len(all))
	for _, e := range all {
		if (state != "" && jstr(e, "state") != state) || (cat != "" && jstr(e, "categoryCode") != cat) {
			continue
		}
		if containsFold(strings.Join([]string{jstr(e, "internalTaxCode"), jstr(e, "providerCode"), jstr(e, "categoryName"), jstr(e, "outcomeName")}, " "), terms...) {
			rows = append(rows, e)
		}
	}
	if err := sortBy(rows, q["sort"], "internalTaxCode", "categoryName", "outcomeLabel", "providerCode", "stateLabel"); err != nil {
		return nil, err
	}
	nameAll(ctx, sc, slicePage(rows, page, size), personRef("changedBy", "Changed by"))
	cats := map[string]string{}
	for _, e := range all {
		cats[jstr(e, "categoryCode")] = jstr(e, "categoryName")
	}
	return jobj{"provider": provider, "providerLabel": taxProviderLabel(provider), "activeProvider": active, "items": slicePage(rows, page, size), "total": len(rows),
		"page": page, "size": size, "completeness": completeness(all), "canEdit": sc.access.Can(permTaxClassifyAdmin),
		"states": []jobj{{"value": "UNMAPPED", "label": codeStateLabels["UNMAPPED"]}, {"value": "UNVERIFIED", "label": codeStateLabels["UNVERIFIED"]},
			{"value": "VERIFIED", "label": codeStateLabels["VERIFIED"]}, {"value": "NOT_NEEDED", "label": codeStateLabels["NOT_NEEDED"]}}}, nil
}

// codeUpdate validates one change and returns the upstream body.
func codeUpdate(provider, code string, verified bool, reason string) (jobj, error) {
	if !validProvider(provider) {
		return nil, domain.FieldProblem(domain.CodeValidationFailed, "Validation failed", "provider", "Choose Stripe or Avalara.")
	}
	body := jobj{"provider": provider, "verified": verified, "reason": reason}
	if code == "" {
		if verified {
			return nil, domain.FieldProblem(domain.CodeValidationFailed, "Validation failed", "verified", "A code that is not set can't be verified.")
		}
		body["providerCode"] = nil
		return body, nil
	}
	if msg := validProviderCode(provider, code); msg != "" {
		return nil, domain.FieldProblem(domain.CodeValidationFailed, "Validation failed", "providerCode", msg)
	}
	body["providerCode"] = code
	return body, nil
}

func (s *staffTaxImpl) putCode(ctx context.Context, sc staffCtx, internal string, body jobj) (jobj, error) {
	var out jobj
	err := sc.call(ctx, sc.up.TaxReturns, port.StaffCall{Method: http.MethodPut, Path: "/v1/tax/provider-code-map/" + url.PathEscape(internal), Body: body}, &out)
	return out, taxErr(err)
}

func (s *staffTaxImpl) UpdateCode(ctx context.Context, c *domain.Caller, internal string, body map[string]any) (any, error) {
	sc, err := s.need(ctx, c, permTaxClassifyAdmin)
	if err != nil {
		return nil, err
	}
	reason, err := taxReason(body, "reason")
	if err != nil {
		return nil, err
	}
	up, err := codeUpdate(strings.ToUpper(vs(body, "provider")), vs(body, "providerCode"), vb(body, "verified"), reason)
	if err != nil {
		return nil, err
	}
	out, err := s.putCode(ctx, sc, internal, up)
	if err != nil {
		return nil, err
	}
	nameAll(ctx, sc, []jobj{out}, personRef("changedBy", "Changed by"))
	st := codeState(out)
	out["state"], out["stateLabel"] = st, codeStateLabels[st]
	out["outcomeLabel"] = taxOutcomeLabel(jstr(out, "outcome"))
	out["outcomeName"] = outcomeName(jstr(out, "internalTaxCode"))
	return out, nil
}

// ---------------------------------------------------------------- CSV

var codeMapHeader = []string{"internal_tax_code", "category", "outcome", "provider", "provider_code", "verified"}

func (s *staffTaxImpl) ExportCodeMap(ctx context.Context, c *domain.Caller, provider string) (any, error) {
	sc, err := s.need(ctx, c, "")
	if err != nil {
		return nil, err
	}
	provider = strings.ToUpper(strings.TrimSpace(provider))
	if !validProvider(provider) {
		return nil, domain.ValidationProblem(map[string]string{"provider": "Choose Stripe or Avalara"})
	}
	rows, err := s.providerMap(ctx, sc, provider)
	if err != nil {
		return nil, err
	}
	var b strings.Builder
	w := csv.NewWriter(&b)
	_ = w.Write(codeMapHeader)
	for _, e := range rows {
		_ = w.Write([]string{csvSafe(jstr(e, "internalTaxCode")), csvSafe(jstr(e, "categoryName")), jstr(e, "outcome"), provider, csvSafe(jstr(e, "providerCode")), strconv.FormatBool(boolean(e, "verified"))})
	}
	w.Flush()
	return jobj{"filename": "tax-code-map-" + strings.ToLower(provider) + ".csv", "csv": b.String(), "rows": len(rows)}, nil
}

// csvSafe keeps a spreadsheet from running a cell as a formula.
func csvSafe(s string) string {
	if s != "" && strings.ContainsAny(s[:1], "=+-@\t\r") {
		return "'" + s
	}
	return s
}

type importLine struct {
	line     int
	internal string
	code     string
	verified bool
}

func parseCodeCSV(text string) ([]importLine, []jobj, error) {
	r := csv.NewReader(strings.NewReader(text))
	r.FieldsPerRecord = -1
	r.TrimLeadingSpace = true
	recs, err := r.ReadAll()
	if err != nil {
		return nil, nil, domain.FieldProblem(domain.CodeValidationFailed, "Validation failed", "csv", "That isn't a readable CSV file: "+err.Error())
	}
	if len(recs) < 2 {
		return nil, nil, domain.FieldProblem(domain.CodeValidationFailed, "Validation failed", "csv", "The file needs a header row and at least one code.")
	}
	idx := map[string]int{}
	for i, h := range recs[0] {
		idx[strings.ToLower(strings.TrimSpace(strings.TrimPrefix(h, "\ufeff")))] = i
	}
	ci, ok1 := idx["internal_tax_code"]
	pi, ok2 := idx["provider_code"]
	if !ok1 || !ok2 {
		return nil, nil, domain.FieldProblem(domain.CodeValidationFailed, "Validation failed", "csv", "The header needs the columns internal_tax_code and provider_code (verified is optional).")
	}
	vi, hasV := idx["verified"]
	if len(recs)-1 > maxImportRows {
		return nil, nil, domain.FieldProblem(domain.CodeValidationFailed, "Validation failed", "csv", "At most "+strconv.Itoa(maxImportRows)+" rows per file.")
	}
	lines, problems := []importLine{}, []jobj{}
	for i, rec := range recs[1:] {
		n := i + 2
		cell := func(j int) string {
			if j < len(rec) {
				return strings.TrimSpace(strings.TrimPrefix(rec[j], "'"))
			}
			return ""
		}
		l := importLine{line: n, internal: cell(ci), code: cell(pi)}
		if l.internal == "" {
			problems = append(problems, jobj{"line": n, "message": "No internal tax code."})
			continue
		}
		if hasV {
			switch strings.ToLower(cell(vi)) {
			case "true", "yes", "1", "y":
				l.verified = true
			case "", "false", "no", "0", "n":
			default:
				problems = append(problems, jobj{"line": n, "internalTaxCode": l.internal, "message": `Verified must be true or false, not "` + cell(vi) + `".`})
				continue
			}
		}
		lines = append(lines, l)
	}
	return lines, problems, nil
}

func (s *staffTaxImpl) ImportCodeMap(ctx context.Context, c *domain.Caller, body map[string]any) (any, error) {
	sc, err := s.need(ctx, c, permTaxClassifyAdmin)
	if err != nil {
		return nil, err
	}
	provider := strings.ToUpper(vs(body, "provider"))
	if !validProvider(provider) {
		return nil, domain.FieldProblem(domain.CodeValidationFailed, "Validation failed", "provider", "Choose Stripe or Avalara.")
	}
	text, _ := body["csv"].(string)
	if strings.TrimSpace(text) == "" {
		return nil, domain.FieldProblem(domain.CodeValidationFailed, "Validation failed", "csv", "Choose a CSV file.")
	}
	if len(text) > maxImportBytes {
		return nil, domain.FieldProblem(domain.CodeValidationFailed, "Validation failed", "csv", "The file is too large.")
	}
	dryRun := vb(body, "dryRun")
	reason := ""
	if !dryRun {
		if reason, err = taxReason(body, "reason"); err != nil {
			return nil, err
		}
	}
	lines, problems, err := parseCodeCSV(text)
	if err != nil {
		return nil, err
	}
	current, err := s.providerMap(ctx, sc, provider)
	if err != nil {
		return nil, err
	}
	byCode := map[string]jobj{}
	for _, e := range current {
		byCode[jstr(e, "internalTaxCode")] = e
	}
	type change struct {
		line   importLine
		update jobj
	}
	changes, unchanged := []change{}, 0
	preview := []jobj{}
	seen := map[string]bool{}
	for _, l := range lines {
		cur, known := byCode[l.internal]
		switch {
		case !known:
			problems = append(problems, jobj{"line": l.line, "internalTaxCode": l.internal, "message": "No such internal tax code."})
			continue
		case seen[l.internal]:
			problems = append(problems, jobj{"line": l.line, "internalTaxCode": l.internal, "message": "This code appears twice in the file."})
			continue
		}
		seen[l.internal] = true
		if jstr(cur, "providerCode") == l.code && boolean(cur, "verified") == l.verified {
			unchanged++
			continue
		}
		up, uerr := codeUpdate(provider, l.code, l.verified, firstNonEmpty(reason, "preview"))
		if uerr != nil {
			msg := "Not valid."
			if p, ok := domain.AsProblem(uerr); ok && len(p.Errors) > 0 {
				for _, m := range p.Errors {
					msg = m
				}
			}
			problems = append(problems, jobj{"line": l.line, "internalTaxCode": l.internal, "message": msg})
			continue
		}
		changes = append(changes, change{l, up})
		preview = append(preview, jobj{"line": l.line, "internalTaxCode": l.internal, "from": jstr(cur, "providerCode"), "to": l.code, "verified": l.verified,
			"fromVerified": boolean(cur, "verified")})
	}
	out := jobj{"provider": provider, "rows": len(lines), "changed": len(changes), "unchanged": unchanged, "problems": problems, "preview": slicePreview(preview), "applied": 0,
		"dryRun": dryRun}
	if dryRun || len(problems) > 0 {
		// One bad row stops the whole import so a file is never half applied.
		out["dryRun"] = dryRun
		out["blocked"] = !dryRun && len(problems) > 0
		return out, nil
	}
	applied := 0
	failed := []jobj{}
	for _, ch := range changes {
		if _, perr := s.putCode(ctx, sc, ch.line.internal, ch.update); perr != nil {
			failed = append(failed, jobj{"line": ch.line.line, "internalTaxCode": ch.line.internal, "message": taxProblemText(perr)})
			continue
		}
		applied++
	}
	out["applied"], out["problems"] = applied, failed
	return out, nil
}

func slicePreview(rows []jobj) []jobj { return rows[:min(len(rows), 100)] }

func taxProblemText(err error) string {
	if p, ok := domain.AsProblem(err); ok {
		return firstNonEmpty(p.Detail, p.Title)
	}
	return "Not saved."
}
