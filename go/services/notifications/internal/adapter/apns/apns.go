// Package apns implements port.PushSender with Apple Push Notification service over HTTP/2 using token-based
// authentication (ES256 JWT signed with the .p8 key, refreshed every 50 minutes).
package apns

import (
	"context"
	"crypto/ecdsa"
	"crypto/x509"
	"encoding/json"
	"encoding/pem"
	"errors"
	"fmt"
	"net/http"
	"strings"
	"sync"
	"time"

	"github.com/golang-jwt/jwt/v5"
	"github.com/google/uuid"

	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/adapter/providerhttp"
	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/port"
)

// Name is the provider name used in routing config.
const Name = "apns"

// Hosts.
const (
	ProductionURL = "https://api.push.apple.com"
	SandboxURL    = "https://api.sandbox.push.apple.com"
)

// Config configures the adapter. The key comes from APNS_KEY_P8.
type Config struct {
	KeyID    string
	TeamID   string
	BundleID string
	BaseURL  string
}

type senderImpl struct {
	cfg    Config
	key    *ecdsa.PrivateKey
	client *http.Client
	now    func() time.Time

	mu       sync.Mutex
	token    string
	issuedAt time.Time
}

var _ port.PushSender = (*senderImpl)(nil)

// ParseKey parses an APNs .p8 (PKCS#8 PEM) EC private key.
func ParseKey(p8 []byte) (*ecdsa.PrivateKey, error) {
	block, _ := pem.Decode(p8)
	if block == nil {
		return nil, errors.New("apns: key is not PEM")
	}
	k, err := x509.ParsePKCS8PrivateKey(block.Bytes)
	if err != nil {
		return nil, fmt.Errorf("apns: parse key: %w", err)
	}
	ec, ok := k.(*ecdsa.PrivateKey)
	if !ok {
		return nil, errors.New("apns: key is not ECDSA")
	}
	return ec, nil
}

// New returns the APNs push sender. client must support HTTP/2 (the default transport negotiates it over TLS).
func New(cfg Config, key *ecdsa.PrivateKey, client *http.Client) port.PushSender {
	if cfg.BaseURL == "" {
		cfg.BaseURL = ProductionURL
	}
	cfg.BaseURL = strings.TrimSuffix(cfg.BaseURL, "/")
	return &senderImpl{cfg: cfg, key: key, client: client, now: time.Now}
}

func (s *senderImpl) Name() string { return Name }

func (s *senderImpl) Accepts(p domain.PushPlatform) bool { return p == domain.PushAPNs }

func (s *senderImpl) bearer(force bool) (string, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if !force && s.token != "" && s.now().Sub(s.issuedAt) < 50*time.Minute {
		return s.token, nil
	}
	now := s.now()
	t := jwt.NewWithClaims(jwt.SigningMethodES256, jwt.MapClaims{"iss": s.cfg.TeamID, "iat": now.Unix()})
	t.Header["kid"] = s.cfg.KeyID
	signed, err := t.SignedString(s.key)
	if err != nil {
		return "", fmt.Errorf("sign: %w", err)
	}
	s.token, s.issuedAt = signed, now
	return signed, nil
}

func (s *senderImpl) Send(ctx context.Context, msg domain.PushMessage) (domain.Receipt, error) {
	tok, err := s.bearer(false)
	if err != nil {
		return domain.Receipt{}, domain.Retry(Name, 0, "jwt").Wrap(err)
	}
	payload := map[string]any{"aps": map[string]any{
		"alert": map[string]string{"title": msg.Title, "body": msg.Body},
		"sound": "default",
	}}
	for k, v := range msg.Data {
		if k != "aps" {
			payload[k] = v
		}
	}
	req, err := providerhttp.NewJSONRequest(ctx, s.cfg.BaseURL+"/3/device/"+msg.Token.Token, payload)
	if err != nil {
		return domain.Receipt{}, domain.Permanent(Name, 0, "request").Wrap(err)
	}
	apnsID := msg.NotificationID
	if _, err := uuid.Parse(apnsID); err != nil {
		apnsID = uuid.NewString()
	}
	req.Header.Set("Authorization", "bearer "+tok)
	req.Header.Set("apns-topic", s.cfg.BundleID)
	req.Header.Set("apns-push-type", "alert")
	req.Header.Set("apns-priority", "10")
	req.Header.Set("apns-id", apnsID)
	res, err := providerhttp.Do(s.client, req, Name)
	if err != nil {
		return domain.Receipt{}, fmt.Errorf("%s request: %w", Name, err)
	}
	if !res.OK() {
		var e struct {
			Reason string `json:"reason"`
		}
		_ = json.Unmarshal(res.Body, &e)
		switch e.Reason {
		case "ExpiredProviderToken", "InvalidProviderToken":
			_, _ = s.bearer(true)
			return domain.Receipt{}, domain.Retry(Name, res.Status, e.Reason).Wrap(nil)
		case "BadDeviceToken", "Unregistered", "DeviceTokenNotForTopic":
			return domain.Receipt{}, domain.Permanent(Name, res.Status, e.Reason).Wrap(nil)
		}
		return domain.Receipt{}, providerhttp.Classify(Name, res, e.Reason).Wrap(res.Detail())
	}
	id := res.Header.Get("apns-id")
	if id == "" {
		id = apnsID
	}
	return domain.Receipt{Provider: Name, ProviderMessageID: id, AcceptedAt: time.Now().UTC()}, nil
}
