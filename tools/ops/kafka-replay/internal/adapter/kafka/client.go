// Package kafka implements the tool's ports with franz-go, the client the Go services already use (go/pkg/events).
package kafka

import (
	"context"
	"crypto/tls"
	"errors"
	"fmt"

	"github.com/twmb/franz-go/pkg/kgo"
	"github.com/twmb/franz-go/pkg/sasl"
	"github.com/twmb/franz-go/pkg/sasl/plain"
	"github.com/twmb/franz-go/pkg/sasl/scram"

	"github.com/billionairedev24/diaspora-commerce/tools/ops/kafka-replay/internal/config"
)

// options turns the connection settings into franz-go options.
func options(cfg config.Kafka) ([]kgo.Opt, error) {
	opts := []kgo.Opt{kgo.SeedBrokers(cfg.Brokers...), kgo.ClientID("kafka-replay")}
	if cfg.TLS {
		opts = append(opts, kgo.DialTLSConfig(&tls.Config{MinVersion: tls.VersionTLS12}))
	}
	if cfg.SASLMechanism != "" {
		mech, err := mechanism(cfg)
		if err != nil {
			return nil, err
		}
		opts = append(opts, kgo.SASL(mech))
	}
	return opts, nil
}

func mechanism(cfg config.Kafka) (sasl.Mechanism, error) {
	switch cfg.SASLMechanism {
	case "plain":
		return plain.Auth{User: cfg.SASLUser, Pass: cfg.SASLPassword}.AsMechanism(), nil
	case "scram-sha-256":
		return scram.Auth{User: cfg.SASLUser, Pass: cfg.SASLPassword}.AsSha256Mechanism(), nil
	case "scram-sha-512":
		return scram.Auth{User: cfg.SASLUser, Pass: cfg.SASLPassword}.AsSha512Mechanism(), nil
	default:
		return nil, errors.New("unknown SASL mechanism " + cfg.SASLMechanism)
	}
}

// Cluster owns the connection used by the reader, writer and group inspector.
type Cluster struct {
	cfg    config.Kafka
	client *kgo.Client
}

// NewCluster connects lazily: franz-go dials on first use, so a wrong address surfaces on the first command.
func NewCluster(cfg config.Kafka) (*Cluster, error) {
	opts, err := options(cfg)
	if err != nil {
		return nil, err
	}
	client, err := kgo.NewClient(opts...)
	if err != nil {
		return nil, fmt.Errorf("kafka client: %w", err)
	}
	return &Cluster{cfg: cfg, client: client}, nil
}

// Close releases the connections.
func (c *Cluster) Close() { c.client.Close() }

// Ping checks that a broker answers.
func (c *Cluster) Ping(ctx context.Context) error {
	if err := c.client.Ping(ctx); err != nil {
		return fmt.Errorf("kafka ping %v: %w", c.cfg.Brokers, err)
	}
	return nil
}
