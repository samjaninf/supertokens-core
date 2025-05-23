/*
 *    Copyright (c) 2024, VRAI Labs and/or its affiliates. All rights reserved.
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

package io.supertokens.test.bulkimport;

import io.supertokens.Main;
import io.supertokens.ProcessState;
import io.supertokens.authRecipe.AuthRecipe;
import io.supertokens.bulkimport.BulkImport;
import io.supertokens.bulkimport.BulkImportUserPaginationContainer;
import io.supertokens.cronjobs.CronTaskTest;
import io.supertokens.cronjobs.bulkimport.ProcessBulkImportUsers;
import io.supertokens.emailpassword.EmailPassword;
import io.supertokens.featureflag.EE_FEATURES;
import io.supertokens.featureflag.FeatureFlagTestContent;
import io.supertokens.pluginInterface.STORAGE_TYPE;
import io.supertokens.pluginInterface.Storage;
import io.supertokens.pluginInterface.authRecipe.AuthRecipeUserInfo;
import io.supertokens.pluginInterface.bulkimport.BulkImportStorage;
import io.supertokens.pluginInterface.bulkimport.BulkImportStorage.BULK_IMPORT_USER_STATUS;
import io.supertokens.pluginInterface.bulkimport.BulkImportUser;
import io.supertokens.pluginInterface.bulkimport.BulkImportUser.LoginMethod;
import io.supertokens.pluginInterface.bulkimport.exceptions.BulkImportBatchInsertException;
import io.supertokens.pluginInterface.bulkimport.sqlStorage.BulkImportSQLStorage;
import io.supertokens.pluginInterface.multitenancy.AppIdentifier;
import io.supertokens.pluginInterface.multitenancy.TenantIdentifier;
import io.supertokens.storageLayer.StorageLayer;
import io.supertokens.test.TestingProcessManager;
import io.supertokens.test.Utils;
import io.supertokens.thirdparty.ThirdParty;
import io.supertokens.userroles.UserRoles;
import io.supertokens.webauthn.WebAuthN;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestRule;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static io.supertokens.test.bulkimport.BulkImportTestUtils.generateBulkImportUser;
import static io.supertokens.test.bulkimport.BulkImportTestUtils.generateBulkImportUserWithEmailPasswordAndRoles;
import static org.junit.Assert.*;

public class BulkImportTest {
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
    public void shouldAddUsersInBulkImportUsersTable() throws Exception {
        String[] args = { "../" };

        TestingProcessManager.TestingProcess process = TestingProcessManager.start(args);
        assertNotNull(process.checkOrWaitForEvent(ProcessState.PROCESS_STATE.STARTED));
        Main main = process.getProcess();

        if (StorageLayer.getStorage(main).getType() != STORAGE_TYPE.SQL || StorageLayer.isInMemDb(main)) {
            return;
        }

        List<BulkImportUser> users = generateBulkImportUser(10);

        BulkImportStorage storage = (BulkImportStorage) StorageLayer.getStorage(process.getProcess());
        BulkImport.addUsers(new AppIdentifier(null, null), storage, users);

        List<BulkImportUser> addedUsers = storage.getBulkImportUsers(new AppIdentifier(null, null), 100,
                BULK_IMPORT_USER_STATUS.NEW, null, null);

        // Verify that all users are present in addedUsers
        for (BulkImportUser user : users) {
            BulkImportUser matchingUser = addedUsers.stream()
                    .filter(addedUser -> user.id.equals(addedUser.id))
                    .findFirst()
                    .orElse(null);

            assertNotNull(matchingUser);
            assertEquals(BULK_IMPORT_USER_STATUS.NEW, matchingUser.status);
            assertEquals(user.toRawDataForDbStorage(), matchingUser.toRawDataForDbStorage());
        }

        process.kill();
        assertNotNull(process.checkOrWaitForEvent(ProcessState.PROCESS_STATE.STOPPED));
    }

    @Test
    public void shouldCreatedNewIdsIfDuplicateIdIsFound() throws Exception {
        String[] args = { "../" };

        TestingProcessManager.TestingProcess process = TestingProcessManager.start(args);
        assertNotNull(process.checkOrWaitForEvent(ProcessState.PROCESS_STATE.STARTED));
        Main main = process.getProcess();

        if (StorageLayer.getStorage(main).getType() != STORAGE_TYPE.SQL || StorageLayer.isInMemDb(main)) {
            return;
        }

        List<BulkImportUser> users = generateBulkImportUser(10);

        // We are setting the id of the second user to be the same as the first user to ensure a duplicate id is present
        users.get(1).id = users.get(0).id;

        List<String> initialIds = users.stream().map(user -> user.id).collect(Collectors.toList());

        BulkImportStorage storage = (BulkImportStorage) StorageLayer.getStorage(process.getProcess());
        AppIdentifier appIdentifier = new AppIdentifier(null, null);
        BulkImport.addUsers(appIdentifier, storage, users);

        List<BulkImportUser> addedUsers = storage.getBulkImportUsers(appIdentifier, 1000, BULK_IMPORT_USER_STATUS.NEW,
                null, null);

        // Verify that the other properties are same but ids changed
        for (BulkImportUser user : users) {
            BulkImportUser matchingUser = addedUsers.stream()
                    .filter(addedUser -> user.toRawDataForDbStorage().equals(addedUser.toRawDataForDbStorage()))
                    .findFirst()
                    .orElse(null);

            assertNotNull(matchingUser);
            assertEquals(BULK_IMPORT_USER_STATUS.NEW, matchingUser.status);
            assertFalse(initialIds.contains(matchingUser.id));
        }

        process.kill();
        assertNotNull(process.checkOrWaitForEvent(ProcessState.PROCESS_STATE.STOPPED));
    }

    @Test
    public void testGetUsersStatusFilter() throws Exception {
        String[] args = { "../" };

        TestingProcessManager.TestingProcess process = TestingProcessManager.start(args);
        assertNotNull(process.checkOrWaitForEvent(ProcessState.PROCESS_STATE.STARTED));
        Main main = process.getProcess();

        if (StorageLayer.getStorage(main).getType() != STORAGE_TYPE.SQL || StorageLayer.isInMemDb(main)) {
            return;
        }

        BulkImportSQLStorage storage = (BulkImportSQLStorage) StorageLayer.getStorage(process.getProcess());
        AppIdentifier appIdentifier = process.getAppForTesting().toAppIdentifier();

        // Test with status = 'NEW'
        {
            List<BulkImportUser> users = generateBulkImportUser(10);
            BulkImport.addUsers(appIdentifier, storage, users);

            List<BulkImportUser> addedUsers = storage.getBulkImportUsers(appIdentifier, 100,
                    BULK_IMPORT_USER_STATUS.NEW, null, null);
            assertEquals(10, addedUsers.size());
        }

        // Test with status = 'PROCESSING'
        {
            List<BulkImportUser> users = generateBulkImportUser(10);
            BulkImport.addUsers(appIdentifier, storage, users);

            // Update the users status to PROCESSING
            storage.startTransaction(con -> {
                for (BulkImportUser user : users) {
                    storage.updateBulkImportUserStatus_Transaction(appIdentifier, con, user.id,
                            BULK_IMPORT_USER_STATUS.PROCESSING, null);
                }
                storage.commitTransaction(con);
                return null;
            });

            List<BulkImportUser> addedUsers = storage.getBulkImportUsers(appIdentifier, 100,
                    BULK_IMPORT_USER_STATUS.PROCESSING, null, null);
            assertEquals(10, addedUsers.size());
        }

        // Test with status = 'FAILED'
        {
            List<BulkImportUser> users = generateBulkImportUser(10);
            BulkImport.addUsers(appIdentifier, storage, users);

            // Update the users status to FAILED
            storage.startTransaction(con -> {
                for (BulkImportUser user : users) {
                    storage.updateBulkImportUserStatus_Transaction(appIdentifier, con, user.id,
                            BULK_IMPORT_USER_STATUS.FAILED, null);
                }
                storage.commitTransaction(con);
                return null;
            });

            List<BulkImportUser> addedUsers = storage.getBulkImportUsers(appIdentifier, 100,
                    BULK_IMPORT_USER_STATUS.FAILED, null, null);
            assertEquals(10, addedUsers.size());
        }

        process.kill();
        assertNotNull(process.checkOrWaitForEvent(ProcessState.PROCESS_STATE.STOPPED));
    }

    @Test
    public void randomPaginationTest() throws Exception {
        String[] args = { "../" };

        TestingProcessManager.TestingProcess process = TestingProcessManager.startIsolatedProcess(args, false);

        // We are setting a high initial wait time to ensure the cron job doesn't run while we are running the tests
        CronTaskTest.getInstance(process.getProcess()).setInitialWaitTimeInSeconds(ProcessBulkImportUsers.RESOURCE_KEY,
                1000000);

        process.startProcess();

        assertNotNull(process.checkOrWaitForEvent(ProcessState.PROCESS_STATE.STARTED));
        Main main = process.getProcess();

        if (StorageLayer.getStorage(main).getType() != STORAGE_TYPE.SQL || StorageLayer.isInMemDb(main)) {
            return;
        }

        BulkImportStorage storage = (BulkImportStorage) StorageLayer.getStorage(process.getProcess());

        int numberOfUsers = 500;
        // Insert users in batches
        {
            int batchSize = 100;
            for (int i = 0; i < numberOfUsers; i += batchSize) {
                List<BulkImportUser> users = generateBulkImportUser(batchSize);
                BulkImport.addUsers(new AppIdentifier(null, null), storage, users);
                // Adding a delay between each batch to ensure the createdAt different
                Thread.sleep(1000);
            }
        }

        // Get all inserted users
        List<BulkImportUser> addedUsers = storage.getBulkImportUsers(new AppIdentifier(null, null), 1000, null, null,
                null);
        assertEquals(numberOfUsers, addedUsers.size());

        // We are sorting the users based on createdAt and id like we do in the storage layer
        List<BulkImportUser> sortedUsers = addedUsers.stream()
                .sorted((user1, user2) -> {
                    int compareResult = Long.compare(user2.createdAt, user1.createdAt);
                    if (compareResult == 0) {
                        return user2.id.compareTo(user1.id);
                    }
                    return compareResult;
                })
                .collect(Collectors.toList());

        int[] limits = new int[] { 10, 14, 20, 23, 50, 100, 110, 150, 200, 510 };

        for (int limit : limits) {
            int indexIntoUsers = 0;
            String paginationToken = null;
            do {
                BulkImportUserPaginationContainer users = BulkImport.getUsers(new AppIdentifier(null, null), storage,
                        limit, null, paginationToken);

                for (BulkImportUser actualUser : users.users) {
                    BulkImportUser expectedUser = sortedUsers.get(indexIntoUsers);

                    assertEquals(expectedUser.id, actualUser.id);
                    assertEquals(expectedUser.status, actualUser.status);
                    assertEquals(expectedUser.toRawDataForDbStorage(), actualUser.toRawDataForDbStorage());
                    indexIntoUsers++;
                }

                paginationToken = users.nextPaginationToken;
            } while (paginationToken != null);

            assert (indexIntoUsers == sortedUsers.size());
        }

        process.kill();
        assertNotNull(process.checkOrWaitForEvent(ProcessState.PROCESS_STATE.STOPPED));
    }

    @Test
    public void testGetBulkImportUsersCount() throws Exception {
        String[] args = { "../" };

        TestingProcessManager.TestingProcess process = TestingProcessManager.start(args);
        assertNotNull(process.checkOrWaitForEvent(ProcessState.PROCESS_STATE.STARTED));
        Main main = process.getProcess();

        if (StorageLayer.getStorage(main).getType() != STORAGE_TYPE.SQL || StorageLayer.isInMemDb(main)) {
            return;
        }

        BulkImportSQLStorage storage = (BulkImportSQLStorage) StorageLayer.getStorage(process.getProcess());
        AppIdentifier appIdentifier = new AppIdentifier(null, null);

        // Test with status = 'NEW'
        {
            List<BulkImportUser> users = generateBulkImportUser(10);
            BulkImport.addUsers(appIdentifier, storage, users);

            long count = BulkImport.getBulkImportUsersCount(appIdentifier, storage, BULK_IMPORT_USER_STATUS.NEW);
            assertEquals(10, count);
        }

        // Test with status = 'PROCESSING'
        {
            List<BulkImportUser> users = generateBulkImportUser(10);
            BulkImport.addUsers(appIdentifier, storage, users);

            // Update the users status to PROCESSING
            storage.startTransaction(con -> {
                for (BulkImportUser user : users) {
                    storage.updateBulkImportUserStatus_Transaction(appIdentifier, con, user.id,
                            BULK_IMPORT_USER_STATUS.PROCESSING, null);
                }
                storage.commitTransaction(con);
                return null;
            });

            long count = BulkImport.getBulkImportUsersCount(appIdentifier, storage, BULK_IMPORT_USER_STATUS.PROCESSING);
            assertEquals(10, count);
        }

        // Test with status = 'FAILED'
        {
            List<BulkImportUser> users = generateBulkImportUser(10);
            BulkImport.addUsers(appIdentifier, storage, users);

            // Update the users status to FAILED
            storage.startTransaction(con -> {
                for (BulkImportUser user : users) {
                    storage.updateBulkImportUserStatus_Transaction(appIdentifier, con, user.id,
                            BULK_IMPORT_USER_STATUS.FAILED, null);
                }
                storage.commitTransaction(con);
                return null;
            });

            long count = BulkImport.getBulkImportUsersCount(appIdentifier, storage, BULK_IMPORT_USER_STATUS.FAILED);
            assertEquals(10, count);
        }

        process.kill();
        assertNotNull(process.checkOrWaitForEvent(ProcessState.PROCESS_STATE.STOPPED));
    }

    @Test
    public void shouldImportTheUserInTheSameTenant() throws Exception {
        String[] args = { "../" };

        TestingProcessManager.TestingProcess process = TestingProcessManager.start(args);
        assertNotNull(process.checkOrWaitForEvent(ProcessState.PROCESS_STATE.STARTED));
        Main main = process.getProcess();

        if (StorageLayer.getStorage(main).getType() != STORAGE_TYPE.SQL || StorageLayer.isInMemDb(main)) {
            return;
        }

        FeatureFlagTestContent.getInstance(main).setKeyValue(FeatureFlagTestContent.ENABLED_FEATURES,
                new EE_FEATURES[] { EE_FEATURES.MULTI_TENANCY, EE_FEATURES.MFA, EE_FEATURES.ACCOUNT_LINKING });

        // Create tenants
        BulkImportTestUtils.createTenants(process);

        // Create user roles
        {
            UserRoles.createNewRoleOrModifyItsPermissions(main, "role1", null);
            UserRoles.createNewRoleOrModifyItsPermissions(main, "role2", null);
        }

        AppIdentifier appIdentifier = process.getAppForTesting().toAppIdentifier();
        List<BulkImportUser> users = generateBulkImportUser(1);

        AuthRecipeUserInfo importedUser = BulkImport.importUser(main, appIdentifier, users.get(0));

        BulkImportTestUtils.assertBulkImportUserAndAuthRecipeUserAreEqual(main, appIdentifier,
                appIdentifier.getAsPublicTenantIdentifier(), StorageLayer.getStorage(main), users.get(0), importedUser);

        process.kill();
        assertNotNull(process.checkOrWaitForEvent(ProcessState.PROCESS_STATE.STOPPED));
    }

    @Test
    public void shouldImportTheUserInMultipleTenantsWithDifferentStorages() throws Exception {
        String[] args = { "../" };

        TestingProcessManager.TestingProcess process = TestingProcessManager.start(args);
        assertNotNull(process.checkOrWaitForEvent(ProcessState.PROCESS_STATE.STARTED));
        Main main = process.getProcess();

        if (StorageLayer.getStorage(main).getType() != STORAGE_TYPE.SQL || StorageLayer.isInMemDb(main)) {
            return;
        }

        FeatureFlagTestContent.getInstance(main).setKeyValue(FeatureFlagTestContent.ENABLED_FEATURES,
                new EE_FEATURES[] { EE_FEATURES.MULTI_TENANCY, EE_FEATURES.MFA, EE_FEATURES.ACCOUNT_LINKING });

        // Create tenants
        BulkImportTestUtils.createTenants(process);

        // Create user roles
        {
            UserRoles.createNewRoleOrModifyItsPermissions(main, "role1", null);
            UserRoles.createNewRoleOrModifyItsPermissions(main, "role2", null);
        }

        TenantIdentifier t1 = new TenantIdentifier(null, process.getAppForTesting().getAppId(), "t1");
        TenantIdentifier t2 = new TenantIdentifier(null, process.getAppForTesting().getAppId(), "t2");

        Storage storageT1 = StorageLayer.getStorage(t1, main);
        Storage storageT2 = StorageLayer.getStorage(t2, main);

        AppIdentifier appIdentifier = process.getAppForTesting().toAppIdentifier();

        List<BulkImportUser> usersT1 = generateBulkImportUser(1, List.of(t1.getTenantId()), 0);
        List<BulkImportUser> usersT2 = generateBulkImportUser(1, List.of(t2.getTenantId()), 1);

        BulkImportUser bulkImportUserT1 = usersT1.get(0);
        BulkImportUser bulkImportUserT2 = usersT2.get(0);

        AuthRecipeUserInfo importedUser1 = BulkImport.importUser(main, appIdentifier, bulkImportUserT1);
        AuthRecipeUserInfo importedUser2 = BulkImport.importUser(main, appIdentifier, bulkImportUserT2);

        BulkImportTestUtils.assertBulkImportUserAndAuthRecipeUserAreEqual(main, appIdentifier, t1, storageT1,
                bulkImportUserT1,
                importedUser1);
        BulkImportTestUtils.assertBulkImportUserAndAuthRecipeUserAreEqual(main, appIdentifier, t2, storageT2,
                bulkImportUserT2,
                importedUser2);

        process.kill();
        assertNotNull(process.checkOrWaitForEvent(ProcessState.PROCESS_STATE.STOPPED));
    }

    @Test
    public void shouldImportUsersConcurrently() throws Exception {
        String[] args = { "../" };

        TestingProcessManager.TestingProcess process = TestingProcessManager.start(args);
        assertNotNull(process.checkOrWaitForEvent(ProcessState.PROCESS_STATE.STARTED));
        Main main = process.getProcess();

        if (StorageLayer.getStorage(main).getType() != STORAGE_TYPE.SQL || StorageLayer.isInMemDb(main)) {
            return;
        }

        FeatureFlagTestContent.getInstance(main).setKeyValue(FeatureFlagTestContent.ENABLED_FEATURES,
                new EE_FEATURES[] { EE_FEATURES.MULTI_TENANCY, EE_FEATURES.MFA, EE_FEATURES.ACCOUNT_LINKING });

        // Create tenants
        BulkImportTestUtils.createTenants(process);

        // Create user roles
        {
            UserRoles.createNewRoleOrModifyItsPermissions(main, "role1", null);
            UserRoles.createNewRoleOrModifyItsPermissions(main, "role2", null);
        }

        AppIdentifier appIdentifier = process.getAppForTesting().toAppIdentifier();
        List<BulkImportUser> users = generateBulkImportUser(10);

        // Concurrently import users
        ExecutorService executor = Executors.newFixedThreadPool(10);
        List<Future<AuthRecipeUserInfo>> futures = new ArrayList<>();

        for (BulkImportUser user : users) {
            Future<AuthRecipeUserInfo> future = executor.submit(() -> {
                return BulkImport.importUser(main, appIdentifier, user);
            });
            futures.add(future);
        }

        executor.shutdown();
        executor.awaitTermination(1, TimeUnit.MINUTES);

        for (int i = 0; i < users.size(); i++) {
            AuthRecipeUserInfo importedUser = futures.get(i).get();
            BulkImportTestUtils.assertBulkImportUserAndAuthRecipeUserAreEqual(main, appIdentifier,
                    appIdentifier.getAsPublicTenantIdentifier(), StorageLayer.getStorage(main), users.get(i),
                    importedUser);
        }

        process.kill();
        assertNotNull(process.checkOrWaitForEvent(ProcessState.PROCESS_STATE.STOPPED));
    }

    @Test
    public void shouldImportWithPlainTextPassword() throws Exception {
        String[] args = { "../" };

        TestingProcessManager.TestingProcess process = TestingProcessManager.start(args);
        assertNotNull(process.checkOrWaitForEvent(ProcessState.PROCESS_STATE.STARTED));
        Main main = process.getProcess();

        if (StorageLayer.getStorage(main).getType() != STORAGE_TYPE.SQL || StorageLayer.isInMemDb(main)) {
            return;
        }

        FeatureFlagTestContent.getInstance(main).setKeyValue(FeatureFlagTestContent.ENABLED_FEATURES,
                new EE_FEATURES[] { EE_FEATURES.MULTI_TENANCY, EE_FEATURES.MFA, EE_FEATURES.ACCOUNT_LINKING });

        // Create tenants
        BulkImportTestUtils.createTenants(process);

        // Create user roles
        {
            UserRoles.createNewRoleOrModifyItsPermissions(main, "role1", null);
            UserRoles.createNewRoleOrModifyItsPermissions(main, "role2", null);
        }

        AppIdentifier appIdentifier = process.getAppForTesting().toAppIdentifier();
        List<BulkImportUser> users = generateBulkImportUser(1);
        BulkImportUser bulkImportUser = users.get(0);

        // Set passwordHash to null and plainTextPassword to a value to ensure we do a plainTextPassword import
        for (LoginMethod lm : bulkImportUser.loginMethods) {
            if (Objects.equals(lm.recipeId, "emailpassword")) {
                lm.passwordHash = null;
                lm.hashingAlgorithm = null;
                lm.plainTextPassword = "testPass@123";
            }
        }
    
        AuthRecipeUserInfo importedUser = BulkImport.importUser(main, appIdentifier, bulkImportUser);

        BulkImportTestUtils.assertBulkImportUserAndAuthRecipeUserAreEqual(main, appIdentifier,
                appIdentifier.getAsPublicTenantIdentifier(), StorageLayer.getStorage(main), bulkImportUser, importedUser);

        process.kill();
        assertNotNull(process.checkOrWaitForEvent(ProcessState.PROCESS_STATE.STOPPED));
    }

    @Test
    public void shouldFailIfUserWithSameEmailAlreadyExists() throws Exception {
        String[] args = { "../" };

        TestingProcessManager.TestingProcess process = TestingProcessManager.start(args);
        assertNotNull(process.checkOrWaitForEvent(ProcessState.PROCESS_STATE.STARTED));
        Main main = process.getProcess();

        if (StorageLayer.getStorage(main).getType() != STORAGE_TYPE.SQL || StorageLayer.isInMemDb(main)) {
            return;
        }

        FeatureFlagTestContent.getInstance(main).setKeyValue(FeatureFlagTestContent.ENABLED_FEATURES,
                new EE_FEATURES[] { EE_FEATURES.MULTI_TENANCY, EE_FEATURES.MFA, EE_FEATURES.ACCOUNT_LINKING });

        // Create tenants
        BulkImportTestUtils.createTenants(process);

        // Create user roles
        {
            UserRoles.createNewRoleOrModifyItsPermissions(main, "role1", null);
            UserRoles.createNewRoleOrModifyItsPermissions(main, "role2", null);
        }

        AppIdentifier appIdentifier = new AppIdentifier(null, null);
        AuthRecipeUserInfo emailPasswordUser = EmailPassword.signUp(main, "user0@example.com", "somePasswqord");

        List<BulkImportUser> users = generateBulkImportUser(1); //generates BM user with multiple login methods

        try {
            AuthRecipeUserInfo importedUser = BulkImport.importUser(main, appIdentifier, users.get(0));
            fail();
        } catch (BulkImportBatchInsertException expected) {
            //expected
        }
        process.kill();
        assertNotNull(process.checkOrWaitForEvent(ProcessState.PROCESS_STATE.STOPPED));
    }


    @Test
    public void shouldFailIfPrimaryUserWithSameEmailAlreadyExists() throws Exception {
        String[] args = { "../" };

        TestingProcessManager.TestingProcess process = TestingProcessManager.start(args);
        assertNotNull(process.checkOrWaitForEvent(ProcessState.PROCESS_STATE.STARTED));
        Main main = process.getProcess();

        if (StorageLayer.getStorage(main).getType() != STORAGE_TYPE.SQL || StorageLayer.isInMemDb(main)) {
            return;
        }

        FeatureFlagTestContent.getInstance(main).setKeyValue(FeatureFlagTestContent.ENABLED_FEATURES,
                new EE_FEATURES[] { EE_FEATURES.MULTI_TENANCY, EE_FEATURES.MFA, EE_FEATURES.ACCOUNT_LINKING });

        // Create tenants
        BulkImportTestUtils.createTenants(process);

        // Create user roles
        {
            UserRoles.createNewRoleOrModifyItsPermissions(main, "role1", null);
            UserRoles.createNewRoleOrModifyItsPermissions(main, "role2", null);
        }

        AppIdentifier appIdentifier = new AppIdentifier(null, null);
        AuthRecipeUserInfo emailPasswordUser = EmailPassword.signUp(main, "user0@example.com", "somePasswqord");
        AuthRecipe.createPrimaryUser(main, emailPasswordUser.getSupertokensUserId());

        List<BulkImportUser> users = generateBulkImportUser(1); //generates BM user with multiple login methods

        try {
            AuthRecipeUserInfo importedUser = BulkImport.importUser(main, appIdentifier, users.get(0));
            fail();
        } catch (BulkImportBatchInsertException expected) {
            //expected
        }
        process.kill();
        assertNotNull(process.checkOrWaitForEvent(ProcessState.PROCESS_STATE.STOPPED));
    }

    @Test
    public void shouldFailIfPrimaryWebAuthNUserWithSameEmailAlreadyExists() throws Exception {
        String[] args = { "../" };

        TestingProcessManager.TestingProcess process = TestingProcessManager.start(args);
        assertNotNull(process.checkOrWaitForEvent(ProcessState.PROCESS_STATE.STARTED));
        Main main = process.getProcess();

        if (StorageLayer.getStorage(main).getType() != STORAGE_TYPE.SQL || StorageLayer.isInMemDb(main)) {
            return;
        }

        FeatureFlagTestContent.getInstance(main).setKeyValue(FeatureFlagTestContent.ENABLED_FEATURES,
                new EE_FEATURES[] { EE_FEATURES.MULTI_TENANCY, EE_FEATURES.MFA, EE_FEATURES.ACCOUNT_LINKING });

        // Create tenants
        BulkImportTestUtils.createTenants(process);

        // Create user roles
        {
            UserRoles.createNewRoleOrModifyItsPermissions(main, "role1", null);
            UserRoles.createNewRoleOrModifyItsPermissions(main, "role2", null);
        }

        AppIdentifier appIdentifier = process.getAppForTesting().toAppIdentifier();

        AuthRecipeUserInfo webauthnUser = WebAuthN.saveUser(StorageLayer.getStorage(main),
                process.getAppForTesting(),
                "user0@example.com", io.supertokens.utils.Utils.getUUID(), "example.com");
        AuthRecipe.createPrimaryUser(main, webauthnUser.getSupertokensUserId());

        List<BulkImportUser> users = generateBulkImportUser(1); //generates BM user with multiple login methods

        try {
            AuthRecipeUserInfo importedUser = BulkImport.importUser(main, appIdentifier, users.get(0));
            fail();
        } catch (BulkImportBatchInsertException expected) {
            //expected
        }
        process.kill();
        assertNotNull(process.checkOrWaitForEvent(ProcessState.PROCESS_STATE.STOPPED));
    }

    @Test
    public void shouldSucceedIfThirdpartyUserWithSameEmailAlreadyExists() throws Exception {
        String[] args = { "../" };

        TestingProcessManager.TestingProcess process = TestingProcessManager.start(args);
        assertNotNull(process.checkOrWaitForEvent(ProcessState.PROCESS_STATE.STARTED));
        Main main = process.getProcess();

        if (StorageLayer.getStorage(main).getType() != STORAGE_TYPE.SQL || StorageLayer.isInMemDb(main)) {
            return;
        }

        FeatureFlagTestContent.getInstance(main).setKeyValue(FeatureFlagTestContent.ENABLED_FEATURES,
                new EE_FEATURES[] { EE_FEATURES.MULTI_TENANCY, EE_FEATURES.MFA, EE_FEATURES.ACCOUNT_LINKING });

        // Create tenants
        BulkImportTestUtils.createTenants(process);

        // Create user roles
        {
            UserRoles.createNewRoleOrModifyItsPermissions(main, "role1", null);
            UserRoles.createNewRoleOrModifyItsPermissions(main, "role2", null);
        }

        AppIdentifier appIdentifier = process.getAppForTesting().toAppIdentifier();
        ThirdParty.SignInUpResponse thirdPartyUser = ThirdParty.signInUp(main, "google", "123123", "user0@example.com");

        List<BulkImportUser> users = generateBulkImportUserWithEmailPasswordAndRoles(1, List.of("public", "t1"), 0, List.of("role1", "role2"));


        AuthRecipeUserInfo importedUser = BulkImport.importUser(main, appIdentifier, users.get(0));

        assertNotEquals(thirdPartyUser.user.getSupertokensUserId(), importedUser.getSupertokensUserId());
        assertTrue(importedUser.isPrimaryUser);
        assertFalse(thirdPartyUser.user.isPrimaryUser);

        AuthRecipeUserInfo thirdPartyUserAgain = AuthRecipe.getUserById(main, thirdPartyUser.user.getSupertokensUserId());
        assertFalse(thirdPartyUserAgain.isPrimaryUser);
        assertEquals(1, thirdPartyUserAgain.loginMethods.length); // assert that it's not a linked user

        process.kill();
        assertNotNull(process.checkOrWaitForEvent(ProcessState.PROCESS_STATE.STOPPED));
    }

    @Test
    public void shouldFailIfThirdpartyUserWithSameEmailAlreadyExists() throws Exception {
        String[] args = { "../" };

        TestingProcessManager.TestingProcess process = TestingProcessManager.start(args);
        assertNotNull(process.checkOrWaitForEvent(ProcessState.PROCESS_STATE.STARTED));
        Main main = process.getProcess();

        if (StorageLayer.getStorage(main).getType() != STORAGE_TYPE.SQL || StorageLayer.isInMemDb(main)) {
            return;
        }

        FeatureFlagTestContent.getInstance(main).setKeyValue(FeatureFlagTestContent.ENABLED_FEATURES,
                new EE_FEATURES[] { EE_FEATURES.MULTI_TENANCY, EE_FEATURES.MFA, EE_FEATURES.ACCOUNT_LINKING });

        // Create tenants
        BulkImportTestUtils.createTenants(process);

        // Create user roles
        {
            UserRoles.createNewRoleOrModifyItsPermissions(main, "role1", null);
            UserRoles.createNewRoleOrModifyItsPermissions(main, "role2", null);
        }

        AppIdentifier appIdentifier = new AppIdentifier(null, null);
        ThirdParty.SignInUpResponse thirdpartyUser = ThirdParty.signInUp(main, "google", "123123", "user0@example.com");
        AuthRecipe.createPrimaryUser(main, thirdpartyUser.user.getSupertokensUserId());

        List<BulkImportUser> users = generateBulkImportUserWithEmailPasswordAndRoles(1, List.of("public", "t1"), 0, List.of("role1", "role2"));

        try {
            AuthRecipeUserInfo importedUser = BulkImport.importUser(main, appIdentifier, users.get(0));
            fail();
        } catch (BulkImportBatchInsertException expected) {
            //expected
        }
        process.kill();
        assertNotNull(process.checkOrWaitForEvent(ProcessState.PROCESS_STATE.STOPPED));
    }

    @Test
    public void shouldLinkBulkMigratedAccounts() throws Exception {
        String[] args = { "../" };

        TestingProcessManager.TestingProcess process = TestingProcessManager.start(args);
        assertNotNull(process.checkOrWaitForEvent(ProcessState.PROCESS_STATE.STARTED));
        Main main = process.getProcess();

        if (StorageLayer.getStorage(main).getType() != STORAGE_TYPE.SQL || StorageLayer.isInMemDb(main)) {
            return;
        }

        FeatureFlagTestContent.getInstance(main).setKeyValue(FeatureFlagTestContent.ENABLED_FEATURES,
                new EE_FEATURES[] { EE_FEATURES.MULTI_TENANCY, EE_FEATURES.MFA, EE_FEATURES.ACCOUNT_LINKING });

        // Create tenants
        BulkImportTestUtils.createTenants(process);

        // Create user roles
        {
            UserRoles.createNewRoleOrModifyItsPermissions(main, "role1", null);
            UserRoles.createNewRoleOrModifyItsPermissions(main, "role2", null);
        }

        AppIdentifier appIdentifier = process.getAppForTesting().toAppIdentifier();

        List<BulkImportUser> users = generateBulkImportUser(1);

        AuthRecipeUserInfo importedUser = BulkImport.importUser(main, appIdentifier, users.get(0));

        assertTrue(importedUser.isPrimaryUser);
        assertEquals(3, importedUser.loginMethods.length);

        process.kill();
        assertNotNull(process.checkOrWaitForEvent(ProcessState.PROCESS_STATE.STOPPED));
    }

    @Test
    public void shouldFailLinkingBulkMigratedAccountsOnTwoTenants() throws Exception {
        String[] args = { "../" };

        TestingProcessManager.TestingProcess process = TestingProcessManager.start(args);
        assertNotNull(process.checkOrWaitForEvent(ProcessState.PROCESS_STATE.STARTED));
        Main main = process.getProcess();

        if (StorageLayer.getStorage(main).getType() != STORAGE_TYPE.SQL || StorageLayer.isInMemDb(main)) {
            return;
        }

        FeatureFlagTestContent.getInstance(main).setKeyValue(FeatureFlagTestContent.ENABLED_FEATURES,
                new EE_FEATURES[] { EE_FEATURES.MULTI_TENANCY, EE_FEATURES.MFA, EE_FEATURES.ACCOUNT_LINKING });

        // Create tenants
        BulkImportTestUtils.createTenants(process);

        // Create user roles
        {
            UserRoles.createNewRoleOrModifyItsPermissions(main, "role1", null);
            UserRoles.createNewRoleOrModifyItsPermissions(main, "role2", null);
        }

        AppIdentifier appIdentifier = process.getAppForTesting().toAppIdentifier();

        //set up an existing primary user on public tenant with email B
        ThirdParty.SignInUpResponse thirdpartyResponse = ThirdParty.signInUp(process.getAppForTesting(),
                StorageLayer.getStorage(main),main, "google", "123123", "emailB@example.com",
                true);
        AuthRecipe.createPrimaryUser(main, thirdpartyResponse.user.getSupertokensUserId());

        BulkImportUser user = generateBulkImportUser(1).get(0);
        user.loginMethods.get(0).email = "emailA@example.com";
        user.loginMethods.get(0).tenantIds = List.of("public");

        // one of the "recipeUsers" has the same email as the primary user but on a different tenant
        user.loginMethods.get(1).email = "emailB@example.com";
        user.loginMethods.get(1).tenantIds = List.of("t1");

        try {
            AuthRecipeUserInfo importedUser = BulkImport.importUser(main, appIdentifier, user);
            fail();
        } catch (BulkImportBatchInsertException expected) {
            //ignore
        }

        process.kill();
        assertNotNull(process.checkOrWaitForEvent(ProcessState.PROCESS_STATE.STOPPED));
    }

    @Test
    public void shouldFailLinkingBulkMigratedAccountsOnThreeTenants() throws Exception {
        String[] args = { "../" };

        TestingProcessManager.TestingProcess process = TestingProcessManager.start(args);
        assertNotNull(process.checkOrWaitForEvent(ProcessState.PROCESS_STATE.STARTED));
        Main main = process.getProcess();

        if (StorageLayer.getStorage(main).getType() != STORAGE_TYPE.SQL || StorageLayer.isInMemDb(main)) {
            return;
        }

        FeatureFlagTestContent.getInstance(main).setKeyValue(FeatureFlagTestContent.ENABLED_FEATURES,
                new EE_FEATURES[] { EE_FEATURES.MULTI_TENANCY, EE_FEATURES.MFA, EE_FEATURES.ACCOUNT_LINKING });

        // Create tenants
        BulkImportTestUtils.createTenantsWithinOneUserPool(process);

        // Create user roles
        {
            UserRoles.createNewRoleOrModifyItsPermissions(main, "role1", null);
            UserRoles.createNewRoleOrModifyItsPermissions(main, "role2", null);
        }

        AppIdentifier appIdentifier = process.getAppForTesting().toAppIdentifier();

        //set up an existing primary user on public tenant with email B
        ThirdParty.SignInUpResponse thirdpartyResponse = ThirdParty.signInUp(new TenantIdentifier(null, process.getAppForTesting().getAppId(), "t1"),
                StorageLayer.getStorage(main),main, "google", "123123", "emailB@example.com",
                true);
        AuthRecipe.createPrimaryUser(main, thirdpartyResponse.user.getSupertokensUserId());

        BulkImportUser user = generateBulkImportUser(1).get(0);
        user.loginMethods.get(0).email = "emailA@example.com"; //this is the primary user
        user.loginMethods.get(0).tenantIds = List.of("public");

        user.loginMethods.get(1).email = "emailC@example.com";
        user.loginMethods.get(1).tenantIds = List.of("t1");

        // one of the "recipeUsers" has the same email as the primary user but on a different tenant
        user.loginMethods.get(2).email = "emailB@example.com";
        user.loginMethods.get(2).tenantIds = List.of("t2");

        try {
            AuthRecipeUserInfo importedUser = BulkImport.importUser(main, appIdentifier, user);
            fail();
        } catch (BulkImportBatchInsertException expected) {
            //ignore
            Map<String, Exception> exceptionByUserId = expected.exceptionByUserId;
            for (Exception e : exceptionByUserId.values()){
                assertTrue(e.getMessage().contains("E027"));
            }
        }

        process.kill();
        assertNotNull(process.checkOrWaitForEvent(ProcessState.PROCESS_STATE.STOPPED));
    }

    @Test
    public void shouldFailLinkingBulkMigratedAccountsOnThreeTenantsWithWebAuthNUser() throws Exception {
        String[] args = { "../" };

        TestingProcessManager.TestingProcess process = TestingProcessManager.start(args);
        assertNotNull(process.checkOrWaitForEvent(ProcessState.PROCESS_STATE.STARTED));
        Main main = process.getProcess();

        if (StorageLayer.getStorage(main).getType() != STORAGE_TYPE.SQL || StorageLayer.isInMemDb(main)) {
            return;
        }

        FeatureFlagTestContent.getInstance(main).setKeyValue(FeatureFlagTestContent.ENABLED_FEATURES,
                new EE_FEATURES[] { EE_FEATURES.MULTI_TENANCY, EE_FEATURES.MFA, EE_FEATURES.ACCOUNT_LINKING });

        // Create tenants
        BulkImportTestUtils.createTenantsWithinOneUserPool(process);

        // Create user roles
        {
            UserRoles.createNewRoleOrModifyItsPermissions(main, "role1", null);
            UserRoles.createNewRoleOrModifyItsPermissions(main, "role2", null);
        }

        AppIdentifier appIdentifier = process.getAppForTesting().toAppIdentifier();

        //set up an existing primary user on public tenant with email B
        AuthRecipeUserInfo webauthnUser = WebAuthN.saveUser(StorageLayer.getStorage(main),
                process.getAppForTesting(),
                "emailB@example.com", io.supertokens.utils.Utils.getUUID(), "example.com");
        AuthRecipe.createPrimaryUser(main, webauthnUser.getSupertokensUserId());

        BulkImportUser user = generateBulkImportUser(1).get(0);
        user.loginMethods.get(0).email = "emailA@example.com"; //this is the primary user
        user.loginMethods.get(0).tenantIds = List.of("public");

        user.loginMethods.get(1).email = "emailC@example.com";
        user.loginMethods.get(1).tenantIds = List.of("t1");

        // one of the "recipeUsers" has the same email as the primary user but on a different tenant
        user.loginMethods.get(2).email = "emailB@example.com";
        user.loginMethods.get(2).tenantIds = List.of("t2");

        try {
            AuthRecipeUserInfo importedUser = BulkImport.importUser(main, appIdentifier, user);
            fail();
        } catch (BulkImportBatchInsertException expected) {
            //ignore
            Map<String, Exception> exceptionByUserId = expected.exceptionByUserId;
            for (Exception e : exceptionByUserId.values()){
                assertTrue(e.getMessage().contains("E027"));
            }
        }

        process.kill();
        assertNotNull(process.checkOrWaitForEvent(ProcessState.PROCESS_STATE.STOPPED));
    }
}
