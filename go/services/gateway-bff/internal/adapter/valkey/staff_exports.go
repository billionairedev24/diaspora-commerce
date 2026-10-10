package valkey

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"time"

	"github.com/redis/go-redis/v9"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

// exportJobsImpl keeps staff export jobs in Valkey so every BFF replica sees them: one key per job and a capped list
// of job ids per owner, both expiring a day after the last update.
type exportJobsImpl struct{ rdb redis.UniversalClient }

var _ port.ExportJobStore = (*exportJobsImpl)(nil)

// NewExportJobs returns the Valkey-backed export job store.
func NewExportJobs(rdb redis.UniversalClient) port.ExportJobStore { return &exportJobsImpl{rdb: rdb} }

const maxExportsPerOwner = 50

func exportKey(id string) string       { return prefix + "staff:export:" + id }
func exportsOfKey(owner string) string { return prefix + "staff:exports:" + owner }

func (s *exportJobsImpl) Save(ctx context.Context, job domain.StoredExportJob, ttl time.Duration) error {
	b, err := json.Marshal(job)
	if err != nil {
		return fmt.Errorf("encode export job: %w", err)
	}
	exists, err := s.rdb.Exists(ctx, exportKey(job.ID)).Result()
	if err != nil {
		return fmt.Errorf("export job exists: %w", err)
	}
	pipe := s.rdb.TxPipeline()
	pipe.Set(ctx, exportKey(job.ID), b, ttl)
	if exists == 0 {
		pipe.LPush(ctx, exportsOfKey(job.Owner), job.ID)
		pipe.LTrim(ctx, exportsOfKey(job.Owner), 0, maxExportsPerOwner-1)
	}
	pipe.Expire(ctx, exportsOfKey(job.Owner), ttl)
	if _, err := pipe.Exec(ctx); err != nil {
		return fmt.Errorf("save export job: %w", err)
	}
	return nil
}

func (s *exportJobsImpl) Get(ctx context.Context, id string) (domain.StoredExportJob, error) {
	b, err := s.rdb.Get(ctx, exportKey(id)).Bytes()
	if errors.Is(err, redis.Nil) {
		return domain.StoredExportJob{}, port.ErrNotFound
	}
	if err != nil {
		return domain.StoredExportJob{}, fmt.Errorf("get export job: %w", err)
	}
	var job domain.StoredExportJob
	if err := json.Unmarshal(b, &job); err != nil {
		return domain.StoredExportJob{}, fmt.Errorf("decode export job: %w", err)
	}
	return job, nil
}

func (s *exportJobsImpl) List(ctx context.Context, owner string) ([]domain.StoredExportJob, error) {
	ids, err := s.rdb.LRange(ctx, exportsOfKey(owner), 0, maxExportsPerOwner-1).Result()
	if err != nil {
		return nil, fmt.Errorf("list export jobs: %w", err)
	}
	out := []domain.StoredExportJob{}
	for _, id := range ids {
		job, err := s.Get(ctx, id)
		if errors.Is(err, port.ErrNotFound) {
			continue
		}
		if err != nil {
			return nil, err
		}
		out = append(out, job)
	}
	return out, nil
}
