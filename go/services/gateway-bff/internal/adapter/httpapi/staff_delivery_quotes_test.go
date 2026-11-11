package httpapi_test

import (
	"encoding/json"
	"net/http"
	"strings"
	"testing"
)

// Delivery quotes (Customer care): the queue tabs, who has each case, the decision with a reason, the settings and the
// permissions, all relayed from orders-service with names (never ids) on the screen.
func TestStaffDeliveryQuotes(t *testing.T) {
	f := &fakePlatform{payoutSt: "REQUESTED"}
	person := func(id, first string, perms ...string) map[string]any {
		return map[string]any{"id": id, "firstName": first, "lastName": "Test", "email": first + "@afrotransact.com", "principalType": "STAFF",
			"roles": []string{"platform.customer_support"}, "permissions": perms}
	}
	flags := func(can ...string) map[string]any {
		w := map[string]any{"stage": "QUOTE", "holder": "with Amina Bello", "assigneeId": "stf-9", "assigneeName": "Amina Bello", "assignedByName": "Sade Ojo",
			"assignedAt": "2026-11-10T10:00:00Z", "stageEnteredAt": "2026-11-10T09:00:00Z", "waitingSeconds": 7200, "slaState": "ON_TIME", "slaDueAt": "2026-11-11T15:00:00Z",
			"escalationLevel": 1, "escalatedAt": "2026-11-10T13:00:00Z", "escalationReason": "Overdue", "escalatedTo": []string{"Kofi Mensah"}}
		for _, c := range can {
			w["can"+c] = true
		}
		return w
	}
	quote := func(status, label string, workflow map[string]any) map[string]any {
		return map[string]any{"id": "q-1", "reference": "DQ-2026-AB12CD", "status": status, "statusLabel": label, "customerName": "Ada Obi", "place": "Marfa, TX 79843", "currency": "USD",
			"itemCount": 3, "contact": "EMAIL", "note": "Gate code 4821", "packages": []any{}, "lines": []any{map[string]any{"variantAtin": "ATIN-1", "title": "Jollof spice", "quantity": 3, "unitPriceMinor": 1500}},
			"workflow": workflow, "createdAt": "2026-11-10T09:00:00Z",
			"timeline": []any{map[string]any{"action": "REQUESTED", "actorName": "Ada Obi", "note": "Gate code 4821", "at": "2026-11-10T09:00:00Z"},
				map[string]any{"action": "CLAIMED", "actorName": "Amina Bello", "note": "Claimed by Amina Bello", "at": "2026-11-10T10:00:00Z"}}}
	}
	var agreeFailsWith string
	f.override = func(w http.ResponseWriter, r *http.Request, body map[string]any) bool {
		p := r.URL.Path
		token := strings.TrimPrefix(r.Header.Get("Authorization"), "Bearer ")
		switch {
		case p == "/v1/me" && token == "dq-agent":
			writeJ(w, 200, person("stf-9", "Amina", "delivery-quotes:view", "delivery-quotes:agree"))
		case p == "/v1/me" && token == "dq-viewer":
			writeJ(w, 200, person("stf-8", "Vera", "delivery-quotes:view"))
		case p == "/v1/me" && token == "dq-none":
			writeJ(w, 200, person("stf-7", "Nia", "orders:read"))
		case p == "/v1/staff/delivery-quotes" && r.Method == http.MethodGet:
			writeJ(w, 200, map[string]any{"items": []any{quote("IN_PROGRESS", "Being quoted", flags("Release", "Escalate", "Decide")),
				map[string]any{"id": "q-2", "reference": "DQ-2026-ZZ99YY", "status": "READY", "statusLabel": "Quote ready", "customerName": "Bayo Ade", "place": "Alpine, TX 79830",
					"itemCount": 1, "amountMinor": 2250, "workflow": map[string]any{}, "createdAt": "2026-11-09T09:00:00Z"}}, "page": 0, "size": 25, "total": 2})
		case p == "/v1/staff/delivery-quotes/q-1" && r.Method == http.MethodGet:
			writeJ(w, 200, quote("IN_PROGRESS", "Being quoted", flags("Release", "Escalate", "Decide", "Assign")))
		case p == "/v1/staff/delivery-quotes/q-1/assignment/candidates":
			writeJ(w, 200, []any{map[string]any{"id": "stf-9", "name": "Amina Bello"}, map[string]any{"id": "stf-6", "name": "Kofi Mensah"}})
		case p == "/v1/staff/delivery-quotes/q-1/agree" && r.Method == http.MethodPost:
			if agreeFailsWith != "" {
				problemDetail(w, 422, agreeFailsWith, "The amount must be between $5.00 and $500.00")
				return true
			}
			writeJ(w, 200, quote("READY", "Quote ready", map[string]any{}))
		case strings.HasPrefix(p, "/v1/staff/delivery-quotes/q-1/") && r.Method == http.MethodPost:
			writeJ(w, 200, quote("IN_PROGRESS", "Being quoted", flags("Release")))
		case p == "/v1/staff/delivery-quote-settings" && r.Method == http.MethodGet:
			writeJ(w, 200, map[string]any{"slaBusinessDays": 1, "dueSoonPercent": 75, "unassignedAfterHours": 4, "level2AfterHours": 24, "autoAssign": "NONE",
				"level1": map[string]any{"roleKey": nil, "userIds": []string{"stf-6"}}, "level2": map[string]any{"userIds": []string{}}, "level1People": []string{"Kofi Mensah"}, "level2People": []string{},
				"minAmountMinor": 500, "maxAmountMinor": 50000, "validityHours": 48, "requestsPerHour": 5, "duplicateWindowMinutes": 15,
				"changes": []any{map[string]any{"actorName": "Sade Ojo", "reason": "Kofi covers escalations", "changedAt": "2026-11-09T09:00:00Z"}}})
		case p == "/v1/staff/delivery-quote-settings" && r.Method == http.MethodPut:
			writeJ(w, 200, map[string]any{})
		default:
			return false
		}
		return true
	}
	raw := staffBFF(t, f)
	do := func(token, method, path string, body any, want int) map[string]any {
		t.Helper()
		rec := raw(token, method, path, body)
		if rec.Code != want {
			t.Fatalf("%s %s as %s = %d, want %d: %s", method, path, token, rec.Code, want, rec.Body.String())
		}
		if rec.Body.Len() == 0 || want >= 300 {
			return nil
		}
		return jsonOf(t, rec)
	}

	// The module is derived from the quote permissions: an agent and a viewer see it, someone with other permissions does not.
	hasModule := func(token string) bool {
		shell := do(token, "GET", "/v1/staff/session", nil, 200)
		return strings.Contains(toJSON(t, shell), `"deliveryQuotes"`)
	}
	if !hasModule("dq-agent") || !hasModule("dq-viewer") || hasModule("dq-none") {
		t.Fatalf("module visibility: agent %v viewer %v none %v", hasModule("dq-agent"), hasModule("dq-viewer"), hasModule("dq-none"))
	}
	do("dq-none", "GET", "/v1/staff/resources/delivery-quotes", nil, 403)

	// The queue: tabs, who has it, SLA, escalation and the charge, by name.
	list := do("dq-agent", "GET", "/v1/staff/resources/delivery-quotes?status=MY", nil, 200)
	if !strings.Contains(toJSON(t, list), "My queue") || !strings.Contains(toJSON(t, list), "Waiting on customer") {
		t.Fatalf("tabs = %v", list["facets"])
	}
	row := list["rows"].([]any)[0].(map[string]any)["cells"].(map[string]any)
	if row["with"] != "Amina Bello" || row["sla"] != "On time" || row["escalation"] != "Level 1" || row["status"] != "Being quoted" || row["place"] != "Marfa, TX 79843" {
		t.Fatalf("row = %v", row)
	}
	if q := f.bodies["GET /v1/staff/delivery-quotes"]; q != nil {
		t.Fatalf("unexpected body %v", q)
	}
	if !f.called("GET /v1/staff/delivery-quotes") {
		t.Fatal("orders was not asked for the queue")
	}
	ready := list["rows"].([]any)[1].(map[string]any)["cells"].(map[string]any)
	if ready["with"] != "The customer" || ready["amountMinor"] != float64(2250) || ready["status"] != "Quote ready" {
		t.Fatalf("ready row = %v", ready)
	}

	// The sheet: request, who has it, escalation, timeline with names and no ids.
	sheet := do("dq-agent", "GET", "/v1/staff/resources/delivery-quotes/q-1", nil, 200)
	text := toJSON(t, sheet)
	for _, want := range []string{"Ada Obi", "Marfa, TX 79843", "Gate code 4821", "Amina Bello", "Sade Ojo", "Kofi Mensah", "Jollof spice", "Claimed by Amina Bello"} {
		if !strings.Contains(text, want) {
			t.Errorf("sheet lacks %q: %s", want, text)
		}
	}
	// Nobody is shown as an id: the people on the sheet's fields and timeline are names.
	for _, sec := range sheet["sections"].([]any) {
		for _, fld := range sec.(map[string]any)["fields"].([]any) {
			if v, _ := fld.(map[string]any)["value"].(string); strings.HasPrefix(v, "stf-") {
				t.Errorf("a person id is shown: %v", fld)
			}
		}
	}

	// Actions follow the permission and the flags orders computed for the caller.
	actionKeys := func(sheet map[string]any) map[string]bool {
		out := map[string]bool{}
		for _, a := range sheet["actions"].([]any) {
			m := a.(map[string]any)
			out[m["key"].(string)] = m["allowed"] == true
		}
		return out
	}
	agent := actionKeys(sheet)
	if !agent["agree"] || !agent["decline"] || !agent["release"] || !agent["escalate"] || agent["claim"] {
		t.Fatalf("agent actions = %v", agent)
	}
	if v := actionKeys(do("dq-viewer", "GET", "/v1/staff/resources/delivery-quotes/q-1", nil, 200)); v["agree"] || v["decline"] || v["claim"] {
		t.Fatalf("a viewer may not act: %v", v)
	}
	do("dq-viewer", "POST", "/v1/staff/resources/delivery-quotes/q-1/actions/agree", map[string]any{"values": map[string]any{"amount": 18, "reason": "Rural route"}}, 403)

	// Agree: dollars become minor units, the reason and the person's name go to orders. A missing reason is refused here.
	do("dq-agent", "POST", "/v1/staff/resources/delivery-quotes/q-1/actions/agree", map[string]any{"values": map[string]any{"amount": 18.5}}, 422)
	do("dq-agent", "POST", "/v1/staff/resources/delivery-quotes/q-1/actions/agree", map[string]any{"values": map[string]any{"amount": 18.5, "reason": "Rural route, 140 miles"}}, 200)
	b := f.bodies["POST /v1/staff/delivery-quotes/q-1/agree"]
	if b["amountMinor"] != float64(1850) || b["reason"] != "Rural route, 140 miles" || b["actorName"] != "Amina Test" {
		t.Fatalf("agree body = %v", b)
	}
	agreeFailsWith = "orders.invalid-quote-amount"
	do("dq-agent", "POST", "/v1/staff/resources/delivery-quotes/q-1/actions/agree", map[string]any{"values": map[string]any{"amount": 1, "reason": "Too cheap to be true"}}, 422)
	agreeFailsWith = ""

	do("dq-agent", "POST", "/v1/staff/resources/delivery-quotes/q-1/actions/decline", map[string]any{"values": map[string]any{"reason": "We do not run there"}}, 200)
	if b := f.bodies["POST /v1/staff/delivery-quotes/q-1/decline"]; b["reason"] != "We do not run there" || b["actorName"] != "Amina Test" {
		t.Fatalf("decline body = %v", b)
	}
	do("dq-agent", "POST", "/v1/staff/resources/delivery-quotes/q-1/actions/escalate", map[string]any{"values": map[string]any{"reason": "Customer is waiting"}}, 200)
	if b := f.bodies["POST /v1/staff/delivery-quotes/q-1/assignment"]; b["action"] != "ESCALATE" || b["reason"] != "Customer is waiting" {
		t.Fatalf("escalate body = %v", b)
	}

	// Settings: anyone who views reads them; changing needs delivery-quotes:assign.
	settings := do("dq-viewer", "GET", "/v1/staff/resources/delivery-quote-settings/current", nil, 200)
	if !strings.Contains(toJSON(t, settings), "Kofi covers escalations") || !strings.Contains(toJSON(t, settings), "1 business day") {
		t.Fatalf("settings = %v", settings)
	}
	do("dq-agent", "PATCH", "/v1/staff/resources/delivery-quote-settings/current", map[string]any{"values": map[string]any{"slaBusinessDays": 2, "reason": "Busy week for the team"}}, 403)
}

func toJSON(t *testing.T, v any) string {
	t.Helper()
	raw, err := json.Marshal(v)
	if err != nil {
		t.Fatal(err)
	}
	return string(raw)
}
