# Schema Rework — Operator Guide

This document covers the user-table schema rework shipped in
`supertokens-core 12.0.0` together with `supertokens-postgresql-plugin 9.5.0`
and `supertokens-plugin-interface 8.6.0`. It is the operator-facing runbook for
the migration from `all_auth_recipe_users` and the four `*_user_to_tenant`
projections onto a new set of reservation tables, plus the per-tenant
`migration_mode` config that stages the cutover safely.

If you are running self-hosted SuperTokens on PostgreSQL, this is the document
you need before upgrading past 11.x core.

---

## Release summary (12.0.0)

### Background

1. **Lower the transaction isolation level from SERIALIZABLE to READ_COMMITTED.** The previous default made the
   database the bottleneck on multi-tenant workloads: SI-lock accumulation on the auth-recipe paths drove
   serialization retries and pushed Postgres into thrashing under realistic concurrency. READ_COMMITTED is the
   new baseline for every storage operation, dropping the per-transaction cost on signup, link, makePrimary,
   updateEmail, and addUserIdToTenant. The correctness lost in lowering the isolation level is recovered by
   structural changes below, not by application-level retries.

2. **Push invariants into the database schema instead of carrying them in application logic.** Account-info
   uniqueness was previously enforced by Java-side conflict checks reading projections of
   `all_auth_recipe_users` — correct only because SERIALIZABLE made the read-then-write effectively atomic. The
   new reservation tables (in the postgresql-plugin release) encode the same rules as primary-key constraints,
   so the database itself is the referee. Core's role shrinks accordingly: it acquires a `LockedUser` token for
   operations that need cross-row serialization, calls the new storage methods, and translates returned
   conflicts into the existing API responses. No HTTP contract changes.

For operators, the rollout is staged behind a per-tenant `migration_mode` config that lets you walk from
`LEGACY` (current production behaviour) to `MIGRATED` (new tables only) one step at a time, with backfill
running between the two midpoints. The new state-machine validator in `MigrationModeTransition` enforces
single-step transitions and refuses the final flip while any user still needs backfilling, so it's hard to
accidentally cut over too early. See the runbook sections below for the end-to-end procedure.

### Added

- `migration.MigrationModeTransition` — state-machine validator that runs from
  `Multitenancy.validateTenantConfig` whenever a tenant-config CRUD payload carries
  `coreConfig.migration_mode`. Refuses non-adjacent transitions and runs a `getBackfillPendingUsersCount` probe
  before allowing `DUAL_WRITE_READ_NEW → MIGRATED`.
- `cronjobs.backfill.BackfillReservationTables` — per-app, 5-minute, self-skipping when mode is `LEGACY`.
  Resumes implicitly on restart (`WHERE time_joined = 0`).
- `GET /migration/mode` and `GET /migration/backfill/progress[?verify=true]` — read-only operator endpoints,
  both root-CUD-aware.
- Offline migration SQL scripts shipped in `supertokens-postgresql-plugin/migration-scripts/`:
  `migration-backfill.sql`, `dump_old_canonical.sql`, `dump_new_canonical.sql` (backfill plus
  side-by-side data parity dump).
- Initialisation: storages now warm up in parallel during boot.

### Changed

- `Multitenancy.addNewOrUpdateAppOrTenant` validates the migration-mode transition before persisting the
  tenant config.
- Core-side conflict detection for `makePrimaryUser` / `linkAccounts` / `updateEmail` rewritten on top of the
  new `LockedUser` interfaces; the storage-cast and read-only narrowing now go through
  `StorageUtils.getAuthRecipeReadOnlyStorage`.
- Session-refresh path no longer upserts `user_last_active` on every request — now throttled.
- InMemoryDB write/read paths mirror PG dispatch for testability. InMemoryDB defaults to `MIGRATED` for tests.

---

## What and why, in one paragraph

`all_auth_recipe_users` and the four `*_user_to_tenant` projections (emailpassword,
passwordless, thirdparty, webauthn) are being retired in favour of three
**reservation tables** — `recipe_user_account_infos`, `recipe_user_tenants`,
`primary_user_tenants` — plus two new columns on `app_id_to_user_id`
(`time_joined`, `primary_or_recipe_user_time_joined`). The new tables are the
source of truth for account-info uniqueness, tenant membership of recipe users,
and account-info reservations held by primary users in a linked group. Two
intertwined motivations drove the change:

