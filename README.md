# Double-Entry Ledger Service

A small, correctness-first ledger service built on Java 21 / Spring Boot 3 /
PostgreSQL. It exists to demonstrate how a ledger's core invariants -
balance, immutability, idempotency, and concurrency safety - should be
enforced: **in the database, not just in application code**, because a
constraint cannot race or be bypassed the way an in-memory check can.

## Table of contents

- [Problem statement](#problem-statement)
- [Double-entry accounting, as applied here](#double-entry-accounting-as-applied-here)
- [Architecture](#architecture)
- [How to run](#how-to-run)
- [How to run the tests](#how-to-run-the-tests)
- [API surface](#api-surface)
- [Observability](#observability)
- [Design decisions](#design-decisions)
- [Scope cuts](#scope-cuts)

## Problem statement

Double-entry bookkeeping is a 500-year-old accounting discipline built on one
rule: **every recorded event affects at least two accounts, and the total
value debited must always equal the total value credited.** A "transaction"
(a sale, a transfer, a refund) is really a *set* of individual postings
("entries"), each one either a debit or a credit against a specific account,
and the set is only valid if it balances to zero net movement.

This discipline exists because it makes entire classes of error
self-detecting: money cannot appear or disappear as a side effect of a bug,
because any single-sided change breaks the debit/credit balance and is
mechanically checkable. A ledger service's job is to make that mechanical
check *impossible to bypass* - not a best-effort application-level
validation that a retried request, a concurrent write, or a future
maintenance script could quietly sidestep.

That is the central design commitment of this service: the balance
invariant, entry immutability, and (as far as a single-service scope
allows) safe concurrent posting are enforced by PostgreSQL constraints and
triggers, with application-level checks layered on top only to give
callers a fast, friendly error instead of a raw database exception - never
as the sole line of defense.

## Double-entry accounting, as applied here

- An **account** (`accounts`) is a named bucket of one `account_type`
  (`ASSET`, `LIABILITY`, `EQUITY`) and one ISO 4217 `currency`. It has **no
  stored balance** - see [Design decisions](#why-balances-are-derived-not-stored).
- A **transaction** (`transactions`) is the atomic unit of posting: a
  description, a status (`PENDING` / `POSTED` / `FAILED`), and a set of
  entries. A transaction either posts completely and correctly, or not at
  all.
- An **entry** (`entries`) is one leg of a transaction: an unsigned
  `amount`, a `direction` (`DEBIT` or `CREDIT`), the `account_id` it posts
  against, and the `transaction_id` it belongs to. Entries are
  **append-only** - see [immutability](#db-level-immutability-and-balance-trigger-mechanisms).
- **Balance convention**: this service uses a single, unconditional
  "debit-positive" formula - `balance = sum(DEBIT entries) - sum(CREDIT
  entries)` - independent of `account_type`. Real-world "normal balance"
  accounting (where a credit *increases* a LIABILITY/EQUITY account) is a
  presentation-layer concern callers can derive from this value plus
  `account_type`; keeping the stored/computed formula unconditional avoids
  ambiguity given this service doesn't yet model REVENUE/EXPENSE accounts.
- **Self-transfers / same account on multiple legs**: allowed, deliberately.
  See [Design decisions](#self-transfers).

## Architecture

```mermaid
flowchart TB
    subgraph Client
        C[HTTP client]
    end

    subgraph API["API layer (Spring Web / MVC)"]
        AC[AccountController]
        TC[TransactionController]
        GEH[GlobalExceptionHandler]
        CIF[CorrelationIdFilter<br/>MDC request/trace id]
    end

    subgraph Service["Service layer"]
        AS[AccountService]
        TS[TransactionService<br/>idempotency orchestration]
        TW[TransactionWriter<br/>locking + persistence + FX + outbox insert]
        TBV[TransactionBalanceValidator<br/>app-level pre-check, base-currency-aware]
        FXP[FxRateProvider / DbFxRateProvider<br/>DB-backed rate lookup]
    end

    subgraph Data["Repository layer (Spring Data JPA)"]
        AR[AccountRepository<br/>SELECT ... FOR UPDATE]
        TR[TransactionRepository]
        ER[EntryRepository]
        FXR[FxRateRepository]
        OER[OutboxEventRepository<br/>SELECT ... FOR UPDATE SKIP LOCKED]
    end

    subgraph DB["PostgreSQL"]
        ACCT[(accounts)]
        TXN[(transactions)]
        ENT[(entries)]
        FXT[(fx_rates<br/>static seeded rates)]
        OUT[(outbox_events<br/>PENDING/PUBLISHED/FAILED)]
        TRIG1{{"trg_entries_immutable<br/>(V4) rejects UPDATE/DELETE"}}
        TRIG2{{"trg_check_transaction_balance<br/>(V9, supersedes V5) sum(base_currency_amount), deferred, checked at COMMIT"}}
    end

    subgraph Async["Async relay + event pipeline"]
        OR[OutboxRelay<br/>@Scheduled poller]
        KAFKA[["Kafka topic<br/>transaction-posted-events"]]
        CONS[TransactionPostedEventConsumer<br/>logging proof-of-pipeline @KafkaListener]
    end

    C -->|HTTP JSON| CIF --> AC & TC
    AC --> AS
    TC --> TS
    TS --> TBV
    TS --> TW
    TW --> FXP
    FXP --> FXR
    AS --> AR & ER
    TW --> AR & TR & ER & OER
    AC -.4xx.-> GEH
    TC -.4xx.-> GEH

    AR --> ACCT
    TR --> TXN
    ER --> ENT
    FXR --> FXT
    OER --> OUT
    ENT -. fires .-> TRIG1
    ENT -. fires at commit .-> TRIG2
    TRIG1 -. guards .-> ENT
    TRIG2 -. guards balance of .-> TXN

    OR -->|SELECT...FOR UPDATE SKIP LOCKED| OUT
    OR -->|publish, sync send| KAFKA
    KAFKA --> CONS
```

**Layering, top to bottom:**

1. **Controllers** (`AccountController`, `TransactionController`) - HTTP
   binding, request validation (`@Valid`/Bean Validation), OpenAPI
   annotations. No business logic.
2. **`GlobalExceptionHandler`** - translates every exception the layers
   below can throw into a documented, uniform `ErrorResponse` JSON body
   with the right HTTP status - so nothing below this layer ever needs to
   think about HTTP.
3. **Services** - `AccountService` (simple CRUD/read), and for
   transactions, two collaborating beans: `TransactionService` (idempotency
   orchestration - see below) and `TransactionWriter` (owns every DB
   read/write for transactions/entries, including account-row locking, FX
   conversion via `FxRateProvider`, and the outbox insert).
   `TransactionBalanceValidator` is a pure, stateless pre-check, now
   operating on FX-converted (base-currency) amounts.
4. **Repositories** - Spring Data JPA interfaces over the tables above,
   including `FxRateRepository` (rate lookups) and `OutboxEventRepository`
   (the relay's locked-poll query).
5. **PostgreSQL** - the schema itself carries the enforcement triggers that
   hold regardless of what the application layer does or forgets to do
   (see below), plus the `fx_rates` and `outbox_events` tables.
6. **Async relay + event pipeline** - `OutboxRelay`, a `@Scheduled` poller
   entirely decoupled from the request/response cycle, drains
   `outbox_events` into Kafka; `TransactionPostedEventConsumer` is a
   minimal logging consumer that proves the pipeline end-to-end. See
   [Event publishing (outbox pattern)](#event-publishing-outbox-pattern)
   for the full design.

## How to run

Requires Docker (for `docker-compose`) - no local JDK/Maven needed just to
run the service, only to build/test it (see next section).

```bash
git clone <this repo> && cd double-entry-ledger

# Clean start: build the app image and start Postgres + Kafka + the app together.
docker compose up --build

# App: http://localhost:8080
# Swagger UI: http://localhost:8080/swagger-ui.html
# OpenAPI doc (live, generated): http://localhost:8080/v3/api-docs.yaml
# Health: http://localhost:8080/actuator/health
# Metrics: http://localhost:8080/actuator/metrics
# Kafka (host access, e.g. for a local kafka-console-consumer): localhost:9092
```

Flyway migrations run automatically on startup against the `postgres`
service defined in `docker-compose.yml`; there is no separate migration
step.

As of Phase 2 (outbox + Kafka event publishing), the compose stack also
brings up a single-broker **Kafka** service (KRaft mode, no Zookeeper) and
a one-shot **`kafka-init`** container that explicitly provisions the
`transaction-posted-events` topic (partitions/replication-factor set
explicitly, not left to auto-create) before `app` is allowed to start -
`app`'s `depends_on` requires `kafka` to be healthy and `kafka-init` to
have *completed successfully* first. `docker compose up --build` therefore
brings up `postgres` + `kafka` + `kafka-init` + `app`, in that dependency
order, with one command; nothing extra to run for Kafka. See
`docker-compose.yml` for the exact broker config and host port mapping
(platform-engineer owns verifying the exact port/env-var names stay
accurate here).

To reset to a genuinely clean state (drop all data, including the
`ledger_postgres_data` and `ledger_kafka_data` volumes):

```bash
docker compose down -v
docker compose up --build
```

Config is entirely environment-variable driven (see `application.yml` and
`docker-compose.yml`): `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USERNAME`,
`DB_PASSWORD`, `SERVER_PORT`, `KAFKA_BOOTSTRAP_SERVERS`, plus the
`FX_BASE_CURRENCY` and `LEDGER_OUTBOX_*` variables covered in the design
sections below.

## How to run the tests

There are **two separate test suites**, deliberately kept apart:

### 1. Main Testcontainers suite (`mvn test`)

Unit tests plus Postgres-backed integration tests (real Postgres via
Testcontainers - no H2/mocks for anything that touches the schema, because
this schema leans on Postgres-specific behavior: deferred constraint
triggers, CHECK constraints, `gen_random_uuid()`). Requires Docker running
locally (Testcontainers starts its own throwaway Postgres container per
test run).

**JDK version note:** the project targets **Java 21**
(`spring-boot-starter-parent` 3.5.16 requires it), and Maven runs under
whatever JDK `JAVA_HOME` points to - which, on many machines, is *not* the
system default. If `mvn -version` shows a JDK older than 21, point
`JAVA_HOME` at a JDK 21 install before running Maven. This repo ships an
`.sdkmanrc` pinning `java=21.0.1.fx-zulu`; with
[SDKMAN!](https://sdkman.io/) installed:

```bash
# One-time, if you don't already have a JDK 21 via SDKMAN!:
sdk install java 21.0.1.fx-zulu

# From the repo root: auto-switches this shell to the pinned JDK
# (requires `sdkman_auto_env=true` in ~/.sdkman/etc/config, or run
# `sdk env` manually every new shell).
sdk env

mvn test
```

Without SDKMAN!, the equivalent is simply pointing `JAVA_HOME` at any local
JDK 21 install and putting it first on `PATH` for the command, e.g.:

```bash
export JAVA_HOME=/path/to/jdk-21
export PATH="$JAVA_HOME/bin:$PATH"
mvn test
```

### 2. Black-box API test suite (`api-tests/`)

A **standalone Maven module**, intentionally independent of the main
`pom.xml`/`src/test` tree (own `groupId:artifactId`, own dependencies -
REST Assured, Allure, `swagger-request-validator` for OpenAPI-contract
assertions). It exercises the API purely over HTTP against a **live,
already-running instance** - it does not start anything itself. Run it
against the docker-compose stack:

```bash
# In one terminal: start the app (see "How to run" above)
docker compose up --build

# In another terminal (same JDK-21 JAVA_HOME requirement as above):
cd api-tests
mvn test \
  -Dapi.baseUri=http://localhost:8080 \
  -Dopenapi.spec.path=../openapi.yaml
```

Both properties have the defaults shown above, so plain `mvn test` from
`api-tests/` works out of the box against a stack running on
`localhost:8080` with `openapi.yaml` co-located at the repo root as
checked in.

This suite validates actual HTTP responses (status codes, bodies,
`Content-Type`) against `openapi.yaml` as the source-of-truth contract, in
addition to plain correctness assertions - it is what caught the
contract-vs-behavior gaps fixed in Phase 4 (malformed-UUID 500s, the
wrong-Content-Type 500, and the overloaded-409 case). It is owned/maintained
independently of the main test suite; do not merge it into the root
`pom.xml`.

## API surface

See `openapi.yaml` at the repo root (or `GET /v3/api-docs.yaml` on a
running instance, or Swagger UI) for the full, authoritative contract.
Summary:

| Endpoint | Purpose | Idempotent? |
|---|---|---|
| `POST /accounts` | Create an account | No (see [Design decisions](#why-idempotency-keys-are-required)) |
| `POST /transactions` | Post a balanced transaction; also FX-converts every entry into the base currency (see [Multi-currency & FX](#multi-currency--fx)) | Yes, via required `Idempotency-Key` header |
| `GET /transactions/{id}` | Fetch a transaction + its entries | n/a (read) |
| `GET /accounts/{id}/balance` | Derived, on-the-fly balance **in the account's own native currency** - not base-currency-aware (see [Multi-currency & FX](#multi-currency--fx)) | n/a (read) |
| `GET /accounts/{id}/entries` | Paginated entry history | n/a (read) |
| `GET /actuator/health` | Liveness + DB connectivity | n/a |
| `GET /actuator/metrics` | JVM + HTTP request metrics | n/a |

`POST /transactions` also returns **422** if no FX rate is available to
convert one of the request's entries into the ledger's base currency
(`UnsupportedCurrencyPairException`), on top of the pre-existing 422 for an
unbalanced transaction. `EntryResponse` (embedded in `TransactionResponse`
and `PagedEntriesResponse`) gained three fields as of Phase 1:
`baseCurrencyAmount`, `fxRateUsed`, `fxRateEffectiveAt` - see `openapi.yaml`
for the full schema. Phase 2 (outbox/Kafka) added no new HTTP-visible
behavior; the outbox and relay are entirely internal.

## Observability

- **`/actuator/health`** - exposes an aggregate status plus a per-component
  breakdown (`management.endpoint.health.show-details: always`), including
  Spring Boot's built-in `db` health indicator, which runs a real
  validation query against PostgreSQL - so a database outage/misconfig is
  reflected as `DOWN` here, not just as request-time 500s.
- **`/actuator/metrics`** - Micrometer-backed; out of the box this includes
  JVM metrics (heap, GC, threads) and HTTP server request metrics
  (`http.server.requests`, tagged by URI/method/status) with no additional
  code, via `spring-boot-starter-actuator` auto-configuration.
- Only `health` and `metrics` are exposed
  (`management.endpoints.web.exposure.include`) - a deliberate allow-list,
  not `*`: there is no authentication in front of these endpoints in this
  phase (see [Scope cuts](#scope-cuts)), so endpoints that could leak
  configuration/environment details (`env`, `beans`, `configprops`, ...)
  are never exposed.
- **Structured JSON logging** - every log line is emitted as one JSON
  object (`logback-spring.xml`, via `logstash-logback-encoder`), not a
  free-text line, so it's directly machine-parseable by a log aggregator.
- **Correlation id** - `CorrelationIdFilter` assigns a `requestId` (reusing
  an inbound `X-Request-Id` header if the caller supplied one) to every
  request, puts it in SLF4J's MDC for the lifetime of that request, and
  echoes it back as a response header. Every log line emitted while
  handling one request - across controller, service, and repository code -
  therefore carries the same id and can be correlated/grepped back into a
  single request's timeline. This is deliberately *not* a full distributed
  tracing integration (no span model, no exporter, no backend) - just the
  minimum needed for "logs from one request are traceable together",
  which is what was asked for. A real Micrometer Tracing bridge could be
  added later without conflicting with this (it would contribute its own
  `traceId`/`spanId` to the same MDC map).

## Design decisions

### Why balances are derived, not stored

`accounts` has no `balance` column. Every balance is computed on read by
summing `entries.amount` (signed by `direction`) for that account
(`EntryRepository#sumSignedAmountsByAccountId`). A stored, mutable balance
column would be a second, denormalized source of truth that every write
path must keep in lockstep with the entries - and the moment it drifts
(a missed update, a partial failure, a manual data fix), the ledger's
history and its "current state" disagree with no mechanical way to tell
which one is right. Deriving balance from the append-only entry log means
there is only ever one source of truth, and Postgres MVCC/snapshot
isolation already guarantees a balance read sees either *all* of a
committed transaction's entries or *none* of them - never a torn,
half-posted transaction - with no extra mechanism required.

### Why idempotency keys are required (and how replay/conflict detection works)

`POST /transactions` moves money and is exactly the kind of call a client
must be able to safely retry after a timeout/dropped connection without
risking a duplicate posting. There's no other natural dedupe key for a
freshly-created resource (unlike, say, a client-supplied UUID for the
resource itself), so a required `Idempotency-Key` header is the mechanism.

Mechanism (implemented across `TransactionService` + `TransactionWriter`):

1. **Read-before-write.** On every call, first look up an existing
   transaction by `idempotency_key`. If found, compare the stored
   request's fingerprint against the incoming request
   (`IdempotencyRequestMatcher`): identical body -> return the original
   result with **200** (a true replay, no reprocessing); different body ->
   **409** (`IdempotencyKeyConflictException`) - key reuse with a different
   payload is a client bug, not a retry, and must not silently return the
   wrong result.
2. **First-time key.** Not found -> validate the entries balance
   (app-level pre-check, pure arithmetic, no I/O) -> insert the transaction
   + its entries in one DB transaction -> **201**.
3. **Concurrent race on a brand-new key.** Two requests can both see "not
   found" before either commits. The `uq_transactions_idempotency_key`
   UNIQUE constraint (checked immediately at INSERT, not deferred)
   guarantees at most one wins; the loser's `DataIntegrityViolationException`
   is caught, confirmed (via SQLSTATE `23505` + constraint name) to really
   be this constraint and not some other DB error, and the loser then
   re-reads the winner's row and runs the same replay-vs-conflict
   comparison as case 1. The loser's failed INSERT genuinely never commits
   - this is a real constraint backstop for the race, not a try/catch that
   papers over a partial write.

`POST /accounts` is deliberately **not** idempotent in this phase - there is
no natural dedupe key for account creation, and accounts are comparatively
low-frequency reference-data writes, not a retried-under-load hot path the
way transaction posting is. A retried `POST /accounts` creates a second,
distinct account.

### Why pessimistic row-level locking (ascending account-id order), and precisely what it does and doesn't protect against

`TransactionWriter#lockAccountsInOrder` takes `SELECT ... FOR UPDATE` locks
on every account referenced by a transaction's entries, in ascending
account-id order, before writing anything.

**Why pessimistic over optimistic (`@Version`):** optimistic locking
detects a conflicting concurrent write only *after the fact* (the second
writer's commit fails and must retry) and only for updates to a version-
tracked row. Posting a transaction here is pure `INSERT` of new,
independent entry rows - there is no single row being updated that a
`@Version` column could guard. What a future overdraft/sufficient-funds
check *would* need is to prevent two concurrent posts from both reading the
same pre-post balance and both passing a check that only one of them
should pass (a classic TOCTOU race) - and for that, pessimistic locking
(blocking the second reader until the first finishes) is the direct
mechanism; optimistic locking would only let you detect the collision after
letting both proceed, which is the wrong shape for a "don't let this
happen at all" invariant.

**What the lock does *not* protect against, because it needs no
protecting:** `GET /accounts/{id}/balance` correctness, and "no lost/
duplicated entries". Both already hold unconditionally from (a) the
insert-only design - two concurrent posts never overwrite each other's
rows, so there is no lost-update window - (b) the deferred
per-transaction balance constraint trigger (V5), which makes each
transaction's own entries commit atomically as one balanced all-or-nothing
unit, and (c) Postgres MVCC, which guarantees a reader never observes a
torn, half-posted transaction. These three hold with or without the
account-row lock; do not attribute them to the lock (an earlier version of
this README/test-suite javadoc did, and it was corrected in Phase 4 - see
`ConcurrentTransferIntegrationTest`).

**What the lock actually protects against today:** nothing yet enforces a
check-then-act business rule keyed on current balance (no overdraft check
exists in this scope), so today the lock's concrete, provable effect is
**serialized write ordering and deadlock-freedom** under contention on the
same account(s) - proven by `ConcurrentTransferIntegrationTest` (200
concurrent, alternating-direction transfers between the same two accounts,
real threads, no sleeps/retries). It is taken now, ahead of that future
need, specifically so that whenever a balance-dependent check-then-act rule
*is* added, the lock ordering that makes it race-free is already in place
and already proven deadlock-free - not bolted on under time pressure later.

**Deadlock avoidance:** locks are acquired in ascending account-id order,
never in request-payload (debit-first) order. Two transactions referencing
the same two accounts with opposite debit/credit roles (T1: debit A/credit
B; T2: debit B/credit A) would deadlock under payload-order locking (T1
holds A waiting for B, T2 holds B waiting for A). Sorting collapses both
transactions onto the same acquisition order regardless of role, so the
second transaction to reach the first account simply waits for the first
to finish - it can never hold a lock the other needs while waiting on a
lock the other holds.

### DB-level immutability and balance-trigger mechanisms

Both are enforced by the schema itself, not by application discipline:

- **Immutability (`V4__enforce_entries_immutability.sql`)** - a `BEFORE
  UPDATE OR DELETE` trigger on `entries` unconditionally raises an
  exception. Chosen over a `RULE` (which would silently no-op the
  mutation instead of failing loudly - worse on a ledger, where a client
  seeing "0 rows updated" could easily miss that history was protected
  rather than actually changed) and over `REVOKE`-based privilege
  management (which depends on the app always connecting as a specific
  role and that role never being granted broader privileges later - a
  concern that lives outside the migration that defines the invariant).
  Corrections are made by inserting new, offsetting entries in a new
  transaction - never by mutating history.
- **Balance invariant (`V5__enforce_transaction_balance.sql`)** - a
  `DEFERRABLE INITIALLY DEFERRED` constraint trigger on `entries`,
  checked once at `COMMIT` (not after each individual row), verifying
  `sum(DEBIT) = sum(CREDIT)` per `transaction_id`. Deferred because a
  balanced transaction requires >= 2 entry rows to exist - an immediate
  trigger firing after the first INSERT would reject every transaction's
  first entry. This lets application code insert entries one row at a
  time while still getting an all-or-nothing guarantee at commit,
  regardless of what application code does or forgets to do. Also fires
  on DELETE as defense-in-depth, in case the immutability trigger is ever
  bypassed by a superuser.

### Self-transfers

**Allowed, deliberately.** The invariant this service enforces is
`sum(DEBIT) == sum(CREDIT)` for the whole transaction - not a rule about
which accounts may appear more than once, or in both directions, within
one transaction. A transaction where the same account is both debited and
credited (e.g. as part of a larger multi-leg/compound posting, or a
trivial two-leg debit+credit of the same account netting to zero balance
change) is standard, valid double-entry practice. Rejecting it would be an
arbitrary business rule with no grounding in double-entry accounting
principles, and there is no natural definition of "self-transfer" once
more than two legs are involved (is it any repeated account? only exactly
two opposite-direction legs of equal amount? something else?). See
`TransactionControllerIntegrationTest#createTransaction_allowsSameAccountAsBothDebitAndCreditLeg`
for the test proving this is deliberate rather than an accidental gap.

### Multi-currency & FX

As of Phase 1 of v1 -> v1.1, every entry is converted into a configurable
base/reporting currency (`ledger.fx.base-currency`, env var
`FX_BASE_CURRENCY`, default `USD`) at post time, and a single transaction
can legitimately span entries against accounts in different currencies -
see the `V7`-`V9` migrations, `FxRateProvider`/`DbFxRateProvider`, and
`TransactionWriter#createAndPersist`.

**Why FX conversion happens inside the same DB transaction as posting.**
`TransactionWriter#createAndPersist` calls `fxRateProvider.convert(...)`
for every entry and passes the result straight into the `Entry`
constructor, all inside its single `@Transactional` method - the same
transaction that inserts the `Transaction` and `Entry` rows. There is no
separate "convert, then post" step and therefore no window in which a
transaction could be posted with stale, missing, or drifted FX data: if no
rate is available for a genuinely cross-currency pair,
`UnsupportedCurrencyPairException` propagates before any row is written,
and the whole attempt (including the already-flushed `Transaction` row)
rolls back. Conversion is a property of posting, not a step before or
after it.

**Why the rate is recorded per-entry, not per-transaction.** A
transaction's legs can reference accounts in different currencies (e.g.
one EUR-account leg and one USD-account leg in the same transaction), so
"the FX rate for this transaction" is not even well-defined in general -
only "the FX rate for this entry's account currency -> base currency" is.
Recording `fx_rate_used`/`fx_rate_effective_at`/`base_currency_amount` on
`entries` (V8) rather than on `transactions` is therefore not just
finer-grained, it is the only version of this that is never wrong. The
cost - a same-currency transaction repeats an identical (identity) rate on
every one of its entries instead of storing it once - is an acceptable,
deliberate trade for correctness over a few bytes of duplication.

**Why the balance trigger now checks `base_currency_amount`, not native
`amount`.** V5's original trigger summed `entries.amount` directly across
DEBIT/CREDIT legs, which was correct only because every entry was
implicitly the same currency. Once entries can span currencies, raw
amounts are not meaningfully additive - "100 EUR debit, 100 USD credit"
sums to 0 as raw numbers while being economically nonsense, and a real
balanced cross-currency transaction ("100 EUR debit, 108 USD credit" at a
1.08 rate) would be wrongly rejected. V9 supersedes V5's trigger function
(via `CREATE OR REPLACE FUNCTION` plus an explicit `DROP`/`CREATE
CONSTRAINT TRIGGER` - V5's own migration file is never edited, per this
project's forward-only discipline) to sum `base_currency_amount` instead,
with both legs already expressed in the same currency by the time they're
inserted. It also hard-fails (rather than silently passing) if any entry
in the transaction has a NULL `base_currency_amount`, so the invariant
can't be silently bypassed by a write that skips FX conversion entirely.

**Why the balance check is exact equality, no epsilon - and what makes
that safe.** V9's check is `v_net_base_amount <> 0` at `NUMERIC(19,4)`
scale, with no rounding tolerance. This is only safe because rounding is
centralized to a single point in the whole pipeline -
`DbFxRateProvider.convert()`, which rounds `amount.multiply(rate)` to
scale 4 with `RoundingMode.HALF_UP` once, at the moment
`base_currency_amount` is computed, and nowhere else. Every value the
trigger ever sums was already rounded the same way, so exact equality
holds by construction; loosening the check to tolerate a small delta
would paper over a rounding bug instead of catching one.

**Scope cut: no live FX API, no triangulation.** `DbFxRateProvider` reads
static, seeded rates from `fx_rates` (illustrative USD<->EUR, USD<->GBP,
USD<->JPY, EUR<->GBP pairs, explicitly not live market data) - a
deliberate, documented cut, not an oversight. `FxRateProvider` is a plain
interface with one implementation wired up; swapping in a live external
FX API later is a single new `@Component` implementing it, with no change
to `TransactionWriter` or the schema. There is also no triangulation
through a third currency - only pairs explicitly seeded in both
directions are supported, and an unsupported pair returns 422
(`UnsupportedCurrencyPairException`), the same status/pattern as
`UnbalancedTransactionException`.

**Current limitation: `GET /accounts/{id}/balance` is not
base-currency-aware.** It sums `entries.amount` (the native amount) for
one account - which is correct and unambiguous for that account, since an
account has exactly one currency and every entry against it is already in
that currency - but it does not convert or express the result in the
ledger's base currency, and there is no endpoint that would let a caller
compare or total balances across accounts in different currencies. The
only FX-aware data exposed today is per-entry, on `POST /transactions`'
response (and anywhere else `EntryResponse` appears): `baseCurrencyAmount`,
`fxRateUsed`, `fxRateEffectiveAt`. A base-currency-aware balance/reporting
endpoint is a real gap, not a design decision - flagged here rather than
left implicit, and proven as a limitation (not just asserted) by
`ConcurrentMultiCurrencyTransferIntegrationTest`, which has to reconcile
concurrent multi-currency transfers via `base_currency_amount` directly
because the existing balance endpoint can't.

### Event publishing (outbox pattern)

Phase 2 of v1 -> v1.1 adds durable, at-least-once publication of a
`TransactionPosted` event to Kafka for every posted transaction, via the
transactional outbox pattern (`outbox_events` table, V10;
`TransactionWriter`; `OutboxRelay`; `TransactionPostedEventConsumer`).

**Atomicity: the event is recorded in the same DB transaction as
posting.** `TransactionWriter#createAndPersist` inserts exactly one
`outbox_events` row (`event_type = TransactionPosted`) as the last
statement inside its single `@Transactional` method - after the entries
are saved and flushed, but still strictly before the method returns and
the transaction commits. This is the entire point of the outbox pattern:
"the transaction posted" and "an event describing it was durably
recorded" are one atomic DB commit, never two independent writes that
could drift (entries commit but the app crashes before a separate Kafka
publish call; or a direct Kafka publish succeeds but the enclosing DB
transaction then rolls back). Nothing in this codebase writes to Kafka
directly from the request path - `OutboxRelay` is the only reader of
`PENDING` rows, running later, in its own separate transaction(s).

**Why `SELECT ... FOR UPDATE SKIP LOCKED`.** `OutboxRelay` polls on a
`@Scheduled` interval (`ledger.outbox.poll-interval-ms`, default 2000ms)
and locks one eligible row at a time via
`OutboxEventRepository#lockNextEligibleForRelay`. `FOR UPDATE` locks the
row for the duration of the relay's transaction; `SKIP LOCKED` means a
second concurrent relay pass never blocks on a row another pass already
holds, and never selects it either - it moves on. Only one relay instance
runs in this scope, so today this mostly protects against overlapping
scheduled ticks; it is taken deliberately ahead of need so a future
multi-instance relay deployment is safe by construction rather than
requiring a redesign later.

**Bounded retry, never infinite.** A failed publish attempt increments
`attempt_count` and schedules the next eligible retry with exponential
backoff (`base-seconds * 2^attemptCount`, capped at
`backoff-max-seconds`; defaults 30s base / 900s cap, both configurable).
Once `attempt_count` reaches `ledger.outbox.max-attempts` (default 5), the
row is dead-lettered to `FAILED` and never retried again. A ledger service
that retried forever on a permanently broken downstream would eventually
just be spinning - bounding it and surfacing `FAILED` rows is the honest
alternative to a silent infinite retry loop.

**At-least-once delivery, not exactly-once - and what that means for
consumers.** A row can, in a narrow window, be successfully sent to Kafka
and then fail to have its status update committed (e.g. a crash between
the acknowledged `kafkaTemplate.send(...).get(...)` and the follow-up
`save()`), in which case the next relay pass re-publishes it. This is
standard outbox-relay behavior, not a bug. Consequently, any consumer of
`transaction-posted-events` - including a real downstream service, not
just the logging proof-of-pipeline one shipped here - **must** be
idempotent, e.g. by deduplicating on `transactionId`, which is stable and
unique per event. `TransactionPostedEventConsumer` itself does no such
deduplication because it has no state or side effect beyond a log line;
this is explicitly flagged so it's not mistaken for a reference
implementation of consumer-side idempotency.

**Producer durability.** The Kafka producer is configured with `acks=all`
plus `enable.idempotence=true` (`application.yml`, env-var-driven like the
rest of this project's config), so `OutboxRelay`'s synchronous
`kafkaTemplate.send(...).get(...)` wait genuinely corresponds to
"acknowledged by all in-sync replicas," not just "handed to the client
library."

**Operational lesson: single-broker Kafka and internal-topic replication
factor.** Worth surfacing here because it is exactly the kind of
non-obvious gotcha that bites anyone self-hosting a single-broker Kafka
for dev/test, not just this project: Kafka's internal topics
(`__consumer_offsets`, the transaction-state log) default to
replication-factor 3, which is unsatisfiable on a single broker. Their
auto-creation then silently never succeeds, the consumer-group
coordinator never comes up, and **every** consumer-group-based consumer
(`@KafkaListener`/`subscribe()` - i.e. every real consumer, including
`TransactionPostedEventConsumer`) stalls forever with zero records - even
though messages are demonstrably present on the topic (confirmed via a
manual, non-group, explicit-partition-assignment consumer during
diagnosis). This was found by the black-box API test suite, not by code
review, and fixed via three env vars on the `kafka` service in
`docker-compose.yml`: `KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR=1`,
`KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR=1`,
`KAFKA_TRANSACTION_STATE_LOG_MIN_ISR=1`. Verified end-to-end: a real
posted transaction was observed being received by the logging consumer
after the fix. If you ever stand up your own single-broker Kafka for this
project (or any project), start with these three set.

## Scope cuts

Explicitly out of scope for this service, flagged so they are not mistaken
for oversights:

- **Live FX rates** - as of Phase 1 of v1 -> v1.1, every entry is converted
  into a configurable base/reporting currency (`ledger.fx.base-currency`,
  default USD) and posted transactions can legitimately span multiple
  currencies (see `V7`-`V9` migrations, `FxRateProvider`, and
  [Multi-currency & FX](#multi-currency--fx)). What remains out of scope:
  rates come from a static, seeded `fx_rates` table, not a live external FX
  API (a documented, deliberately pluggable scope cut - swapping in a live
  provider is a single new `FxRateProvider` bean); and there is no
  triangulation/inversion through a third currency or the opposite pair -
  only pairs explicitly seeded (in both directions) in `fx_rates` are
  supported.
- **No base-currency-aware balance/reporting endpoint** - `GET
  /accounts/{id}/balance` sums an account's entries in that account's own
  native currency only; there is no endpoint that expresses or totals
  balances in the base currency across accounts of different currencies.
  The only FX-aware values exposed today are per-entry
  (`baseCurrencyAmount`/`fxRateUsed`/`fxRateEffectiveAt` on
  `EntryResponse`). See [Multi-currency & FX](#multi-currency--fx) for the
  full reasoning.
- **Event bus / Kafka - single relay instance, at-least-once, one consumer**
  - as of Phase 2 of v1 -> v1.1, every posted transaction durably records a
  `TransactionPosted` event in the same DB transaction as posting (the
  outbox pattern; `V10` migration, `TransactionWriter`, `OutboxRelay`) and
  publishes it to a real Kafka broker (see
  [Event publishing (outbox pattern)](#event-publishing-outbox-pattern)).
  What remains out of scope: delivery is **at-least-once, not
  exactly-once** (a consumer must dedupe by `transactionId`); only a single
  `OutboxRelay` instance runs, with no distributed coordination beyond the
  `SELECT ... FOR UPDATE SKIP LOCKED` query that would make a future
  multi-instance relay safe; the Kafka topology is a single broker (KRaft,
  no Zookeeper, no multi-broker cluster/replication beyond factor 1); and
  `TransactionPostedEventConsumer` is explicitly a minimal logging
  proof-of-pipeline consumer, not a real downstream service - no
  analytics/reporting/notification consumer exists yet.
- **Authentication / authorization** - there is no auth layer. This is why
  `/actuator/*` exposure is deliberately minimal (see
  [Observability](#observability)) - it's the one place this gap has a
  direct operational consequence today.
