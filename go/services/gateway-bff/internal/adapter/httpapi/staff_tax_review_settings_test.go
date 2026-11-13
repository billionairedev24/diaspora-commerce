package httpapi_test

import (
	"strings"
	"testing"
)

// The audit sample tab and the tax review settings screen (docs/engineering/listing-rules.md D7). The mode is ALL until
// someone with tax:classify-admin changes it, names replace ids, and the system's own approvals read as the system.

func TestStaffTaxAuditSampleTab(t *testing.T) {
	f, do, _ := taxBFF(t)

	queue := do("admin", "GET", "/v1/staff/tax/reviews", nil, 200)
	values := []string{}
	for _, s := range queue["statuses"].([]any) {
		values = append(values, s.(map[string]any)["value"].(string))
	}
	if strings.Join(values, ",") != "PENDING_REVIEW,AUDIT_SAMPLE,APPROVED,REJECTED,STALE" {
		t.Fatalf("tabs = %v, want the Audit sample tab after the queue", values)
	}
	for _, it := range items(t, queue) {
		if it["atin"] == "AT-AUDIT" {
			t.Fatalf("an audit sample item is not in the review queue: %v", it)
		}
	}

	sample := do("admin", "GET", "/v1/staff/tax/reviews?status=AUDIT_SAMPLE", nil, 200)
	rows := items(t, sample)
	if len(rows) != 1 || rows[0]["atin"] != "AT-AUDIT" || sample["status"] != "AUDIT_SAMPLE" {
		t.Fatalf("audit sample = %v", sample)
	}
	row := rows[0]
	if row["auditSample"] != true || row["autoApproved"] != true {
		t.Fatalf("row flags = %v", row)
	}
	if row["reviewedByName"] != "Automatic approval (risk-based rule)" || row["classifiedByName"] != "Automatic approval (risk-based rule)" {
		t.Fatalf("the system must read as the system, not as an id or Unknown user: %v", row)
	}
	if row["categoryName"] != "Skincare & cosmetics" && !strings.Contains(row["categoryName"].(string), "kincare") {
		t.Fatalf("category by name: %v", row["categoryName"])
	}
	if !strings.Contains(row["why"].(string), "confirm it or move it") {
		t.Fatalf("why = %v", row["why"])
	}
	if got := strings.Join(f.calls[len(f.calls)-3:], " "); !strings.Contains(got, "auditSample=true") {
		t.Fatalf("tax-returns was not asked for the audit sample: %v", f.calls)
	}
	do("admin", "GET", "/v1/staff/tax/reviews?status=NOPE", nil, 422)

	sheet := do("admin", "GET", "/v1/staff/tax/reviews/AT-AUDIT", nil, 200)
	if sheet["canConfirm"] != true || sheet["canApprove"] != false || sheet["auditSample"] != true {
		t.Fatalf("an audit item is confirmed, not approved: %v", sheet)
	}
	trail := sheet["audit"].([]any)
	last := trail[0].(map[string]any)
	if last["actionLabel"] != "Put in the audit sample" || last["actorName"] != "Automatic approval (risk-based rule)" {
		t.Fatalf("audit trail = %v", trail)
	}
	if do("auditor", "GET", "/v1/staff/tax/reviews/AT-AUDIT", nil, 200)["canConfirm"] != false {
		t.Fatal("a reader cannot confirm")
	}

	do("admin", "POST", "/v1/staff/tax/reviews/AT-AUDIT/approve", map[string]any{"reason": "Checked the label and the answers"}, 200)
	if left := items(t, do("admin", "GET", "/v1/staff/tax/reviews?status=AUDIT_SAMPLE", nil, 200)); len(left) != 1 {
		// the fake keeps listing its single sample record; what matters is that tax-returns got the confirm
		t.Fatalf("unexpected sample list %v", left)
	}
	if f.bodies["POST /v1/tax/variant-classifications/AT-AUDIT/approve"]["reason"] != "Checked the label and the answers" {
		t.Fatalf("approve body = %v", f.bodies["POST /v1/tax/variant-classifications/AT-AUDIT/approve"])
	}
}

