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

package io.supertokens.webserver.api.migration;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.supertokens.Main;
import io.supertokens.pluginInterface.MigrationMode;
import io.supertokens.pluginInterface.Storage;
import io.supertokens.pluginInterface.exceptions.StorageQueryException;
import io.supertokens.pluginInterface.migration.MigrationBackfillStorage;
import io.supertokens.pluginInterface.multitenancy.AppIdentifier;
import io.supertokens.pluginInterface.multitenancy.TenantIdentifier;
import io.supertokens.pluginInterface.multitenancy.exceptions.TenantOrAppNotFoundException;
import io.supertokens.storageLayer.StorageLayer;
import io.supertokens.webserver.WebserverAPI;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.List;

/**
 * GET  /migration/backfill/progress[?verify=true]
 *   – Root CUD ("", public): returns
 *       { status, cuds: [{connectionUriDomain, mode, pendingUsers, inconsistentUsersCount?, verifySkipped?, status?}, ...] }
 *     Each CUD entry has either a {mode, pendingUsers, ...} payload OR
 *     {status: "FEATURE_NOT_SUPPORTED"} when the storage doesn't implement MigrationBackfillStorage.
 *   – Any other CUD:         returns
 *       { status, mode, pendingUsers, inconsistentUsersCount?, verifySkipped? }
 *     or { status: "FEATURE_NOT_SUPPORTED_ERROR" }.
 *
 * `pendingUsers` is always 0 for modes that no longer read from old tables (DUAL_WRITE_READ_NEW, MIGRATED).
 *
 * `inconsistentUsersCount` is only included when verify=true AND all of:
 *   - pendingUsers == 0 (no point verifying while backfill is still running)
 *   - mode still reads from old tables (already-migrated CUDs have no meaningful inconsistency to detect)
 *
 * When verify=true but the count was skipped, `verifySkipped` carries the reason:
 *   - "backfillIncomplete"  pending > 0
 *   - "migrated"            mode no longer reads from old tables
 * This gives monitoring callers a stable response shape regardless of CUD state.
 */
public class MigrationBackfillProgressAPI extends WebserverAPI {
    private static final long serialVersionUID = 9823745862341L;

    public MigrationBackfillProgressAPI(Main main) {
        super(main, "");
    }

    @Override
    public String getPath() {
        return "/migration/backfill/progress";
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException, ServletException {
        try {
            AppIdentifier appIdentifier = getAppIdentifier(req);
            boolean verify = "true".equals(req.getParameter("verify"));

            if (isRootCUD(appIdentifier)) {
                JsonArray cuds = new JsonArray();
                for (List<TenantIdentifier> tenants : StorageLayer.getTenantsWithUniqueUserPoolId(main)) {
                    TenantIdentifier rep = tenants.get(0);
                    Storage storage = StorageLayer.getStorage(rep, main);
                    JsonObject entry = new JsonObject();
                    entry.addProperty("connectionUriDomain", rep.getConnectionUriDomain());
                    if (!(storage instanceof MigrationBackfillStorage)) {
                        // Surface unsupported CUDs explicitly so operators can tell them apart
                        // from CUDs that are missing from the cluster entirely.
                        entry.addProperty("status", "FEATURE_NOT_SUPPORTED");
                        cuds.add(entry);
                        continue;
                    }
                    AppIdentifier cudApp = new AppIdentifier(rep.getConnectionUriDomain(), null);
                    fillProgressFields(entry, (MigrationBackfillStorage) storage, cudApp, verify);
                    cuds.add(entry);
                }
                JsonObject result = new JsonObject();
                result.addProperty("status", "OK");
                result.add("cuds", cuds);
                sendJsonResponse(200, result, resp);
            } else {
                Storage storage = StorageLayer.getStorage(appIdentifier.getAsPublicTenantIdentifier(), main);
                if (!(storage instanceof MigrationBackfillStorage)) {
                    JsonObject result = new JsonObject();
                    result.addProperty("status", "FEATURE_NOT_SUPPORTED_ERROR");
                    sendJsonResponse(200, result, resp);
                    return;
                }
                JsonObject result = new JsonObject();
                fillProgressFields(result, (MigrationBackfillStorage) storage, appIdentifier, verify);
                result.addProperty("status", "OK");
                sendJsonResponse(200, result, resp);
            }
        } catch (TenantOrAppNotFoundException | StorageQueryException e) {
            throw new ServletException(e);
        }
    }

    /**
     * Populates {@code mode}, {@code pendingUsers}, and (when verify=true)
     * either {@code inconsistentUsersCount} or {@code verifySkipped} on the given entry.
     */
    private void fillProgressFields(JsonObject entry, MigrationBackfillStorage mbs,
                                    AppIdentifier app, boolean verify) throws StorageQueryException {
        MigrationMode mode = mbs.getMigrationMode();
        entry.addProperty("mode", mode.name());

        // Only meaningful while actively backfilling (DUAL_WRITE_READ_OLD). All other modes either
        // never write to new tables (LEGACY) or have already flipped their read path to new tables.
        int pending = mode.readsFromOldTables() ? mbs.getBackfillPendingUsersCount(app) : 0;
        entry.addProperty("pendingUsers", pending);

        if (!verify) {
            return;
        }

        // Completeness scan is only relevant pre-flip (still reading from old tables), only when
        // backfill is done, and must be explicitly requested — it is a full table scan.
        if (!mode.readsFromOldTables()) {
            entry.addProperty("verifySkipped", "migrated");
        } else if (pending > 0) {
            entry.addProperty("verifySkipped", "backfillIncomplete");
        } else {
            entry.addProperty("inconsistentUsersCount", mbs.verifyBackfillCompleteness(app));
        }
    }

}
