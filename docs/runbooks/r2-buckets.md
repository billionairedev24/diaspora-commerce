# Cloudflare R2 buckets and API tokens (media service)

Production media lives in Cloudflare R2. Two buckets, one API token per bucket, no AWS S3, UploadThing or MinIO.
`go/services/media` refuses to start in `APP_ENV=prod` if any of this is wrong (`internal/config/validate.go`).

| Bucket | Holds | Public? | Token (Object Read & Write, this bucket only) |
|---|---|---|---|
| `afrotransact-prod-media` | released product images and storefront media | yes, custom domain `media.afrotransact.com` | `R2_ACCESS_KEY_ID`, `R2_SECRET_ACCESS_KEY` |
| `afrotransact-prod-kyc-evidence` | **quarantine** (uploads waiting for moderation), KYC documents, proof and return photos, purchase orders, shipping documents, resumes, exports | never: no custom domain, `r2.dev` off | `R2_PRIVATE_ACCESS_KEY_ID`, `R2_PRIVATE_SECRET_ACCESS_KEY` |

How a public image flows: the browser gets a presigned PUT into the **private** bucket under `quarantine/...`; on
`Complete` media inspects, strips EXIF, moderates, then copies the cleaned image into the **public** bucket and deletes
the quarantined upload. The public bucket therefore never contains an unreleased image and no WAF rule is needed to hide
`quarantine/` (`quarantineStore: private` in `config/prod.yaml`). Optional belt and braces: a Cloudflare WAF custom rule
on `media.afrotransact.com` blocking URI paths that start with `/quarantine/`.

No secret goes in the repo. Values go only into the secret store (below).

## Test environment (8-day trial) versus production

Same steps, different names and domain. The trial runs `docs/runbooks/trial-environment.md` and uses a throwaway domain.

