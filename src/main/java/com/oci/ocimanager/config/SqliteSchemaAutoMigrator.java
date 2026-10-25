package com.oci.ocimanager.config;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Set;

/**
 * 🌟 自动化 SQLite 数据库 Schema 迁移器 (个人自用精简版)
 * 解决 SQLite 在 JPA/Hibernate ddl-auto: update 下无法为现有表自动增加新列的问题。
 */
@Configuration
public class SqliteSchemaAutoMigrator implements BeanPostProcessor {

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        if (bean instanceof DataSource dataSource) {
            migrateDatabase(dataSource);
        }
        return bean;
    }

    private void migrateDatabase(DataSource dataSource) {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {

            System.out.println("🛠️ [数据库自检] 正在校验并自动补全 SQLite 数据库结构...");

            // 1. 检查并补全 creation_tasks 表
            checkAndAddColumns(stmt, "creation_tasks", new String[][]{
                {"success_count", "INTEGER DEFAULT 0"},
                {"instance_name", "TEXT"},
                {"os_name", "TEXT"},
                {"os_version", "TEXT"},
                {"ssh_key", "TEXT"},
                {"attempt_count", "INTEGER DEFAULT 0"},
                {"start_time", "BIGINT DEFAULT 0"},
                {"instances", "INTEGER DEFAULT 1"},
                {"retry_interval", "INTEGER DEFAULT 0"},
                {"public_ip", "INTEGER DEFAULT 0"},
                {"ipv6", "INTEGER DEFAULT 0"},
                {"auto_stop", "INTEGER DEFAULT 0"},
                {"tg_id", "TEXT"},
                {"password", "TEXT"},
                {"status", "TEXT"},
                {"log", "TEXT"},
                {"ocpus", "REAL DEFAULT 0"},
                {"memory", "REAL DEFAULT 0"},
                {"disk", "BIGINT DEFAULT 0"},
                {"image_id", "TEXT"},
                {"shape", "TEXT"},
                {"region", "TEXT"},
                {"account", "TEXT"}
            });

            // 2. 检查并补全 traffic_limit 表
            checkAndAddColumns(stmt, "traffic_limit", new String[][]{
                {"limit_gb", "INTEGER DEFAULT 0"},
                {"last_warning_month", "TEXT"}
            });

            // 3. 检查并补全 system_config 表
            checkAndAddColumns(stmt, "system_config", new String[][]{
                {"config_value", "TEXT"}
            });

            // 4. 检查并创建 admin_passkey 通行密钥表
            try {
                stmt.execute("CREATE TABLE IF NOT EXISTS admin_passkey (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "credential_id TEXT UNIQUE NOT NULL," +
                    "public_key_spki TEXT NOT NULL," +
                    "algorithm INTEGER NOT NULL DEFAULT -7," +
                    "name TEXT NOT NULL," +
                    "sign_count INTEGER DEFAULT 0," +
                    "aaguid TEXT," +
                    "created_at TEXT," +
                    "last_used_at TEXT" +
                ");");
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_passkey_cred_id ON admin_passkey(credential_id);");
            } catch (Exception ignored) {}

            checkAndAddColumns(stmt, "admin_passkey", new String[][]{
                {"credential_id", "TEXT"},
                {"public_key_spki", "TEXT"},
                {"algorithm", "INTEGER DEFAULT -7"},
                {"name", "TEXT"},
                {"sign_count", "INTEGER DEFAULT 0"},
                {"aaguid", "TEXT"},
                {"created_at", "TEXT"},
                {"last_used_at", "TEXT"}
            });

            System.out.println("✅ [数据库自检] SQLite 数据库字段校验与自动迁移完成。");
        } catch (Exception e) {
            System.err.println("❌ SQLite 数据库自动迁移校验异常: " + e.getMessage());
        }
    }

    private void checkAndAddColumns(Statement stmt, String tableName, String[][] columns) {
        Set<String> existingColumns = new HashSet<>();
        try (ResultSet rs = stmt.executeQuery("PRAGMA table_info(" + tableName + ")")) {
            while (rs.next()) {
                existingColumns.add(rs.getString("name").toLowerCase());
            }
        } catch (Exception e) {
            return;
        }

        if (existingColumns.isEmpty()) {
            return;
        }

        for (String[] colDef : columns) {
            String colName = colDef[0];
            String colType = colDef[1];
            if (!existingColumns.contains(colName.toLowerCase())) {
                String alterSql = "ALTER TABLE " + tableName + " ADD COLUMN " + colName + " " + colType;
                try {
                    stmt.execute(alterSql);
                    System.out.println("➕ [数据库表结构更新] 成功向 " + tableName + " 表增加缺失字段: " + colName);
                } catch (Exception e) {
                    System.err.println("⚠️ 向 " + tableName + " 增加字段 " + colName + " 失败: " + e.getMessage());
                }
            }
        }
    }
}
