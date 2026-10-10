# media

Direct uploads and file inspection. Go 1.27.1 · PostgreSQL · local port **8207**.

## Flow

1. `POST /v1/uploads {purpose, fileName, contentType, size}` — the declared file is checked against the purpose rules
   and the service returns a short-lived upload URL for the purpose's store.
2. The browser or app `PUT`s the file **directly to storage** (no file bytes pass through our servers).
3. `POST /v1/assets/{id}/complete` — the service reads the object back, **detects the real type from the bytes**
   (a renamed HTML file never passes as a JPEG), measures it and applies the rules. Rejected files are deleted.

| Purpose | Store (dev/uat/prod) | Visibility | Rules |
|---|---|---|---|
| `product-image` | **UploadThing** (R2 as alternative) | public CDN | JPG/PNG/WebP ≤ 8 MB, ≥ 500 px; flags non-white backgrounds; ≥ 1000 px marked "eligible as main image" |
| `storefront` | UploadThing | public | JPG/PNG/WebP ≤ 8 MB |
| `proof-photo`, `return-photo` | private bucket | 5-minute signed URLs | images ≤ 10 MB |
| `kyc-document` | private, object-locked bucket on the primary cloud | 5-minute signed URLs, owner or staff only | images or PDF ≤ 15 MB |

Stores implement one `ObjectStore` port: **UploadThing** (REST v7), **S3-compatible** (AWS S3, Cloudflare R2, Google
Cloud Storage via HMAC interoperability, MinIO locally) and **Azure Blob** (SAS). Which store serves which purpose is
configuration (`stores` + `purposes` in `config/<env>.yaml`), so the cloud decision stays open.

Contract: [`contracts/openapi/media.yaml`](../../../contracts/openapi/media.yaml).

## Configuration

`DB_URL`, `UPLOADTHING_API_KEY`, `UPLOADTHING_APP_ID`, `R2_ACCESS_KEY_ID`/`R2_SECRET_ACCESS_KEY`, `AZURE_STORAGE_KEY`,
`MINIO_ACCESS_KEY`/`MINIO_SECRET_KEY` (local), `AUTH_ENABLED`, `AUTH_ISSUER_URI`. AWS credentials come from the default
chain (IRSA on EKS).

## Run and test

```bash
cd go/services/media
GOTOOLCHAIN=go1.27.1 go test ./...   # rules, inspector, UploadThing (httptest), full flow, MinIO Testcontainers
```