1. **Lower the transaction isolation level from SERIALIZABLE to READ_COMMITTED.**
   Under load, SERIALIZABLE was accumulating predicate / SI-locks on the linking
   and account-info paths, surfacing as `could not serialize access` retry
   storms and growing tail latency on Postgres. Dropping to READ_COMMITTED
   removes that class of database-side contention and reduces the
   per-transaction cost on hot endpoints (signup, link, makePrimary,
   updateEmail).
2. **Move invariants from application code into the database schema.**
   Uniqueness of (email, tenant), (phone, tenant), (third_party_id+user_id,
   tenant), and the "one primary user owns this account-info on this tenant"
   rule used to be enforced by sequence-of-checks in the Java layer — correct
   only because SERIALIZABLE made them effectively atomic. The new reservation
   tables encode the rules as primary-key/unique constraints, and conflicts are
   detected by the database itself via `INSERT … ON CONFLICT … RETURNING`.

To make the swap survivable, every read and write path is gated by a new
per-app config field `migration_mode` ∈ `{LEGACY, DUAL_WRITE_READ_OLD,
DUAL_WRITE_READ_NEW, MIGRATED}`. A `BackfillReservationTables` cron and an
idempotent SQL script copy existing rows into the new tables; a state-machine
guard in `MigrationModeTransition` forces operators to walk the chain one step
at a time and refuses the final flip to `MIGRATED` while any user still needs
backfilling.

---

## Architecture overview

### New persisted state

**`app_id_to_user_id` — two new columns**

```sql
time_joined                       BIGINT NOT NULL DEFAULT 0,
primary_or_recipe_user_time_joined BIGINT NOT NULL DEFAULT 0,
```

Plus four new pagination indexes (`app_id_to_user_id_pagination_index1..4`)
that drop `tenant_id` (this table is app-scoped, not tenant-scoped) and a new
`ON UPDATE CASCADE` on the self-FK to `primary_or_recipe_user_id`.

**`recipe_user_account_infos`** — per-recipe-user account info (one row per
identifier per recipe per user). PK
`(app_id, recipe_id, recipe_user_id, account_info_type, third_party_id, third_party_user_id)`.
`primary_user_id` is nullable and points at the primary-group id when the user
is linked. App-scoped FK only.

**`recipe_user_tenants`** — per-tenant projection of the above. PK
`(app_id, tenant_id, recipe_id, account_info_type, third_party_id, third_party_user_id, account_info_value)`.
Tenant-FK with `ON DELETE CASCADE`. This is the index used for
`listUsersByAccountInfo` and dashboard search.

**`primary_user_tenants`** — account-info reservations held by primary users
per tenant. PK `(app_id, tenant_id, account_info_type, account_info_value)` —
this is the uniqueness lock that prevents two primary users sharing
email/phone/thirdParty on the same tenant.

`account_info_type` is `'email' | 'phone' | 'tparty'` (lowercase). Padding
columns `third_party_id`/`third_party_user_id` are `''` (empty string) on
non-thirdparty rows; on thirdparty rows the type==`tparty` form is composite
`third_party_id::third_party_user_id` stored in `account_info_value` with the
padding columns empty.

### `MigrationMode`

```
LEGACY              writeold + readold
DUAL_WRITE_READ_OLD writeold + writenew + readold
DUAL_WRITE_READ_NEW writeold + writenew + readnew
MIGRATED            writenew + readnew
```

Read flag helpers: `readsFromOldTables()`, `readsFromNewTables()`,
`writesToOldTables()`, `writesToNewTables()`. Default when the field is absent:
`LEGACY`.

### Allowed transitions

The validator in `MigrationModeTransition` (called from
`Multitenancy.validateTenantConfig` when the tenant CRUD payload carries
`coreConfig.migration_mode`) enforces:

```
LEGACY              ↔  DUAL_WRITE_READ_OLD
DUAL_WRITE_READ_OLD ↔  DUAL_WRITE_READ_NEW
DUAL_WRITE_READ_NEW →  MIGRATED    (only when getBackfillPendingUsersCount == 0)
MIGRATED            (terminal; no outgoing transitions)
```

The `MIGRATED → *` block exists because once writes stop hitting old tables
those tables are stale forever — letting a reader fall back to them would
surface dead data. Operators who genuinely need to back out of `MIGRATED` must
drop and re-populate the old tables out of band first. Two documented escape
hatches exist: direct DB edit of `tenant_configs.core_config`, and
`config.yaml` boot-time load (no previous-state context, so no transition rule
fires).

### Backfill

Two complementary paths, same SQL semantics:

