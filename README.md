# Nightgals

A verified-members-only social app for finding people to go out with.

Two things make it different from a normal dating app:

1. **Nobody can post a photo or a video until a human being has looked at their
   government ID and approved them.** Every endpoint that publishes anything
   checks that gate.
2. **Verified does not mean identified.** Members are known to each other by a
   generated handle like `VelvetFalcon482` — never by the legal name on their
   ID. The platform knows exactly who you are; the app does not tell anybody
   else.
3. **Scrolling is free, seeing the person is paid.** A feed card is free. What a
   creator marked exclusive sits behind a paywall, at the price *she* sets.
4. **Money moves in both directions.** Viewers pay creators for access. Creators
   pay the platform for the right to publish, via a bronze/silver/gold package.
5. **A password never signs anyone in on its own.** Every sign-in is completed
   with a six-digit code emailed to the account.

- Java 21, Spring Boot 4.1
- PostgreSQL in Docker, schema owned by Flyway
- JWT auth with rotating refresh tokens, plus a one-time code on every sign-in
- Uploads in object storage — AWS S3 in production, MinIO locally, same code path
- Full Swagger/OpenAPI documentation

## Run it

```bash
docker compose up -d      # Postgres on :5433, MinIO on :9000 (console :9001)
BOOTSTRAP_ADMIN_PASSWORD='ChangeMe123!' ./mvnw spring-boot:run
```

Then open **http://localhost:8080/swagger-ui.html**.

Flyway builds the schema on first start. `BOOTSTRAP_ADMIN_PASSWORD` creates
`admin@nightgals.local` on an empty database — without an admin there is nobody
who can approve the first member. It is only used once; later starts ignore it.

> Postgres is published on **5433**, not 5432, because another Postgres container
> was already using 5432 on this machine. Change it in `compose.yaml` if you like.

```bash
./mvnw test          # 95 tests, spins up a throwaway Postgres via Testcontainers
```

Sign-in codes are emailed, so a machine with no outbound SMTP needs
`MAIL_ENABLED=false`, which writes each code to the log instead. **If mail is
broken, nobody can sign in** — that is the point of the second factor, but it
means a mail outage is a total outage. `OTP_LOGIN_REQUIRED=false` is the
break-glass. Gmail returning `535 5.7.8 BadCredentials` means the app password is
wrong, revoked, or belongs to an account without 2-Step Verification enabled —
see `.env.example`. `docker compose`
also brings up MinIO and creates the `nightgals-media` bucket; set
`STORAGE_PROVIDER=local` to write to the filesystem instead.

## Signing in takes two calls

A correct password does not produce a session. It produces a *challenge*, and a
six-digit code goes to the address on the account:

```
POST /api/v1/auth/login       -> { otpRequired: true, challengeId, maskedEmail }
POST /api/v1/auth/otp/verify  -> tokens
```

Codes are single-use, expire in ten minutes, and a challenge burns after five
wrong guesses. Only the SHA-256 is stored, so a database dump contains no usable
codes. Opening challenges is rate-limited per account, which is what stops
somebody who already has a password from simply retrying until one is guessable.

Set `OTP_LOGIN_REQUIRED=false` to fall back to password-only sign-in — for local
work, or as a break-glass measure when the mail provider is down. The response
shape does not change: `otpRequired` comes back `false` and `auth` holds the
tokens.

**Registering still takes one call.** The account is created and signed in
immediately; a confirmation code is sent alongside but nothing waits on it.
Somebody who arrived to look at one creator is looking at her seconds after
submitting the form.

## Signing in vs. being verified

These are separate, on purpose. **Verification is not required to log in.**
Registration returns tokens immediately and login works from that moment — only
`SUSPENDED` and `DEACTIVATED` accounts are refused. People can sign up, look
around, and finish KYC whenever they are ready.

What verification gates is *publishing* — uploading media, going live, appearing
to other members — and, since it is also a browse gate, *looking*: an unverified
account gets `403` from the feed and from other people's profiles. Only members
who completed verification can see the members who completed verification.

## Pseudonymous by default

Every account is assigned a handle at registration, so nobody has to pick one to
get started. What other members can see:

| | Owner & staff | Another member |
|---|---|---|
| `username` | yes | **yes — this is the only identity shown** |
| `displayName` (optional private nickname) | yes | no |
| exact `dateOfBirth` | yes | no (only `age`) |
| email | yes | no |
| legal name, document number, ID images | staff only | never |

