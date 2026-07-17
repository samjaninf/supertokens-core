/*
 *    Copyright (c) 2025, VRAI Labs and/or its affiliates. All rights reserved.
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

package io.supertokens.test.accountlinking;

import io.supertokens.ProcessState;
import io.supertokens.authRecipe.AuthRecipe;
import io.supertokens.emailpassword.EmailPassword;
import io.supertokens.featureflag.EE_FEATURES;
import io.supertokens.featureflag.FeatureFlagTestContent;
import io.supertokens.pluginInterface.STORAGE_TYPE;
import io.supertokens.pluginInterface.Storage;
import io.supertokens.pluginInterface.authRecipe.AuthRecipeUserInfo;
import io.supertokens.storageLayer.StorageLayer;
import io.supertokens.test.TestingProcessManager;
import io.supertokens.test.Utils;
import io.supertokens.thirdparty.ThirdParty;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestRule;

import static org.junit.Assert.*;

/**
 * Regression test for the node test
 * "should create a linked user after password reset flow if the main user was deleted"
 * (backend-sdk-testing test/accountlinking/emailpasswordapis2.test.js).
 *
 * Scenario: EP user is primary, TP user (same email) linked to it. The EP login method is
 * deleted with removeAllLinkedAccounts=false, so the group's primary user id refers to a
 * recipe user that no longer has any recipe_user_account_infos rows. Then (as the SDK does
 * during password reset consume) a new EP user with the same email is created and linked
 * back to the primary user. Before the COALESCE fallback in the in-memory
 * GeneralQueries.linkAccounts_Transaction, that last link failed with a NOT NULL constraint
 * violation on app_id_to_user_id.primary_or_recipe_user_id.
 */
public class PasswordResetAfterPrimaryLoginMethodDeleteTest {
    @Rule
    public TestRule watchman = Utils.getOnFailure();

    @Rule
    public TestRule retryFlaky = Utils.retryFlakyTest();

    @AfterClass
    public static void afterTesting() {
        Utils.afterTesting();
    }

    @Before
    public void beforeEach() {
        Utils.reset();
    }

    @Test
    public void createAndLinkNewEpUserAfterDelete_anyStorage() throws Exception {
        // No migration_mode override and no in-memory skip: on the sqlite matrix this runs
        // against the in-memory storage (hardcoded MIGRATED), which is where the
        // NOT NULL constraint failure on app_id_to_user_id.primary_or_recipe_user_id
        // was reproducible before the COALESCE fallback in linkAccounts_Transaction.
        runScenario(null);
    }

    @Test
    public void createAndLinkNewEpUserAfterDelete_LEGACY() throws Exception {
        runScenarioWithMigrationMode("LEGACY");
    }

    @Test
    public void createAndLinkNewEpUserAfterDelete_DUAL_WRITE_READ_OLD() throws Exception {
        runScenarioWithMigrationMode("DUAL_WRITE_READ_OLD");
    }

    @Test
    public void createAndLinkNewEpUserAfterDelete_DUAL_WRITE_READ_NEW() throws Exception {
        runScenarioWithMigrationMode("DUAL_WRITE_READ_NEW");
    }

    @Test
    public void createAndLinkNewEpUserAfterDelete_MIGRATED() throws Exception {
        runScenarioWithMigrationMode("MIGRATED");
    }

    private void runScenarioWithMigrationMode(String migrationMode) throws Exception {
        runScenario(migrationMode);
    }

