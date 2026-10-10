// Package azblobstore implements port.ObjectStore on Azure Blob Storage using user-delegation or account-key SAS.
package azblobstore

import (
	"context"
	"fmt"
	"io"
	"strings"
	"time"

	"github.com/Azure/azure-sdk-for-go/sdk/azcore/to"
	"github.com/Azure/azure-sdk-for-go/sdk/storage/azblob"
	"github.com/Azure/azure-sdk-for-go/sdk/storage/azblob/blob"
	"github.com/Azure/azure-sdk-for-go/sdk/storage/azblob/sas"

	"github.com/billionairedev24/diaspora-commerce/go/services/media/internal/port"
)

// Options configure the Azure store. AccountKey is optional in AKS with workload identity (not covered here:
// production deployments use the shared-key SAS only for short-lived upload URLs issued server-side).
type Options struct {
	Account       string
	AccountKey    string
	Container     string
	PublicBaseURL string
}

type storeImpl struct {
	opts   Options
	client *azblob.Client
	cred   *azblob.SharedKeyCredential
}

var _ port.ObjectStore = (*storeImpl)(nil)

// New returns an Azure Blob ObjectStore.
func New(o Options) (port.ObjectStore, error) {
	cred, err := azblob.NewSharedKeyCredential(o.Account, o.AccountKey)
	if err != nil {
		return nil, fmt.Errorf("azure credential: %w", err)
	}
	client, err := azblob.NewClientWithSharedKeyCredential(fmt.Sprintf("https://%s.blob.core.windows.net/", o.Account), cred, nil)
	if err != nil {
		return nil, fmt.Errorf("azure client: %w", err)
	}
	return &storeImpl{opts: o, client: client, cred: cred}, nil
}

func (s *storeImpl) Name() string { return "azure-blob" }

func (s *storeImpl) sasURL(key string, perms sas.BlobPermissions, ttl time.Duration) (string, error) {
	q, err := sas.BlobSignatureValues{
		Protocol:      sas.ProtocolHTTPS,
		ExpiryTime:    time.Now().UTC().Add(ttl),
		Permissions:   perms.String(),
		ContainerName: s.opts.Container,
		BlobName:      key,
	}.SignWithSharedKey(s.cred)
	if err != nil {
		return "", fmt.Errorf("sign sas: %w", err)
	}
	return fmt.Sprintf("https://%s.blob.core.windows.net/%s/%s?%s", s.opts.Account, s.opts.Container, key, q.Encode()), nil
}

func (s *storeImpl) PrepareUpload(_ context.Context, key, _, contentType string, _ int64, ttl time.Duration) (port.Upload, error) {
	u, err := s.sasURL(key, sas.BlobPermissions{Create: true, Write: true}, ttl)
	if err != nil {
		return port.Upload{}, err
	}
	up := port.Upload{Method: "PUT", URL: u, Key: key, ExpiresAt: time.Now().Add(ttl),
		Headers: map[string]string{"x-ms-blob-type": "BlockBlob", "Content-Type": contentType}}
	if s.opts.PublicBaseURL != "" {
		up.PublicURL = strings.TrimSuffix(s.opts.PublicBaseURL, "/") + "/" + key
	}
	return up, nil
}

func (s *storeImpl) Open(ctx context.Context, key string, n int64) (io.ReadCloser, int64, string, error) {
	opts := &azblob.DownloadStreamOptions{}
	if n > 0 {
		opts.Range = blob.HTTPRange{Offset: 0, Count: n}
	}
	res, err := s.client.DownloadStream(ctx, s.opts.Container, key, opts)
	if err != nil {
		return nil, 0, "", fmt.Errorf("download %s: %w", key, err)
	}
	size := to.Ptr(int64(0))
	if res.ContentLength != nil {
		size = res.ContentLength
	}
	if res.ContentRange != nil {
		if i := strings.LastIndexByte(*res.ContentRange, '/'); i >= 0 {
			_, _ = fmt.Sscan((*res.ContentRange)[i+1:], size)
		}
	}
	ct := ""
	if res.ContentType != nil {
		ct = *res.ContentType
	}
	return res.Body, *size, ct, nil
}

func (s *storeImpl) Delete(ctx context.Context, key string) error {
	if _, err := s.client.DeleteBlob(ctx, s.opts.Container, key, nil); err != nil {
		return fmt.Errorf("delete %s: %w", key, err)
	}
	return nil
}

func (s *storeImpl) SignedGetURL(_ context.Context, key string, ttl time.Duration) (string, error) {
	return s.sasURL(key, sas.BlobPermissions{Read: true}, ttl)
}