`displayName` is optional and private — a profile can be created without one.
The legal name and date of birth captured during KYC live in `kyc_submissions`
and appear only in the admin DTOs.

```
GET  /api/v1/usernames/suggestions?count=5   fresh handles (no auth — for the signup screen)
GET  /api/v1/me/username                     my handle
PUT  /api/v1/me/username                     claim a specific one
POST /api/v1/me/username/reroll              take a new random one
```

Rules: 3–30 characters, starts with a letter, letters/digits/underscores.
Handles impersonating the platform or its staff (`admin*`, `nightgals*`,
`official*`, `mod_*`, `support`, …) are refused. Changes are limited by a
cooldown — 30 days by default, `nightgals.username.change-cooldown` — so a
handle is a stable identity rather than a disposable one. Fixing only the
capitalisation of your own handle is free.

## Two kinds of account

`accountType` is set at registration and decides the entire journey. **KYC is a
creator requirement only** — a fan never uploads a passport to pay for content.

```jsonc
POST /api/v1/auth/register
{ "email": "...", "password": "...", "accountType": "VIEWER" }   // the default
{ "email": "...", "password": "...", "accountType": "CREATOR" }
```

`GET /api/v1/me` returns `nextStep`, which is the field to drive the UI from:

| Account | nextStep |
|---|---|
| Viewer | `BROWSE` — nothing to complete, ever |
| Creator | `CREATE_PROFILE` → `SUBMIT_KYC` → `AWAIT_REVIEW` → `DONE` (`RESUBMIT_KYC` if refused) |

Asking a fan for a date of birth to buy a photo is the friction this split
removes — a viewer is never *prompted* for a profile or an identity document.

They are not blocked from one either: **submitting a profile or starting KYC
upgrades the account automatically.** Nobody fills in a public profile by
accident, so treating it as intent removes a round trip and a whole class of
client bug. A viewer who only browses and pays is never upgraded.

`POST /api/v1/me/become-creator` upgrades a viewer explicitly, for clients that
want a deliberate "start creating" step. They keep their
handle, their unlocks, their subscription and their payment history; `nextStep`
moves to `CREATE_PROFILE`. One direction only — a creator with published content
and an earnings ledger cannot revert.

