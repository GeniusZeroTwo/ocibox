package com.oci.ocimanager.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yaml.snakeyaml.Yaml;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AccountYamlServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void isSafeYamlNameRejectsReservedSystemKeysAndSpecialSyntax() {
        AccountYamlService service = new AccountYamlService();

        for (String reserved : new String[]{
                "oci", "accounts", "server", "spring", "app", "port", "user",
                "tenancy", "fingerprint", "region", "key-file", "admin-password", "proxy"
        }) {
            assertFalse(service.isSafeYamlName(reserved), "Reserved key must be rejected: " + reserved);
            assertFalse(service.isSafeYamlName(reserved.toUpperCase()), "Uppercase reserved key must be rejected: " + reserved);
        }

        assertFalse(service.isSafeYamlName(" bad-space"));
        assertFalse(service.isSafeYamlName("bad:colon"));
        assertFalse(service.isSafeYamlName("bad[bracket]"));
        assertFalse(service.isSafeYamlName("bad#comment"));
        assertFalse(service.isSafeYamlName("bad\nline"));

        assertTrue(service.isSafeYamlName("tokyo-arm-01"));
        assertTrue(service.isSafeYamlName("首尔主力号_02"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void yamlCrudStrictlyStaysWithinOciAccountsAndPreservesTopLevelConfig() throws Exception {
        Path ymlPath = tempDir.resolve("application.yml");
        String initialYaml = """
                server:
                  address: 127.0.0.1
                  port: 5000
                app:
                  admin-username: "admin"
                  admin-password: "PlainSecret#123"
                oci:
                  accounts:
                    "[alpha]":
                      user: "ocid1.user.oc1..aaaa"
                      fingerprint: "11:22:33"
                      tenancy: "ocid1.tenancy.oc1..aaaa"
                      region: "ap-tokyo-1"
                      key-file: "/tmp/alpha.pem"
                """;
        Files.writeString(ymlPath, initialYaml, StandardCharsets.UTF_8);
        File ymlFile = ymlPath.toFile();

        AccountYamlService service = new AccountYamlService() {
            @Override
            public File getConfigFile() {
                return ymlFile;
            }
        };

        // Attempt to delete top-level or property keys must return false and leave YAML untouched
        assertFalse(service.deleteAccountFromYaml(ymlFile, "server"));
        assertFalse(service.deleteAccountFromYaml(ymlFile, "app"));
        assertFalse(service.deleteAccountFromYaml(ymlFile, "user"));
        assertThrows(IllegalArgumentException.class,
                () -> service.renameAccountInYaml(ymlFile, "alpha", "server"));

        // Rename alpha -> beta
        assertTrue(service.renameAccountInYaml(ymlFile, "alpha", "beta"));
        Map<String, Object> parsedAfterRename = new Yaml().load(Files.readString(ymlPath, StandardCharsets.UTF_8));
        Map<String, Object> serverNode = (Map<String, Object>) parsedAfterRename.get("server");
        assertEquals(5000, serverNode.get("port"));
        Map<String, Object> accountsAfterRename = (Map<String, Object>) ((Map<String, Object>) parsedAfterRename.get("oci")).get("accounts");
        assertTrue(accountsAfterRename.containsKey("[beta]"));
        assertFalse(accountsAfterRename.containsKey("[alpha]"));

        // Delete beta (the last account) -> normalizes accounts to empty map `{}`
        assertTrue(service.deleteAccountFromYaml(ymlFile, "beta"));
        Map<String, Object> parsedAfterDelete = new Yaml().load(Files.readString(ymlPath, StandardCharsets.UTF_8));
        Object accountsAfterDelete = ((Map<String, Object>) parsedAfterDelete.get("oci")).get("accounts");
        assertInstanceOf(Map.class, accountsAfterDelete);
        assertTrue(((Map<?, ?>) accountsAfterDelete).isEmpty());

        // Upsert gamma into `accounts: {}`
        service.upsertAccountInYaml(
                ymlFile,
                "gamma",
                "ocid1.user.oc1..gggg",
                "aa:bb:cc",
                "ocid1.tenancy.oc1..gggg",
                "ap-seoul-1",
                "/tmp/gamma.pem"
        );
        Map<String, Object> parsedAfterUpsert = new Yaml().load(Files.readString(ymlPath, StandardCharsets.UTF_8));
        Map<String, Object> accountsAfterUpsert = (Map<String, Object>) ((Map<String, Object>) parsedAfterUpsert.get("oci")).get("accounts");
        assertTrue(accountsAfterUpsert.containsKey("[gamma]"));
    }

    @Test
    void replacePlaintextAdminPasswordUpgradesYamlAndCleansLegacyPlaintextFiles() throws Exception {
        Path ymlPath = tempDir.resolve("application.yml");
        Path dotPwd = tempDir.resolve(".admin-password");
        Path bakPwd = tempDir.resolve("application.yml.bak.password");

        Files.writeString(ymlPath, """
                app:
                  admin-username: "admin"
                  admin-password: "MyPlainPassword99"
                oci:
                  accounts: {}
                """, StandardCharsets.UTF_8);
        Files.writeString(dotPwd, "MyPlainPassword99", StandardCharsets.UTF_8);
        Files.writeString(bakPwd, "MyPlainPassword99", StandardCharsets.UTF_8);

        AccountYamlService service = new AccountYamlService() {
            @Override
            public File getConfigFile() {
                return ymlPath.toFile();
            }
        };

        String hashed = PasswordHasher.hash("MyPlainPassword99");
        assertTrue(service.replacePlaintextAdminPassword("MyPlainPassword99", hashed));
        String updated = Files.readString(ymlPath, StandardCharsets.UTF_8);
        assertFalse(updated.contains("MyPlainPassword99"));
        assertTrue(updated.contains(hashed));
        assertFalse(Files.exists(dotPwd), ".admin-password must be removed");
        assertFalse(Files.exists(bakPwd), "application.yml.bak.password must be removed");
    }
}