**Online (cron)** — `BackfillReservationTables`, per-app, 5-minute tick, batch
size 1000, `SELECT ... FOR UPDATE` on `app_id_to_user_id`. Self-skips when
mode == `LEGACY`. Resume on restart is implicit — `WHERE time_joined = 0`
finds the remaining work, no cursor table needed.

**Offline (psql)** — `migration-scripts/migration-backfill.sql` shipped in the
`supertokens-postgresql-plugin` repository, one set-based pass per target
table, idempotent (`ON CONFLICT DO NOTHING`, `WHERE time_joined = 0`). Accepts
`:'app_id'` to scope to a single app, or runs for the whole pool when unset.

Both paths execute the same logical steps:

1. `app_id_to_user_id.time_joined` ← `MIN(all_auth_recipe_users.time_joined)`.
2. `recipe_user_account_infos` ← one row per recipe identifier
   (`emailpassword:email`, `passwordless:email`/`phone`, `thirdparty:email` +
   `tparty`, `webauthn:email`).
3. `recipe_user_tenants` ← `all_auth_recipe_users ⋈ recipe_user_account_infos`.
4. `primary_user_tenants` ← `DISTINCT recipe_user_tenants ⋈ app_id_to_user_id`
   where `is_linked_or_is_a_primary_user = TRUE`.

The Java path deliberately throws
`IllegalStateException("Unknown recipeId during backfill: ...")` when it sees
a recipe it doesn't have a backfill case for — silently moving past would mark
the user as backfilled (`time_joined` set) while leaving the reservation
tables empty, causing silent data loss after the flip to `MIGRATED`.

### Operator endpoints

- `GET /migration/mode` — root CUD returns
  `{cuds: [{connectionUriDomain, mode}, ...]}`; app CUD returns `{mode}`.
  Setting the mode is **not** done here; it goes through the standard
  `PUT /recipe/multitenancy/connectionuridomain/v2` with
  `coreConfig.migration_mode`.
- `GET /migration/backfill/progress[?verify=true]` — returns
  `{mode, pendingUsers}` per CUD, plus `inconsistentUsersCount` when
  `verify=true` and the mode still reads from old tables. Skipped explicitly
  with `verifySkipped: "backfillIncomplete" | "migrated"` so monitoring
  callers get a stable response shape.

---

## Online runbook (LEGACY → MIGRATED, no downtime)

This is the production path. Old and new instances coexist, traffic stays
live, the cutover is reversible until the final step.

### Pre-flight

- Confirm `plugin-interface >= 8.6.0`, `postgresql-plugin >= 9.5.0`,
  `core >= 12.0.0` are bundled. Mismatched versions will not start.
- Take a logical backup of the live database (`pg_dump --schema-only` plus a
  full data dump if the dataset is small enough; otherwise rely on the active
  replica).
- Decide whether you'll cut over per-CUD or all-at-once. The mode is
  per-app-config (stored in `tenant_configs.core_config`), so an operator can
  stagger CUDs.
- Sanity-check the cluster has CPU headroom for dual-write — every
  linking/email-update operation does both writes during DUAL_WRITE phases.
- Review the schema changes this migration applies. The full DDL is below; the
  pagination indexes are built `CONCURRENTLY` (after the `COMMIT`) so they don't
  lock `app_id_to_user_id` on a live database.

