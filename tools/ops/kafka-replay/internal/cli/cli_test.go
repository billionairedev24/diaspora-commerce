package cli_test

import (
	"bytes"
	"context"
	"errors"
	"strings"
	"testing"
	"time"

	"github.com/billionairedev24/diaspora-commerce/tools/ops/kafka-replay/internal/app"
	"github.com/billionairedev24/diaspora-commerce/tools/ops/kafka-replay/internal/cli"
	"github.com/billionairedev24/diaspora-commerce/tools/ops/kafka-replay/internal/domain"
)

type recorder struct {
	replay app.ReplayOptions
	reset  app.ResetOptions
	listed string
	filter domain.Filter
	err    error
}

func (r *recorder) List(_ context.Context, topic string, f domain.Filter) (*domain.Summary, error) {
	r.listed, r.filter = topic, f
	return domain.NewSummary(), r.err
}

func (r *recorder) Show(_ context.Context, id domain.RecordID, _ bool) (domain.Record, error) {
	return domain.Record{Topic: id.Topic}, r.err
}

func (r *recorder) Replay(_ context.Context, o app.ReplayOptions) (app.ReplayReport, error) {
	r.replay = o
	return app.ReplayReport{}, r.err
}

func (r *recorder) Reset(_ context.Context, o app.ResetOptions) (app.ResetResult, error) {
	r.reset = o
	return app.ResetResult{DryRunCommand: "x --dry-run", ExecuteCommand: "x --execute"}, r.err
}

func run(t *testing.T, rec *recorder, args ...string) (int, string, string) {
	t.Helper()
	var out, errOut bytes.Buffer
	env := cli.Env{
		Out: &out, Err: &errOut, User: "ada",
		Now: func() time.Time { return time.Date(2026, 3, 10, 12, 0, 0, 0, time.UTC) },
		Factory: func(cli.BuildOptions) (cli.Services, func(), error) {
			return cli.Services{Inspector: rec, Replayer: rec, Resetter: rec}, func() {}, nil
		},
	}
	code := cli.Run(context.Background(), args, env)
	return code, out.String(), errOut.String()
}

func TestReplayIsADryRunUnlessExecuteIsGiven(t *testing.T) {
	rec := &recorder{}
	code, out, _ := run(t, rec, "replay", "at.order.events.dlq")
	if code != cli.ExitOK || rec.replay.Execute {
		t.Fatalf("code %d execute %v", code, rec.replay.Execute)
	}
	if !strings.Contains(out, "DRY RUN") {
		t.Fatalf("output does not say dry run:\n%s", out)
	}

	code, out, _ = run(t, rec, "replay", "at.order.events.dlq", "--execute", "--by", "grace")
	if code != cli.ExitOK || !rec.replay.Execute || rec.replay.By != "grace" || !strings.Contains(out, "EXECUTED") {
		t.Fatalf("code %d opts %+v out %s", code, rec.replay, out)
	}
}

func TestReplayPassesFiltersAndLimits(t *testing.T) {
	rec := &recorder{}
	code, _, _ := run(t, rec, "replay", "at.order.events.dlq", "--event-type", "order.paid", "--consumer-group", "payments-service",
		"--key", "o-1", "--since", "2h", "--until", "2026-03-10T11:00:00Z", "--max", "5", "--rate", "2.5", "--force")
	if code != cli.ExitOK {
		t.Fatalf("code %d", code)
	}
	o := rec.replay
	if o.Filter.EventType != "order.paid" || o.Filter.ConsumerGroup != "payments-service" || o.Filter.Key != "o-1" ||
		o.Max != 5 || o.Rate != 2.5 || !o.Force {
		t.Fatalf("opts = %+v", o)
	}
	if !o.Filter.Since.Equal(time.Date(2026, 3, 10, 10, 0, 0, 0, time.UTC)) ||
		!o.Filter.Until.Equal(time.Date(2026, 3, 10, 11, 0, 0, 0, time.UTC)) {
		t.Fatalf("since/until = %v / %v", o.Filter.Since, o.Filter.Until)
	}
	if o.By != "ada" {
		t.Fatalf("by defaults to the user, got %q", o.By)
	}
}

func TestFlagsMayFollowOrPrecedeTheTopic(t *testing.T) {
	rec := &recorder{}
	if code, _, _ := run(t, rec, "list", "--event-type", "order.paid", "at.order.events.dlq"); code != cli.ExitOK || rec.listed != "at.order.events.dlq" {
		t.Fatalf("code %d topic %q", code, rec.listed)
	}
}

func TestUsageErrors(t *testing.T) {
	rec := &recorder{}
	for _, args := range [][]string{
		{}, {"bogus"}, {"list"}, {"replay"}, {"show", "t"}, {"replay", "t", "--max", "-1"},
	} {
		if code, _, _ := run(t, rec, args...); code != cli.ExitUsage {
			t.Errorf("%v: code %d, want %d", args, code, cli.ExitUsage)
		}
	}
	if code, _, errOut := run(t, rec, "replay", "t", "--since", "yesterday-ish"); code != cli.ExitFailure || !strings.Contains(errOut, "--since") {
		t.Errorf("bad --since: code %d, %s", code, errOut)
	}
}

func TestResetRefusalHasItsOwnExitCode(t *testing.T) {
	rec := &recorder{err: app.ErrGroupActive}
	code, out, errOut := run(t, rec, "--brokers", "k:9092", "reset-offsets", "--group", "g", "--topic", "t", "--to-earliest", "--execute")
	if code != cli.ExitRefused {
		t.Fatalf("code %d", code)
	}
	if !strings.Contains(errOut, "active") || !strings.Contains(out, "x --execute") {
		t.Fatalf("out %q err %q", out, errOut)
	}
	if rec.reset.Bootstrap != "k:9092" || !rec.reset.Execute || !rec.reset.ToEarliest {
		t.Fatalf("opts = %+v", rec.reset)
	}
}

func TestOtherErrorsAreFailures(t *testing.T) {
	rec := &recorder{err: errors.New("boom")}
	if code, _, errOut := run(t, rec, "replay", "t", "--execute", "--by", "x"); code != cli.ExitFailure || !strings.Contains(errOut, "boom") {
		t.Fatalf("code %d err %q", code, errOut)
	}
}