    private void runScenario(String migrationMode) throws Exception {
        if (migrationMode != null) {
            Utils.setValueInConfig("migration_mode", migrationMode);
        }
        String[] args = {"../"};
        TestingProcessManager.TestingProcess process = TestingProcessManager.startIsolatedProcess(args, false);
        FeatureFlagTestContent.getInstance(process.getProcess())
                .setKeyValue(FeatureFlagTestContent.ENABLED_FEATURES, new EE_FEATURES[]{
                        EE_FEATURES.ACCOUNT_LINKING, EE_FEATURES.MULTI_TENANCY});
        process.startProcess();
        assertNotNull(process.checkOrWaitForEvent(ProcessState.PROCESS_STATE.STARTED));

        if (StorageLayer.getStorage(process.getProcess()).getType() != STORAGE_TYPE.SQL) {
            return;
        }

        // in-memory storage does not honor migration_mode; mode-dependent coverage requires postgres.
        // The migrationMode == null variant runs on any storage, including in-memory.
        if (migrationMode != null && StorageLayer.isInMemDb(process.getProcess())) {
            return;
        }

        String email = "john.doe@supertokens.com";

        // 1. EP signup + make primary
        AuthRecipeUserInfo epUser = EmailPassword.signUp(process.getProcess(), email, "differentvalidpass123");
        AuthRecipe.createPrimaryUser(process.getProcess(), epUser.getSupertokensUserId());

        // 2. TP user with same email, linked to the EP primary user
        AuthRecipeUserInfo tpUser = ThirdParty.signInUp(process.getProcess(), "google", "abcd2", email).user;
        assertFalse(AuthRecipe.linkAccounts(process.getProcess(), tpUser.getSupertokensUserId(),
                epUser.getSupertokensUserId()).wasAlreadyLinked);

        // 3. Delete the EP login method only (removeAllLinkedAccounts = false)
        AuthRecipe.deleteUser(process.getProcess(), epUser.getSupertokensUserId(), false);

        // 5. Primary user still exists with 1 login method (the TP one)
        AuthRecipeUserInfo primUser = AuthRecipe.getUserById(process.getProcess(), epUser.getSupertokensUserId());
        assertNotNull("primary user is gone after deleting the EP login method [" + migrationMode + "]", primUser);
        assertTrue(primUser.isPrimaryUser);
        assertEquals("unexpected login method count after delete [" + migrationMode + "]",
                1, primUser.loginMethods.length);

        // 6. Password reset token create + consume for the primary user id (SDK flow)
        String token = EmailPassword.generatePasswordResetToken(process.getProcess(),
                epUser.getSupertokensUserId(), email);
        EmailPassword.ConsumeResetPasswordTokenResult consumeResult =
                EmailPassword.consumeResetPasswordToken(process.getProcess(), token);
        assertEquals(epUser.getSupertokensUserId(), consumeResult.userId);
        assertEquals(email, consumeResult.email);

        // 7. SDK dependency: users-by-account-info for the email must return the primary user
        Storage storage = StorageLayer.getBaseStorage(process.getProcess());
        AuthRecipeUserInfo[] usersByEmail = AuthRecipe.getUsersByAccountInfo(process.getAppForTesting(),
                storage, true, email, null, null, null, null);
        assertEquals("users-by-account-info result count [" + migrationMode + "]", 1, usersByEmail.length);
        assertEquals(epUser.getSupertokensUserId(), usersByEmail[0].getSupertokensUserId());
        assertTrue(usersByEmail[0].isPrimaryUser);

        // 8. New EP user with the same email must be creatable (SDK: createNewRecipeUser)
        AuthRecipeUserInfo newEpUser = EmailPassword.signUp(process.getProcess(), email, "validpass123");

        // 9. Linking the new EP user to the still-existing primary user must succeed
        AuthRecipe.LinkAccountsResult linkResult = AuthRecipe.linkAccounts(process.getProcess(),
                newEpUser.getSupertokensUserId(), epUser.getSupertokensUserId());
        assertFalse(linkResult.wasAlreadyLinked);

        // 10. Primary user now has 2 login methods
        AuthRecipeUserInfo finalUser = AuthRecipe.getUserById(process.getProcess(), tpUser.getSupertokensUserId());
        assertNotNull(finalUser);
        assertEquals("final login method count [" + migrationMode + "]", 2, finalUser.loginMethods.length);

        process.kill();
        assertNotNull(process.checkOrWaitForEvent(ProcessState.PROCESS_STATE.STOPPED));
    }
}
