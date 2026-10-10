// Package fsstore implements port.ObjectStore on the local filesystem (local profile and tests).
package fsstore

import (
	"context"
	"errors"
	"fmt"
	"io/fs"
	"os"
	"path/filepath"
	"strings"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/services/carrier-gateway/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/carrier-gateway/internal/port"
)

type fsImpl struct{ root string }

var _ port.ObjectStore = (*fsImpl)(nil)

// New stores objects under root (created on demand).
func New(root string) port.ObjectStore { return &fsImpl{root: root} }

func (s *fsImpl) path(key string) (string, error) {
	clean := filepath.Clean("/" + key)
	if strings.Contains(key, "..") || clean == "/" {
		return "", fmt.Errorf("%w: bad object key %q", domain.ErrInvalid, key)
	}
	return filepath.Join(s.root, filepath.FromSlash(clean)), nil
}

func (s *fsImpl) Put(_ context.Context, key, _ string, data []byte) error {
	p, err := s.path(key)
	if err != nil {
		return err
	}
	if err := os.MkdirAll(filepath.Dir(p), 0o750); err != nil {
		return fmt.Errorf("mkdir: %w", err)
	}
	tmp := p + ".tmp"
	if err := os.WriteFile(tmp, data, 0o600); err != nil {
		return fmt.Errorf("write: %w", err)
	}
	if err := os.Rename(tmp, p); err != nil {
		return fmt.Errorf("rename: %w", err)
	}
	return nil
}

func (s *fsImpl) Get(_ context.Context, key string) ([]byte, error) {
	p, err := s.path(key)
	if err != nil {
		return nil, err
	}
	b, err := os.ReadFile(p) // #nosec G304 -- key is validated and confined to root
	if errors.Is(err, fs.ErrNotExist) {
		return nil, fmt.Errorf("%w: %s", domain.ErrNotFound, key)
	}
	if err != nil {
		return nil, fmt.Errorf("read: %w", err)
	}
	return b, nil
}

// SignedURL is unsupported on the filesystem; clients use GET /v1/labels/{id}/document.
func (s *fsImpl) SignedURL(context.Context, string, time.Duration) (string, error) { return "", nil }
