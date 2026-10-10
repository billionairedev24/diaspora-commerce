// Package valkey implements the BFF's shared state on Valkey/Redis: sessions (AES-GCM encrypted, so tokens are
// never stored in clear), single-use OIDC authorization requests, sign-up challenges, cart line selection and
// fixed-window rate limits. Every replica shares it, so sessions survive restarts and scale-out.
package valkey

import (
	"context"
	"crypto/aes"
	"crypto/cipher"
	"crypto/rand"
	"crypto/sha256"
	"encoding/json"
	"errors"
	"fmt"
	"time"

	"github.com/redis/go-redis/v9"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

const prefix = "bff:"

// Store implements every state port on one Valkey client.
type Store interface {
	port.SessionStore
	port.EphemeralStore
	port.SelectionStore
	port.RateLimiter
	Ping(ctx context.Context) error
}

type storeImpl struct {
	rdb  redis.UniversalClient
	aead cipher.AEAD
}

var _ Store = (*storeImpl)(nil)

// New returns the Valkey store. encryptionSecret derives the AES-256-GCM key that encrypts sessions at rest.
func New(rdb redis.UniversalClient, encryptionSecret string) (Store, error) {
	key := sha256.Sum256([]byte("gateway-bff/session-encryption/v1:" + encryptionSecret))
	block, err := aes.NewCipher(key[:])
	if err != nil {
		return nil, fmt.Errorf("session cipher: %w", err)
	}
	aead, err := cipher.NewGCM(block)
	if err != nil {
		return nil, fmt.Errorf("session gcm: %w", err)
	}
	return &storeImpl{rdb: rdb, aead: aead}, nil
}

func (s *storeImpl) Ping(ctx context.Context) error {
	if err := s.rdb.Ping(ctx).Err(); err != nil {
		return fmt.Errorf("valkey ping: %w", err)
	}
	return nil
}

func (s *storeImpl) seal(key string, v any) ([]byte, error) {
	plain, err := json.Marshal(v)
	if err != nil {
		return nil, fmt.Errorf("encode: %w", err)
	}
	nonce := make([]byte, s.aead.NonceSize())
	if _, err := rand.Read(nonce); err != nil {
		return nil, fmt.Errorf("nonce: %w", err)
	}
	return s.aead.Seal(nonce, nonce, plain, []byte(key)), nil
}

func (s *storeImpl) open(key string, data []byte, v any) error {
	n := s.aead.NonceSize()
	if len(data) < n {
		return errors.New("session record too short")
	}
	plain, err := s.aead.Open(nil, data[:n], data[n:], []byte(key))
	if err != nil {
		return fmt.Errorf("decrypt session: %w", err)
	}
	if err := json.Unmarshal(plain, v); err != nil {
		return fmt.Errorf("decode session: %w", err)
	}
	return nil
}

// ---------------------------------------------------------------- sessions

func sessionKey(key string) string { return prefix + "sess:" + key }

func (s *storeImpl) Load(ctx context.Context, key string) (*domain.Session, error) {
	k := sessionKey(key)
	data, err := s.rdb.Get(ctx, k).Bytes()
	if errors.Is(err, redis.Nil) {
		return nil, nil
	}
	if err != nil {
		return nil, fmt.Errorf("load session: %w", err)
	}
	var sess domain.Session
	if err := s.open(k, data, &sess); err != nil {
		return nil, nil //nolint:nilerr // an unreadable record (key rotation, tampering) is treated as no session
	}
	return &sess, nil
}

func (s *storeImpl) Save(ctx context.Context, key string, sess *domain.Session, ttl time.Duration) error {
	k := sessionKey(key)
	data, err := s.seal(k, sess)
	if err != nil {
		return err
	}
	if err := s.rdb.Set(ctx, k, data, ttl).Err(); err != nil {
		return fmt.Errorf("save session: %w", err)
	}
	return nil
}

func (s *storeImpl) Delete(ctx context.Context, key string) error {
	if err := s.rdb.Del(ctx, sessionKey(key)).Err(); err != nil {
		return fmt.Errorf("delete session: %w", err)
	}
	return nil
}

func (s *storeImpl) Lock(ctx context.Context, key string, ttl time.Duration) (func(), bool, error) {
	k := prefix + "lock:" + key
	token := make([]byte, 16)
	_, _ = rand.Read(token)
	ok, err := s.rdb.SetNX(ctx, k, token, ttl).Result()
	if err != nil {
		return func() {}, false, fmt.Errorf("lock: %w", err)
	}
	if !ok {
		return func() {}, false, nil
	}
	return func() {
		// Release only our own lock.
		_ = s.rdb.Eval(context.WithoutCancel(ctx), `if redis.call("GET", KEYS[1]) == ARGV[1] then return redis.call("DEL", KEYS[1]) end return 0`,
			[]string{k}, token).Err()
	}, true, nil
}

// ---------------------------------------------------------------- OIDC requests and challenges

func (s *storeImpl) put(ctx context.Context, k string, v any, ttl time.Duration) error {
	data, err := s.seal(k, v)
	if err != nil {
		return err
	}
	if err := s.rdb.Set(ctx, k, data, ttl).Err(); err != nil {
		return fmt.Errorf("put %s: %w", k, err)
	}
	return nil
}

func (s *storeImpl) PutAuthRequest(ctx context.Context, state string, r port.AuthRequest, ttl time.Duration) error {
	return s.put(ctx, prefix+"oidc:"+state, r, ttl)
}

func (s *storeImpl) TakeAuthRequest(ctx context.Context, state string) (*port.AuthRequest, error) {
	k := prefix + "oidc:" + state
	data, err := s.rdb.GetDel(ctx, k).Bytes()
	if errors.Is(err, redis.Nil) {
		return nil, nil
	}
	if err != nil {
		return nil, fmt.Errorf("take auth request: %w", err)
	}
	var r port.AuthRequest
	if err := s.open(k, data, &r); err != nil {
		return nil, nil //nolint:nilerr // unreadable = unknown state
	}
	return &r, nil
}

func (s *storeImpl) PutChallenge(ctx context.Context, id string, c port.Challenge, ttl time.Duration) error {
	return s.put(ctx, prefix+"challenge:"+id, c, ttl)
}

func (s *storeImpl) GetChallenge(ctx context.Context, id string) (*port.Challenge, error) {
	k := prefix + "challenge:" + id
	data, err := s.rdb.Get(ctx, k).Bytes()
	if errors.Is(err, redis.Nil) {
		return nil, nil
	}
	if err != nil {
		return nil, fmt.Errorf("get challenge: %w", err)
	}
	var c port.Challenge
	if err := s.open(k, data, &c); err != nil {
		return nil, nil //nolint:nilerr // unreadable = unknown challenge
	}
	return &c, nil
}

func (s *storeImpl) DeleteChallenge(ctx context.Context, id string) error {
	if err := s.rdb.Del(ctx, prefix+"challenge:"+id).Err(); err != nil {
		return fmt.Errorf("delete challenge: %w", err)
	}
	return nil
}

// ---------------------------------------------------------------- cart selection

const selectionTTL = 30 * 24 * time.Hour

func (s *storeImpl) Deselected(ctx context.Context, owner string) (map[string]bool, error) {
	members, err := s.rdb.SMembers(ctx, prefix+"cartsel:"+owner).Result()
	if err != nil {
		return nil, fmt.Errorf("selection: %w", err)
	}
	out := make(map[string]bool, len(members))
	for _, m := range members {
		out[m] = true
	}
	return out, nil
}

func (s *storeImpl) SetSelected(ctx context.Context, owner string, atins []string, selected bool) error {
	if len(atins) == 0 {
		return nil
	}
	k := prefix + "cartsel:" + owner
	members := make([]any, len(atins))
	for i, a := range atins {
		members[i] = a
	}
	pipe := s.rdb.TxPipeline()
	if selected {
		pipe.SRem(ctx, k, members...)
	} else {
		pipe.SAdd(ctx, k, members...)
	}
	pipe.Expire(ctx, k, selectionTTL)
	if _, err := pipe.Exec(ctx); err != nil {
		return fmt.Errorf("set selection: %w", err)
	}
	return nil
}

// ---------------------------------------------------------------- rate limits

func (s *storeImpl) Allow(ctx context.Context, key string, limit int, window time.Duration) (bool, time.Duration, error) {
	slot := time.Now().UnixNano() / int64(window)
	k := fmt.Sprintf("%srl:%s:%d", prefix, key, slot)
	pipe := s.rdb.TxPipeline()
	incr := pipe.Incr(ctx, k)
	pipe.Expire(ctx, k, window)
	if _, err := pipe.Exec(ctx); err != nil {
		return true, 0, fmt.Errorf("rate limit: %w", err)
	}
	if incr.Val() > int64(limit) {
		next := time.Unix(0, (slot+1)*int64(window))
		return false, time.Until(next), nil
	}
	return true, 0, nil
}
