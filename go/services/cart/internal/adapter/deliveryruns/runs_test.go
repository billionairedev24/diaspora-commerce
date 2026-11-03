package deliveryruns

import (
	"context"
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestOpenRunsReadsThePublicAvailableList(t *testing.T) {
	var gotPath, gotQuery string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotPath, gotQuery = r.URL.Path, r.URL.RawQuery
		_, _ = w.Write([]byte(`{"runs":[
		 {"runId":"r1","town":"Bastrop","cutoffAt":"2026-11-03T10:00:00Z","deliversOn":"2026-11-05","timeZone":"America/Chicago",
		  "feeMinor":1299,"currency":"USD","dropMode":"DOORSTEP","acceptingOrders":true,"full":false,"minOrders":10,"ordersSoFar":7,
		  "feeRule":{"method":"AFROTRANSACT_DELIVERY","ruleVersion":3,"amountMinor":1299}},
		 {"runId":"r2","cutoffAt":"2026-11-04T10:00:00Z","deliversOn":"2026-11-06","dropMode":"PICKUP_POINT","acceptingOrders":false},
		 {"runId":"r3","cutoffAt":"2026-11-07T10:00:00Z","deliversOn":"2026-11-08","dropMode":"PICKUP_POINT","full":true}]}`))
	}))
	defer srv.Close()
	runs, err := NewRuns(srv.URL, srv.Client()).OpenRuns(context.Background(), "78602")
	if err != nil || gotPath != "/v1/public/runs/available" || gotQuery != "zip=78602" {
		t.Fatalf("%v %s?%s", err, gotPath, gotQuery)
	}
	if len(runs) != 2 || runs[0].RunID != "r1" || !runs[0].Door || runs[0].MinOrders != 10 || runs[0].OrdersSoFar != 7 ||
		runs[0].FeeRule == nil || runs[0].FeeRule.RuleVersion != 3 || !runs[1].Pickup || !runs[1].Full ||
		runs[1].Currency != "USD" {
		t.Fatalf("runs: %+v", runs)
	}
}
