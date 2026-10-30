// Package cli parses the command line and calls the use cases. It knows nothing about Kafka.
package cli

import (
	"context"
	"errors"
	"flag"
	"fmt"
	"io"
	"os"
	"strings"
	"time"

	"github.com/billionairedev24/diaspora-commerce/tools/ops/kafka-replay/internal/app"
	"github.com/billionairedev24/diaspora-commerce/tools/ops/kafka-replay/internal/domain"
)

// Exit codes.
const (
	ExitOK      = 0
	ExitFailure = 1
	ExitUsage   = 2
	ExitRefused = 3 // reset-offsets --execute refused because the group is active or its state is unknown
)

// BuildOptions tell the factory what the command needs.
type BuildOptions struct {
	Brokers    string
	LedgerPath string // replay only
	// MarkerFor is the DLQ topic whose "<topic>.replayed" marker topic is also used as a ledger; empty for none.
	MarkerFor string
}

// Services are the use cases a command runs.
type Services struct {
	Inspector app.Inspector
	Replayer  app.Replayer
	Resetter  app.OffsetResetter
}

// Factory connects to Kafka and returns the use cases and a function that releases them.
type Factory func(BuildOptions) (Services, func(), error)

// Env is everything outside the arguments the CLI needs.
type Env struct {
	Out     io.Writer
	Err     io.Writer
	Now     func() time.Time
	User    string
	Factory Factory
}

const usage = `kafka-replay: inspect and replay dead-lettered events (docs/runbooks/kafka-replay.md)

usage:
  kafka-replay [--brokers host:port,...] <command> [flags]

commands:
  list <dlq-topic>           summarise a DLQ: counts by exception class, event type, original topic, consumer group
  show <dlq-topic>           print one record (--partition N --offset N [--redact])
  replay <dlq-topic>         re-publish records to their original topic. DRY RUN unless --execute
  reset-offsets              print (or, only if the group is inactive, run) a kafka-consumer-groups.sh offset reset

connection: KAFKA_BOOTSTRAP (default localhost:9092), KAFKA_TLS, KAFKA_SASL_MECHANISM, KAFKA_SASL_USER, KAFKA_SASL_PASSWORD
`

// Run executes one invocation and returns the process exit code.
func Run(ctx context.Context, args []string, env Env) int {
	global := flag.NewFlagSet("kafka-replay", flag.ContinueOnError)
	global.SetOutput(io.Discard)
	brokers := global.String("brokers", "", "bootstrap servers (default $KAFKA_BOOTSTRAP)")
	if err := global.Parse(args); err != nil || global.NArg() == 0 {
		sayraw(env.Err, usage)
		return ExitUsage
	}
	rest := global.Args()
	cmd, cmdArgs := rest[0], rest[1:]
	switch cmd {
	case "list":
		return runList(ctx, *brokers, cmdArgs, env)
	case "show":
		return runShow(ctx, *brokers, cmdArgs, env)
	case "replay":
		return runReplay(ctx, *brokers, cmdArgs, env)
	case "reset-offsets":
		return runReset(ctx, *brokers, cmdArgs, env)
	case "help", "-h", "--help":
		sayraw(env.Out, usage)
		return ExitOK
	default:
		say(env.Err, "unknown command %q\n\n%s", cmd, usage)
		return ExitUsage
	}
}

type filterFlags struct {
	eventType, group, original, key, since, until string
}

func (f *filterFlags) register(fs *flag.FlagSet) {
	fs.StringVar(&f.eventType, "event-type", "", "only records with this event-type header")
	fs.StringVar(&f.group, "consumer-group", "", "only records dead-lettered by this consumer group")
	fs.StringVar(&f.original, "original-topic", "", "only records that came from this topic")
	fs.StringVar(&f.key, "key", "", "only records with this exact key (the aggregate id)")
	fs.StringVar(&f.since, "since", "", "only records dead-lettered at/after this time: RFC 3339 or a duration ago (2h, 30m)")
	fs.StringVar(&f.until, "until", "", "only records dead-lettered before this time: RFC 3339 or a duration ago")
}

func (f *filterFlags) build(now time.Time) (domain.Filter, error) {
	since, err := parseTime(f.since, now)
	if err != nil {
		return domain.Filter{}, fmt.Errorf("--since: %w", err)
	}
	until, err := parseTime(f.until, now)
	if err != nil {
		return domain.Filter{}, fmt.Errorf("--until: %w", err)
	}
	return domain.Filter{EventType: f.eventType, ConsumerGroup: f.group, OriginalTopic: f.original, Key: f.key,
		Since: since, Until: until}, nil
}

