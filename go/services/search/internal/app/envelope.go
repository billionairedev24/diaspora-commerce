package app

import (
	"encoding/json"
	"fmt"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/pkg/events"
)

// envelope builds a platform event.
func envelope(id, typ, aggregate, aggregateID string, at time.Time, payload any) (events.Envelope, error) {
	body, err := json.Marshal(payload)
	if err != nil {
		return events.Envelope{}, fmt.Errorf("encode %s: %w", typ, err)
	}
	return events.Envelope{ID: id, Type: typ, Aggregate: aggregate, AggregateID: aggregateID, OccurredAt: at,
		Payload: body}, nil
}
