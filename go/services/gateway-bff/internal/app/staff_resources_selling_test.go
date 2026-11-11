package app

import (
	"strings"
	"testing"
)

func TestSellingResourcesAreRegisteredNextToApplications(t *testing.T) {
	all := staffResources()
	cases := all["selling-approvals"]
	if cases == nil || cases.module != all["applications"].module || cases.title != "Selling approvals" {
		t.Fatalf("selling-approvals = %+v", cases)
	}
	if all["seller-approvals"] == nil || all["seller-approvals"].module != "apps" {
		t.Fatal("seller-approvals missing from the apps module")
	}
	if r := all["selling-rules"]; r == nil || r.module != "catalog" || r.edit == nil || r.create == nil || r.edit.permission != "selling-rules:manage" {
		t.Fatalf("selling-rules = %+v", r)
	}
	if got, want := len(cases.facets), len(all["applications"].facets); got != want {
		t.Fatalf("the case type has the same queue tabs as applications: %d vs %d", got, want)
	}
}

func TestSellingCaseHasTheSameStepsAndAssignmentAsApplicationsPlusDisqualify(t *testing.T) {
	cases := staffResources()["selling-approvals"]
	have := map[string]staffAction{}
	for _, a := range cases.actions {
		have[a.key] = a
	}
	for _, k := range []string{"claim", "assign", "release", "escalate", "review-approve", "review-flag", "review-request-info", "final-approve", "final-request-info", "send-back", "reject", "disqualify"} {
		if _, ok := have[k]; !ok {
			t.Errorf("no %s action", k)
		}
	}
	if have["review-approve"].permission != "applications:review" || have["final-approve"].permission != "applications:approve" || have["assign"].permission != "applications:assign" {
		t.Errorf("permissions = %q %q %q", have["review-approve"].permission, have["final-approve"].permission, have["assign"].permission)
	}
	if why := have["review-approve"].when(staffCtx{}, jobj{"status": "AWAITING_FINAL_APPROVAL"}); why == "" {
		t.Error("step 1 is not possible after step 1")
	}
	if why := have["disqualify"].when(staffCtx{me: staffMe{ID: "stf-1"}}, jobj{"status": "AWAITING_FINAL_APPROVAL", "reviewerId": "stf-1"}); !strings.Contains(why, "different approver") {
		t.Errorf("the step 1 reviewer cannot disqualify: %q", why)
	}
	form := have["disqualify"].form(nil, staffCtx{}, jobj{})
	var date, note bool
	for _, f := range form.Fields {
		date = date || (f.Key == "reapplyAfter" && f.Type == "date" && f.Required)
		note = note || (f.Key == "note" && f.Required)
	}
	if !date || !note {
		t.Errorf("disqualification needs a reapply date and a message to the seller: %+v", form.Fields)
	}
}

func TestSellingCaseSheetNamesPeopleAndListsDocumentsWithTheirDates(t *testing.T) {
	o := jobj{"id": "sap_1", "reference": "SA-50001", "scopeLabel": "Dietary supplements", "scopeType": "CATEGORY", "orgName": "Kente Naturals", "applicantName": "Ada Obi",
		"status": "PENDING_REVIEW", "kind": "NEW", "source": "SELLER", "submissionCount": float64(1),
		"documents": []any{map[string]any{"kind": "PERMIT_LICENCE", "label": "Permit or licence (with its expiry date)", "fileName": "permit.pdf", "expiresOn": "2027-06-30", "assetId": "a1"}},
		"required":  []any{map[string]any{"kind": "PERMIT_LICENCE"}, map[string]any{"kind": "LIABILITY_INSURANCE", "label": "Liability insurance", "optional": false}},
		"workflow":  map[string]any{"stage": "REVIEW", "holder": "with Amina Bello", "assigneeName": "Amina Bello", "waitingSeconds": float64(7200), "slaState": "ON_TIME"}}
	text := ""
	for _, s := range sellingCaseSections(o) {
		for _, f := range s.Fields {
			text += s.Title + "|" + f.Label + "=" + displayValue(f.Value) + "\n"
		}
	}
	for _, want := range []string{"Who has it|With=Amina Bello", "Application|Seller=Kente Naturals", "Documents|Permit or licence (with its expiry date)=permit.pdf · expires 2027-06-30", "Documents|Liability insurance=Missing"} {
		if !strings.Contains(text, want) {
			t.Errorf("sheet lacks %q in:\n%s", want, text)
		}
	}
	if sellingKindWords(jobj{"source": "MIGRATION"}) != "From bakery flag" || sellingKindWords(jobj{"kind": "RENEWAL"}) != "Renewal" {
		t.Error("kind words")
	}
}
