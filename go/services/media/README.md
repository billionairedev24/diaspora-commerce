# media

Direct uploads and file inspection. Go 1.27.1 · PostgreSQL · local port **8207**.

## Flow

1. `POST /v1/uploads {purpose, fileName, contentType, size}` — the declared file is checked against the purpose rules
   and the service returns a short-lived upload URL for the purpose's store.
2. The browser or app `PUT`s the file **directly to storage** (no file bytes pass through our servers). When
   `upload.formField` is set (UploadThing) the file goes as multipart/form-data in that field, with `upload.headers`.
3. `POST /v1/assets/{id}/complete` — the service reads the object back, **detects the real type from the bytes**
   (a renamed HTML file never passes as a JPEG), measures it and applies the rules. Rejected files are deleted.
   Public images then go through moderation (below) and the response carries `url`, `width`, `height`, `bytes`,
   `contentType`, `status`, `flags` and `moderation` for the product builder.

### Image checks and verdicts (S388, S389)

Product images are checked **per slot**: `POST /v1/uploads` and `POST /v1/assets/import` take `slot` (`MAIN`, `OTHER`
(default), `SWATCH`, `LABEL`) and optional `title`, `brand`, `productTypeCode`; library file names infer it (`SKU.MAIN`
is MAIN, `SKU.PT01` to `PT08` OTHER, `SKU.SWCH` SWATCH). The asset gets a `verdict` (`PASS`, `WARN`, `HOLD`, `REJECT`)
and `checks` (`code`, `level`, `params`, English `message`; no ids, no jargon). `REJECT` = status `REJECTED`, deleted
from quarantine; `HOLD` = `FLAGGED`, private; `PASS`/`WARN` publish. Nothing is public before the checks pass.

| Rule (main image) | Default | Outcome |
|---|---|---|
| Longest side (zoom) / shortest side | 1000 px / 500 px | `MAIN_TOO_SMALL` / `TOO_SMALL`, reject (2000 px is only recommended) |
| White background: share of the outer 2 % ring with all channels at least 250, and the four corners | 95 % | `NOT_WHITE`, reject; a passing background is set to pure 255 (JPEG and PNG; large images are only measured) |
| Product fills the frame (bounding box of pixels more than 12 from white, after a 3 px blur) | reject under 0.75, warn under 0.85 | `FILL_LOW` / `FILL_OK_NEAR` |
| Uniform non-white band along 3+ edges, 1 to 12 % thick, with white inside | | `BORDER`, reject |
| Blank image or a hash in `placeholderHashes` | | `PLACEHOLDER`, reject |
| Blur (variance of the Laplacian of the product, only when it fills the frame), aspect over 2:1 | 100, 2 | `BLUR`, `ASPECT`, warn only |
| Transparent PNG / CMYK JPEG | | flattened on white / converted to RGB (WebP written again becomes PNG) |
| Other slots | warn under 1000 px, reject under 500 px | `LOW_RES`, `TOO_SMALL` |

Every number is configuration (`images.*` in `config/<env>.yaml`); `images.enforce: false` is **shadow mode** (verdict and
checks recorded, `shadow: true`, nothing rejected for quality; type, size and prohibited content are always enforced).
Staff change the starred settings at run time with `PUT /v1/image-rules` (per environment database): `warnOnly` (the
appearance rejects become warnings), min sizes, fill thresholds, the size cap, `allowOnModelApparel`, the mismatch
threshold. `GET /v1/image-rules` returns what is in force. Measurements (`asset.metrics`) are stored, so
`POST /v1/assets/verify` (catalog, scope `media:import`) can judge a published image for the slot it is used in, from its
URL, with today's thresholds, and `POST /v1/assets/rescore` measures old images again (at most 20 per call, two at a time).

The AI hook adds held findings (always HOLD, never REJECT, also when the hook fails or its budget is spent): text, logos,
watermarks (`HELD_TEXT`), props or people on a main image (`HELD_PROPS`, unless the product type is apparel and the
setting allows it), a title and brand mismatch score at or above `mismatchHold` (`HELD_MISMATCH`), placeholder, border
or not-a-product labels (`HELD_REVIEW`), another seller's photo (`HELD_DUPLICATE`). Staff see `moderation.aiLabels` and
`moderation.mismatchScore` next to the image (signed 5-minute URL) when they review it.

### Moderation of public images (S030)