```sql
-- New reservation tables

CREATE TABLE IF NOT EXISTS recipe_user_account_infos (
    app_id              VARCHAR(64)  NOT NULL,
    recipe_user_id      CHAR(36)     NOT NULL,
    recipe_id           VARCHAR(128) NOT NULL,
    account_info_type   VARCHAR(8)   NOT NULL,
    account_info_value  TEXT         NOT NULL,
    third_party_id      VARCHAR(28),
    third_party_user_id VARCHAR(256),
    primary_user_id     CHAR(36)     NULL,
    CONSTRAINT recipe_user_account_infos_pkey
        PRIMARY KEY (app_id, recipe_id, recipe_user_id, account_info_type, third_party_id, third_party_user_id),
    CONSTRAINT recipe_user_account_infos_tenant_id_fkey
        FOREIGN KEY (app_id)
        REFERENCES apps (app_id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_recipe_user_account_infos_app_recipe_user
    ON recipe_user_account_infos (app_id, recipe_user_id);

CREATE TABLE IF NOT EXISTS recipe_user_tenants (
    app_id              VARCHAR(64)  NOT NULL,
    recipe_user_id      CHAR(36)     NOT NULL,
    tenant_id           VARCHAR(64)  NOT NULL,
    recipe_id           VARCHAR(128) NOT NULL,
    account_info_type   VARCHAR(8)   NOT NULL,
    account_info_value  TEXT         NOT NULL,
    third_party_id      VARCHAR(28),
    third_party_user_id VARCHAR(256),
    CONSTRAINT recipe_user_tenants_pkey
        PRIMARY KEY (app_id, tenant_id, recipe_id, account_info_type, third_party_id, third_party_user_id, account_info_value),
    CONSTRAINT recipe_user_tenants_tenant_id_fkey
        FOREIGN KEY (app_id, tenant_id)
        REFERENCES tenants (app_id, tenant_id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_recipe_user_tenants_tenant
    ON recipe_user_tenants (app_id, tenant_id);
CREATE INDEX IF NOT EXISTS idx_recipe_user_tenants_recipe_user_id
    ON recipe_user_tenants (app_id, recipe_user_id);
CREATE INDEX IF NOT EXISTS idx_recipe_user_tenants_account_info
    ON recipe_user_tenants (app_id, tenant_id, account_info_type, account_info_value);

CREATE TABLE IF NOT EXISTS primary_user_tenants (
    app_id             VARCHAR(64) NOT NULL,
    tenant_id          VARCHAR(64) NOT NULL,
    account_info_type  VARCHAR(8)  NOT NULL,
    account_info_value TEXT        NOT NULL,
    primary_user_id    CHAR(36)    NOT NULL,
    CONSTRAINT primary_user_tenants_pkey
        PRIMARY KEY (app_id, tenant_id, account_info_type, account_info_value),
    CONSTRAINT primary_user_tenants_app_id_fkey
        FOREIGN KEY (app_id, tenant_id)
        REFERENCES tenants (app_id, tenant_id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_primary_user_tenants_primary
    ON primary_user_tenants (primary_user_id);

-- New columns on app_id_to_user_id

ALTER TABLE app_id_to_user_id
    ADD COLUMN IF NOT EXISTS time_joined BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS primary_or_recipe_user_time_joined BIGINT NOT NULL DEFAULT 0;

-- Add ON UPDATE CASCADE to every FK referencing app_id_to_user_id(app_id, user_id)

ALTER TABLE app_id_to_user_id
    DROP CONSTRAINT app_id_to_user_id_primary_or_recipe_user_id_fkey;
ALTER TABLE app_id_to_user_id
    ADD CONSTRAINT app_id_to_user_id_primary_or_recipe_user_id_fkey
    FOREIGN KEY (app_id, primary_or_recipe_user_id)
    REFERENCES app_id_to_user_id (app_id, user_id) ON DELETE CASCADE ON UPDATE CASCADE;

ALTER TABLE all_auth_recipe_users
    DROP CONSTRAINT all_auth_recipe_users_primary_or_recipe_user_id_fkey;
ALTER TABLE all_auth_recipe_users
    ADD CONSTRAINT all_auth_recipe_users_primary_or_recipe_user_id_fkey
    FOREIGN KEY (app_id, primary_or_recipe_user_id)
    REFERENCES app_id_to_user_id (app_id, user_id) ON DELETE CASCADE ON UPDATE CASCADE;

ALTER TABLE all_auth_recipe_users
    DROP CONSTRAINT all_auth_recipe_users_user_id_fkey;
ALTER TABLE all_auth_recipe_users
    ADD CONSTRAINT all_auth_recipe_users_user_id_fkey
    FOREIGN KEY (app_id, user_id)
    REFERENCES app_id_to_user_id (app_id, user_id) ON DELETE CASCADE ON UPDATE CASCADE;

ALTER TABLE emailpassword_users
    DROP CONSTRAINT emailpassword_users_user_id_fkey;
ALTER TABLE emailpassword_users
    ADD CONSTRAINT emailpassword_users_user_id_fkey
    FOREIGN KEY (app_id, user_id)
    REFERENCES app_id_to_user_id (app_id, user_id) ON DELETE CASCADE ON UPDATE CASCADE;

ALTER TABLE thirdparty_users
    DROP CONSTRAINT thirdparty_users_user_id_fkey;
ALTER TABLE thirdparty_users
    ADD CONSTRAINT thirdparty_users_user_id_fkey
    FOREIGN KEY (app_id, user_id)
    REFERENCES app_id_to_user_id (app_id, user_id) ON DELETE CASCADE ON UPDATE CASCADE;

ALTER TABLE passwordless_users
    DROP CONSTRAINT passwordless_users_user_id_fkey;
ALTER TABLE passwordless_users
    ADD CONSTRAINT passwordless_users_user_id_fkey
    FOREIGN KEY (app_id, user_id)
    REFERENCES app_id_to_user_id (app_id, user_id) ON DELETE CASCADE ON UPDATE CASCADE;

ALTER TABLE webauthn_users
    DROP CONSTRAINT webauthn_users_user_id_fkey;
ALTER TABLE webauthn_users
    ADD CONSTRAINT webauthn_users_user_id_fkey
    FOREIGN KEY (app_id, user_id)
    REFERENCES app_id_to_user_id (app_id, user_id) ON DELETE CASCADE ON UPDATE CASCADE;

ALTER TABLE webauthn_account_recovery_tokens
    DROP CONSTRAINT webauthn_account_recovery_token_user_id_fkey;
ALTER TABLE webauthn_account_recovery_tokens
    ADD CONSTRAINT webauthn_account_recovery_token_user_id_fkey
    FOREIGN KEY (app_id, user_id)
    REFERENCES app_id_to_user_id (app_id, user_id) ON DELETE CASCADE ON UPDATE CASCADE;

ALTER TABLE userid_mapping
    DROP CONSTRAINT userid_mapping_supertokens_user_id_fkey;
ALTER TABLE userid_mapping
    ADD CONSTRAINT userid_mapping_supertokens_user_id_fkey
    FOREIGN KEY (app_id, supertokens_user_id)
    REFERENCES app_id_to_user_id (app_id, user_id) ON DELETE CASCADE ON UPDATE CASCADE;

COMMIT;

CREATE INDEX CONCURRENTLY IF NOT EXISTS app_id_to_user_id_pagination_index1 ON app_id_to_user_id
    (app_id, primary_or_recipe_user_time_joined DESC, primary_or_recipe_user_id DESC);
CREATE INDEX CONCURRENTLY IF NOT EXISTS app_id_to_user_id_pagination_index2 ON app_id_to_user_id
    (app_id, primary_or_recipe_user_time_joined ASC, primary_or_recipe_user_id DESC);
CREATE INDEX CONCURRENTLY IF NOT EXISTS app_id_to_user_id_pagination_index3 ON app_id_to_user_id
    (recipe_id, app_id, primary_or_recipe_user_time_joined DESC, primary_or_recipe_user_id DESC);
CREATE INDEX CONCURRENTLY IF NOT EXISTS app_id_to_user_id_pagination_index4 ON app_id_to_user_id
    (recipe_id, app_id, primary_or_recipe_user_time_joined ASC, primary_or_recipe_user_id DESC);
```

