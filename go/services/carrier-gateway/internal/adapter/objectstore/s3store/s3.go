// Package s3store implements port.ObjectStore on any S3-compatible API (AWS S3, Cloudflare R2, GCS
// interoperability, MinIO) with aws-sdk-go-v2. Label objects are private; downloads use presigned GET URLs.
package s3store

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"io"
	"time"

	"github.com/aws/aws-sdk-go-v2/aws"
	"github.com/aws/aws-sdk-go-v2/credentials"
	"github.com/aws/aws-sdk-go-v2/service/s3"
	"github.com/aws/aws-sdk-go-v2/service/s3/types"

	"github.com/billionairedev24/diaspora-commerce/go/services/carrier-gateway/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/carrier-gateway/internal/port"
)

// Options configure an S3-compatible store.
type Options struct {
	Endpoint  string // empty for AWS
	Region    string
	Bucket    string
	AccessKey string // empty: default credential chain (IRSA / workload identity)
	SecretKey string
	PathStyle bool
}

type s3Impl struct {
	bucket  string
	client  *s3.Client
	presign *s3.PresignClient
}

var _ port.ObjectStore = (*s3Impl)(nil)

// New returns an S3 ObjectStore.
func New(o Options) port.ObjectStore {
	cfg := aws.Config{Region: o.Region}
	if o.AccessKey != "" {
		cfg.Credentials = credentials.NewStaticCredentialsProvider(o.AccessKey, o.SecretKey, "")
	}
	client := s3.NewFromConfig(cfg, func(opt *s3.Options) {
		if o.Endpoint != "" {
			opt.BaseEndpoint = aws.String(o.Endpoint)
		}
		opt.UsePathStyle = o.PathStyle
	})
	return &s3Impl{bucket: o.Bucket, client: client, presign: s3.NewPresignClient(client)}
}

func (s *s3Impl) Put(ctx context.Context, key, contentType string, data []byte) error {
	_, err := s.client.PutObject(ctx, &s3.PutObjectInput{
		Bucket:        aws.String(s.bucket),
		Key:           aws.String(key),
		Body:          bytes.NewReader(data),
		ContentLength: aws.Int64(int64(len(data))),
		ContentType:   aws.String(contentType),
	})
	if err != nil {
		return fmt.Errorf("put %s: %w", key, err)
	}
	return nil
}

func (s *s3Impl) Get(ctx context.Context, key string) ([]byte, error) {
	out, err := s.client.GetObject(ctx, &s3.GetObjectInput{Bucket: aws.String(s.bucket), Key: aws.String(key)})
	var nsk *types.NoSuchKey
	if errors.As(err, &nsk) {
		return nil, fmt.Errorf("%w: %s", domain.ErrNotFound, key)
	}
	if err != nil {
		return nil, fmt.Errorf("get %s: %w", key, err)
	}
	defer func() { _ = out.Body.Close() }()
	b, err := io.ReadAll(out.Body)
	if err != nil {
		return nil, fmt.Errorf("read %s: %w", key, err)
	}
	return b, nil
}

func (s *s3Impl) SignedURL(ctx context.Context, key string, ttl time.Duration) (string, error) {
	req, err := s.presign.PresignGetObject(ctx, &s3.GetObjectInput{Bucket: aws.String(s.bucket), Key: aws.String(key)},
		s3.WithPresignExpires(ttl))
	if err != nil {
		return "", fmt.Errorf("presign %s: %w", key, err)
	}
	return req.URL, nil
}