`product-image` and `storefront` uploads land in **quarantine** (`quarantine/<key>` on S3-compatible stores. In prod
`quarantineStore: private` puts them in the private bucket and publishing copies the cleaned image to the public bucket,
so no bucket policy or WAF rule is needed; elsewhere the bucket policy must make only the purpose prefixes public,
never `quarantine/*`; UploadThing uploads are `private`) and
are published only when moderation passes:

1. **Rules**: real type and size (8 MB, staff-editable), then the slot-aware image checks above (verdict `PASS`, `WARN`, `HOLD` or `REJECT`; a rejected image is deleted).
2. **Metadata stripping** (`internal/adapter/imageproc`): EXIF (GPS, camera), XMP, IPTC, comments and PNG/WebP text
   chunks are removed losslessly; a JPEG with an EXIF orientation is rotated and re-encoded so it still displays
   upright. Images over `moderation.maxPixels` (40 MP) are rejected.
3. **Duplicates**: a 64-bit difference hash (dHash) is compared by Hamming distance (≤ `duplicateDistance`, 6) with
   completed public images (`asset.phash`, migration `002`). Another organisation's near-identical photo holds the
   image for review (possibly lifted from another listing); the organisation's own is only noted (`duplicateOf`).
4. **AI hook** (`internal/adapter/aimoderation` → ai-gateway `POST /v1/ai/moderate-image`, a 768 px JPEG preview,
   client `media-service` with scope `ai:use`): `ALLOW`, `REVIEW` (watermarks, contact details, text overlays, not a
   product photo …) or `BLOCK` (prohibited content). When the hook fails or times out, `failMode: closed` (uat, prod)
   holds the image and `open` (dev) publishes it with the note "AI moderation skipped".

`moderation.verdict` is `PASSED` (the cleaned image is written under its public key, the quarantined upload deleted,
`url` public), `HELD` (status `FLAGGED`, private; the owner and staff see a 5-minute signed URL) or `BLOCKED` (status
`REJECTED`, deleted). Staff with `media:moderate` or `catalog:moderate` release or reject held images with
`POST /v1/assets/{id}/review {decision: APPROVE|REJECT, note}`. Updates are optimistic (`asset.version`), so two
concurrent completions publish once. Tests use fakes and httptest servers: UploadThing and the AI are never called.

| Purpose | Store (dev/uat/prod) | Visibility | Rules |
|---|---|---|---|
| `product-image` | **UploadThing** (R2 as alternative) | public CDN | JPG/PNG/WebP ≤ 8 MB; per-slot checks: the main image needs ≥ 1000 px on its longest side, a pure white background and the product filling ≥ 75 % of the frame (see "Image checks") |
| `storefront` | UploadThing | public | JPG/PNG/WebP ≤ 8 MB |
| `proof-photo`, `return-photo` | private bucket | 5-minute signed URLs | images ≤ 10 MB |
| `kyc-document` | private, object-locked bucket on the primary cloud | 5-minute signed URLs, owner or staff only | images or PDF ≤ 15 MB |
| `purchase-order` | private bucket | long-lived signed URLs; staff, `retail-service`, `gateway-bff` | PDF ≤ 10 MB |
| `shipping-document` | private bucket | long-lived signed URLs; staff, `retail-service`, `gateway-bff` | PDF or JPG/PNG/WebP ≤ 15 MB |
| `career-resume` | private bucket | long-lived signed URLs; the applicant, staff, `retail-service`, `gateway-bff` (guests) | PDF ≤ 5 MB |
| `export` | private bucket | long-lived signed URLs; staff, `retail-service`, `gateway-bff` | CSV or XLSX ≤ 50 MB |

The real type is checked on completion for every purpose: CSV must sniff as plain text (HTML, scripts and binaries
declared as CSV are rejected) and XLSX must be a ZIP package with `[Content_Types].xml` and `xl/workbook.xml` (read
from the local file headers in the first 16 MB).

### Service clients (S243) and BFF-forwarded tokens (S250)

identity issues client-credentials tokens with the client id as audience and subject (principal type `SERVICE`),
so besides tokens for its own audience media accepts the **own service tokens of `retail-service` and
`gateway-bff`** (`internal/adapter/authn`). Those two clients may use only the document purposes above, which in turn
refuse every other service client; human callers of `purchase-order`, `shipping-document` and `export` must be staff.
Existing purposes keep their rules (owner org, uploader or staff/service).

