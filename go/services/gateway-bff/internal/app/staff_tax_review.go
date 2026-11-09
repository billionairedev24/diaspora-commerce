package app

import (
	"context"
	"net/http"
	"net/url"
	"path"
	"slices"
	"strconv"
	"strings"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

// The tax review queue: product variants whose tax classification waits for a person (vendor supplies the facts,
// ADMIN TAX REVIEW approves the classification, then the product can be published). Decisions are the same records
// once they are approved or sent back, with who decided, when and under which rule version.

var reviewStatuses = []string{"PENDING_REVIEW", "APPROVED", "REJECTED", "STALE", "MISSING_FACTS", "DRAFT"}

func (s *staffTaxImpl) Reviews(ctx context.Context, c *domain.Caller, q url.Values) (any, error) {
	sc, err := s.need(ctx, c, "")
	if err != nil {
		return nil, err
	}
	page, size, err := pageOf(q, 25)
	if err != nil {
		return nil, err
	}
	status := strings.ToUpper(strings.TrimSpace(q.Get("status")))
	if status == "" {
		status = "PENDING_REVIEW"
	}
	if !slices.Contains(reviewStatuses, status) {
		return nil, domain.ValidationProblem(map[string]string{"status": "Choose a review status"})
	}
	up := url.Values{"status": {status}, "page": {strconv.Itoa(page)}, "size": {strconv.Itoa(size)}}
	for _, k := range []string{"categoryCode", "sellerOrgId", "q"} {
		if v := strings.TrimSpace(q.Get(k)); v != "" {
			up.Set(k, v)
		}
	}
	var raw any
	if err := sc.get(ctx, sc.up.TaxReturns, "/v1/tax/variant-classifications", up, &raw); err != nil {
		return nil, taxErr(err)
	}
	list := jpage(raw)
	names := s.categoryNames(ctx, sc)
	nameAll(ctx, sc, list.items, personRef("reviewedBy", "Decided by"), personRef("classifiedBy", "Submitted by"))
	for _, it := range list.items {
		nameAll(ctx, sc, objs(it, "decisions"), sellerRef("sellerOrgId"))
	}
	rows := make([]jobj, 0, len(list.items))
	for _, it := range list.items {
		rows = append(rows, reviewRow(it, names))
	}
	total := int64(len(rows))
	if list.total != nil {
		total = *list.total
	}
	counts := []jobj{}
	for _, st := range reviewStatuses[:4] {
		counts = append(counts, jobj{"value": st, "label": taxRecordStatus[st]})
	}
	return jobj{"items": rows, "total": total, "page": page, "size": size, "status": status, "statuses": counts,
		"canDecide": sc.access.Can(permTaxClassifyAdmin)}, nil
}

// categoryNames maps category codes to their names (one profile list read).
func (s *staffTaxImpl) categoryNames(ctx context.Context, sc staffCtx) map[string]string {
	var raw any
	out := map[string]string{}
	if err := sc.get(ctx, sc.up.TaxReturns, "/v1/tax/profiles", nil, &raw); err != nil {
		return out
	}
	for _, r := range jpage(raw).items {
		out[jstr(r, "categoryCode")] = jstr(r, "name")
	}
	return out
}

func sellerNames(it jobj) []string {
	out := []string{}
	for _, d := range objs(it, "decisions") {
		if n := jstr(d, "sellerOrgName"); n != "" && !slices.Contains(out, n) {
			out = append(out, n)
		}
	}
	return out
}

func proposedOf(it jobj) jobj {
	res, _ := it["resolution"].(map[string]any)
	if res == nil {
		return jobj{"status": "", "statusLabel": "No decision yet"}
	}
	status := jstr(res, "status")
	out := jobj{"status": status, "statusLabel": taxResolveStatus[status], "outcome": jstr(res, "outcome"), "outcomeLabel": "",
		"internalTaxCode": jstr(res, "internalTaxCode"), "rateDisplay": jstr(res, "rateDisplay"), "rowId": jstr(res, "rowId"),
		"reason": jstr(res, "reason"), "ruleVersion": jstr(res, "ruleVersion")}
	if o := jstr(res, "outcome"); o != "" {
		out["outcomeLabel"] = taxOutcomeLabel(o)
	}
	return out
}

func reviewRow(it jobj, names map[string]string) jobj {
	status := jstr(it, "status")
	cat := jstr(it, "categoryCode")
	return jobj{"atin": jstr(it, "atin"), "gtin": jstr(it, "gtin"), "title": jstr(it, "title"), "sellers": sellerNames(it), "categoryCode": cat,
		"categoryName": firstNonEmpty(names[cat], domain.Humanize(cat)), "status": status, "statusLabel": taxRecordStatus[status],
		"why": reviewWhy(it), "proposed": proposedOf(it), "classifiedAt": jstr(it, "classifiedAt"), "classifiedByName": jstr(it, "classifiedByName"),
		"reviewedAt": jstr(it, "reviewedAt"), "reviewedByName": jstr(it, "reviewedByName"), "ruleVersion": jstr(it, "ruleVersion")}
}

// reviewWhy says in plain words why this variant needs a person.
func reviewWhy(it jobj) string {
	res, _ := it["resolution"].(map[string]any)
	status := jstr(it, "status")
	switch status {
	case "STALE":
		return "The product's facts or category changed after it was approved, so the old decision no longer holds."
	case "REJECTED":
		return "Sent back to the seller. It returns here when they change it."
	case "MISSING_FACTS", "DRAFT":
		return "The seller still has to answer the tax questions."
	case "APPROVED":
		return "Approved: the stored decision is used at checkout."
	}
	switch jstr(res, "status") {
	case "NEEDS_APPROVAL":
		return "The category needs the Texas tax professional's sign-off before anything in it can go live."
	case "REFINE_CODE":
		return "The category uses a generic provider code. Verify the specific code for this product."
	case "CONFLICT_OPEN":
		return "The category waits on an open decision about which code applies."
	case "BLOCKED":
		return "As described this can't be sold: a mixed bundle must be itemised."
	}
	if n := len(objs(it, "evidence")); n > 0 {
		return "The seller claims a tax exemption and attached proof. Check the proof matches the product."
	}
	if audit := objs(it, "audit"); len(audit) > 0 && jstr(audit[len(audit)-1], "action") == "RECLASSIFIED" {
		return "Moved to this category. Check that the answers still fit."
	}
	return "New or changed listing: check the category, the answers and the tax decision."
}

func (s *staffTaxImpl) loadVariant(ctx context.Context, sc staffCtx, atin string) (jobj, error) {
	var o jobj
	if err := sc.get(ctx, sc.up.TaxReturns, "/v1/tax/variant-classifications/"+url.PathEscape(atin), nil, &o); err != nil {
		return nil, taxErr(err)
	}
	return o, nil
}

func (s *staffTaxImpl) Review(ctx context.Context, c *domain.Caller, atin string) (any, error) {
	sc, err := s.need(ctx, c, "")
	if err != nil {
		return nil, err
	}
	rec, err := s.loadVariant(ctx, sc, atin)
	if err != nil {
		return nil, err
	}
	return s.reviewView(ctx, sc, rec), nil
}

func (s *staffTaxImpl) reviewView(ctx context.Context, sc staffCtx, rec jobj) jobj {
	cat := jstr(rec, "categoryCode")
	profile, perr := s.loadProfile(ctx, sc, cat)
	facts := map[string]taxFact{}
	if perr == nil {
		facts = taxFactsOf(profile)
	}
	nameAll(ctx, sc, []jobj{rec}, personRef("reviewedBy", "Decided by"), personRef("classifiedBy", "Submitted by"))
	nameAll(ctx, sc, objs(rec, "decisions"), sellerRef("sellerOrgId"))
	nameAll(ctx, sc, objs(rec, "evidence"), personRef("uploadedBy", "Uploaded by"))
	nameAll(ctx, sc, objs(rec, "audit"), personRef("actor", "Person"))
	row := reviewRow(rec, map[string]string{cat: jstr(profile, "name")})
	proposed := proposedOf(rec)
	proposed["because"] = ""
	if perr == nil {
		for _, r := range objs(profile, "decisionTable") {
			if jstr(r, "id") == jstr(proposed, "rowId") {
				proposed["because"] = describeRow(r, facts)
			}
		}
	}
	profileStatus := jstr(profile, "status")
	status := jstr(rec, "status")
	canDecide := sc.access.Can(permTaxClassifyAdmin)
	view := jobj{"atin": row["atin"], "gtin": row["gtin"], "title": row["title"], "sellers": row["sellers"], "categoryCode": cat, "categoryName": row["categoryName"],
		"departmentName": taxDepartmentName(jstr(profile, "department")), "productTypeCode": jstr(rec, "productTypeCode"), "status": status, "statusLabel": row["statusLabel"], "why": row["why"],
		"profileStatus": profileStatus, "profileStatusLabel": taxGateLabel(profileStatus), "proposed": proposed,
		"facts": factValues(rec, facts, profile), "sellerListings": sellerListings(rec, facts), "evidence": evidenceView(rec, facts),
		"decision":         jobj{"reviewedByName": row["reviewedByName"], "reviewedAt": row["reviewedAt"], "ruleVersion": jstr(rec, "ruleVersion")},
		"classifiedByName": row["classifiedByName"], "classifiedAt": row["classifiedAt"], "audit": auditView(rec), "canDecide": canDecide,
		"canApprove": canDecide && (status == "PENDING_REVIEW" || status == "STALE"), "approve": approveForm(rec, profile),
		"refinedProviderCodes": orEmptyMap(rec["refinedProviderCodes"])}
	if v, ok := rec["ordersAffected"]; ok {
		view["ordersAffected"] = v
	}
	return view
}

func orEmptyMap(v any) any {
	if v == nil {
		return map[string]any{}
	}
	return v
}

func factValues(rec jobj, facts map[string]taxFact, profile jobj) []jobj {
	vals, _ := rec["facts"].(map[string]any)
	out := []jobj{}
	seen := map[string]bool{}
	add := func(id string) {
		v, ok := vals[id]
		if !ok || v == nil || seen[id] {
			return
		}
		seen[id] = true
		f, known := facts[id]
		if !known {
			f = taxFact{id: id, label: domain.Humanize(id), choices: map[string]string{}}
		}
		out = append(out, jobj{"id": id, "label": f.label, "value": f.valueText(v), "owner": taxOwners[ownerOf(profile, id)]})
	}
	for _, f := range objs(profile, "facts") {
		add(jstr(f, "id"))
	}
	ids := make([]string, 0, len(vals))
	for id := range vals {
		ids = append(ids, id)
	}
	slices.Sort(ids)
	for _, id := range ids {
		add(id)
	}
	return out
}

func ownerOf(profile jobj, id string) string {
	for _, f := range objs(profile, "facts") {
		if jstr(f, "id") == id {
			return jstr(f, "owner")
		}
	}
	return ""
}

func sellerListings(rec jobj, facts map[string]taxFact) []jobj {
	out := []jobj{}
	for _, d := range objs(rec, "decisions") {
		beh := []jobj{}
		vals, _ := d["sellerBehaviour"].(map[string]any)
		ids := make([]string, 0, len(vals))
		for id := range vals {
			ids = append(ids, id)
		}
		slices.Sort(ids)
		for _, id := range ids {
			f, ok := facts[id]
			if !ok {
				f = taxFact{id: id, label: domain.Humanize(id), choices: map[string]string{}}
			}
			beh = append(beh, jobj{"label": f.label, "value": f.valueText(vals[id])})
		}
		res, _ := d["resolution"].(map[string]any)
		out = append(out, jobj{"sellerName": firstNonEmpty(jstr(d, "sellerOrgName"), "Unknown seller"), "answers": beh, "outcomeLabel": taxOutcomeLabel(jstr(res, "outcome")),
			"statusLabel": taxResolveStatus[jstr(res, "status")]})
	}
	return out
}

var imageExt = []string{".png", ".jpg", ".jpeg", ".webp", ".gif"}

func evidenceView(rec jobj, facts map[string]taxFact) []jobj {
	out := []jobj{}
	for _, e := range objs(rec, "evidence") {
		name := firstNonEmpty(jstr(e, "fileName"), path.Base(jstr(e, "url")))
		label := facts[jstr(e, "factId")].label
		out = append(out, jobj{"factId": jstr(e, "factId"), "factLabel": firstNonEmpty(label, domain.Humanize(jstr(e, "factId"))), "fileName": name, "url": jstr(e, "url"),
			"isImage": slices.Contains(imageExt, strings.ToLower(path.Ext(name))), "uploadedByName": jstr(e, "uploadedByName"), "uploadedAt": jstr(e, "uploadedAt")})
	}
	return out
}

func auditView(rec jobj) []jobj {
	out := []jobj{}
	for _, a := range objs(rec, "audit") {
		out = append(out, jobj{"at": jstr(a, "at"), "actorName": jstr(a, "actorName"), "action": jstr(a, "action"),
			"actionLabel": firstNonEmpty(taxAuditActions[jstr(a, "action")], domain.Humanize(jstr(a, "action"))), "reason": jstr(a, "reason"), "ruleVersion": jstr(a, "ruleVersion")})
	}
	// Newest first.
	slices.Reverse(out)
	return out
}

// approveForm is what the approve sheet asks beyond the reason: the facts only the tax team sets and, for a category on
// a generic provider code, the verified specific code per provider.
func approveForm(rec, profile jobj) jobj {
	staff := []jobj{}
	vals, _ := rec["facts"].(map[string]any)
	for _, f := range objs(profile, "facts") {
		if jstr(f, "owner") != "STAFF" {
			continue
		}
		staff = append(staff, jobj{"id": jstr(f, "id"), "label": jstr(f, "label"), "kind": jstr(f, "kind"), "help": jstr(f, "help"), "value": vals[jstr(f, "id")]})
	}
	refine := jstr(profile, "status") == "REFINE_CODE"
	providers := []jobj{}
	if refine {
		have, _ := rec["refinedProviderCodes"].(map[string]any)
		for _, p := range []string{"STRIPE", "AVALARA"} {
			v, _ := have[p].(string)
			providers = append(providers, jobj{"provider": p, "label": taxProviderLabel(p), "value": v, "required": p == "STRIPE"})
		}
	}
	return jobj{"staffFacts": staff, "needsRefinedCode": refine, "refinedProviders": providers}
}

func (s *staffTaxImpl) ApproveReview(ctx context.Context, c *domain.Caller, atin string, body map[string]any) (any, error) {
	sc, err := s.need(ctx, c, permTaxClassifyAdmin)
	if err != nil {
		return nil, err
	}
	reason, err := taxReason(body, "reason")
	if err != nil {
		return nil, err
	}
	out := jobj{"reason": reason}
	if sf, ok := body["staffFacts"].(map[string]any); ok && len(sf) > 0 {
		out["staffFacts"] = sf
	}
	if rc, ok := body["refinedProviderCodes"].(map[string]any); ok {
		clean := map[string]any{}
		for p, v := range rc {
			if code, _ := v.(string); strings.TrimSpace(code) != "" {
				if verr := validProviderCode(p, strings.TrimSpace(code)); verr != "" {
					return nil, domain.FieldProblem(domain.CodeValidationFailed, "Validation failed", "refinedProviderCodes", verr)
				}
				clean[p] = strings.TrimSpace(code)
			}
		}
		if len(clean) > 0 {
			out["refinedProviderCodes"] = clean
		}
	}
	var rec jobj
	if err := sc.post(ctx, sc.up.TaxReturns, "/v1/tax/variant-classifications/"+url.PathEscape(atin)+"/approve", out, &rec); err != nil {
		return nil, taxErr(err)
	}
	return s.reviewView(ctx, sc, rec), nil
}

func (s *staffTaxImpl) ReclassifyReview(ctx context.Context, c *domain.Caller, atin string, body map[string]any) (any, error) {
	sc, err := s.need(ctx, c, permTaxClassifyAdmin)
	if err != nil {
		return nil, err
	}
	category := vs(body, "categoryCode")
	if category == "" {
		return nil, domain.FieldProblem(domain.CodeValidationFailed, "Validation failed", "categoryCode", "Choose the category it belongs in.")
	}
	reason, err := taxReason(body, "reason")
	if err != nil {
		return nil, err
	}
	out := jobj{"categoryCode": category, "reason": reason}
	if pt := vs(body, "productTypeCode"); pt != "" {
		out["productTypeCode"] = pt
	}
	var rec jobj
	if err := sc.post(ctx, sc.up.TaxReturns, "/v1/tax/variant-classifications/"+url.PathEscape(atin)+"/reclassify", out, &rec); err != nil {
		return nil, taxErr(err)
	}
	return s.reviewView(ctx, sc, rec), nil
}

// RequestInformation asks the seller for more before a decision. tax-returns owns the record and the seller's
// notification: the BFF relays to its request-information route (proposed addition to the contract; until it is live the
// call answers "temporarily unavailable" and nothing is stored here).
func (s *staffTaxImpl) RequestInformation(ctx context.Context, c *domain.Caller, atin string, body map[string]any) (any, error) {
	sc, err := s.need(ctx, c, permTaxClassifyAdmin)
	if err != nil {
		return nil, err
	}
	message, err := taxReason(body, "message")
	if err != nil {
		return nil, err
	}
	var rec jobj
	err = sc.call(ctx, sc.up.TaxReturns, port.StaffCall{Method: http.MethodPost, Path: "/v1/tax/variant-classifications/" + url.PathEscape(atin) + "/request-information",
		Body: jobj{"message": message, "reason": message}, IdempotencyKey: sc.idem}, &rec)
	if err != nil {
		return nil, taxErr(err)
	}
	return s.reviewView(ctx, sc, rec), nil
}
