# Authorized Signatory Signature - configuration, access and migration

## 1. JWT verification settings (required before any signature endpoint works)

The backend defines **no** signing key, algorithm, issuer, audience or role-claim name; they must come
from the UMS token contract. Until `algorithm`, a key and `roles-claim` are all set, every signature
endpoint (including reads) answers **503** and nothing can be uploaded, replaced or removed.

| Environment variable | Property | Required | Meaning |
|---|---|---|---|
| `AUTH_JWT_ALGORITHM` | `app.security.jwt.algorithm` | yes | `HS256/384/512` or `RS256/384/512`. The token header `alg` must equal it exactly. |
| `AUTH_JWT_SECRET` | `app.security.jwt.secret` | HS* only | Shared secret (>= 32 bytes for HS256). |
| `AUTH_JWT_SECRET_ENCODING` | `app.security.jwt.secret-encoding` | no | `UTF8` (default) or `BASE64` - how the secret string is turned into key bytes. |
| `AUTH_JWT_PUBLIC_KEY_PEM` | `app.security.jwt.public-key-pem` | RS* only | X.509 SubjectPublicKeyInfo PEM; literal `\n` accepted. |
| `AUTH_JWT_ROLES_CLAIM` | `app.security.jwt.roles-claim` | yes | Claim holding the user's roles (string, list, or dotted path such as `realm.roles`). |
| `AUTH_JWT_REQUIRED_ROLE` | `app.security.jwt.required-role` | no | Defaults to `FINANCE_EXECUTIVE` (case-insensitive, `ROLE_` prefix tolerated). |
| `AUTH_JWT_ISSUER` | `app.security.jwt.issuer` | no | Enforced only when set. |
| `AUTH_JWT_AUDIENCE` | `app.security.jwt.audience` | no | Enforced only when set. |

Always enforced: valid signature, mandatory `exp` (30 s clock skew), `nbf` when present.

**What must be obtained from the UMS owners:** the signing algorithm, the secret or public key, the exact
name of the roles claim and how `FINANCE_EXECUTIVE` appears in it, and the issuer/audience values if UMS sets them.

## 2. Access-control policy

| Endpoint | Requirement |
|---|---|
| `PUT /api/v1/company-profile/{id}/signature` (upload/replace) | verified token **with** `FINANCE_EXECUTIVE` |
| `DELETE /api/v1/company-profile/{id}/signature` | verified token **with** `FINANCE_EXECUTIVE` |
| `GET /api/v1/company-profile/{id}/signature` (metadata) | any verified, unexpired token |
| `GET /api/v1/company-profile/signature-assets/{ref}` (image) | any verified, unexpired token |

No public-access policy has been approved, so reads are authenticated. Consequence for the frontend:
an `<img src>` cannot send the `Authorization` header, so the image must be fetched with `fetch`/axios
(`Authorization: Bearer ...`) and displayed from a Blob URL. Server-side PDF generation reads the image
from the database directly and does not use HTTP, so PDFs are unaffected. If invoice rendering must work
without a token, an explicit decision to make the image endpoint public (or to embed it via a signed,
short-lived URL) is required.

## 3. Database migration status

`V7__authorized_signature.sql` is a manual script: there is no Flyway/Liquibase runtime or history table
in this application or in the `accounts_receivable` database (checked read-only). Therefore:

* **Do not assume V7 has been executed.** The app runs with `spring.jpa.hibernate.ddl-auto=update`, which
  created `company_signature_asset`, `company_profile.authorized_signature_reference` and
  `invoice.seller_signature_reference` in the dev database when the application context first started
  with this code. Those objects match V7 except `company_profile_id`/`sha256` are `VARCHAR` not `CHAR`
  and the index `idx_signature_asset_profile` does not exist.
* **Environment where Hibernate already created the objects (current dev DB):** do *not* run V7 (the
  `ALTER TABLE ... ADD COLUMN` statements fail with "duplicate column"). Optionally add only the index:
  `CREATE INDEX idx_signature_asset_profile ON company_signature_asset (company_profile_id);`
* **Fresh environments where DDL auto-update is disabled:** run V7 once, as is, before deploying.
* Verify afterwards: the three objects exist, `company_signature_asset` has 7 columns, and both reference
  columns are nullable. Existing invoices keep a NULL signature reference and render without one.
