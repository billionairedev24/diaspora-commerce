// Command kafka-replay inspects dead-letter topics and replays their records to the original topic.
package main

import (
	"context"
	"fmt"
	"os"
	"os/signal"
	"syscall"

	"github.com/billionairedev24/diaspora-commerce/tools/ops/kafka-replay/internal/adapter/kafka"
	"github.com/billionairedev24/diaspora-commerce/tools/ops/kafka-replay/internal/adapter/ledger"
	"github.com/billionairedev24/diaspora-commerce/tools/ops/kafka-replay/internal/adapter/shell"
	"github.com/billionairedev24/diaspora-commerce/tools/ops/kafka-replay/internal/app"
	"github.com/billionairedev24/diaspora-commerce/tools/ops/kafka-replay/internal/cli"
	"github.com/billionairedev24/diaspora-commerce/tools/ops/kafka-replay/internal/config"
	"github.com/billionairedev24/diaspora-commerce/tools/ops/kafka-replay/internal/port"
)

func main() {
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	clock := shell.NewClock()
	code := cli.Run(ctx, os.Args[1:], cli.Env{
		Out:     os.Stdout,
		Err:     os.Stderr,
		Now:     clock.Now,
		User:    currentUser(),
		Factory: connect(clock),
	})
	stop()
	os.Exit(code)
}

func currentUser() string {
	for _, k := range []string{"USER", "USERNAME", "LOGNAME"} {
		if v := os.Getenv(k); v != "" {
			return v
		}
	}
	return ""
}

// connect wires the Kafka adapters; nothing dials until the first request.
func connect(clock port.Clock) cli.Factory {
	return func(opts cli.BuildOptions) (cli.Services, func(), error) {
		cfg, err := config.FromEnv(opts.Brokers)
		if err != nil {
			return cli.Services{}, nil, fmt.Errorf("connection settings: %w", err)
		}
		cluster, err := kafka.NewCluster(cfg)
		if err != nil {
			return cli.Services{}, nil, fmt.Errorf("connect: %w", err)
		}
		reader := kafka.NewReader(cluster)
		writer := kafka.NewWriter(cluster)
		closers := []func(){cluster.Close}
		release := func() {
			for i := len(closers) - 1; i >= 0; i-- {
				closers[i]()
			}
		}

		var led port.Ledger
		if opts.LedgerPath != "" {
			file, err := ledger.NewFile(opts.LedgerPath)
			if err != nil {
				release()
				return cli.Services{}, nil, fmt.Errorf("ledger: %w", err)
			}
			closers = append(closers, func() { _ = file.Close() })
			led = file
			if opts.MarkerFor != "" {
				led = ledger.NewMulti(file, ledger.NewMarker(reader, writer, opts.MarkerFor))
			}
		}
		return cli.Services{
			Inspector: app.NewInspector(reader),
			Replayer:  app.NewReplayer(reader, writer, led, clock),
			Resetter:  app.NewOffsetResetter(kafka.NewGroupInspector(cluster), shell.NewRunner()),
		}, release, nil
	}
}
