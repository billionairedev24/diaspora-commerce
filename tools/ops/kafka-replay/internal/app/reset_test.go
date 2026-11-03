package app_test

import (
	"context"
	"errors"
	"strings"
	"testing"
	"time"

	"github.com/billionairedev24/diaspora-commerce/tools/ops/kafka-replay/internal/app"
	"github.com/billionairedev24/diaspora-commerce/tools/ops/kafka-replay/internal/port"
)

type fakeGroups struct {
	state port.GroupState
	err   error
}

func (f fakeGroups) State(context.Context, string) (port.GroupState, error) { return f.state, f.err }

type fakeRunner struct {
	calls [][]string
	out   string
	err   error
}

func (f *fakeRunner) Run(_ context.Context, name string, args ...string) ([]byte, error) {
	f.calls = append(f.calls, append([]string{name}, args...))
	return []byte(f.out), f.err
}

func resetOpts() app.ResetOptions {
	return app.ResetOptions{Bootstrap: "kafka:9092", Group: "payments-service", Topic: "at.order.events",
		ToDatetime: time.Date(2026, 3, 4, 5, 6, 7, 0, time.UTC)}
}

func TestResetPrintsTheExactCommandsAndDoesNotRun(t *testing.T) {
	runner := &fakeRunner{}
	res, err := app.NewOffsetResetter(fakeGroups{state: port.GroupState{State: "Stable", Members: 2}}, runner).
		Reset(context.Background(), resetOpts())
	if err != nil {
		t.Fatal(err)
	}
	if len(runner.calls) != 0 {
		t.Fatalf("print mode ran %v", runner.calls)
	}
	want := "kafka-consumer-groups.sh --bootstrap-server kafka:9092 --group payments-service --topic at.order.events " +
		"--reset-offsets --to-datetime 2026-03-04T05:06:07.000Z"
	if res.DryRunCommand != want+" --dry-run" || res.ExecuteCommand != want+" --execute" {
		t.Fatalf("commands:\n%s\n%s", res.DryRunCommand, res.ExecuteCommand)
	}
}

func TestResetExecuteRefusesWhileTheGroupIsActive(t *testing.T) {
	runner := &fakeRunner{}
	opts := resetOpts()
	opts.Execute = true
	_, err := app.NewOffsetResetter(fakeGroups{state: port.GroupState{State: "Stable", Members: 2}}, runner).
		Reset(context.Background(), opts)
	if !errors.Is(err, app.ErrGroupActive) {
		t.Fatalf("err = %v", err)
	}
	if len(runner.calls) != 0 {
		t.Fatalf("ran %v on an active group", runner.calls)
	}
}

func TestResetExecuteRefusesWhenStateIsUnknown(t *testing.T) {
	runner := &fakeRunner{}
	opts := resetOpts()
	opts.Execute = true
	_, err := app.NewOffsetResetter(fakeGroups{err: errors.New("no broker")}, runner).Reset(context.Background(), opts)
	if !errors.Is(err, app.ErrGroupStateUnknown) || len(runner.calls) != 0 {
		t.Fatalf("err = %v, calls = %v", err, runner.calls)
	}
}

func TestResetExecuteRunsWhenTheGroupIsEmpty(t *testing.T) {
	for _, state := range []string{"Empty", "Dead"} {
		runner := &fakeRunner{out: "NEW-OFFSET 42"}
		opts := resetOpts()
		opts.Execute = true
		opts.CommandConfig = "/etc/kafka/client.properties"
		res, err := app.NewOffsetResetter(fakeGroups{state: port.GroupState{State: state}}, runner).Reset(context.Background(), opts)
		if err != nil || !res.Executed {
			t.Fatalf("%s: err %v executed %v", state, err, res.Executed)
		}
		if len(runner.calls) != 1 || runner.calls[0][0] != "kafka-consumer-groups.sh" ||
			!strings.Contains(strings.Join(runner.calls[0], " "), "--command-config /etc/kafka/client.properties") ||
			runner.calls[0][len(runner.calls[0])-1] != "--execute" {
			t.Fatalf("%s: calls = %v", state, runner.calls)
		}
	}
}

func TestResetNeedsExactlyOneTarget(t *testing.T) {
	opts := resetOpts()
	opts.ToEarliest = true
	r := app.NewOffsetResetter(fakeGroups{}, &fakeRunner{})
	if _, err := r.Reset(context.Background(), opts); err == nil {
		t.Fatal("two targets accepted")
	}
	opts.ToEarliest = false
	opts.ToDatetime = time.Time{}
	if _, err := r.Reset(context.Background(), opts); err == nil {
		t.Fatal("no target accepted")
	}
}

func TestResetQuotesUnsafeArguments(t *testing.T) {
	opts := resetOpts()
	opts.Group = "weird group; rm -rf /"
	res, err := app.NewOffsetResetter(fakeGroups{}, &fakeRunner{}).Reset(context.Background(), opts)
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(res.DryRunCommand, `--group 'weird group; rm -rf /'`) {
		t.Fatalf("command = %s", res.DryRunCommand)
	}
}
