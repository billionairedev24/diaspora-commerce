package domain

import "time"

// Filter selects DLQ records. Zero fields match everything.
type Filter struct {
	EventType     string
	ConsumerGroup string
	OriginalTopic string
	Key           string
	// Since and Until bound FailedAt (inclusive / exclusive). Zero means unbounded.
	Since time.Time
	Until time.Time
}

// Matches reports whether the record passes every set criterion.
func (f Filter) Matches(r Record) bool {
	if f.EventType != "" && r.EventType() != f.EventType {
		return false
	}
	if f.ConsumerGroup != "" && r.ConsumerGroup() != f.ConsumerGroup {
		return false
	}
	if f.OriginalTopic != "" && r.OriginalTopic() != f.OriginalTopic {
		return false
	}
	if f.Key != "" && string(r.Key) != f.Key {
		return false
	}
	failed := r.FailedAt()
	if !f.Since.IsZero() && failed.Before(f.Since) {
		return false
	}
	if !f.Until.IsZero() && !failed.Before(f.Until) {
		return false
	}
	return true
}