func TestStaffTaxReviewSettingsShowTheModeInForceAndNames(t *testing.T) {
	_, do, _ := taxBFF(t)

	v := do("admin", "GET", "/v1/staff/tax/review-settings", nil, 200)

	if v["mode"] != "ALL" || v["modeLabel"] != "Review every item" || v["canChange"] != true {
		t.Fatalf("default = %v", v)
	}
	if v["auditPercent"] != float64(5) {
		t.Fatalf("auditPercent = %v", v["auditPercent"])
	}
	if w := v["warning"].(string); !strings.Contains(w, "Review by risk is how tax review works by default") {
		t.Fatalf("warning = %q", w)
	}
	names := v["alwaysReviewNames"].([]any)
	if len(names) != 6 {
		t.Fatalf("always-review names = %v", names)
	}
	for _, n := range names {
		if strings.Contains(n.(string), "-") && strings.ToLower(n.(string)) == n.(string) {
			t.Fatalf("categories are shown by name, not code: %v", names)
		}
	}
	cats := v["categories"].([]any)
	if len(cats) != 73 {
		t.Fatalf("categories = %d", len(cats))
	}
	gated := 0
	for _, c := range cats {
		m := c.(map[string]any)
		if m["gated"] == true {
			gated++
			if m["gateLabel"] == "" {
				t.Fatalf("a gated category says why: %v", m)
			}
		}
	}
	if gated != 16 {
		t.Fatalf("gated categories = %d, want the 16 that are not ACTIVE", gated)
	}
	if len(v["alwaysReviewStatuses"].([]any)) != 4 {
		t.Fatalf("statuses = %v", v["alwaysReviewStatuses"])
	}
}

func TestStaffTaxReviewSettingsChange(t *testing.T) {
	f, do, raw := taxBFF(t)

	do("admin", "PUT", "/v1/staff/tax/review-settings", map[string]any{"mode": "RISK_BASED", "reason": "short"}, 422)
	if e := fieldErr(t, do("admin", "PUT", "/v1/staff/tax/review-settings", map[string]any{"mode": "RISK_BASED", "reason": "short"}, 422), "reason"); e == "" {
		t.Fatal("a reason of 10+ characters is required")
	}
	if fieldErr(t, do("admin", "PUT", "/v1/staff/tax/review-settings", map[string]any{"mode": "SOMETIMES", "reason": "Tax professional agreed"}, 422), "mode") == "" {
		t.Fatal("unknown mode")
	}
	if fieldErr(t, do("admin", "PUT", "/v1/staff/tax/review-settings", map[string]any{"mode": "RISK_BASED", "auditPercent": 101, "reason": "Tax professional agreed"}, 422), "auditPercent") == "" {
		t.Fatal("percentage out of range")
	}
	if fieldErr(t, do("admin", "PUT", "/v1/staff/tax/review-settings", map[string]any{"mode": "RISK_BASED", "auditPercent": 2.5, "reason": "Tax professional agreed"}, 422), "auditPercent") == "" {
		t.Fatal("percentage must be whole")
	}
	if f.review["mode"] != "ALL" {
		t.Fatal("nothing was sent upstream for refused changes")
	}
	for _, who := range []string{"auditor", "finance", "noperm"} {
		if rec := raw(who, "PUT", "/v1/staff/tax/review-settings", map[string]any{"mode": "RISK_BASED", "reason": "Tax professional agreed"}); rec.Code != 403 {
			t.Fatalf("%s changed the review mode: %d", who, rec.Code)
		}
	}

	v := do("admin", "PUT", "/v1/staff/tax/review-settings", map[string]any{"mode": "RISK_BASED", "auditPercent": 10,
		"alwaysReview": []string{"haircare", "gift-cards", "haircare"}, "reason": "Tax professional agreed, ref TX-7"}, 200)

	if v["mode"] != "RISK_BASED" || v["auditPercent"] != float64(10) || v["changedByName"] != "Ada Okafor" {
		t.Fatalf("changed = %v", v)
	}
	body := f.bodies["PUT /v1/tax/review-settings"]
	if list := body["alwaysReview"].([]any); len(list) != 2 {
		t.Fatalf("duplicates are dropped before they are sent: %v", list)
	}
	hist := v["history"].([]any)
	if len(hist) != 1 {
		t.Fatalf("history = %v", hist)
	}
	h := hist[0].(map[string]any)
	if h["changedByName"] != "Ada Okafor" || h["reason"] != "Tax professional agreed, ref TX-7" {
		t.Fatalf("history entry = %v", h)
	}
	if !strings.Contains(h["toLabel"].(string), "Review by risk, 10% audit sample, always review: ") || strings.Contains(h["toLabel"].(string), "haircare") {
		t.Fatalf("history is in words, with names: %v", h["toLabel"])
	}
	if h["fromLabel"] != "Review every item" {
		t.Fatalf("from = %v", h["fromLabel"])
	}
	if !strings.Contains(strings.Join(f.calls, "\n"), "PUT /v1/tax/review-settings") {
		t.Fatalf("calls = %v", f.calls)
	}
}
