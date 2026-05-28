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
import io.supertokens.pluginInterface.Storage;
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
 * GET  /migration/mode
 *   – Root CUD ("", public): returns { status, cuds: [{connectionUriDomain, mode}, ...] }
 *   – Any other CUD:         returns { status, mode }
 *
 * Read-only convenience for operators. To set the migration mode, use the existing
 * multitenancy CRUD endpoint with a coreConfig.migration_mode entry, e.g.
 *
 *   PUT /recipe/multitenancy/connectionuridomain/v2
 *   { "connectionUriDomain": "...", "coreConfig": { "migration_mode": "DUAL_WRITE_READ_OLD" } }
 *
 * That goes through overwriteTenantConfig + MultitenancyHelper.refreshAfterKnownTenantChange,
 * which rebuilds the storage's config from the persisted JSON and makes the new value
 * visible to this endpoint on the next call.
 */
public class MigrationModeAPI extends WebserverAPI {
    private static final long serialVersionUID = 4823845786237L;

    public MigrationModeAPI(Main main) {
        super(main, "");
    }

    @Override
    public String getPath() {
        return "/migration/mode";
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException, ServletException {
        try {
            AppIdentifier appIdentifier = getAppIdentifier(req);

            if (isRootCUD(appIdentifier)) {
                JsonArray cuds = new JsonArray();
                for (List<TenantIdentifier> tenants : StorageLayer.getTenantsWithUniqueUserPoolId(main)) {
                    TenantIdentifier rep = tenants.get(0);
                    Storage storage = StorageLayer.getStorage(rep, main);
                    if (!(storage instanceof MigrationBackfillStorage)) {
                        continue;
                    }
                    JsonObject entry = new JsonObject();
                    entry.addProperty("connectionUriDomain", rep.getConnectionUriDomain());
                    entry.addProperty("mode", ((MigrationBackfillStorage) storage).getMigrationMode().name());
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
                result.addProperty("status", "OK");
                result.addProperty("mode", ((MigrationBackfillStorage) storage).getMigrationMode().name());
                sendJsonResponse(200, result, resp);
            }
        } catch (TenantOrAppNotFoundException e) {
            throw new ServletException(e);
        }
    }

    private static boolean isRootCUD(AppIdentifier appIdentifier) {
        return appIdentifier.getConnectionUriDomain().isEmpty()
                && appIdentifier.getAppId().equals(AppIdentifier.DEFAULT_APP_ID);
    }
}