```sql
-- activity_log audit table (core 12.0.3 / postgresql-plugin 9.5.2):
-- append-only, range-partitioned by created_at into one partition per UTC month.

CREATE TABLE IF NOT EXISTS activity_log (
    id                        BIGINT GENERATED ALWAYS AS IDENTITY,
    app_id                    VARCHAR(64)  NOT NULL DEFAULT 'public',
    tenant_id                 VARCHAR(64)  NOT NULL DEFAULT 'public',
    recipe_user_id            VARCHAR(128),
    primary_or_recipe_user_id VARCHAR(128),
    event_type                VARCHAR(64)  NOT NULL,
    status                    VARCHAR(128),
    auth_principal            VARCHAR(256),
    identifier                VARCHAR(256),
    created_at                BIGINT       NOT NULL,
    payload                   TEXT
) PARTITION BY RANGE (created_at);

-- DEFAULT partition is a backstop; the core pre-creates the current/next month at boot
-- and the CleanupActivityLogPartitions cron maintains the monthly partitions thereafter.
CREATE TABLE IF NOT EXISTS activity_log_default PARTITION OF activity_log DEFAULT;

CREATE INDEX IF NOT EXISTS activity_log_created_at_brin ON activity_log USING brin (created_at);
```

### Step 1 — Deploy new code, leave config in `LEGACY`

- Rolling deploy of the new core+plugin binaries.
- Do not touch `migration_mode` in any tenant config. New instances boot with
  the default `LEGACY` and behave identically to the old version on every
  read and every write — the new tables exist (created on first boot, atomic
  DDL batch) but stay empty.
- Mixed-version cluster during the rolling deploy is fine: old instances and
  new-instances-in-LEGACY both write to old tables and read from old tables.

**Verify:** `GET /migration/mode` against the root CUD returns
`"mode": "LEGACY"` for every entry. The three new tables exist in PG
(`\dt recipe_user_*`, `\dt primary_user_tenants`). `time_joined` columns exist
on `app_id_to_user_id` (`\d+ app_id_to_user_id`).

**Rollback:** Redeploy the prior binaries; nothing in `LEGACY` mode has
touched the new tables, so there is nothing to undo.