// parseTime accepts RFC 3339 or a positive duration meaning "that long ago".
func parseTime(v string, now time.Time) (time.Time, error) {
	if v == "" {
		return time.Time{}, nil
	}
	if t, err := time.Parse(time.RFC3339, v); err == nil {
		return t, nil
	}
	if d, err := time.ParseDuration(v); err == nil && d > 0 {
		return now.Add(-d), nil
	}
	return time.Time{}, fmt.Errorf("%q is neither an RFC 3339 time nor a duration such as 2h", v)
}

func newFlagSet(name string, env Env) *flag.FlagSet {
	fs := flag.NewFlagSet(name, flag.ContinueOnError)
	fs.SetOutput(env.Err)
	return fs
}

// splitTopic lets the topic come before or after the flags.
func splitTopic(args []string) (string, []string) {
	if len(args) > 0 && !strings.HasPrefix(args[0], "-") {
		return args[0], args[1:]
	}
	return "", args
}

// say, sayln and sayraw write operator messages; a failed terminal write is not actionable, so it is dropped.
func say(w io.Writer, format string, args ...any) { _, _ = fmt.Fprintf(w, format, args...) }
func sayln(w io.Writer, msg string)               { _, _ = fmt.Fprintln(w, msg) }
func sayraw(w io.Writer, msg string)              { _, _ = fmt.Fprint(w, msg) }

func fail(env Env, err error) int {
	say(env.Err, "error: %v\n", err)
	return ExitFailure
}

func runList(ctx context.Context, brokers string, args []string, env Env) int {
	topic, args := splitTopic(args)
	fs := newFlagSet("list", env)
	var ff filterFlags
	ff.register(fs)
	if err := fs.Parse(args); err != nil {
		return ExitUsage
	}
	if topic == "" && fs.NArg() == 1 {
		topic = fs.Arg(0)
	}
	if topic == "" {
		sayln(env.Err, "list: DLQ topic required, e.g. kafka-replay list at.order.events.dlq")
		return ExitUsage
	}
	filter, err := ff.build(env.Now())
	if err != nil {
		return fail(env, err)
	}
	svc, closeFn, err := env.Factory(BuildOptions{Brokers: brokers})
	if err != nil {
		return fail(env, err)
	}
	defer closeFn()
	summary, err := svc.Inspector.List(ctx, topic, filter)
	if err != nil {
		return fail(env, err)
	}
	if err := app.RenderSummary(env.Out, topic, summary); err != nil {
		return fail(env, err)
	}
	return ExitOK
}

func runShow(ctx context.Context, brokers string, args []string, env Env) int {
	topic, args := splitTopic(args)
	fs := newFlagSet("show", env)
	partition := fs.Int("partition", -1, "partition of the DLQ record")
	offset := fs.Int64("offset", -1, "offset of the DLQ record")
	redact := fs.Bool("redact", false, "mask every string value in the payload")
	if err := fs.Parse(args); err != nil {
		return ExitUsage
	}
	if topic == "" && fs.NArg() == 1 {
		topic = fs.Arg(0)
	}
	if topic == "" || *partition < 0 || *offset < 0 {
		sayln(env.Err, "show: needs <dlq-topic> --partition N --offset N (find them with `list` or the replay report)")
		return ExitUsage
	}
	svc, closeFn, err := env.Factory(BuildOptions{Brokers: brokers})
	if err != nil {
		return fail(env, err)
	}
	defer closeFn()
	rec, err := svc.Inspector.Show(ctx, domain.RecordID{Topic: topic, Partition: int32(*partition), Offset: *offset}, *redact) // #nosec G115 -- bounded by flag.Int
	if err != nil {
		return fail(env, err)
	}
	if err := app.RenderRecord(env.Out, rec); err != nil {
		return fail(env, err)
	}
	return ExitOK
}

