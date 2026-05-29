/*
 *    Copyright (c) 2026, VRAI Labs and/or its affiliates. All rights reserved.
 *
 *    This software is licensed under the Apache License, Version 2.0 (the
 *    "License") as published by the Apache Software Foundation.
 *
 *    You may not use this file except in compliance with the License. You may
 *    obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 *    WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *    License for the specific language governing permissions and limitations
 *    under the License.
 */

package io.supertokens.migration;

import io.supertokens.Main;
import io.supertokens.pluginInterface.MigrationMode;
import io.supertokens.pluginInterface.Storage;
import io.supertokens.pluginInterface.exceptions.InvalidConfigException;
import io.supertokens.pluginInterface.exceptions.StorageQueryException;
import io.supertokens.pluginInterface.migration.MigrationBackfillStorage;
import io.supertokens.pluginInterface.multitenancy.AppIdentifier;
import io.supertokens.pluginInterface.multitenancy.exceptions.TenantOrAppNotFoundException;
import io.supertokens.storageLayer.StorageLayer;

/**
 * Validates state-machine transitions of {@link MigrationMode} as set through the
 * standard multitenancy core_config CRUD path. Invoked from
 * {@code Multitenancy.validateTenantConfig} when the core_config carries a
 * {@code migration_mode} entry.
 *
 * <p>Allowed transitions:
 * <pre>
 *   LEGACY              ↔  DUAL_WRITE_READ_OLD
 *   DUAL_WRITE_READ_OLD ↔  DUAL_WRITE_READ_NEW
 *   DUAL_WRITE_READ_NEW →  MIGRATED            (only when getBackfillPendingUsersCount == 0)
 *   MIGRATED                                    (terminal; no outgoing transitions)
 * </pre>
 *
 * <p>The {@code MIGRATED} terminal restriction reflects a real constraint: once writes
 * stop going to the old tables, the old tables are stale forever, so a rollback to any
 * mode that reads from old tables would surface stale data. Operators who need to undo
 * a {@code MIGRATED} state must drop and re-populate the old tables (out of band) and
 * then return through the chain.
 *
 * <p>The expensive {@code getBackfillPendingUsersCount} probe runs only on
 * transitions whose target is {@code MIGRATED}. All other transitions are validated
 * by enum comparison alone.
 *
 * <p><b>Escape hatches.</b> This validator only runs in the standard multitenancy CRUD
 * path. Operators can bypass it via:
 * <ul>
 *   <li>Direct DB edit of {@code tenant_configs.core_config} — bypasses validation, but
 *       the in-memory {@code PostgreSQLConfig} only picks up the change on the next
 *       tenant-config refresh (caused by another CRUD update or a process restart).
 *   <li>{@code config.yaml} loaded at process boot — no previous-state context, so no
 *       transition rules run.
 * </ul>
 * These are intentional safety valves for recovery from a wedged state machine.
 */
public class MigrationModeTransition {

    private MigrationModeTransition() {
    }

    /**
     * Validates the proposed transition from {@code oldMode} to {@code newMode} for the
     * given tenant's app. A no-op transition (oldMode == newMode) is always allowed.
     *
     * @param main             the {@link Main} instance, used to look up the storage for the backfill probe
     * @param appIdentifier    the app the storage belongs to (used for the backfill probe)
     * @param oldMode          the current mode (must not be null — caller resolves missing to LEGACY)
     * @param newMode          the proposed mode (must not be null — caller resolves missing to LEGACY)
     *
     * @throws InvalidConfigException if the transition is not allowed, with an operator-facing message
     */
    public static void validate(Main main, AppIdentifier appIdentifier,
                                MigrationMode oldMode, MigrationMode newMode)
            throws InvalidConfigException {

        if (oldMode == newMode) {
            return; // no-op
        }

        // MIGRATED is terminal: any outgoing transition would resume writes to stale old tables.
        if (oldMode == MigrationMode.MIGRATED) {
            throw new InvalidConfigException(
                    "migration_mode is currently MIGRATED, which is terminal. Old tables are stale " +
                    "and cannot be re-introduced into the read path without first being repopulated " +
                    "out of band. Refusing transition to " + newMode + ".");
        }

        // All other transitions must move exactly one step forward or backward in the chain.
        if (!isOneStep(oldMode, newMode)) {
            throw new InvalidConfigException(
                    "migration_mode transitions must progress one step at a time. " +
                    "Refusing " + oldMode + " → " + newMode + ". " +
                    "Allowed neighbours of " + oldMode + ": " + neighboursOf(oldMode) + ".");
        }

        // The only step that requires a runtime probe is → MIGRATED, because crossing
        // that boundary stops writes to old tables. Skip the probe for every other
        // transition so we don't pay a count() query on each tenant config update.
        if (newMode == MigrationMode.MIGRATED) {
            requireBackfillComplete(main, appIdentifier);
        }
    }

    /**
     * Returns true iff oldMode and newMode are adjacent in the chain
     * LEGACY ↔ DUAL_WRITE_READ_OLD ↔ DUAL_WRITE_READ_NEW ↔ MIGRATED.
     */
    private static boolean isOneStep(MigrationMode oldMode, MigrationMode newMode) {
        return Math.abs(oldMode.ordinal() - newMode.ordinal()) == 1;
    }

    private static String neighboursOf(MigrationMode mode) {
        switch (mode) {
            case LEGACY:
                return "DUAL_WRITE_READ_OLD";
            case DUAL_WRITE_READ_OLD:
                return "LEGACY, DUAL_WRITE_READ_NEW";
            case DUAL_WRITE_READ_NEW:
                return "DUAL_WRITE_READ_OLD, MIGRATED";
            case MIGRATED:
                return "(none — terminal)";
            default:
                return "(unknown)";
        }
    }

    /**
     * Runs the {@code getBackfillPendingUsersCount} probe and rejects the transition
     * if any user is still pending or if the storage can't be queried.
     *
     * <p>Fail-safe-reject on query failure: operator can retry once storage is healthy.
     */
    private static void requireBackfillComplete(Main main, AppIdentifier appIdentifier)
            throws InvalidConfigException {
        Storage storage;
        try {
            storage = StorageLayer.getStorage(appIdentifier.getAsPublicTenantIdentifier(), main);
        } catch (TenantOrAppNotFoundException e) {
            throw new InvalidConfigException(
                    "Cannot transition to MIGRATED: tenant storage is not available — " + e.getMessage());
        }

        if (!(storage instanceof MigrationBackfillStorage)) {
            throw new InvalidConfigException(
                    "Cannot transition to MIGRATED: storage plugin does not implement " +
                    "MigrationBackfillStorage, so backfill completeness cannot be verified.");
        }

        int pending;
        try {
            pending = ((MigrationBackfillStorage) storage).getBackfillPendingUsersCount(appIdentifier);
        } catch (StorageQueryException e) {
            throw new InvalidConfigException(
                    "Cannot transition to MIGRATED: failed to query backfill progress — " +
                    e.getMessage() + ". Retry once storage is healthy.");
        }

        if (pending > 0) {
            throw new InvalidConfigException(
                    "Cannot transition to MIGRATED: " + pending + " user(s) still need backfilling. " +
                    "Run the backfill (or wait for the cron) until pendingUsers == 0, then retry.");
        }
    }
}
