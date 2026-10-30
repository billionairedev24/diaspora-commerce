package ledger

import (
	"context"

	"github.com/billionairedev24/diaspora-commerce/tools/ops/kafka-replay/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/tools/ops/kafka-replay/internal/port"
)

type multiLedger struct {
	ledgers []port.Ledger
}

var _ port.Ledger = (*multiLedger)(nil)

// NewMulti combines ledgers: a record counts as replayed if any of them says so, and every entry goes to all of them
// in order, stopping at the first failure.
func NewMulti(ledgers ...port.Ledger) port.Ledger { return &multiLedger{ledgers: ledgers} }

func (m *multiLedger) Replayed(ctx context.Context, id domain.RecordID) (bool, error) {
	for _, l := range m.ledgers {
		done, err := l.Replayed(ctx, id)
		if err != nil {
			return false, err //nolint:wrapcheck // already wrapped by the ledger
		}
		if done {
			return true, nil
		}
	}
	return false, nil
}

func (m *multiLedger) Append(ctx context.Context, e port.LedgerEntry) error {
	for _, l := range m.ledgers {
		if err := l.Append(ctx, e); err != nil {
			return err //nolint:wrapcheck // already wrapped by the ledger
		}
	}
	return nil
}
