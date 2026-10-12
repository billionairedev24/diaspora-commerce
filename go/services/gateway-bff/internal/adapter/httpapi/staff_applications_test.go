package httpapi_test

import (
	"encoding/json"
	"net/http"
	"strings"
	"sync"
	"testing"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
)

// fakeReview is seller's two-step review for one application: its status, and how the next decision is answered.
type fakeReview struct {
	mu        sync.Mutex
	status    string
	answer    func(w http.ResponseWriter) // nil: apply the decision and answer 200
	decisions []map[string]any
	keys      []string
}

func (s *fakeReview) serve(w http.ResponseWriter, r *http.Request, body map[string]any) bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	p := r.URL.Path
	if !strings.HasPrefix(p, "/v1/applications/app-7") {
		return false
	}
	if r.Method == http.MethodPost {
		s.decisions = append(s.decisions, body)
		s.keys = append(s.keys, r.Header.Get("Idempotency-Key"))
		if s.answer != nil {
			s.answer(w)
			return true
		}
		switch body["action"] {
		case "APPROVE":
			s.status = "AWAITING_FINAL_APPROVAL"
		case "FINAL_APPROVE":
			s.status = "APPROVED"
		}
	}
	writeJ(w, 200, map[string]any{"id": "app-7", "reference": "APP-30200", "kind": "SELLER", "status": s.status, "reviewerId": "stf-9",
		"business": map[string]any{"storeName": "Kente Naturals"}})
	return true
}

func (s *fakeReview) set(status string, answer func(w http.ResponseWriter)) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.status, s.answer = status, answer
}

func (s *fakeReview) posts() int {
	s.mu.Lock()
	defer s.mu.Unlock()
	return len(s.decisions)
}

func alreadyApproved(w http.ResponseWriter) {
	w.Header().Set("Content-Type", "application/problem+json")
	w.WriteHeader(http.StatusConflict)
	_ = json.NewEncoder(w).Encode(map[string]any{"type": "about:blank", "title": "Already decided", "status": 409, "code": "seller.already-decided",
		"detail": "This application is already approved (by Ada Obi, 12 Oct 2026, 14:03 UTC)", "decision": "APPROVED", "decidedBy": "Ada Obi"})
}

func actionOf(t *testing.T, sheet map[string]any, key string) map[string]any {
	t.Helper()
	for _, a := range sheet["actions"].([]any) {
		if m := a.(map[string]any); m["key"] == key {
			return m
		}
	}
	t.Fatalf("no action %s in %v", key, sheet["actions"])
	return nil
}

// A final approval relays the console's Idempotency-Key and the approver's name; a repeat on an approved application
// goes to seller (which owns the rules) and seller's "already approved" answer is ALREADY_DONE with its detail, while
// actions that are simply not possible yet are refused by the BFF without calling seller.
func TestApplicationDecisionsAreIdempotentAndAlreadyDoneIsInformation(t *testing.T) {
	rev := &fakeReview{status: "AWAITING_FINAL_APPROVAL"}
	do := staffBFF(t, &fakePlatform{override: rev.serve})
	final := map[string]any{"values": map[string]any{"reasonCode": "DOCUMENTS_VERIFIED"}}
	const path = "/v1/staff/resources/applications/app-7"

	sheet := jsonOf(t, do("admin", "GET", path, nil))
	if a := actionOf(t, sheet, "final-approve"); a["allowed"] != true {
		t.Fatalf("final approval by a second person: %v", a)
	}
	rec := do("admin", "POST", path+"/actions/final-approve", final, "Idempotency-Key", "final-7-0123456789abcdef")
	if rec.Code != 200 {
		t.Fatalf("final approve = %d %s", rec.Code, rec.Body.String())
	}
	approved := jsonOf(t, rec)
	if approved["status"] != "APPROVED" || actionOf(t, approved, "final-approve")["allowed"] != false {
		t.Fatalf("approved sheet = %v", approved)
	}
	if got := rev.decisions[0]; got["action"] != "FINAL_APPROVE" || got["actorName"] != "Ifeoma Okafor" || got["reasonCode"] != "DOCUMENTS_VERIFIED" {
		t.Fatalf("decision body = %v", got)
	}
	if rev.keys[0] != "final-7-0123456789abcdef" {
		t.Fatalf("Idempotency-Key relayed = %q", rev.keys[0])
	}

	// A stale sheet (or a second tab) repeats it: seller replays the same person's decision → 200.
	if rec := do("admin", "POST", path+"/actions/final-approve", final, "Idempotency-Key", "final-7-fedcba9876543210"); rec.Code != 200 {
		t.Fatalf("repeat by the approver = %d %s", rec.Code, rec.Body.String())
	}
	// Seller says someone already approved it: information, with who and when.
	rev.set("APPROVED", alreadyApproved)
	for _, action := range []string{"final-approve", "reject", "review-approve"} {
		values := map[string]any{"values": map[string]any{"reasonCode": "DOCUMENTS_VERIFIED", "note": "n"}}
		rec := do("admin", "POST", path+"/actions/"+action, values, "Idempotency-Key", "k-"+action+"-0123456789")
		pr := jsonOf(t, rec)
		if rec.Code != 409 || pr["code"] != domain.CodeAlreadyDone || pr["detail"] != "This application is already approved (by Ada Obi, 12 Oct 2026, 14:03 UTC)" {
			t.Fatalf("%s on an approved application = %d %v", action, rec.Code, pr)
		}
	}

	// Not possible yet: the BFF refuses without calling seller, and never says it needs a reviewer decision when it is
	// already decided.
	rev.set("PENDING_REVIEW", nil)
	before := rev.posts()
	rec = do("admin", "POST", path+"/actions/final-approve", final)
	if pr := jsonOf(t, rec); rec.Code != 409 || pr["code"] != domain.CodeConflict {
		t.Fatalf("final approve while pending review = %d %v", rec.Code, pr)
	}
	if rev.posts() != before {
		t.Fatal("seller was called for an action that is not possible yet")
	}

	// Seller's state machine: step 1 only while pending review; step 2 while awaiting final approval or flagged.
	rev.set("FLAGGED", nil)
	flagged := jsonOf(t, do("admin", "GET", path, nil))
	if actionOf(t, flagged, "review-approve")["allowed"] != false || actionOf(t, flagged, "review-request-info")["allowed"] != false {
		t.Fatalf("step 1 on a flagged application: %v", flagged["actions"])
	}
	for _, key := range []string{"final-approve", "send-back", "reject"} {
		if actionOf(t, flagged, key)["allowed"] != true {
			t.Fatalf("%s on a flagged application: %v", key, actionOf(t, flagged, key))
		}
	}
}
