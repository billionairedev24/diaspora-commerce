package s3store_test

import (
	"bytes"
	"context"
	"io"
	"net/http"
	"os"
	"testing"
	"time"

	"github.com/aws/aws-sdk-go-v2/aws"
	"github.com/aws/aws-sdk-go-v2/credentials"
	"github.com/aws/aws-sdk-go-v2/service/s3"
	"github.com/testcontainers/testcontainers-go"
	"github.com/testcontainers/testcontainers-go/wait"

	"github.com/billionairedev24/diaspora-commerce/go/services/media/internal/adapter/s3store"
)

func TestPresignedUploadRoundTripOnMinIO(t *testing.T) {
	if testing.Short() || os.Getenv("SKIP_DOCKER_TESTS") == "1" {
		t.Skip("integration test needs Docker")
	}
	ctx := context.Background()
	ctr, err := testcontainers.Run(ctx, "pgsty/minio:latest",
		testcontainers.WithEnv(map[string]string{"MINIO_ROOT_USER": "minioadmin", "MINIO_ROOT_PASSWORD": "minioadmin"}),
		testcontainers.WithCmd("server", "/data"),
		testcontainers.WithExposedPorts("9000/tcp"),
		testcontainers.WithWaitStrategy(wait.ForHTTP("/minio/health/ready").WithPort("9000/tcp").WithStartupTimeout(time.Minute)),
	)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = ctr.Terminate(context.Background()) })
	endpoint, err := ctr.PortEndpoint(ctx, "9000/tcp", "http")
	if err != nil {
		t.Fatal(err)
	}
	admin := s3.NewFromConfig(aws.Config{Region: "us-east-1",
		Credentials: credentials.NewStaticCredentialsProvider("minioadmin", "minioadmin", "")},
		func(o *s3.Options) { o.BaseEndpoint = aws.String(endpoint); o.UsePathStyle = true })
	if _, err := admin.CreateBucket(ctx, &s3.CreateBucketInput{Bucket: aws.String("media")}); err != nil {
		t.Fatal(err)
	}

	st := s3store.New(s3store.Options{Name: "minio", Endpoint: endpoint, Region: "us-east-1", Bucket: "media",
		AccessKey: "minioadmin", SecretKey: "minioadmin", PathStyle: true})
	payload := bytes.Repeat([]byte("x"), 2048)
	up, err := st.PrepareUpload(ctx, "product-image/org/a.jpg", "a.jpg", "image/jpeg", int64(len(payload)), time.Minute)
	if err != nil {
		t.Fatal(err)
	}
	req, _ := http.NewRequestWithContext(ctx, up.Method, up.URL, bytes.NewReader(payload))
	for k, v := range up.Headers {
		req.Header.Set(k, v)
	}
	req.ContentLength = int64(len(payload))
	res, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	_ = res.Body.Close()
	if res.StatusCode != http.StatusOK {
		t.Fatalf("presigned PUT status %d", res.StatusCode)
	}

	body, size, ct, err := st.Open(ctx, up.Key, 100)
	if err != nil {
		t.Fatal(err)
	}
	head, _ := io.ReadAll(body)
	_ = body.Close()
	if size != 2048 || len(head) != 100 || ct != "image/jpeg" {
		t.Fatalf("open: size=%d read=%d ct=%s", size, len(head), ct)
	}
	signed, err := st.SignedGetURL(ctx, up.Key, time.Minute)
	if err != nil || signed == "" {
		t.Fatalf("signed url: %v", err)
	}
	if err := st.Delete(ctx, up.Key); err != nil {
		t.Fatal(err)
	}

	// Publishing a moderated image: a server-side put under the public key.
	pub := s3store.New(s3store.Options{Name: "minio", Endpoint: endpoint, Region: "us-east-1", Bucket: "media",
		AccessKey: "minioadmin", SecretKey: "minioadmin", PathStyle: true, PublicBaseURL: "https://cdn.example.test/"})
	key, publicURL, err := pub.Put(ctx, "product-image/org/b.jpg", "b.jpg", "image/jpeg", []byte("clean-jpeg"), true)
	if err != nil {
		t.Fatal(err)
	}
	if key != "product-image/org/b.jpg" || publicURL != "https://cdn.example.test/product-image/org/b.jpg" {
		t.Fatalf("put: %s %s", key, publicURL)
	}
	obj, err := admin.GetObject(ctx, &s3.GetObjectInput{Bucket: aws.String("media"), Key: aws.String(key)})
	if err != nil {
		t.Fatal(err)
	}
	stored, _ := io.ReadAll(obj.Body)
	_ = obj.Body.Close()
	if string(stored) != "clean-jpeg" || aws.ToString(obj.ContentType) != "image/jpeg" ||
		aws.ToString(obj.CacheControl) != "public, max-age=31536000, immutable" {
		t.Fatalf("stored %q %s %s", stored, aws.ToString(obj.ContentType), aws.ToString(obj.CacheControl))
	}
}