| | Anonymous visitor | Registered viewer | Creator (KYC'd) |
|---|---|---|---|
| Browse feed, read profiles | ✅ | ✅ | ✅ |
| Content the creator marked **FREE** | ✅ | ✅ | ✅ |
| See who is live | ✅ | ✅ | ✅ |
| Unlock / subscribe | ❌ sign in | ✅ | ✅ |
| Content marked **EXCLUSIVE** | ❌ | ✅ once paid | ✅ once paid |
| Post content, go live, earn | ❌ | ❌ | ✅ |

The public endpoints — `GET /members`, `/members/{id}/profile`,
`/members/{id}/media`, `/live`, and free media files — need no token at all.
Anything `EXCLUSIVE` returns `401` to an anonymous caller and `402` to a
signed-in one who has not paid.

## Free vs exclusive content

Every photo, video and live session carries a **tier**, chosen by the creator —
not derived from upload order:

| Tier | Who sees it |
|---|---|
| `FREE` | Everyone, including anonymous visitors. The shop window. |
| `EXCLUSIVE` | Only viewers who unlocked that creator or hold a subscription. |

```bash
# a teaser anyone can see
curl -X POST /api/v1/me/media/photos -F file=@shot.jpg -F tier=FREE
# the thing being sold  (tier defaults to EXCLUSIVE)
curl -X POST /api/v1/me/media/photos -F file=@shot.jpg -F tier=EXCLUSIVE
# move it later
curl -X PATCH /api/v1/me/media/{id} -d '{"tier":"FREE"}'
```

Live sessions take the same `tier` — a free broadcast pulls people in, an
exclusive one is for paying viewers.

**The profile picture is always `FREE`.** The first photo a creator uploads
becomes it, and it cannot be moved behind the paywall — a card with no image
gives nobody a reason to pay. Make another photo primary first.

Locked items still appear in the gallery with `locked: true` and no `url`, so
clients can render blurred placeholders and an honest count. Only consuming
`EXCLUSIVE` content counts toward a creator's share of subscription revenue —
free views earn nothing.

## Money

Money moves in two directions, and they are worth keeping straight.

### Viewers pay creators

Free:

- the browse feed and every card on it — handle, age, city, vibe, bio
- everything the creator herself marked `FREE`; that is her shop window
- the counts of what is locked, so the paywall is honest rather than a mystery

Paid: everything she marked `EXCLUSIVE`, and her live sessions.

**One payment, one creator, all of her content.** There is no photo tier and no
video tier — a viewer picks a person, not a menu, and every extra choice on a
payment screen is somewhere to hesitate.

**Each creator sets her own price.** `PUT /me/profile` takes `unlockPriceMinor`;
leaving it null sells her at the platform default. The price is bounded by
`min-price-minor` and `max-price-minor` — the floor keeps the commission worth
collecting, the ceiling catches a mistyped extra zero. Her price rides along on
her feed card and her profile, so no client has to ask twice.

| | What it buys | Price |
|---|---|---|
| Profile unlock | one creator, everything she posted, 30 days | hers, default KES 100 |
| Subscription | every creator, for the plan's term | KES 300 / 900 / 2400 (1w / 1m / 3m) |

```
GET  /api/v1/billing/plans              prices (open, no auth)
POST /api/v1/billing/unlocks/{userId}   unlock one creator, at her price
POST /api/v1/billing/subscriptions      subscribe
GET  /api/v1/billing/entitlements       what the caller can already see
GET  /api/v1/billing/purchases          payment history
```

### Creators pay the platform

Publishing needs a package. Passing KYC says *who you are*; the package says
*what you may post*:

| | Covers | Allowance | Price |
|---|---|---|---|
| `BRONZE` | photos only | 12 photos | KES 500 / month |
| `SILVER` | video only | 6 videos | KES 1000 / month |
| `GOLD` | photos and video | 40 photos, 20 videos | KES 1500 / month |

```
GET  /api/v1/billing/creator-packages        the catalogue (open, no auth)
GET  /api/v1/billing/creator-packages/mine   package held + allowance left
POST /api/v1/billing/creator-packages        buy one
```

An upload with no package returns **402**; one the package does not cover
returns 402 naming the upgrade; one past the allowance returns **409**. The three
are kept distinct because each has a different next step for the creator.

Allowances count what is *currently posted*, so deleting frees a slot. Renewing
the same package extends the row already in play rather than inserting a second
one starting in the future — which would leave a creator who had just paid being
shown the old expiry date. Switching packages does insert a row, and the
longer-running cover wins, so an upgrade applies at once and a downgrade never
claws back time already paid for.

Limits and prices are configuration (`nightgals.creator-packages`).
`enabled: false` makes publishing free and restores the old flat 9-photo /
3-video allowance.

Locked media is still *listed* — `locked: true` with a null `url` — so a client
can render blurred placeholders and a truthful count. Fetching a locked file
returns **402 Payment Required**, which is the client's cue to open the paywall.

Prices, durations and plans are all configuration (`nightgals.monetization`).
`enabled: false` turns the whole paywall off and makes every entitlement check
pass — useful for a launch period.

### No payment provider is integrated yet

That is deliberate, and the seam is clean. A purchase is created; the configured
`PaymentProvider` says how to pay; access is granted in exactly one place.
Nothing else in the codebase knows how money arrives. Pick one with
`nightgals.monetization.provider`:

| | Behaviour | Admin involved? |
|---|---|---|
| `auto` **(default)** | Purchase is `COMPLETED` before the response is written. `action: NONE`. | No |
| `manual` | Purchase stays `PENDING` with instructions. | Yes |

**`auto` collects nothing.** It exists so the product can be walked end to end —
sign up, buy a package, publish, unlock, watch — without a human confirming every
payment. It logs a banner at every startup saying so. Running it in front of real
users gives away every paid thing on the platform.

`manual` is a real till-number workflow, not a placeholder: money lands on a till
or by transfer, somebody reconciles it, and confirms it here.

```
GET  /api/v1/admin/billing/purchases/pending
POST /api/v1/admin/billing/purchases/{id}/settle?providerReference=MPESA-XYZ
POST /api/v1/admin/billing/grants?viewerId=…&targetId=…&duration=P30D
```

When you wire in M-Pesa Daraja, write one class implementing `PaymentProvider` —
STK push in `startPayment`, callback calls `settle()` — and name it in that
property. **No access-control code changes.** Both routes to `COMPLETED` share
one private `grant()`, so an auto-settled purchase and a webhook-settled one
cannot drift on what they hand out. `settle()` is idempotent and
`provider_reference` is unique per provider, so a replayed webhook cannot grant
twice.

## Creator earnings and payouts

Money owed to creators is an **append-only ledger**, not a balance field. Each
entry records what the viewer paid, the platform's cut, and the creator's net —
so the balance is always reconstructible and always auditable. Nothing is ever
incremented in place.

Entries move `PENDING` → `AVAILABLE` → `RESERVED` → `PAID`:

| State | Meaning |
|---|---|
| `PENDING` | Just earned, inside the hold period — a refund can still reverse it |
| `AVAILABLE` | Hold elapsed, payable |
| `RESERVED` | Attached to an open payout; cannot be spent twice |
| `PAID` | The payout completed |
| `REVERSED` | Refunded or clawed back |

### How revenue is attributed

**Unlocks are exact.** A viewer paid KES 100 for one creator, so that creator
earns KES 100 minus commission. Guarded by a unique index on the purchase, so
replaying settlement cannot pay twice.

**Subscriptions use user-centric attribution.** A subscriber's payment is split
among the creators *that subscriber actually viewed* that month — not thrown into
one platform-wide pool. Viewing somebody fifty times counts once: it is the
breadth of a subscriber's attention that divides their payment, not the volume.
A subscriber who viewed nobody contributes nothing and the platform keeps it.

Attribution is not automatic, because it can only be computed once the month's
viewing is in:

```
POST /api/v1/admin/payouts/distribute?period=2026-07
```

Idempotent per (purchase, creator, period) — re-running credits creators newly
viewed since the last run without paying anyone twice.

### The superadmin pays creators by hand

There is no automated disbursement. The queue is the day's work:

```
GET  /api/v1/admin/payouts/queue              who is waiting, how much, where to send
POST /api/v1/admin/payouts/{id}/approve       optional: intent to pay
POST /api/v1/admin/payouts/{id}/paid?reference=QGR7XK2LMN
POST /api/v1/admin/payouts/{id}/reject?reason=…
GET  /api/v1/admin/payouts/summary            commission, owed, paid out
POST /api/v1/admin/payouts/adjustments        manual credit or debit
```

The routine: open the queue, check `accountName` against the name on the
creator's verified ID, send the M-Pesa or bank transfer, then record the
transaction code. The reference is **required** — a payment nobody can trace is a
dispute waiting to happen. Rejecting instead returns the reserved entries to the
creator's available balance, so money is never stranded.

The queue is the only place a full destination number is shown. Creators see
their own masked (`********5678`).

### The creator's side

```
GET  /api/v1/me/earnings           available / pending / reserved / paid / lifetime
GET  /api/v1/me/earnings/entries   the ledger, with gross and commission per line
PUT  /api/v1/me/payout-account     M-Pesa number or bank account
POST /api/v1/me/payouts            request the whole available balance
GET  /api/v1/me/payouts            history
```

Safeguards, all covered by tests:

- **One payout in flight per creator**, enforced by a partial unique index — so
  two concurrent requests cannot both reserve the same balance.
- **The payout account is frozen while a payout is open**, because changing it
  mid-flight would send money to a destination no administrator checked.
- **The destination is copied onto the payout at request time**, so editing the
  account later never rewrites the history of where money actually went.
- **Below the minimum is refused** (default KES 1000, to keep transfer fees sane).

Configure under `nightgals.earnings`: `commission-percent` (default 30),
`hold-period` (default `P7D`), `minimum-payout-minor` (default KES 1000).

## Live sessions

Nightgals stores session metadata only — it does not ingest, transcode or serve
video. The host supplies a `playbackUrl` from whatever streaming provider they
use; this API decides who may see it.

```
POST /api/v1/me/live                    announce or start (requires APPROVED)
POST /api/v1/me/live/{id}/end           stop
GET  /api/v1/live                       who is broadcasting now
GET  /api/v1/live/{id}/playback         the URL — 402 unless the host is unlocked
```

Unpaid viewers still *see* that a session is happening (`locked: true`,
`liveNow` on the feed card); they just cannot get the URL.

## Onboarding, which is the whole product

Every account carries a `verificationStatus`. It is what authorisation is built on:

| State | Meaning | Can post media? |
|---|---|---|
| `UNVERIFIED` | Registered, no ID submitted | No |
| `PENDING_REVIEW` | ID submitted, waiting on a human | No |
| `APPROVED` | An admin matched their ID to their selfie | **Yes** |
| `REJECTED` | Review failed, reason recorded | No |

The path through it:

```
POST /api/v1/auth/register            → UNVERIFIED, handle assigned
PUT  /api/v1/me/profile                 date of birth, city, vibe (nickname optional)
POST /api/v1/me/kyc                     document type + identity details → DRAFT
POST /api/v1/me/kyc/documents/{kind}    one call per required image
POST /api/v1/me/kyc/submit            → PENDING_REVIEW
     ... an admin reviews ...
POST /api/v1/admin/kyc/{id}/review    → APPROVED or REJECTED
POST /api/v1/billing/creator-packages   bronze, silver or gold
POST /api/v1/me/media/photos            now unlocked
```

`GET /api/v1/me` returns a `nextStep` field (`BROWSE`, `CREATE_PROFILE`,
`SUBMIT_KYC`, `AWAIT_REVIEW`, `RESUBMIT_KYC`, `DONE`) so a client can decide what
screen to show with one call. **Viewers are always `BROWSE`** — none of the above
applies to them.

Which images are required depends on the document type:

| Document type | Required images |
|---|---|
| `NATIONAL_ID` | `ID_FRONT`, `ID_BACK`, `SELFIE` |
| `PASSPORT` | `PASSPORT_PAGE`, `SELFIE` |
| `DRIVERS_LICENSE` | `ID_FRONT`, `ID_BACK`, `SELFIE` |

**KYC and the package are the two gates on posting.** Once a creator is approved
and holds a package, her uploads publish immediately — there is no review queue
and nothing waits on staff. A
moderator can remove an item afterwards with
`POST /api/v1/admin/media/{id}/takedown?reason=…`, which hides it from everyone
but its owner and shows the creator why.

## How identity documents are handled

This app holds the most sensitive data it will ever hold during onboarding, so
the decisions are deliberate:

- **The raw document number is never stored.** On arrival it is normalised,
  salted with a per-environment pepper, SHA-256 hashed, and only the hash plus
  the last 4 characters are persisted. The hash still detects a second account
  opened on the same ID; the reviewer reads the real number off the image.
- **Document images are never publicly reachable.** No public URL, no presigned
  link — they stream through an authenticated endpoint restricted to
  `MODERATOR`/`ADMIN`, with `Cache-Control: no-store`. This is why the S3 store
  hands out no presigned URLs even though the SDK is right there: a presigned URL
  works for anyone holding it until it expires, so it cannot re-check a paywall
  or an audit rule on each request the way the streaming endpoint does.
- **One-time codes are stored only as SHA-256**, like refresh tokens, and are
  swept once they expire.
- **Every document view is audited.** `kyc_access_log` records which staff
  member opened which document, when, and from which IP. The log write is in the
  same transaction as the read, so an image cannot be served without it.
- **Documents are purged on a schedule.** `KycRetentionJob` deletes the files 90
  days after a decision (`nightgals.storage.kyc-retention`). The database rows
  survive with `purgedAt` set, so the evidence that verification happened
  outlives the passport scan itself.
- **A reviewer cannot approve their own submission**, and a decided submission
  cannot be re-decided.
- Passwords are BCrypt cost 12. Refresh tokens are stored only as SHA-256
  hashes, and rotate on every use.

Kenya's Data Protection Act 2019 treats identity documents as sensitive personal
data. The design above is aimed at that standard, but **this has not had a legal
review** — get one before going live, and register with the ODPC as a data
controller.

## Layout

```
src/main/java/com/nightgals/
├── config/     security, OpenAPI, typed properties, admin bootstrap
├── common/     base entity, error envelope, exception handler, hashing
├── storage/    StorageService + S3 and local implementations + upload validation
├── mail/       branded HTML email: one-time codes, receipts, notifications
├── user/       User, roles, verification status, usernames, /me
├── auth/       JWT, refresh tokens, register/login
│   └── otp/    one-time sign-in codes: issue, resend, consume, purge
├── profile/    optional nickname, DOB, city, vibe
├── kyc/        submissions, documents, review queue, audit log, retention job
├── media/      photo and video upload (KYC-gated) + takedown
├── discovery/  the browse feed and its cards
├── billing/    purchases, subscriptions, unlocks, creator packages, entitlements
├── earnings/   creator ledger, payout requests, admin payout queue
└── live/       broadcast session metadata
src/main/resources/db/migration/   V1__init.sql … V10__creator_pricing_and_gender.sql
```

`ddl-auto` is `validate`, so the app refuses to start if the entities and the
Flyway schema ever drift apart. Schema changes go in a new numbered migration —
never by editing one that has already run anywhere.

## Configuration

All of it in `application.yml`, overridable by environment variable. See
`.env.example`.

| Variable | Purpose |
|---|---|
| `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` | Database connection |
| `JWT_SECRET` | Token signing key. **Minimum 32 characters.** |
| `DOCUMENT_HASH_PEPPER` | Salts the document-number hash. Set once per environment — rotating it breaks duplicate detection on existing rows. |
| `nightgals.username.change-cooldown` | Wait between handle changes (default `P30D`) |
| `MONETIZATION_ENABLED` | `false` makes everything free and disables every paywall |
| `PAYMENT_PROVIDER` | `auto` (default) settles every purchase instantly and collects nothing; `manual` needs an admin |
| `nightgals.monetization.*` | Currency, default prices, plans, per-creator price bounds |
| `CREATOR_PACKAGES_ENABLED` | `false` makes publishing free and restores the flat 9-photo / 3-video allowance |
| `nightgals.creator-packages.*` | Bronze/silver/gold prices and allowances |
| `MAIL_HOST`, `MAIL_PORT`, `MAIL_USERNAME`, `MAIL_PASSWORD` | SMTP. Gmail needs an app password. |
| `MAIL_ENABLED` | `false` logs one-time codes instead of sending them. Never correct in production. |
| `APP_BASE_URL` | Where links inside emails point |
| `OTP_LOGIN_REQUIRED` | `false` drops back to password-only sign-in |
| `nightgals.earnings.commission-percent` | Platform's cut (default 30) |
| `nightgals.earnings.hold-period` | How long earnings stay unpayable (default `P7D`) |
| `nightgals.earnings.minimum-payout-minor` | Smallest payout processed (default KES 1000) |
| `BOOTSTRAP_ADMIN_EMAIL` / `_PASSWORD` | First admin, on an empty database only |
| `STORAGE_PROVIDER` | `s3` (default) or `local` |
| `S3_BUCKET`, `S3_REGION` | Object storage target |
| `S3_ENDPOINT` | MinIO's address locally. **Leave blank on AWS** so the SDK resolves the regional endpoint. |
| `S3_ACCESS_KEY` / `S3_SECRET_KEY` | **Leave blank on AWS** and use an instance or task role instead. |
| `S3_PATH_STYLE` | Must be `true` for MinIO; harmless on AWS |
| `STORAGE_ROOT` | Upload directory, when `STORAGE_PROVIDER=local` |
| `CORS_ORIGINS` | Comma-separated allowed origins |

Limits: images 10MB (JPEG/PNG/WebP/HEIC), videos 100MB (MP4/QuickTime/WebM).
Item counts come from the creator's package.

### Deploying to S3

The local MinIO container and production AWS speak the same protocol, so the same
`S3StorageService` runs against both — deploying means pointing `S3_BUCKET` at a
real bucket and *removing* `S3_ENDPOINT`, `S3_ACCESS_KEY` and `S3_SECRET_KEY` so
the SDK falls back to the instance role. Nothing else changes.

The bucket should have public access blocked. Objects are never served directly:
every read goes through the app so the paywall is re-checked on each request.

## Not built yet

Scoped out deliberately, not forgotten — mutual matching, chat, party events,
and block/report. Browsing exists but is deliberately simple: newest-first with
a city filter, no geo radius, no ranking, no recommendation.

A real payment provider is the obvious next piece; see the billing section for
exactly what implementing one involves. Note that it needs **two** capabilities
now — collecting from viewers *and* disbursing to creators. M-Pesa Daraja covers
both (STK push in, B2C out), but they are separate integrations and separate
approvals.

Also unbuilt on the money side: automated disbursement (payouts are manual by
design for now), tax/withholding reporting, and a creator-facing statement or
invoice per period.

Also worth doing before production:

- **Verify uploads properly.** Content types are client-supplied and only
  checked because it is cheap. Add magic-byte sniffing and a malware scan.
- **Turn on bucket encryption at rest**, and keep KYC objects under a lifecycle
  rule that matches the retention job rather than trusting the job alone.
- **Move off Gmail SMTP.** It is fine for development and will throttle or flag
  real volume. SES or Postmark; the change is `spring.mail.*` and nothing else.
- **Rate-limit `/auth/login` by IP.** Codes are rate-limited per account, which
  bounds guessing against one victim, but nothing yet bounds an attacker
  spraying one password across many accounts.
- Consider an automated KYC vendor (Smile ID handles Kenyan IDs well) in front
  of the manual queue, keeping human review as the fallback.
