// Package shell implements the CommandRunner and Clock ports with os/exec and the real clock.
package shell

import (
	"context"
	"fmt"
	"os/exec"
	"time"

	"github.com/billionairedev24/diaspora-commerce/tools/ops/kafka-replay/internal/port"
)

type runnerImpl struct{}

var _ port.CommandRunner = runnerImpl{}

// NewRunner returns a CommandRunner that executes programs on this machine.
func NewRunner() port.CommandRunner { return runnerImpl{} }

func (runnerImpl) Run(ctx context.Context, name string, args ...string) ([]byte, error) {
	// The program and arguments come from the operator's own flags, never from Kafka data.
	out, err := exec.CommandContext(ctx, name, args...).CombinedOutput() // #nosec G204
	if err != nil {
		return out, fmt.Errorf("run %s: %w", name, err)
	}
	return out, nil
}

type clockImpl struct{}

var _ port.Clock = clockImpl{}

// NewClock returns the wall clock.
func NewClock() port.Clock { return clockImpl{} }

func (clockImpl) Now() time.Time { return time.Now().UTC() }

func (clockImpl) Sleep(ctx context.Context, d time.Duration) error {
	t := time.NewTimer(d)
	defer t.Stop()
	select {
	case <-ctx.Done():
		return fmt.Errorf("sleep: %w", ctx.Err())
	case <-t.C:
		return nil
	}
}
