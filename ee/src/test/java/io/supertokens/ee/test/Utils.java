package io.supertokens.ee.test;

import io.supertokens.Main;
import io.supertokens.ee.EEFeatureFlag;
import io.supertokens.pluginInterface.PluginInterfaceTesting;
import io.supertokens.storageLayer.StorageLayer;
import org.apache.tomcat.util.http.fileupload.FileUtils;
import org.junit.rules.TestRule;
import org.junit.rules.TestWatcher;
import org.junit.runner.Description;
import org.mockito.Mockito;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;

public abstract class Utils extends Mockito {

    private static ByteArrayOutputStream byteArrayOutputStream;

    public static void afterTesting() {
        String installDir = "../../";
        try {

            // remove config.yaml file
            String workerId = System.getProperty("org.gradle.test.worker", "");
            ProcessBuilder pb = new ProcessBuilder("rm", "config" + workerId + ".yaml");
            pb.directory(new File(installDir));
            Process process = pb.start();
            process.waitFor();

            // remove webserver-temp folders created by tomcat
            final File webserverTemp = new File(installDir + "webserver-temp");
            try {
                FileUtils.deleteDirectory(webserverTemp);
            } catch (Exception ignored) {
            }

            // remove .started folders created by processes
            final File dotStartedFolder = new File(installDir + ".started");
            try {
                FileUtils.deleteDirectory(dotStartedFolder);
            } catch (Exception ignored) {
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static void reset() {
        Main.isTesting = true;
        PluginInterfaceTesting.isTesting = true;
        Main.makeConsolePrintSilent = true;
        EEFeatureFlag.resetLisenseCheckRequests();
        String installDir = "../../";
        try {

            // if the default config is not the same as the current config, we must reset the storage layer
            File ogConfig = new File("../../temp/config.yaml");
            String workerId = System.getProperty("org.gradle.test.worker", "");
            File currentConfig = new File("../../config" + workerId + ".yaml");
            if (currentConfig.isFile()) {
                byte[] ogConfigContent = Files.readAllBytes(ogConfig.toPath());
                byte[] currentConfigContent = Files.readAllBytes(currentConfig.toPath());
                if (!Arrays.equals(ogConfigContent, currentConfigContent)) {
                    StorageLayer.close();
                }
            }

            ProcessBuilder pb = new ProcessBuilder("cp", "temp/config.yaml", "./config" + workerId + ".yaml");
            pb.directory(new File(installDir));
            Process process = pb.start();
            process.waitFor();

            // in devConfig, it's set to false. However, in config, it's commented. So we comment it out so that it
            // mimics production. Refer to https://github.com/supertokens/supertokens-core/issues/118
            commentConfigValue("disable_telemetry");

            // Configure the PostgreSQL connection for the test process (for Docker/CI test envs).
            // IMPORTANT: Config.canBeUsed() returns true only if a user/password (or connection URI)
            // is set. Setting just host/port is NOT enough — without user/password the core SILENTLY
            // falls back to the in-memory DB, which grants ALL EE features for free and breaks the
            // license tests (they'd see 8 features instead of the licensed set). So we set the full
            // connection here, sourced from the test env and defaulting to the standard local Postgres.
            setValueInConfig("postgresql_host", "\"" + envOrProp("TEST_PG_HOST", "localhost") + "\"");
            setValueInConfig("postgresql_port", envOrProp("TEST_PG_PORT", "5432"));
            setValueInConfig("postgresql_user", "\"" + envOrProp("TEST_PG_USER", "root") + "\"");
            setValueInConfig("postgresql_password", "\"" + envOrProp("TEST_PG_PASSWORD", "root") + "\"");

            TestingProcessManager.killAll();
            TestingProcessManager.deleteAllInformation();
            TestingProcessManager.killAll();

            byteArrayOutputStream = new ByteArrayOutputStream();
            System.setErr(new PrintStream(byteArrayOutputStream));
        } catch (Exception e) {
            e.printStackTrace();
        }
        System.gc();
    }

    // Reads a value from the environment, falling back to a system property, then a default.
    private static String envOrProp(String name, String defaultValue) {
        String v = System.getenv(name);
        if (v == null || v.isEmpty()) {
            v = System.getProperty(name);
        }
        return (v != null && !v.isEmpty()) ? v : defaultValue;
    }

    static void commentConfigValue(String key) throws IOException {
        // we close the storage layer since there might be a change in the db related config.
        StorageLayer.close();

        String oldStr = "\n((#\\s)?)" + key + "(:|((:\\s).+))\n";
        String newStr = "\n# " + key + ":";

        StringBuilder originalFileContent = new StringBuilder();
        String workerId = System.getProperty("org.gradle.test.worker", "");
        try (BufferedReader reader = new BufferedReader(new FileReader("../../config" + workerId + ".yaml"))) {
            String currentReadingLine = reader.readLine();
            while (currentReadingLine != null) {
                originalFileContent.append(currentReadingLine).append(System.lineSeparator());
                currentReadingLine = reader.readLine();
            }
            String modifiedFileContent = originalFileContent.toString().replaceAll(oldStr, newStr);
            try (BufferedWriter writer = new BufferedWriter(new FileWriter("../../config" + workerId + ".yaml"))) {
                writer.write(modifiedFileContent);
            }
        }

    }

    public static void setValueInConfig(String key, String value) throws IOException {
        // we close the storage layer since there might be a change in the db related config.
        StorageLayer.close();

        String oldStr = "\n((#\\s)?)" + key + "(:|((:\\s).+))\n";
        String newStr = "\n" + key + ": " + value + "\n";
        StringBuilder originalFileContent = new StringBuilder();
        String workerId = System.getProperty("org.gradle.test.worker", "");
        try (BufferedReader reader = new BufferedReader(new FileReader("../../config" + workerId + ".yaml"))) {
            String currentReadingLine = reader.readLine();
            while (currentReadingLine != null) {
                originalFileContent.append(currentReadingLine).append(System.lineSeparator());
                currentReadingLine = reader.readLine();
            }
            String modifiedFileContent = originalFileContent.toString().replaceAll(oldStr, newStr);
            try (BufferedWriter writer = new BufferedWriter(new FileWriter("../../config" + workerId + ".yaml"))) {
                writer.write(modifiedFileContent);
            }
        }
    }

    public static TestRule getOnFailure() {
        return new TestWatcher() {
            @Override
            protected void failed(Throwable e, Description description) {
                System.out.println(byteArrayOutputStream.toString(StandardCharsets.UTF_8));
            }
        };
    }
}
