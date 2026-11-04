package app

import (
	"context"
	"errors"
	"fmt"
	"strings"
	"time"

	"github.com/billionairedev24/diaspora-commerce/tools/ops/kafka-replay/internal/port"
)

// Refusals returned by Reset --execute.
var (
	// ErrGroupActive means consumers are attached; Kafka would reject the reset and a partial one is dangerous.
	ErrGroupActive = errors.New("consumer group is active: stop every consumer of the group first")
	// ErrGroupStateUnknown means the broker could not be asked, so inactivity cannot be proven.
	ErrGroupStateUnknown = errors.New("cannot determine the consumer group state")
)

// ResetOptions describe an offset reset. Exactly one of ToDatetime, ToEarliest, ToLatest must be set.
type ResetOptions struct {
	Bootstrap     string
	Group         string
	Topic         string
	ToDatetime    time.Time
	ToEarliest    bool
	ToLatest      bool
	CommandConfig string // --command-config file for kafka-consumer-groups.sh (SASL/TLS)
	KafkaBin      string // path to kafka-consumer-groups.sh
	Execute       bool
}

// ResetResult is what was found and (if asked) done.
type ResetResult struct {
	State          port.GroupState
	StateKnown     bool
	DryRunCommand  string
	ExecuteCommand string
	Executed       bool
	Output         string
}

// OffsetResetter is a guarded front for kafka-consumer-groups.sh --reset-offsets.
type OffsetResetter interface {
	Reset(ctx context.Context, opts ResetOptions) (ResetResult, error)
}

type resetterImpl struct {
	groups port.GroupInspector
	runner port.CommandRunner
}

var _ OffsetResetter = (*resetterImpl)(nil)

// NewOffsetResetter wires the reset-offsets use case.
func NewOffsetResetter(groups port.GroupInspector, runner port.CommandRunner) OffsetResetter {
	return &resetterImpl{groups: groups, runner: runner}
}

func (r *resetterImpl) Reset(ctx context.Context, opts ResetOptions) (ResetResult, error) {
	var res ResetResult
	if opts.Group == "" || opts.Topic == "" || opts.Bootstrap == "" {
		return res, errors.New("reset-offsets: --group, --topic and a bootstrap server are required")
	}
	targets := 0
	if !opts.ToDatetime.IsZero() {
		targets++
	}
	if opts.ToEarliest {
		targets++
	}
	if opts.ToLatest {
		targets++
	}
	if targets != 1 {
		return res, errors.New("reset-offsets: give exactly one of --to-datetime, --to-earliest, --to-latest")
	}
	bin := opts.KafkaBin
	if bin == "" {
		bin = "kafka-consumer-groups.sh"
	}
	dry := commandArgs(opts, "--dry-run")
	exec := commandArgs(opts, "--execute")
	res.DryRunCommand = shellLine(bin, dry)
	res.ExecuteCommand = shellLine(bin, exec)

	state, err := r.groups.State(ctx, opts.Group)
	if err == nil {
		res.State, res.StateKnown = state, true
	}
	if !opts.Execute {
		return res, nil
	}
	if !res.StateKnown {
		return res, fmt.Errorf("reset-offsets: %w: %w", ErrGroupStateUnknown, err)
	}
	if !res.State.Inactive() {
		return res, fmt.Errorf("reset-offsets: group %q is %s with %d member(s): %w", opts.Group, res.State.State,
			res.State.Members, ErrGroupActive)
	}
	out, err := r.runner.Run(ctx, bin, exec...)
	res.Output = string(out)
	if err != nil {
		return res, fmt.Errorf("reset-offsets: %s: %w", bin, err)
	}
	res.Executed = true
	return res, nil
}

func commandArgs(opts ResetOptions, mode string) []string {
	args := []string{"--bootstrap-server", opts.Bootstrap}
	if opts.CommandConfig != "" {
		args = append(args, "--command-config", opts.CommandConfig)
	}
	args = append(args, "--group", opts.Group, "--topic", opts.Topic, "--reset-offsets")
	switch {
	case !opts.ToDatetime.IsZero():
		// An explicit Z: without a zone kafka-consumer-groups.sh reads the time in the machine's local zone.
		args = append(args, "--to-datetime", opts.ToDatetime.UTC().Format("2006-01-02T15:04:05.000Z"))
	case opts.ToEarliest:
		args = append(args, "--to-earliest")
	default:
		args = append(args, "--to-latest")
	}
	return append(args, mode)
}

func shellLine(bin string, args []string) string {
	parts := make([]string, 0, len(args)+1)
	parts = append(parts, bin)
	for _, a := range args {
		parts = append(parts, shellQuote(a))
	}
	return strings.Join(parts, " ")
}

func shellQuote(s string) string {
	if s != "" && strings.IndexFunc(s, func(r rune) bool {
		return !(r >= 'a' && r <= 'z' || r >= 'A' && r <= 'Z' || r >= '0' && r <= '9' || strings.ContainsRune("-_./:=,@%+", r))
	}) < 0 {
		return s
	}
	return "'" + strings.ReplaceAll(s, "'", `'\''`) + "'"
}
