package app_test

import (
	"context"
	"errors"
	"testing"

	"github.com/billionairedev24/diaspora-commerce/go/services/cart/internal/app"
	"github.com/billionairedev24/diaspora-commerce/go/services/cart/internal/domain"
)

type openRuns struct {
	runs []domain.OpenRun
	err  error
	zip  string
}

func (o *openRuns) OpenRuns(_ context.Context, zip string) ([]domain.OpenRun, error) {
	o.zip = zip
	return o.runs, o.err
}

func runSvc(e *eligibility, r *openRuns) app.CartService {
	guests, users := &memRepo{carts: map[string]domain.Cart{}}, &memRepo{carts: map[string]domain.Cart{}}
	return app.NewCartService(guests, users, offers{"ATJOL00001": jollof, "ATDRS00001": dress},
		stock{"ATJOL00001": 3, "ATDRS00001": 10}, marketplace, app.WithPreorders(e), app.WithRuns(r))
}

func TestOpenRunTakesAfroTransactStockAndNeverAlsoAWindow(t *testing.T) {
	e := &eligibility{lines: map[string]domain.PreorderLine{
		"ATJOL00001": {Preorder: true, WindowID: "win-1", Stage: "OPEN", Door: true},
		"ATDRS00001": {Preorder: true, WindowID: "win-1", WindowName: "Lubbock window", Stage: "OPEN", Door: true, DoorFeeMinor: 500}}}
	r := &openRuns{runs: []domain.OpenRun{
		{RunID: "run-full", Full: true, CutoffAt: "2026-11-01T10:00:00Z"},
		{RunID: "run-2", Town: "Bastrop", CutoffAt: "2026-11-03T10:00:00Z", DeliversOn: "2026-11-05", TimeZone: "America/Chicago",
			FeeMinor: 1299, Currency: "USD", Door: true, MinOrders: 10, OrdersSoFar: 7}}}
	s := runSvc(e, r)
	u := domain.Owner{Kind: domain.OwnerUser, ID: "u1"}
	ctx := app.WithLocation(context.Background(),
		domain.Location{Country: "US", Region: "TX", City: "Bastrop", PostalCode: "78602"})
	if _, err := s.Add(ctx, u, "ATJOL00001", "", 1); err != nil {
		t.Fatal(err)
	}
	if _, err := s.Add(ctx, u, "ATDRS00001", "", 1); err != nil {
		t.Fatal(err)
	}
	v, err := s.View(ctx, u)
	if err != nil || len(v.Packages) != 2 {
		t.Fatalf("view: %+v %v", v, err)
	}
	run, window := v.Packages[0].Preorder, v.Packages[1].Preorder
	if r.zip != "78602" || run == nil || window == nil {
		t.Fatalf("packages: %+v / %+v", run, window)
	}
	if v.Packages[0].PackageID != "preorder:run-2" || run.Kind != "RUN" || run.FeeMinor != 1299 || run.MinOrders != 10 ||
		run.OrdersSoFar != 7 || run.MinimumMet || run.ClosesAt != "2026-11-03T10:00:00Z" || run.Locked || len(v.Packages[0].Lines) != 1 ||
		v.Packages[0].Lines[0].VariantATIN != "ATJOL00001" {
		t.Fatalf("run package (retail goods): %+v", run)
	}
	// The seller's own dress is not AfroTransact stock: it is offered the window, not the run, and the jollof is
	// not offered the window as well.
	if window.Kind != "WINDOW" || v.Packages[1].PackageID != "preorder:win-1" || len(v.Packages[1].Lines) != 1 ||
		v.Packages[1].Lines[0].VariantATIN != "ATDRS00001" {
		t.Fatalf("window package (seller goods): %+v", v.Packages[1])
	}
}

func TestNoOpenRunOrAnOutageFallsBackToTheWindow(t *testing.T) {
	e := &eligibility{lines: map[string]domain.PreorderLine{
		"ATJOL00001": {Preorder: true, WindowID: "win-1", Stage: "OPEN", Door: true}}}
	r := &openRuns{}
	s := runSvc(e, r)
	u := domain.Owner{Kind: domain.OwnerUser, ID: "u2"}
	ctx := app.WithLocation(context.Background(), domain.Location{Country: "US", Region: "TX", PostalCode: "78602"})
	if _, err := s.Add(ctx, u, "ATJOL00001", "", 1); err != nil {
		t.Fatal(err)
	}
	for name, runs := range map[string]*openRuns{"none": {}, "all full": {runs: []domain.OpenRun{{RunID: "r", Full: true}}},
		"outage": {err: errors.New("down")}} {
		r.runs, r.err = runs.runs, runs.err
		v, err := s.View(ctx, u)
		if err != nil || v.Packages[0].Preorder == nil || v.Packages[0].Preorder.Kind != "WINDOW" ||
			v.Packages[0].PackageID != "preorder:win-1" {
			t.Fatalf("%s: window expected: %+v %v", name, v.Packages[0], err)
		}
	}
}

func TestRunNeedsAZipAndStockForTheWholeLine(t *testing.T) {
	e := &eligibility{}
	r := &openRuns{runs: []domain.OpenRun{{RunID: "run-1", CutoffAt: "2026-11-03T10:00:00Z", DeliversOn: "2026-11-05",
		Door: true, FeeMinor: 799}}}
	s := runSvc(e, r)
	u := domain.Owner{Kind: domain.OwnerUser, ID: "u3"}
	noZip := app.WithLocation(context.Background(), domain.Location{Country: "US", Region: "TX"})
	if _, err := s.Add(noZip, u, "ATJOL00001", "", 2); err != nil {
		t.Fatal(err)
	}
	v, _ := s.View(noZip, u)
	if v.Packages[0].Preorder != nil || r.zip != "" {
		t.Fatalf("no ZIP, no run: %+v", v.Packages[0])
	}
	withZip := app.WithLocation(context.Background(), domain.Location{PostalCode: "78602"})
	v, _ = s.View(withZip, u)
	if v.Packages[0].Preorder == nil || v.Packages[0].Preorder.Kind != "RUN" || !v.Packages[0].Preorder.MinimumMet {
		t.Fatalf("a ZIP alone finds the run (no minimum means met): %+v", v.Packages[0])
	}
}