### Step 2 — Flip to `DUAL_WRITE_READ_OLD`

For each CUD you're cutting over (root CUD first if you don't have an explicit
per-CUD plan):

```
PUT /recipe/multitenancy/connectionuridomain/v2
{
  "connectionUriDomain": "<cud>",
  "coreConfig": { "migration_mode": "DUAL_WRITE_READ_OLD" }
}
```

The validator permits `LEGACY → DUAL_WRITE_READ_OLD` without a backfill
probe. Multitenancy refresh fans out the new config to every node sharing
this CUD; the per-tenant `PostgreSQLConfig` is reconstructed and subsequent
operations dual-write.

**What's happening:**
- Every new signup, link, makePrimary, updateEmail, updatePhone,
  addUserIdToTenant writes both old and new tables in the same transaction.
  Atomic — either both write or neither.
- Reads still come from old tables; users created in LEGACY are still
  readable because reads haven't moved.
- Linking and conflict-detection still take the legacy path. The new
  reservation-table conflict path is wired in but only fires when
  `writesToNewTables()` is true.

**Verify:**
- Create a test user → row exists in both `all_auth_recipe_users` AND
  `recipe_user_tenants` for the new tenant.
- Link two test users → `primary_user_tenants` has the reservation,
  `app_id_to_user_id.primary_or_recipe_user_id` updated, old tables also
  consistent.
- `GET /migration/mode` shows `DUAL_WRITE_READ_OLD`.

**Rollback:** Same CRUD endpoint, set back to `LEGACY`. Rows that were
dual-written stay in the new tables (harmless), but no read code reads them.

### Step 3 — Backfill existing users

The cron starts running automatically (5-minute tick, batch 1000). Watch
progress with:

```
GET /migration/backfill/progress
```

returns `{cuds: [{connectionUriDomain, mode, pendingUsers}, ...]}`. Wait
until `pendingUsers == 0` for every CUD you're migrating.

For impatient operators with large user counts: run the offline SQL
`migration-scripts/migration-backfill.sql` (from the `supertokens-postgresql-plugin`
repository) against the database from outside — it's idempotent, set-based, and
completes in one transaction. The cron will then see `pendingUsers == 0`
immediately.

**Verify (optional but recommended):**

```
GET /migration/backfill/progress?verify=true
```

This runs the `verifyBackfillCompleteness` scan and returns
`inconsistentUsersCount`. Expect 0.

For a deeper check, generate parity dumps using the dump scripts in
`supertokens-postgresql-plugin/migration-scripts/`:

```bash
psql -v app_id="'<app>'" -f migration-scripts/dump_old_canonical.sql > old.csv
psql -v app_id="'<app>'" -f migration-scripts/dump_new_canonical.sql > new.csv
diff old.csv new.csv
```

Both scripts emit the same canonical projection format, so a clean diff
means the two views agree.

**If the backfill stops with `Unknown recipeId during backfill: '...'`:** a
recipe has shipped rows into `app_id_to_user_id` that the backfill doesn't
have a case for. This is intentional fail-loud behaviour. Add the missing
branch to `MigrationBackfillQueries.backfillAccountInfos()` and ship a patch
release — silently marking these users as backfilled would lose their
reservation rows after the flip to MIGRATED.

**Rollback:** Not needed. Backfill is additive only.

### Step 4 — Flip to `DUAL_WRITE_READ_NEW`

```
PUT /recipe/multitenancy/connectionuridomain/v2
{
  "connectionUriDomain": "<cud>",
  "coreConfig": { "migration_mode": "DUAL_WRITE_READ_NEW" }
}
```

This is the **first risk-bearing step**. Reads now come from the new
reservation tables. If backfill missed anyone, they become invisible until
the next backfill tick. Make sure Step 3's `pendingUsers == 0` was honest.
The validator runs `requireBackfillComplete` only on the `→ MIGRATED`
boundary, not here — so this step does not block on backfill completion.
**You must check progress yourself before issuing it.**

**Deployment order if traffic is live:** all instances of a given CUD must
be on the new code before any of them flip. The CRUD path updates the
persistent tenant config and fans out via
`MultitenancyHelper.refreshAfterKnownTenantChange` — every instance picks
up the new mode on the next tenant-config refresh, in seconds. So:

1. Confirm all instances of the CUD report `DUAL_WRITE_READ_OLD`.
2. Issue the CRUD update.
3. Watch `GET /migration/mode` until every instance flips.

**Verify:**
- Read a user (created pre-cutover) → response matches the pre-cutover
  response.