func runReplay(ctx context.Context, brokers string, args []string, env Env) int {
	topic, args := splitTopic(args)
	fs := newFlagSet("replay", env)
	var ff filterFlags
	ff.register(fs)
	maxN := fs.Int("max", 0, "stop after this many records (0 = no limit)")
	rate := fs.Float64("rate", 0, "records per second (0 = unlimited)")
	execute := fs.Bool("execute", false, "actually publish. Without it this is a dry run")
	force := fs.Bool("force", false, "replay records the ledger says were replayed already")
	by := fs.String("by", env.User, "who is replaying; written to x-replayed-by and the ledger (required with --execute)")
	toTopic := fs.String("to-topic", "", "publish here instead of the x-dlq-original-topic header (needed for at.indexer.dlq.events and at.authz-sync.dlq.events)")
	ledgerPath := fs.String("ledger", "kafka-replay-ledger.jsonl", "JSON-lines ledger of replayed DLQ records")
	marker := fs.Bool("marker-topic", false, "also keep the ledger in <dlq-topic>.replayed so every operator sees it")
	if err := fs.Parse(args); err != nil {
		return ExitUsage
	}
	if topic == "" && fs.NArg() == 1 {
		topic = fs.Arg(0)
	}
	if topic == "" {
		sayln(env.Err, "replay: DLQ topic required, e.g. kafka-replay replay at.order.events.dlq --event-type order.paid")
		return ExitUsage
	}
	if *maxN < 0 || *rate < 0 {
		sayln(env.Err, "replay: --max and --rate must not be negative")
		return ExitUsage
	}
	filter, err := ff.build(env.Now())
	if err != nil {
		return fail(env, err)
	}
	build := BuildOptions{Brokers: brokers, LedgerPath: *ledgerPath}
	if *marker {
		build.MarkerFor = topic
	}
	svc, closeFn, err := env.Factory(build)
	if err != nil {
		return fail(env, err)
	}
	defer closeFn()
	opts := app.ReplayOptions{DLQTopic: topic, Filter: filter, Max: *maxN, Rate: *rate, Execute: *execute,
		Force: *force, By: *by, TargetTopic: *toTopic}
	report, err := svc.Replayer.Replay(ctx, opts)
	if renderErr := app.RenderReplay(env.Out, opts, report); renderErr != nil && err == nil {
		err = renderErr
	}
	if err != nil {
		return fail(env, err)
	}
	return ExitOK
}

func runReset(ctx context.Context, brokers string, args []string, env Env) int {
	fs := newFlagSet("reset-offsets", env)
	group := fs.String("group", "", "consumer group id (see the table in docs/runbooks/kafka-replay.md)")
	topic := fs.String("topic", "", "topic to reset")
	toDatetime := fs.String("to-datetime", "", "RFC 3339 time (or a duration ago, e.g. 6h) to rewind to")
	toEarliest := fs.Bool("to-earliest", false, "rewind to the earliest retained offset")
	toLatest := fs.Bool("to-latest", false, "skip to the end of the topic")
	bin := fs.String("kafka-bin", "kafka-consumer-groups.sh", "path to kafka-consumer-groups.sh")
	cmdConfig := fs.String("command-config", "", "client properties file passed to kafka-consumer-groups.sh (SASL/TLS)")
	execute := fs.Bool("execute", false, "run the reset. Refused unless the group has no active members")
	if err := fs.Parse(args); err != nil {
		return ExitUsage
	}
	ts, err := parseTime(*toDatetime, env.Now())
	if err != nil {
		return fail(env, fmt.Errorf("--to-datetime: %w", err))
	}
	svc, closeFn, err := env.Factory(BuildOptions{Brokers: brokers})
	if err != nil {
		return fail(env, err)
	}
	defer closeFn()
	bootstrap := brokers
	if bootstrap == "" {
		bootstrap = os.Getenv("KAFKA_BOOTSTRAP")
	}
	if bootstrap == "" {
		bootstrap = "localhost:9092"
	}
	opts := app.ResetOptions{Bootstrap: bootstrap, Group: *group, Topic: *topic, ToDatetime: ts, ToEarliest: *toEarliest,
		ToLatest: *toLatest, CommandConfig: *cmdConfig, KafkaBin: *bin, Execute: *execute}
	res, err := svc.Resetter.Reset(ctx, opts)
	if err != nil {
		if renderErr := app.RenderReset(env.Out, opts, res); renderErr != nil {
			say(env.Err, "error: %v\n", renderErr)
		}
		say(env.Err, "error: %v\n", err)
		if errors.Is(err, app.ErrGroupActive) || errors.Is(err, app.ErrGroupStateUnknown) {
			return ExitRefused
		}
		return ExitFailure
	}
	if err := app.RenderReset(env.Out, opts, res); err != nil {
		return fail(env, err)
	}
	return ExitOK
}
