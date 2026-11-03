package domain

import "time"

// Summary is what `list` prints for one DLQ topic.
type Summary struct {
	Total          int
	ByException    map[string]int
	ByEventType    map[string]int
	ByOriginal     map[string]int
	ByConsumerGrp  map[string]int
	Oldest, Newest time.Time
}

// NewSummary returns an empty summary.
func NewSummary() *Summary {
	return &Summary{
		ByException:   map[string]int{},
		ByEventType:   map[string]int{},
		ByOriginal:    map[string]int{},
		ByConsumerGrp: map[string]int{},
	}
}

// Add counts one record.
func (s *Summary) Add(r Record) {
	s.Total++
	s.ByException[orUnknown(r.ExceptionClass())]++
	s.ByEventType[orUnknown(r.EventType())]++
	s.ByOriginal[orUnknown(r.OriginalTopic())]++
	s.ByConsumerGrp[orUnknown(r.ConsumerGroup())]++
	t := r.FailedAt()
	if s.Oldest.IsZero() || t.Before(s.Oldest) {
		s.Oldest = t
	}
	if t.After(s.Newest) {
		s.Newest = t
	}
}

func orUnknown(v string) string {
	if v == "" {
		return "(none)"
	}
	return v
}