- Read a user created during DUAL_WRITE_READ_OLD → also matches.
- Listing endpoints (`/users`, dashboard search) return the same set as
  before.
- Account-info conflicts on link/makePrimary/updateEmail now come from
  `primary_user_tenants` (new code path).

**Rollback:** Drop the mode back to `DUAL_WRITE_READ_OLD`. Old tables were
kept in sync, so reading from them is safe. **This rollback window stays
open until Step 6.**

### Step 5 — Soak

Leave the CUD in `DUAL_WRITE_READ_NEW` for at least one full release cycle
(1–2 weeks). Watch:

- Error rates (no rise expected; an opportunistic rise in
  `EMAIL_CHANGE_NOT_ALLOWED_ERROR` or
  `ACCOUNT_INFO_ALREADY_ASSOCIATED_WITH_ANOTHER_PRIMARY_USER_ID_ERROR`
  could indicate stale dual-write).
- Latencies (`updateEmail` and `linkAccounts` do roughly 2× the work in
  DUAL_WRITE — expect a measurable, but still bounded, latency increase).
- `GET /migration/backfill/progress?verify=true` periodically to confirm no
  drift.

### Step 6 — Flip to `MIGRATED`

```
PUT /recipe/multitenancy/connectionuridomain/v2
{
  "connectionUriDomain": "<cud>",
  "coreConfig": { "migration_mode": "MIGRATED" }
}
```

The validator runs `requireBackfillComplete` and refuses if any user has
`time_joined = 0`. Once the call succeeds, writes to old tables stop. From
this point old tables are stale.

**This step is one-way** through the standard CRUD path. The validator
blocks the reverse. To genuinely back out, you must (a) drop the tenant
config row in PG directly (escape hatch documented in
`MigrationModeTransition`), AND (b) replay every write since the flip into
the old tables out of band. Plan for the soak in Step 5 to be long enough
that you don't need this.

### Step 7 — Drop the deprecated tables (future release)

After every CUD has been on `MIGRATED` for at least one release cycle, ship
a separate release that does:

```sql
DROP TABLE IF EXISTS emailpassword_user_to_tenant;
DROP TABLE IF EXISTS passwordless_user_to_tenant;
DROP TABLE IF EXISTS thirdparty_user_to_tenant;
DROP TABLE IF EXISTS webauthn_user_to_tenant;
DROP TABLE IF EXISTS all_auth_recipe_users;
```

And in the same release, delete the `*_legacy` query helpers, the
`MigrationMode` enum's `LEGACY` / `DUAL_WRITE_*` modes (or the whole enum),
and the `migration_mode` config field. **Do not bundle the drop with the
cutover release.**

---

## Offline runbook (cold migration via SQL)

For deployments that can take a maintenance window, or operators
uncomfortable with the multi-mode online cutover, the same outcome is
reachable with a single planned downtime.

### Pre-flight

- Confirm versions as above.
- Schedule a maintenance window. Estimate is dataset-dependent; on a
  few-million-user database the script is set-based and completes in
  single-digit minutes; multi-tens-of-millions can take longer because the
  `recipe_user_tenants ⋈ all_auth_recipe_users` join scales with row count.
- Backup the database.

### Step 1 — Stop traffic

Drain or block all SuperTokens API traffic. No writes can be in flight
during the migration — the offline SQL script runs in a single transaction
and any concurrent writes against the old tables will not be reflected in
the new tables.

### Step 2 — Deploy new binaries, still in `LEGACY`

Boot one core+plugin instance against the database. This creates the three
new tables and adds the two new columns on `app_id_to_user_id` — the atomic
DDL batch in `GeneralQueries.createTablesIfNotExists` handles this on first
start.

Verify the schema is in place with:

```sql
\dt recipe_user_account_infos
\dt recipe_user_tenants
\dt primary_user_tenants
\d+ app_id_to_user_id      -- expect time_joined, primary_or_recipe_user_time_joined columns
```

Shut the instance down before the data backfill so nothing is writing while
psql runs.

### Step 3 — Run the offline backfill

The script lives in the `supertokens-postgresql-plugin` repository at
`migration-scripts/migration-backfill.sql`. Run it from a checkout of that
repository (or copy the file out and run from anywhere — it has no external
dependencies):

```bash
psql "<connection-uri>" -v app_id="''" -f migration-scripts/migration-backfill.sql
```

Pass `-v app_id="'my-app'"` to scope to a single app if you're staging by
tenant; unset means all apps. This runs four set-based INSERTs and one
UPDATE inside a single transaction (see the script for the exact SQL).

### Step 4 — Verify

