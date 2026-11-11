package app

import (
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
	"net/url"
	"strings"
	"testing"
)

func appWithFlow(w jobj) jobj {
	return jobj{"id": "app-1", "status": "PENDING_REVIEW", "workflow": w}
}

func TestQueueTabsMapToSellerFilters(t *testing.T) {
	cases := map[string]string{"": "queue=ALL", "ALL": "queue=ALL", "MY": "queue=MY", "ESCALATED": "queue=ESCALATED", "RETURNED": "queue=RETURNED",
		"OVERDUE": "overdue=true&queue=ALL", "UNASSIGNED": "queue=ALL&unassigned=true"}
	for tab, want := range cases {
		v := url.Values{"status": {tab}}
		applicationQueueParams(tab, v)
		if got := v.Encode(); got != want {
			t.Errorf("tab %q -> %q, want %q", tab, got, want)
		}
	}
}

func TestRowShowsWhoHasItHowLongAndTheSLA(t *testing.T) {
	o := appWithFlow(jobj{"stage": "REVIEW", "holder": "with Amina Bello", "assigneeName": "Amina Bello", "waitingSeconds": float64(2 * 86400), "slaState": "OVERDUE", "escalationLevel": float64(1)})
	c := workflowCells(o)
	if c["with"] != "Amina Bello" || c["waiting"] != float64(172800) || c["sla"] != "Overdue" || c["escalation"] != "Level 1" {
		t.Fatalf("cells = %v", c)
	}
	pool := workflowCells(appWithFlow(jobj{"stage": "APPROVAL", "slaState": "ON_TIME", "waitingSeconds": float64(10800)}))
	if pool["with"] != "Any approver" {
		t.Fatalf("pool = %v", pool)
	}
	if got := stageLine(appWithFlow(jobj{"stage": "APPROVAL", "holder": "any approver", "waitingSeconds": float64(10800)})); got != "Awaiting approval · any approver · since 3 h" {
		t.Fatalf("stage line = %q", got)
	}
	if got := stageLine(appWithFlow(jobj{"stage": "REVIEW", "holder": "with Amina Bello", "waitingSeconds": float64(2 * 86400)})); got != "Awaiting review · with Amina Bello · since 2 days" {
		t.Fatalf("stage line = %q", got)
	}
	returned := workflowCells(jobj{"status": "INFO_REQUESTED", "workflow": jobj{}})
	if returned["with"] != "The applicant" {
		t.Fatalf("returned = %v", returned)
	}
}

func TestSentBackBannerNamesWhoWhenAndWhy(t *testing.T) {
	o := appWithFlow(jobj{"stage": "REVIEW", "returnedByName": "Adam Ade", "returnedAt": "2026-10-12T14:00:00Z", "returnNote": "The TIN was not checked", "returnCount": float64(2)})
	b := returnedBanner(o)
	if b == nil || !strings.Contains(b.Text, "Adam Ade") || !strings.Contains(b.Text, "The TIN was not checked") || !strings.Contains(b.Text, "2 times") {
		t.Fatalf("banner = %+v", b)
	}
	if returnedBanner(appWithFlow(jobj{"stage": "APPROVAL", "returnedByName": "Adam Ade"})) != nil {
		t.Fatal("the banner belongs to the review queue")
	}
}

func TestActionsFollowSellersFlagsNotTheViewersGuess(t *testing.T) {
	when := map[string]func(staffCtx, jobj) string{}
	for _, a := range applicationAssignmentActions() {
		when[a.key] = a.when
	}
	viewer := appWithFlow(jobj{"canClaim": false, "canAssign": false, "canRelease": false, "canEscalate": false})
	supervisor := appWithFlow(jobj{"canClaim": false, "canAssign": true, "canRelease": true, "canEscalate": true})
	for key, f := range when {
		if f(staffCtx{}, viewer) == "" {
			t.Errorf("%s allowed for a viewer who may not act", key)
		}
	}
	if when["assign"](staffCtx{}, supervisor) != "" || when["claim"](staffCtx{}, supervisor) == "" {
		t.Fatal("a supervisor assigns but cannot claim a case they may not hold")
	}
}

func TestReviewerAndApproverAreNamedFromTheTimelineWhenIdentityCannotNameThem(t *testing.T) {
	o := jobj{"reviewerId": "3d932142-fecb-49cf-80cf-1a5cd3300b0b", "finalApproverId": "4536b6e7-6c6d-45cb-b7d7-42acabb8e9e4", "timeline": []any{
		map[string]any{"action": "APPROVE", "actorId": "3d932142-fecb-49cf-80cf-1a5cd3300b0b", "actorName": "Rita Okoro"},
		map[string]any{"action": "FINAL_APPROVE", "actorId": "4536b6e7-6c6d-45cb-b7d7-42acabb8e9e4", "actorName": "Adam Ade"}}}
	nameFromTimeline(o)
	if o["reviewerName"] != "Rita Okoro" || o["finalApproverName"] != "Adam Ade" {
		t.Fatalf("names = %v / %v", o["reviewerName"], o["finalApproverName"])
	}
	act := timelineActivity(o)
	if len(act) != 2 || act[0].Actor != "Adam Ade" {
		t.Fatalf("activity = %+v", act)
	}
}

func TestUnresolvedPeopleReadUnknownUserWithAHintNeverTheUUID(t *testing.T) {
	r := &staffResource{names: []nameRef{personRef("reviewerId", "Reviewer")}}
	o := jobj{"reviewerId": "3d932142-fecb-49cf-80cf-1a5cd3300b0b", "reviewerName": "Unknown user"}
	secs := r.nameSections(o, []domain.DetailSection{{Title: "Details"}})
	var shown []string
	for _, s := range secs {
		if s.Collapsed {
			continue
		}
		for _, f := range s.Fields {
			shown = append(shown, f.Label+"="+f.Value)
		}
	}
	joined := strings.Join(shown, "|")
	if strings.Contains(joined, "3d932142") || !strings.Contains(joined, "Reviewer=Unknown user (") {
		t.Fatalf("visible = %s", joined)
	}
}

func TestSettingsFormKeepsLevelsInOrderAndNeedsAReason(t *testing.T) {
	f := reviewSettingsForm(jobj{"reviewSlaHours": float64(24), "autoAssign": "NONE"})
	var reason bool
	keys := map[string]bool{}
	for _, fld := range f.Fields {
		keys[fld.Key] = true
		reason = reason || (fld.Key == "reason" && fld.Required)
	}
	for _, k := range []string{"reviewSlaHours", "approvalSlaHours", "dueSoonPercent", "autoAssign", "reviewLevel1Role", "reviewLevel2Person", "approvalLevel1Role"} {
		if !keys[k] {
			t.Errorf("form lacks %s", k)
		}
	}
	if !reason {
		t.Fatal("a reason is required")
	}
}