**User tokens forwarded by the BFF** (`auth.forwardedAudiences: [gateway-bff]`): a signed-in user's access token
minted for the BFF's audience is accepted **as that user** — sellers uploading product images through Seller Center,
signed-in applicants uploading résumés, staff storing exports from the staff console — so every purpose rule applies
to the user, never to the BFF. A service token of another client with that audience is refused.

### Signed download links

Private assets come with a signed `url` and its `urlExpiresAt`. `GET /v1/assets/{id}?urlTtlSeconds=N` asks for a
lifetime: the document purposes get up to `links.maxLongTtl` (`SIGNED_URL_MAX_TTL`, default 14 days, e.g. for the PO
link e-mailed to suppliers); every other purpose stays at `links.defaultTtl` (`SIGNED_URL_DEFAULT_TTL`, 5 minutes).
Longer requests are capped, not refused, and `urlExpiresAt` reports what was granted.

**Media-hosted long-lived links (S250).** Storage signers cap lifetimes — S3-compatible stores (AWS S3, R2, GCS via
HMAC, MinIO) and UploadThing at 7 days, and on AWS with IRSA a presigned URL dies with the session credentials
(hours). So with `LINK_SIGNING_KEY` set, a request above the default lifetime returns a **media-hosted link**
`<publicBaseUrl>/v1/l/<token>` (`publicBaseUrl` defaults to `<API_BASE_URL>/media`) valid for the granted lifetime
(14 days for the PO e-mailed to suppliers) on every cloud: `GET /v1/l/{token}` needs no bearer token, checks the
HMAC-SHA256 token (asset id + expiry; `LINK_SIGNING_KEYS_PREVIOUS` still verify after a rotation) and redirects (302,
`no-store`, `no-referrer`) to a fresh storage URL of `links.redirectTtl` (5 minutes). Expired links answer 410, forged
ones 404, and only document purposes are served. Without the key, long links stay storage-signed and capped as above.
notifications fetches PO attachments from these links (its allow-list adds `<API_BASE_URL>/media/v1/l/`).

Stores implement one `ObjectStore` port: **UploadThing** (REST v7), **S3-compatible** (AWS S3, Cloudflare R2, Google
Cloud Storage via HMAC interoperability, MinIO locally) and **Azure Blob** (SAS). Which store serves which purpose is
configuration (`stores` + `purposes` in `config/<env>.yaml`), so the cloud decision stays open.

Contract: [`contracts/openapi/media.yaml`](../../../contracts/openapi/media.yaml).

## Configuration

`DB_URL`, `UPLOADTHING_API_KEY` + `UPLOADTHING_APP_ID` or `UPLOADTHING_TOKEN` (the dashboard's base64 token),
`R2_ACCESS_KEY_ID`/`R2_SECRET_ACCESS_KEY`, `AZURE_STORAGE_KEY`, `MINIO_ACCESS_KEY`/`MINIO_SECRET_KEY` (local),
`AUTH_ENABLED`, `AUTH_ISSUER_URL`, `AUTH_AUDIENCE`, `AUTH_FORWARDED_AUDIENCES` (`gateway-bff`), `SIGNED_URL_DEFAULT_TTL`
(5m), `SIGNED_URL_MAX_TTL` (336h), `LINK_SIGNING_KEY` (secret, ≥ 32 characters), `LINK_SIGNING_KEYS_PREVIOUS`,
`LINK_REDIRECT_TTL`, `API_BASE_URL` / `PUBLIC_BASE_URL`, `AI_GATEWAY_URL`, `MODERATION_AI_ENABLED`,
`MODERATION_AI_FAIL_MODE` (`open` | `closed`), `MODERATION_AI_TIMEOUT`, `MODERATION_DUPLICATE_DISTANCE`,
`MODERATION_MAX_PIXELS`, `AUTH_TOKEN_URL`, `MEDIA_CLIENT_ID` (`media-service`), `MEDIA_CLIENT_SECRET` (secret),
`MEDIA_CLIENT_SCOPES` (`ai:use`). AWS credentials come from the default chain (IRSA on EKS).

## Run and test

```bash
cd go/services/media
GOTOOLCHAIN=go1.27.2 go test ./...   # rules, link policy, inspector (CSV/XLSX), service-client and forwarded tokens,
                                     # handlers, UploadThing (httptest: private uploads, multipart ingest, local URL
                                     # signing), metadata stripping + dHash, moderation flows with a fake AI hook,
                                     # media-hosted links, MinIO and PostgreSQL (postgres:17) Testcontainers
```