Run the verification queries that ship as commented-out SQL at the bottom
of `migration-backfill.sql`. All three should return 0:

```sql
-- Users still missing time_joined
SELECT COUNT(*) FROM app_id_to_user_id WHERE time_joined = 0;

-- Users missing reservation rows
SELECT COUNT(*) FROM app_id_to_user_id a
LEFT JOIN recipe_user_account_infos rai
  ON a.app_id = rai.app_id AND a.user_id = rai.recipe_user_id
WHERE rai.recipe_user_id IS NULL;

-- Linked users missing primary reservations
SELECT COUNT(*) FROM app_id_to_user_id a
WHERE a.is_linked_or_is_a_primary_user = TRUE
  AND NOT EXISTS (
    SELECT 1 FROM primary_user_tenants pt
    WHERE pt.app_id = a.app_id
      AND pt.primary_user_id = a.primary_or_recipe_user_id
  );
```

For absolute confidence run the canonical dump comparison:

```bash
psql "<connection-uri>" -f migration-scripts/dump_old_canonical.sql > old.csv
psql "<connection-uri>" -f migration-scripts/dump_new_canonical.sql > new.csv
diff old.csv new.csv
```

A non-empty diff means a user exists in one projection but not the other,
or with different account info — investigate before flipping.

### Step 5 — Set every tenant to `MIGRATED` directly

Because the instance is down, you can either:

**Option A — edit the tenant_configs row directly** (escape-hatch from
`MigrationModeTransition`):

```sql
-- For each app's public tenant. Example for the base tenant on the root CUD:
UPDATE tenant_configs
SET core_config = jsonb_set(
    core_config::jsonb,
    '{migration_mode}',
    '"MIGRATED"'::jsonb
)::text
WHERE connection_uri_domain = '' AND app_id = 'public' AND tenant_id = 'public';
```

Adjust the JSON-cast syntax to your column type (`text` vs `jsonb`).
Repeat per `(connection_uri_domain, app_id, tenant_id)` row that needs a
non-default mode.

**Option B — boot, flip, shut down**: bring the new core up still in
`LEGACY`, issue
`PUT /recipe/multitenancy/connectionuridomain/v2` with
`migration_mode: "MIGRATED"` (the validator will accept because
`pendingUsers == 0`), shut down. This is closer to the documented happy
path and avoids any concern that direct-edit syntax doesn't match your
column type, at the cost of two restarts.

Either way: avoid stepping through `DUAL_WRITE_READ_OLD` and
`DUAL_WRITE_READ_NEW` because there's no traffic to dual-write for, and
avoid leaving any tenant in a transient mode.

### Step 6 — Bring traffic back up

Start all instances. They boot, read the persisted
`migration_mode: "MIGRATED"` from the tenant config, dispatch every
read/write to the new tables, and the old tables stop receiving writes.

**Verify** with a few synthetic users:

- Create user, link, update email — every operation succeeds.
- `SELECT COUNT(*) FROM all_auth_recipe_users WHERE … (a row that should
  have just been touched)` — old tables have not been written to (the
  row's last-modified is stale).
- `GET /migration/mode` returns `"mode": "MIGRATED"` for every CUD.

### Step 7 — Drop deprecated tables (later release)

Same as Step 7 of the online runbook; do not pair with the cutover.

---

## Decision matrix

| Scenario | Recommended path |
|---|---|
| Production with HA, can't tolerate downtime | **Online**, one CUD at a time, with the cron driving backfill |
| Single-region single-instance, comfortable with a maintenance window | **Offline**, one psql invocation |
| Very large dataset (>50M users) | Online, but kick off the offline SQL during Step 3 to skip the 5-minute cron tick latency |
| Self-hosted dev/staging | Offline; it's the simplest |
| Want to validate parity before flipping prod | Online to `DUAL_WRITE_READ_NEW`, soak, then use the canonical dump diff to spot-check before `MIGRATED` |

---

## Rollback reference

| From | To | How | Safe? |
|---|---|---|---|
| `LEGACY` (new code) | prior binary | Redeploy old binaries | Yes — new tables are empty |
| `DUAL_WRITE_READ_OLD` | `LEGACY` | CRUD update | Yes — extra rows in new tables are inert |
| `DUAL_WRITE_READ_NEW` | `DUAL_WRITE_READ_OLD` | CRUD update | Yes — old tables in sync from dual-writes |
| `MIGRATED` | `DUAL_WRITE_READ_NEW` | **Blocked** by validator; requires direct-DB edit AND backfill old tables from new | Risky — only as planned recovery |
