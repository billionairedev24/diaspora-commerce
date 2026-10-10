// Package s3store implements port.ObjectStore on any S3-compatible API: AWS S3, Cloudflare R2, Google Cloud
// Storage (interoperability/HMAC) and MinIO for local development.
package s3store

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"io"
	"strings"
	"time"

	"github.com/aws/aws-sdk-go-v2/aws"
	"github.com/aws/aws-sdk-go-v2/credentials"
	"github.com/aws/aws-sdk-go-v2/service/s3"

	"github.com/billionairedev24/diaspora-commerce/go/services/media/internal/port"
)

// Options configure an S3-compatible store.
type Options struct {
	Name          string // r2 | s3 | gcs | minio (for logs and asset records)
	Endpoint      string // empty for AWS; https://<account>.r2.cloudflarestorage.com; https://storage.googleapis.com; http://localhost:9000
	Region        string
	Bucket        string
	AccessKey     string
	SecretKey     string
	PublicBaseURL string // CDN base for public buckets (MEDIA_PUBLIC_URL), e.g. https://media.<env domain>
	PathStyle     bool   // MinIO
}

type storeImpl struct {
	opts    Options
	client  *s3.Client
	presign *s3.PresignClient
}

var _ port.ObjectStore = (*storeImpl)(nil)

// New returns an S3-compatible ObjectStore. Static credentials are used when given; otherwise the default chain
// (IRSA on EKS) applies.
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
	return &storeImpl{opts: o, client: client, presign: s3.NewPresignClient(client)}
}

func (s *storeImpl) Name() string { return s.opts.Name }

func (s *storeImpl) PrepareUpload(ctx context.Context, key, _, contentType string, size int64, ttl time.Duration) (port.Upload, error) {
	req, err := s.presign.PresignPutObject(ctx, &s3.PutObjectInput{
		Bucket:        aws.String(s.opts.Bucket),
		Key:           aws.String(key),
		ContentType:   aws.String(contentType),
		ContentLength: aws.Int64(size),
	}, s3.WithPresignExpires(ttl))
	if err != nil {
		return port.Upload{}, fmt.Errorf("presign put: %w", err)
	}
	headers := map[string]string{}
	for k, v := range req.SignedHeader {
		if strings.EqualFold(k, "host") {
			continue
		}
		headers[k] = strings.Join(v, ",")
	}
	up := port.Upload{Method: req.Method, URL: req.URL, Headers: headers, Key: key, ExpiresAt: time.Now().Add(ttl)}
	if s.opts.PublicBaseURL != "" {
		up.PublicURL = strings.TrimSuffix(s.opts.PublicBaseURL, "/") + "/" + key
	}
	return up, nil
}

// Put writes an object; public objects are served from PublicBaseURL (the bucket policy makes the purpose prefixes,
// never quarantine/, publicly readable).
func (s *storeImpl) Put(ctx context.Context, key, _, contentType string, data []byte, public bool) (string, string, error) {
	in := &s3.PutObjectInput{
		Bucket:        aws.String(s.opts.Bucket),
		Key:           aws.String(key),
		Body:          bytes.NewReader(data),
		ContentType:   aws.String(contentType),
		ContentLength: aws.Int64(int64(len(data))),
	}
	if public {
		// Keys are unique per upload, so CDN copies never go stale.
		in.CacheControl = aws.String("public, max-age=31536000, immutable")
	}
	_, err := s.client.PutObject(ctx, in)
	if err != nil {
		return "", "", fmt.Errorf("put %s: %w", key, err)
	}
	publicURL := ""
	if public && s.opts.PublicBaseURL != "" {
		publicURL = strings.TrimSuffix(s.opts.PublicBaseURL, "/") + "/" + key
	}
	return key, publicURL, nil
}

func (s *storeImpl) Open(ctx context.Context, key string, n int64) (io.ReadCloser, int64, string, error) {
	in := &s3.GetObjectInput{Bucket: aws.String(s.opts.Bucket), Key: aws.String(key)}
	if n > 0 {
		in.Range = aws.String(fmt.Sprintf("bytes=0-%d", n-1))
	}
	out, err := s.client.GetObject(ctx, in)
	if err != nil {
		return nil, 0, "", fmt.Errorf("get %s: %w", key, err)
	}
	size := aws.ToInt64(out.ContentLength)
	if out.ContentRange != nil {
		if i := strings.LastIndexByte(*out.ContentRange, '/'); i >= 0 {
			_, _ = fmt.Sscan((*out.ContentRange)[i+1:], &size)
		}
	}
	return out.Body, size, aws.ToString(out.ContentType), nil
}

func (s *storeImpl) Delete(ctx context.Context, key string) error {
	_, err := s.client.DeleteObject(ctx, &s3.DeleteObjectInput{Bucket: aws.String(s.opts.Bucket), Key: aws.String(key)})
	if err != nil {
		return fmt.Errorf("delete %s: %w", key, err)
	}
	return nil
}

// maxPresignTTL is the SigV4 query-signature limit (7 days) on AWS S3, R2, GCS (HMAC) and MinIO. URLs signed with
// temporary credentials (IRSA, STS) also stop working when those credentials expire.
const maxPresignTTL = 7 * 24 * time.Hour

func (s *storeImpl) MaxSignedURLTTL() time.Duration { return maxPresignTTL }

func (s *storeImpl) SignedGetURL(ctx context.Context, key string, ttl time.Duration) (string, error) {
	if key == "" {
		return "", errors.New("empty key")
	}
	req, err := s.presign.PresignGetObject(ctx, &s3.GetObjectInput{Bucket: aws.String(s.opts.Bucket), Key: aws.String(key)},
		s3.WithPresignExpires(ttl))
	if err != nil {
		return "", fmt.Errorf("presign get: %w", err)
	}
	return req.URL, nil
}