| | Trial | Production |
|---|---|---|
| Public bucket | `afrotransact-prod-media` in the trial Cloudflare account (the prod config's bucket names are fixed; the trial account is separate and torn down afterwards) | `afrotransact-prod-media` |
| Private bucket | `afrotransact-prod-kyc-evidence` in the trial account | `afrotransact-prod-kyc-evidence` |
| Public domain | `media.trial.afrotransact.com`, set through `MEDIA_PUBLIC_URL` | `media.afrotransact.com` |
| Web origins for CORS | `https://trial.afrotransact.com`, `https://seller.trial.afrotransact.com`, `https://admin.trial.afrotransact.com` | `https://afrotransact.com`, `https://www.afrotransact.com`, `https://seller.afrotransact.com`, `https://admin.afrotransact.com` |
| Secret store path | `afrotransact/trial/media` | `afrotransact/prod/media` |
| Afterwards | delete both buckets (empty them first) and revoke both tokens at teardown | keep; never reuse the trial tokens |

If the trial uses the production Cloudflare account, bucket names collide: delete the trial buckets at teardown and
create fresh ones for production, with new tokens.

Use real test data only. Do not upload real KYC documents to the trial.

## 1. Create the two buckets

1. Sign in at `https://dash.cloudflare.com`, pick the account.
2. Left menu: **Storage & databases** > **R2 object storage** > **Overview**. If R2 is not enabled, click
   **Purchase R2 Plan** (free tier needs a payment method) and accept.
3. Copy the **Account ID** shown on the right of the overview page. This is `R2_ACCOUNT_ID`.
4. Click **Create bucket**. Name `afrotransact-prod-media`. Location: **Automatic** (or a hint near your users, for
   example Western Europe or Eastern North America; you cannot change it later). Default storage class **Standard**.
   Click **Create bucket**.
5. Repeat for `afrotransact-prod-kyc-evidence`, same location.
6. Open each bucket > **Settings** and confirm **Public access** shows `r2.dev subdomain: Disabled` (leave it disabled
   on both; the public bucket is served through the custom domain only).

## 2. Custom domain for the public bucket only

1. Open `afrotransact-prod-media` > **Settings** > **Custom Domains** > **Add**.
2. Enter `media.afrotransact.com` (trial: `media.trial.afrotransact.com`). The domain's zone must be on this Cloudflare
   account.
3. Click **Continue**, review the DNS record Cloudflare will create, click **Connect Domain**. Wait until the status is
   **Active** (a minute or two, certificate issued automatically).
4. Do not add a custom domain or enable `r2.dev` on `afrotransact-prod-kyc-evidence`.
5. Optional caching: **Rules** > **Cache Rules** for the host: cache everything, edge TTL 1 month (objects are
   immutable: keys contain a UUID).
6. Optional hardening: **Security** > **WAF** > **Custom rules** > **Create rule**: name `block quarantine`, expression
   `(http.host eq "media.afrotransact.com" and starts_with(http.request.uri.path, "/quarantine/"))`, action **Block**.

## 3. CORS (browser uploads)

Uploads are browser-direct presigned PUTs into the **private** bucket (quarantine), so CORS is required there. The public
bucket only serves GETs to images and needs no CORS (add the GET-only rule below if the storefront draws images on a
canvas).

1. Open `afrotransact-prod-kyc-evidence` > **Settings** > **CORS Policy** > **Add CORS policy**.
2. Switch to the **JSON** tab and paste (use the trial origins for the trial):

```json
[
  {
    "AllowedOrigins": [
      "https://afrotransact.com",
      "https://www.afrotransact.com",
      "https://seller.afrotransact.com",
      "https://admin.afrotransact.com"
    ],
    "AllowedMethods": ["PUT", "GET", "HEAD"],
    "AllowedHeaders": ["*"],
    "ExposeHeaders": ["ETag"],
    "MaxAgeSeconds": 3600
  }
]
```

3. **Save**. Mobile apps upload natively and are not subject to CORS.
4. Optional, public bucket `afrotransact-prod-media` > **CORS Policy**, GET only:

```json
[
  {
    "AllowedOrigins": ["https://afrotransact.com", "https://www.afrotransact.com", "https://seller.afrotransact.com", "https://admin.afrotransact.com"],
    "AllowedMethods": ["GET", "HEAD"],
    "AllowedHeaders": ["*"],
    "MaxAgeSeconds": 86400
  }
]
```

Never use `"*"` as an allowed origin on the private bucket.

## 4. One API token per bucket (Object Read & Write, that bucket only)

Do this twice. Never create one token covering both buckets, and never use the account-wide Admin token.

1. **R2 object storage** > **Overview** > **API** (right side) > **Manage API Tokens** (Account API Tokens).
2. **Create Account API token** (use **User API token** only if account tokens are unavailable).
3. Token name: `media-prod-public` (second token: `media-prod-private`).
4. Permissions: **Object Read & Write**.
5. **Specify bucket(s)**: select `afrotransact-prod-media` only (second token: `afrotransact-prod-kyc-evidence` only).
6. TTL: **Forever** for production (put a rotation reminder in the calendar every 6 months); for the trial set the TTL
   to 10 days. Client IP filtering: leave empty (cluster egress IPs change) unless you pin the cluster NAT address.
7. Click **Create API Token**. The next page shows **Access Key ID** and **Secret Access Key** once. Copy both now into
   the secret store (section 5). Ignore the "Token value" and the jurisdiction endpoint; the media service builds the
   endpoint from the account id.
8. Close the page. The secret cannot be shown again; if lost, delete the token and create a new one.

## 5. Where each value goes

Secrets go to the cloud secret store at `afrotransact/<env>/media` with `infra/tofu/scripts/secret-set.sh` (External
Secrets syncs them into the pod). Nothing is committed.

| Value | Env var / secret key | Notes |
|---|---|---|
| Account ID | `R2_ACCOUNT_ID` | builds `https://<id>.r2.cloudflarestorage.com`; or set `R2_ENDPOINT` to the full URL |
| Public token Access Key ID | `R2_ACCESS_KEY_ID` | token scoped to `afrotransact-prod-media` |
| Public token Secret Access Key | `R2_SECRET_ACCESS_KEY` | |
| Private token Access Key ID | `R2_PRIVATE_ACCESS_KEY_ID` | token scoped to `afrotransact-prod-kyc-evidence` |
| Private token Secret Access Key | `R2_PRIVATE_SECRET_ACCESS_KEY` | |
| Public domain | `MEDIA_PUBLIC_URL` | `https://media.afrotransact.com` (trial: the trial domain); already injected by the Helm library |
| Link signing key | `LINK_SIGNING_KEY` | at least 32 random characters, `openssl rand -base64 48` |

Example (run from your machine, values read from a prompt, never from shell history files in the repo):

```bash
infra/tofu/scripts/secret-set.sh afrotransact/prod/media R2_ACCOUNT_ID
infra/tofu/scripts/secret-set.sh afrotransact/prod/media R2_ACCESS_KEY_ID
infra/tofu/scripts/secret-set.sh afrotransact/prod/media R2_SECRET_ACCESS_KEY
infra/tofu/scripts/secret-set.sh afrotransact/prod/media R2_PRIVATE_ACCESS_KEY_ID
infra/tofu/scripts/secret-set.sh afrotransact/prod/media R2_PRIVATE_SECRET_ACCESS_KEY
```

Then restart the media deployment. The service exits at startup with a clear message if a value is missing, the
endpoint still contains `ACCOUNT_ID`, the two stores share a credential, or a private purpose points at the public
bucket.

## 6. Test with a presigned upload

Replace the host with `api.trial...` for the trial. Get a staff or seller token first.

1. Request an upload:

```bash
curl -sS -X POST https://api.afrotransact.com/media/v1/uploads \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"purpose":"product-image","fileName":"test.jpg","contentType":"image/jpeg","size":'"$(stat -c%s test.jpg)"'}'
```

   The response has the asset `id` and an `upload` object with `url`, `method` (`PUT`) and `headers`. The URL host must be
   `<account id>.r2.cloudflarestorage.com` and the path must start with `afrotransact-prod-kyc-evidence/quarantine/`
   (or the bucket in the hostname). The asset `url` is empty: not public yet.
2. Upload the file with exactly the returned headers:

```bash
curl -sS -X PUT "$UPLOAD_URL" -H "Content-Type: image/jpeg" --data-binary @test.jpg -w '%{http_code}\n'
```

   Expect `200`. `403 SignatureDoesNotMatch` means a header differs from the signed ones (send every header returned,
   including any `x-amz-*`). From a browser, a CORS error means section 3 is missing the web origin.
3. Complete it (inspection, EXIF stripping, moderation, publish):

```bash
curl -sS -X POST https://api.afrotransact.com/media/v1/assets/$ID/complete -H "Authorization: Bearer $TOKEN"
```

   Expect `status: "ready"` and a `url` under `https://media.afrotransact.com/product-image/...`. A held image has
   `status: "flagged"` until staff release it.
4. Check it is public: `curl -I <url>` returns `200` with `content-type: image/jpeg`.
5. Check the isolation:
   - `curl -I https://media.afrotransact.com/quarantine/anything` returns `404` (and the quarantined object is gone from
     the private bucket, see the dashboard **Objects** tab).
   - The private bucket has no public URL: `curl -I https://<account id>.r2.cloudflarestorage.com/afrotransact-prod-kyc-evidence/x`
     returns `403`.
   - Request a `kyc-document` upload, complete it and confirm the returned link is a short-lived signed URL (about 5
     minutes) and that it lands in the private bucket only.
6. Token scoping: with the public token's keys, a read of the private bucket must fail (AccessDenied), and vice versa.

## Rotating or revoking a token

Create the replacement token first (section 4), update the secret store, restart media, confirm an upload works, then
delete the old token (**Manage API Tokens** > the token > **Delete**).

## Tear down the trial

Empty both buckets (**Objects** > select all > **Delete**), delete the buckets, delete the custom domain, revoke both
tokens, delete the trial secret path.

## AI spend ceiling and the private MCP server

Both are owner decisions and are enforced in code, not only in runbooks. See the ai-gateway and mcp-server sections of
`docs/runbooks/ai-spend-and-mcp-access.md`.
