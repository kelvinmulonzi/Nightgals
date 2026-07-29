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
3. **Scrolling is free, seeing the person is paid.** A feed card is free to any
   verified member. Photos beyond the preview, all video, and live sessions sit
   behind a paywall.

- Java 21, Spring Boot 4.1
- PostgreSQL in Docker, schema owned by Flyway
- JWT auth with rotating refresh tokens
- Uploads on the local filesystem, behind a `StorageService` interface
- Full Swagger/OpenAPI documentation

## Run it

```bash
docker compose up -d                                  # Postgres on localhost:5433
BOOTSTRAP_ADMIN_PASSWORD='ChangeMe123!' ./mvnw spring-boot:run
```

Then open **http://localhost:8080/swagger-ui.html**.

Flyway builds the schema on first start. `BOOTSTRAP_ADMIN_PASSWORD` creates
`admin@nightgals.local` on an empty database — without an admin there is nobody
who can approve the first member. It is only used once; later starts ignore it.

> Postgres is published on **5433**, not 5432, because another Postgres container
> was already using 5432 on this machine. Change it in `compose.yaml` if you like.

```bash
./mvnw test          # 64 tests, spins up a throwaway Postgres via Testcontainers
```

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

Free, to any verified member:

- the browse feed and every card on it — handle, age, city, vibe, bio
- the first `freePreviewPhotos` photos on a profile (default 1)
- the counts of what is locked, so the paywall is honest rather than a mystery

Paid:

- the rest of somebody's photos
- all of their video
- the playback URL for their live sessions

Two routes, both resolved by the same check in `EntitlementService`:

| | What it buys | Default price |
|---|---|---|
| Profile unlock | one member, 30 days | KES 100 |
| Subscription | every member, for the plan's term | KES 300 / 900 / 2400 (1w / 1m / 3m) |

```
GET  /api/v1/billing/plans              prices (open, no auth)
POST /api/v1/billing/unlocks/{userId}   unlock one member
POST /api/v1/billing/subscriptions      subscribe
GET  /api/v1/billing/entitlements       what the caller can already see
GET  /api/v1/billing/purchases          payment history
```

Locked media is still *listed* — `locked: true` with a null `url` — so a client
can render blurred placeholders and a truthful count. Fetching a locked file
returns **402 Payment Required**, which is the client's cue to open the paywall.

Prices, durations, plans and `free-preview-photos` are all configuration
(`nightgals.monetization`). `enabled: false` turns the whole paywall off and
makes every entitlement check pass — useful for a launch period.

### No payment provider is integrated yet

That is deliberate, and the seam is clean. A purchase is created `PENDING`; the
configured `PaymentProvider` says how to pay; access is granted only when
`BillingService.settle()` runs. Nothing else in the codebase knows how money
arrives.

The default `ManualPaymentProvider` does not fake a payment. It returns
`action: MANUAL` with instructions, and an administrator settles the purchase
once money actually lands:

```
GET  /api/v1/admin/billing/purchases/pending
POST /api/v1/admin/billing/purchases/{id}/settle?providerReference=MPESA-XYZ
POST /api/v1/admin/billing/grants?viewerId=…&targetId=…&duration=P30D
```

That is a real till-number workflow, so it is usable in production, not just a
placeholder. When you wire in M-Pesa Daraja, write one class implementing
`PaymentProvider` — STK push in `startPayment`, callback calls `settle()`. It
drops in automatically (`ManualPaymentProvider` is `@ConditionalOnMissingBean`)
and **no access-control code changes**. `settle()` is idempotent and
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
POST /api/v1/me/media/photos            now unlocked
```

`GET /api/v1/me` returns a `nextStep` field (`CREATE_PROFILE`, `SUBMIT_KYC`,
`AWAIT_REVIEW`, `RESUBMIT_KYC`, `DONE`) so a client can decide what screen to
show with one call.

Which images are required depends on the document type:

| Document type | Required images |
|---|---|
| `NATIONAL_ID` | `ID_FRONT`, `ID_BACK`, `SELFIE` |
| `PASSPORT` | `PASSPORT_PAGE`, `SELFIE` |
| `DRIVERS_LICENSE` | `ID_FRONT`, `ID_BACK`, `SELFIE` |

**KYC is the only gate on posting.** Once a creator is approved their uploads
publish immediately — there is no review queue and nothing waits on staff. A
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
  `MODERATOR`/`ADMIN`, with `Cache-Control: no-store`.
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
├── storage/    StorageService + local implementation + upload validation
├── user/       User, roles, verification status, usernames, /me
├── auth/       JWT, refresh tokens, register/login
├── profile/    optional nickname, DOB, city, vibe
├── kyc/        submissions, documents, review queue, audit log, retention job
├── media/      photo and video upload (KYC-gated) + takedown
├── discovery/  the browse feed and its cards
├── billing/    purchases, subscriptions, unlocks, entitlements, payment provider
├── earnings/   creator ledger, payout requests, admin payout queue
└── live/       broadcast session metadata
src/main/resources/db/migration/   V1__init.sql … V7__account_types.sql
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
| `nightgals.monetization.*` | Currency, prices, plans, manual payment instructions |
| `nightgals.earnings.commission-percent` | Platform's cut (default 30) |
| `nightgals.earnings.hold-period` | How long earnings stay unpayable (default `P7D`) |
| `nightgals.earnings.minimum-payout-minor` | Smallest payout processed (default KES 1000) |
| `BOOTSTRAP_ADMIN_EMAIL` / `_PASSWORD` | First admin, on an empty database only |
| `STORAGE_ROOT` | Upload directory (default `./var/storage`) |
| `CORS_ORIGINS` | Comma-separated allowed origins |

Limits: images 10MB (JPEG/PNG/WebP/HEIC), videos 100MB (MP4/QuickTime/WebM),
9 photos and 3 videos per member.

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

- **Swap local storage for object storage.** `StorageService` has one
  implementation; adding an S3 one touches no callers. The filesystem does not
  survive more than one app instance.
- **Verify uploads properly.** Content types are client-supplied and only
  checked because it is cheap. Add magic-byte sniffing and a malware scan.
- **Encrypt the upload directory at rest**, and keep it off any backup that
  travels more widely than the database does.
- **Rate-limit** `/auth/login`, `/usernames/suggestions` and the KYC endpoints.
- **Email verification** — `emailVerified` exists on the user and is never set.
- Consider an automated KYC vendor (Smile ID handles Kenyan IDs well) in front
  of the manual queue, keeping human review as the fallback.
