// Package ledger remembers which dead-letter records were replayed: a JSON-lines file and an optional marker topic.
package ledger

import (
	"bufio"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"os"
	"sync"

	"github.com/billionairedev24/diaspora-commerce/tools/ops/kafka-replay/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/tools/ops/kafka-replay/internal/port"
)

// Closable is a Ledger that holds resources.
type Closable interface {
	port.Ledger
	io.Closer
}

type fileLedger struct {
	mu   sync.Mutex
	file *os.File
	seen map[domain.RecordID]struct{}
}

var _ Closable = (*fileLedger)(nil)

// NewFile opens (creating if needed) the JSON-lines ledger at path and loads what it already holds.
func NewFile(path string) (Closable, error) {
	f, err := os.OpenFile(path, os.O_RDWR|os.O_CREATE|os.O_APPEND, 0o600) // #nosec G304 -- operator-supplied path
	if err != nil {
		return nil, fmt.Errorf("open ledger %s: %w", path, err)
	}
	l := &fileLedger{file: f, seen: map[domain.RecordID]struct{}{}}
	sc := bufio.NewScanner(f)
	sc.Buffer(make([]byte, 0, 64*1024), 1024*1024)
	line := 0
	for sc.Scan() {
		line++
		if len(sc.Bytes()) == 0 {
			continue
		}
		var e port.LedgerEntry
		if err := json.Unmarshal(sc.Bytes(), &e); err != nil {
			_ = f.Close()
			return nil, fmt.Errorf("ledger %s line %d is not valid JSON (refusing to guess what was replayed): %w",
				path, line, err)
		}
		l.seen[e.ID()] = struct{}{}
	}
	if err := sc.Err(); err != nil {
		_ = f.Close()
		return nil, fmt.Errorf("read ledger %s: %w", path, err)
	}
	return l, nil
}

func (l *fileLedger) Replayed(_ context.Context, id domain.RecordID) (bool, error) {
	l.mu.Lock()
	defer l.mu.Unlock()
	_, ok := l.seen[id]
	return ok, nil
}

func (l *fileLedger) Append(_ context.Context, e port.LedgerEntry) error {
	line, err := json.Marshal(e)
	if err != nil {
		return fmt.Errorf("encode ledger entry: %w", err)
	}
	l.mu.Lock()
	defer l.mu.Unlock()
	if _, err := l.file.Write(append(line, '\n')); err != nil {
		return fmt.Errorf("append ledger: %w", err)
	}
	if err := l.file.Sync(); err != nil {
		return fmt.Errorf("sync ledger: %w", err)
	}
	l.seen[e.ID()] = struct{}{}
	return nil
}

func (l *fileLedger) Close() error {
	if err := l.file.Close(); err != nil {
		return fmt.Errorf("close ledger: %w", err)
	}
	return nil
}
