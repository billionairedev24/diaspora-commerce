// Package linktoken implements port.LinkSigner: compact HMAC-SHA256 tokens for media-hosted long-lived download
// links. A token is base64url(version ‖ asset UUID ‖ expiry seconds ‖ MAC); it carries no other data, so a link
// reveals nothing but the asset id. Several keys can verify (rotation); the first one signs.
package linktoken

import (
	"crypto/hmac"
	"crypto/sha256"
	"encoding/base64"
	"encoding/binary"
	"errors"
	"fmt"
	"time"

	"github.com/google/uuid"

	"github.com/billionairedev24/diaspora-commerce/go/services/media/internal/port"
)

// MinKeyLength is the shortest accepted signing key.
const MinKeyLength = 32

const version byte = 1

// payload: version (1) + UUID (16) + expiry (8); MAC: 32.
const (
	payloadLen = 1 + 16 + 8
	tokenLen   = payloadLen + sha256.Size
)

// ErrInvalid is returned for tokens that are malformed or not signed by any configured key.
var ErrInvalid = errors.New("invalid link token")

type signerImpl struct {
	keys [][]byte
}

var _ port.LinkSigner = (*signerImpl)(nil)

// New returns a signer; keys[0] signs, every key verifies. Each key must have at least MinKeyLength bytes.
func New(keys ...string) (port.LinkSigner, error) {
	s := &signerImpl{}
	for _, k := range keys {
		if k == "" {
			continue
		}
		if len(k) < MinKeyLength {
			return nil, fmt.Errorf("link signing key must have at least %d characters", MinKeyLength)
		}
		s.keys = append(s.keys, []byte(k))
	}
	if len(s.keys) == 0 {
		return nil, errors.New("no link signing key")
	}
	return s, nil
}

func (s *signerImpl) Sign(assetID string, expires time.Time) (string, error) {
	id, err := uuid.Parse(assetID)
	if err != nil {
		return "", fmt.Errorf("asset id: %w", err)
	}
	buf := make([]byte, 0, tokenLen)
	buf = append(buf, version)
	buf = append(buf, id[:]...)
	buf = binary.BigEndian.AppendUint64(buf, uint64(expires.Unix())) //nolint:gosec // expiry is after 1970
	buf = append(buf, mac(s.keys[0], buf)...)
	return base64.RawURLEncoding.EncodeToString(buf), nil
}

func (s *signerImpl) Verify(token string) (string, time.Time, error) {
	raw, err := base64.RawURLEncoding.DecodeString(token)
	if err != nil || len(raw) != tokenLen || raw[0] != version {
		return "", time.Time{}, ErrInvalid
	}
	payload, sig := raw[:payloadLen], raw[payloadLen:]
	for _, k := range s.keys {
		if hmac.Equal(sig, mac(k, payload)) {
			id, err := uuid.FromBytes(payload[1:17])
			if err != nil {
				return "", time.Time{}, ErrInvalid
			}
			exp := int64(binary.BigEndian.Uint64(payload[17:])) //nolint:gosec // signed by us
			return id.String(), time.Unix(exp, 0).UTC(), nil
		}
	}
	return "", time.Time{}, ErrInvalid
}

func mac(key, payload []byte) []byte {
	h := hmac.New(sha256.New, key)
	_, _ = h.Write(payload)
	return h.Sum(nil)
}
