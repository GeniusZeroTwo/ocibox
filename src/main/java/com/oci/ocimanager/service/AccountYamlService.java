package com.oci.ocimanager.service;

import com.oci.ocimanager.config.OciProperties;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * application.yml 账号节点操作组件：严格将账号增删改查约束在 oci.accounts 子树内，
 * 拦截系统保留键名与结构注入，并通过临时文件 + SnakeYAML 语法预检 + 原子替换落盘。
 */
@Component
public class AccountYamlService {

    private static final Set<String> RESERVED_YAML_KEYS = Set.of(
            "oci", "accounts", "server", "spring", "app", "logging", "management",
            "port", "address", "servlet", "tomcat", "datasource", "jpa", "hibernate",
            "hikari", "threads", "task", "mvc", "jmx", "main", "encoding", "virtual",
            "scheduling", "pool", "admin-username", "admin-password", "bot-token",
            "admin-id", "trust-forwarded-for", "trusted-proxies", "version",
            "user", "fingerprint", "tenancy", "region", "key-file", "keyfile",
            "register-time", "registertime", "email", "client-identifier",
            "clientidentifier", "proxy"
    );

    private final Object YAML_FILE_LOCK = new Object();

    @Autowired
    @Lazy
    private OciProperties ociProperties;

    /** 所有 application.yml 读改写操作共用的互斥锁。 */
    public Object lock() {
        return YAML_FILE_LOCK;
    }

    /**
     * 统一检索系统所使用的 application.yml 物理文件路径。
     */
    public File getConfigFile() {
        File file = new File("/root/ocibox/application.yml");
        if (file.exists()) return file;
        file = new File("/root/oci-personal/application.yml");
        if (file.exists()) return file;
        file = new File("/root/oci-manager/application.yml");
        if (file.exists()) return file;
        file = new File("application.yml");
        if (file.exists()) return file;
        file = new File("src/main/resources/application.yml");
        if (file.exists()) return file;
        return null;
    }

    /**
     * 获取可持久化的外部 application.yml，若独立 JAR 启动目录下尚不存在外部配置文件则安全初始化。
     */
    public File getOrCreateWritableConfigFile() throws IOException {
        synchronized (YAML_FILE_LOCK) {
            File existing = getConfigFile();
            if (existing != null && existing.exists()) {
                return existing;
            }
            File baseDir = new File("/root/ocibox").isDirectory()
                    ? new File("/root/ocibox")
                    : (new File("/root/oci-personal").isDirectory()
                        ? new File("/root/oci-personal")
                        : new File("."));
            File target = new File(baseDir, "application.yml");
            List<String> initLines = List.of("oci:", "  accounts: {}");
            writeYamlAtomically(target.toPath(), initLines);
            return target;
        }
    }

    /**
     * 从 application.yml 中按物理行序提取所有账号节点名称（SnakeYAML 严格保持 LinkedHashMap 解析顺序）。
     */
    public List<String> getOrderedAccountNamesFromYaml() {
        synchronized (YAML_FILE_LOCK) {
            File ymlFile = getConfigFile();
            if (ymlFile == null || !ymlFile.exists()) return Collections.emptyList();
            try (InputStream in = new FileInputStream(ymlFile)) {
                Yaml yaml = new Yaml();
                Map<String, Object> yamlMap = yaml.load(in);
                if (yamlMap != null && yamlMap.get("oci") instanceof Map<?, ?> ociMap) {
                    if (ociMap.get("accounts") instanceof Map<?, ?> accountsNode) {
                        List<String> list = new ArrayList<>();
                        for (Object key : accountsNode.keySet()) {
                            if (key != null) {
                                list.add(String.valueOf(key));
                            }
                        }
                        return list;
                    }
                }
            } catch (Exception e) {
                System.err.println("从 application.yml 解析账号次序异常: " + e.getMessage());
            }
            return Collections.emptyList();
        }
    }

    /**
     * 将账号 Map 按 application.yml 中的物理添加次序重新组织为 LinkedHashMap。
     */
    public Map<String, OciProperties.Account> reorderAccounts(Map<String, OciProperties.Account> currentAccounts) {
        if (currentAccounts == null || currentAccounts.isEmpty()) {
            return new LinkedHashMap<>();
        }
        List<String> yamlKeys = getOrderedAccountNamesFromYaml();
        Map<String, OciProperties.Account> orderedMap = new LinkedHashMap<>();
        Set<String> processedKeys = new HashSet<>();

        for (String yamlKey : yamlKeys) {
            String cleanYamlKey = normalizeAccountKey(yamlKey);
            for (Map.Entry<String, OciProperties.Account> entry : currentAccounts.entrySet()) {
                String cleanEntryKey = normalizeAccountKey(entry.getKey());
                if (cleanEntryKey.equals(cleanYamlKey) && !processedKeys.contains(entry.getKey())) {
                    orderedMap.put(entry.getKey(), entry.getValue());
                    processedKeys.add(entry.getKey());
                    break;
                }
            }
        }

        for (Map.Entry<String, OciProperties.Account> entry : currentAccounts.entrySet()) {
            if (!processedKeys.contains(entry.getKey())) {
                orderedMap.put(entry.getKey(), entry.getValue());
                processedKeys.add(entry.getKey());
            }
        }

        return orderedMap;
    }

    @PostConstruct
    public void initAccountOrder() {
        try {
            synchronized (YAML_FILE_LOCK) {
                if (ociProperties != null && ociProperties.getAccounts() != null && !ociProperties.getAccounts().isEmpty()) {
                    Map<String, OciProperties.Account> ordered = reorderAccounts(ociProperties.getAccounts());
                    ociProperties.setAccounts(ordered);
                }
            }
        } catch (Exception e) {
            System.err.println("初始化 OCI 账户顺序失败: " + e.getMessage());
        }
    }

    public static String normalizeAccountKey(String raw) {
        if (raw == null) return "";
        return raw.replaceAll("[\"'\\s\\[\\]\\r\\n]", "");
    }

    public int getLeadingWhitespaceCount(String line) {
        if (line == null) return 0;
        int count = 0;
        while (count < line.length() && (line.charAt(count) == ' ' || line.charAt(count) == '\t')) {
            count++;
        }
        return count;
    }

    /**
     * 匹配账号键行：要求非顶层缩进（>= 2）、非注释行、非系统保留键名。
     * 注意：完整文件级写操作必须进一步通过 {@link #locateAccountsSection(List)} 限定在 oci.accounts 直接子级缩进上。
     */
    public boolean isTargetYamlKey(String line, String targetAccount) {
        if (line == null || targetAccount == null) return false;
        String trimmed = line.trim();
        if (trimmed.isEmpty() || trimmed.startsWith("#")) return false;
        if (getLeadingWhitespaceCount(line) < 2) return false;

        int colonIndex = line.indexOf(':');
        if (colonIndex == -1) return false;

        String afterColon = line.substring(colonIndex + 1).trim();
        if (!afterColon.isEmpty() && !afterColon.startsWith("#") && !"{}".equals(afterColon)) {
            // 账号节点是映射父节点 (如 "  \"[acc]\":")，冒号后不应携带标量值（如 "user: \"ocid...\""）
            return false;
        }

        String keyPart = line.substring(0, colonIndex);
        String cleanKey = normalizeAccountKey(keyPart);
        String cleanTarget = normalizeAccountKey(targetAccount);
        if (cleanKey.isEmpty() || cleanTarget.isEmpty()) return false;
        if (RESERVED_YAML_KEYS.contains(cleanKey.toLowerCase(Locale.ROOT))
                || RESERVED_YAML_KEYS.contains(cleanTarget.toLowerCase(Locale.ROOT))) {
            return false;
        }
        return cleanKey.equals(cleanTarget);
    }

    /**
     * 定位 YAML 行列表中的 oci -> accounts 区块范围与账号子键缩进层级。
     */
    private record AccountsSection(int ociLine, int accountsLine, int accountsIndent, int endExclusive, int childKeyIndent) {}

    private AccountsSection locateAccountsSection(List<String> lines) {
        int ociLine = -1;
        int ociIndent = -1;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
            int indent = getLeadingWhitespaceCount(line);
            if (indent == 0 && trimmed.matches("^oci\\s*:(?:\\s*#.*)?$")) {
                ociLine = i;
                ociIndent = 0;
                break;
            }
        }
        if (ociLine < 0) {
            return null;
        }

        int ociEnd = lines.size();
        for (int i = ociLine + 1; i < lines.size(); i++) {
            String line = lines.get(i);
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
            if (getLeadingWhitespaceCount(line) <= ociIndent) {
                ociEnd = i;
                break;
            }
        }

        int accountsLine = -1;
        int accountsIndent = -1;
        for (int i = ociLine + 1; i < ociEnd; i++) {
            String line = lines.get(i);
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
            int indent = getLeadingWhitespaceCount(line);
            if (indent > ociIndent && trimmed.matches("^accounts\\s*:(?:\\s*\\{\\})?(?:\\s*#.*)?$")) {
                accountsLine = i;
                accountsIndent = indent;
                break;
            }
        }
        if (accountsLine < 0) {
            return new AccountsSection(ociLine, -1, ociIndent + 2, ociEnd, ociIndent + 4);
        }

        int accountsEnd = ociEnd;
        int childKeyIndent = -1;
        for (int i = accountsLine + 1; i < ociEnd; i++) {
            String line = lines.get(i);
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
            int indent = getLeadingWhitespaceCount(line);
            if (indent <= accountsIndent) {
                accountsEnd = i;
                break;
            }
            if (childKeyIndent < 0) {
                childKeyIndent = indent;
            }
        }
        if (childKeyIndent < 0) {
            childKeyIndent = accountsIndent + 2;
        }
        return new AccountsSection(ociLine, accountsLine, accountsIndent, accountsEnd, childKeyIndent);
    }

    private boolean isScopedAccountHeader(List<String> lines, int index, AccountsSection section, String targetAccount) {
        if (section == null || section.accountsLine < 0) return false;
        if (index <= section.accountsLine || index >= section.endExclusive) return false;
        String line = lines.get(index);
        if (getLeadingWhitespaceCount(line) != section.childKeyIndent) return false;
        return isTargetYamlKey(line, targetAccount);
    }

    public void persistAccountPropertyToYaml(String accountName, String propKey, String propValue) {
        File ymlFile = getConfigFile();
        if (ymlFile == null || !ymlFile.exists()) return;
        persistAccountPropertyToYaml(ymlFile, accountName, propKey, propValue);
    }

    public void persistAccountPropertyToYaml(File ymlFile, String accountName, String propKey, String propValue) {
        synchronized (YAML_FILE_LOCK) {
            try {
                if (ymlFile == null || !ymlFile.exists()) return;
                boolean shouldDelete = (propValue == null || propValue.trim().isEmpty());
                if (!shouldDelete && !isSafeYamlValue(propValue)) {
                    System.err.println("拒绝写入不安全的 YAML 值 [" + propKey + "] 账号 [" + accountName + "]");
                    return;
                }

                List<String> lines = Files.readAllLines(ymlFile.toPath(), StandardCharsets.UTF_8);
                AccountsSection section = locateAccountsSection(lines);
                if (section == null || section.accountsLine < 0) return;

                List<String> newLines = new ArrayList<>(lines.size() + 2);
                boolean inTargetAccount = false;
                int targetIndent = -1;
                boolean propAdded = false;

                for (int i = 0; i < lines.size(); i++) {
                    String line = lines.get(i);
                    if (!inTargetAccount && isScopedAccountHeader(lines, i, section, accountName)) {
                        inTargetAccount = true;
                        targetIndent = getLeadingWhitespaceCount(line);
                        newLines.add(line);
                        continue;
                    }

                    if (inTargetAccount) {
                        String trimmed = line.trim();
                        if (trimmed.isEmpty()) {
                            newLines.add(line);
                            continue;
                        }
                        int currentIndent = getLeadingWhitespaceCount(line);
                        if ((currentIndent <= targetIndent && !trimmed.startsWith("#")) || i >= section.endExclusive) {
                            if (!propAdded && !shouldDelete) {
                                newLines.add(" ".repeat(targetIndent + 2) + propKey + ": \"" + propValue + "\"");
                                propAdded = true;
                            }
                            inTargetAccount = false;
                            newLines.add(line);
                        } else {
                            if (trimmed.startsWith(propKey + ":")) {
                                propAdded = true;
                                if (!shouldDelete) {
                                    newLines.add(" ".repeat(currentIndent) + propKey + ": \"" + propValue + "\"");
                                }
                            } else {
                                newLines.add(line);
                            }
                        }
                        continue;
                    }
                    newLines.add(line);
                }

                if (inTargetAccount && !propAdded && !shouldDelete) {
                    newLines.add(" ".repeat(targetIndent + 2) + propKey + ": \"" + propValue + "\"");
                }

                writeYamlAtomically(ymlFile.toPath(), newLines);
            } catch (Exception e) {
                System.err.println("自动写入属性 [" + propKey + "] 到配置文件失败 [" + accountName + "]: " + e.getMessage());
            }
        }
    }

    /**
     * 在 oci.accounts 作用域内重命名账号键，绝不触碰顶层或其它配置段。
     */
    public boolean renameAccountInYaml(File ymlFile, String oldAccount, String newAccount) throws IOException {
        if (ymlFile == null || !ymlFile.exists()) return false;
        String cleanNew = normalizeAccountKey(newAccount);
        if (!isSafeYamlName(cleanNew)) {
            throw new IllegalArgumentException("新账号名不合法或属于系统保留键名");
        }
        synchronized (YAML_FILE_LOCK) {
            List<String> lines = Files.readAllLines(ymlFile.toPath(), StandardCharsets.UTF_8);
            AccountsSection section = locateAccountsSection(lines);
            if (section == null || section.accountsLine < 0) return false;

            List<String> newLines = new ArrayList<>(lines.size());
            boolean found = false;
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                if (!found && isScopedAccountHeader(lines, i, section, oldAccount)) {
                    int colonIndex = line.indexOf(':');
                    String indent = " ".repeat(getLeadingWhitespaceCount(line));
                    newLines.add(indent + "\"[" + cleanNew + "]\"" + line.substring(colonIndex));
                    found = true;
                } else {
                    newLines.add(line);
                }
            }
            if (!found) return false;
            writeYamlAtomically(ymlFile.toPath(), newLines);
            return true;
        }
    }

    /**
     * 在 oci.accounts 作用域内安全删除指定账号子树，若删除后无剩余账号则自动归一化为 `accounts: {}`。
     */
    public boolean deleteAccountFromYaml(File ymlFile, String targetAccount) throws IOException {
        if (ymlFile == null || !ymlFile.exists()) return false;
        String cleanTarget = normalizeAccountKey(targetAccount);
        if (cleanTarget.isEmpty() || RESERVED_YAML_KEYS.contains(cleanTarget.toLowerCase(Locale.ROOT))) {
            return false;
        }
        synchronized (YAML_FILE_LOCK) {
            List<String> lines = Files.readAllLines(ymlFile.toPath(), StandardCharsets.UTF_8);
            AccountsSection section = locateAccountsSection(lines);
            if (section == null || section.accountsLine < 0) return false;

            List<String> newLines = new ArrayList<>(lines.size());
            boolean inTarget = false;
            int targetIndent = -1;
            boolean deleted = false;

            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                if (!inTarget && isScopedAccountHeader(lines, i, section, cleanTarget)) {
                    inTarget = true;
                    targetIndent = getLeadingWhitespaceCount(line);
                    deleted = true;
                    continue;
                }
                if (inTarget) {
                    if (i >= section.endExclusive) {
                        inTarget = false;
                        newLines.add(line);
                        continue;
                    }
                    String trimmed = line.trim();
                    if (trimmed.isEmpty()) {
                        continue;
                    }
                    int currentIndent = getLeadingWhitespaceCount(line);
                    if (currentIndent <= targetIndent && !trimmed.startsWith("#")) {
                        inTarget = false;
                        newLines.add(line);
                    }
                    continue;
                }
                newLines.add(line);
            }

            if (!deleted) return false;

            // 若 accounts 下已无任何子项，恢复为 accounts: {} 以保证 SnakeYAML 解析为 Map 而非 null
            AccountsSection afterSection = locateAccountsSection(newLines);
            if (afterSection != null && afterSection.accountsLine >= 0) {
                boolean hasRemainingChild = false;
                for (int i = afterSection.accountsLine + 1; i < afterSection.endExclusive; i++) {
                    String t = newLines.get(i).trim();
                    if (!t.isEmpty() && !t.startsWith("#")) {
                        hasRemainingChild = true;
                        break;
                    }
                }
                if (!hasRemainingChild) {
                    String accLine = newLines.get(afterSection.accountsLine);
                    String indent = " ".repeat(getLeadingWhitespaceCount(accLine));
                    newLines.set(afterSection.accountsLine, indent + "accounts: {}");
                }
            }

            writeYamlAtomically(ymlFile.toPath(), newLines);
            return true;
        }
    }

    /**
     * 在 oci.accounts 作用域内新增或覆盖同名账号配置；若文件缺少 oci: 或 accounts: 段落则自动补齐并校验落盘。
     */
    public void upsertAccountInYaml(
            File ymlFile,
            String accountName,
            String user,
            String fingerprint,
            String tenancy,
            String region,
            String keyFilePath) throws IOException {
        if (ymlFile == null) {
            throw new IOException("配置文件路径为空");
        }
        String cleanName = normalizeAccountKey(accountName);
        if (!isSafeYamlName(cleanName)) {
            throw new IllegalArgumentException("账号别名包含非法字符或属于系统保留键名");
        }
        if (!isSafeYamlValue(user) || !isSafeYamlValue(fingerprint)
                || !isSafeYamlValue(tenancy) || !isSafeYamlValue(region) || !isSafeYamlValue(keyFilePath)) {
            throw new IllegalArgumentException("OCI 凭据字段包含非法字符");
        }

        synchronized (YAML_FILE_LOCK) {
            List<String> lines = ymlFile.exists()
                    ? new ArrayList<>(Files.readAllLines(ymlFile.toPath(), StandardCharsets.UTF_8))
                    : new ArrayList<>();

            // 1. 若已存在同名账号，先在 oci.accounts 作用域内移除旧节点
            AccountsSection initialSection = locateAccountsSection(lines);
            if (initialSection != null && initialSection.accountsLine >= 0) {
                List<String> cleaned = new ArrayList<>(lines.size());
                boolean inTarget = false;
                int targetIndent = -1;
                for (int i = 0; i < lines.size(); i++) {
                    String line = lines.get(i);
                    if (!inTarget && isScopedAccountHeader(lines, i, initialSection, cleanName)) {
                        inTarget = true;
                        targetIndent = getLeadingWhitespaceCount(line);
                        continue;
                    }
                    if (inTarget) {
                        if (i >= initialSection.endExclusive) {
                            inTarget = false;
                            cleaned.add(line);
                            continue;
                        }
                        String trimmed = line.trim();
                        if (trimmed.isEmpty()) continue;
                        if (getLeadingWhitespaceCount(line) <= targetIndent && !trimmed.startsWith("#")) {
                            inTarget = false;
                            cleaned.add(line);
                        }
                        continue;
                    }
                    cleaned.add(line);
                }
                lines = cleaned;
            }

            // 2. 确保 oci: 与 accounts: 节点存在
            AccountsSection section = locateAccountsSection(lines);
            if (section == null) {
                if (!lines.isEmpty() && !lines.get(lines.size() - 1).trim().isEmpty()) {
                    lines.add("");
                }
                lines.add("oci:");
                lines.add("  accounts:");
                section = locateAccountsSection(lines);
            } else if (section.accountsLine < 0) {
                lines.add(section.ociLine + 1, "  accounts:");
                section = locateAccountsSection(lines);
            }

            // 3. 清除 accounts: 后的 {}
            String accountsHeader = lines.get(section.accountsLine);
            lines.set(section.accountsLine, accountsHeader.replaceFirst(":\\s*\\{\\}(\\s*(?:#.*)?)$", ":$1"));

            int insertIndex = section.accountsLine + 1;
            for (int i = section.accountsLine + 1; i < section.endExclusive; i++) {
                String l = lines.get(i);
                String trimmed = l.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
                if (getLeadingWhitespaceCount(l) > section.accountsIndent) {
                    insertIndex = i + 1;
                } else {
                    break;
                }
            }

            String keyIndent = " ".repeat(section.childKeyIndent);
            String propIndent = " ".repeat(section.childKeyIndent + 2);
            List<String> nodeLines = new ArrayList<>();
            if (insertIndex > section.accountsLine + 1 && insertIndex <= lines.size()
                    && !lines.get(insertIndex - 1).trim().isEmpty()) {
                nodeLines.add("");
            }
            nodeLines.add(keyIndent + "\"[" + cleanName + "]\":");
            nodeLines.add(propIndent + "user: \"" + user + "\"");
            nodeLines.add(propIndent + "fingerprint: \"" + fingerprint + "\"");
            nodeLines.add(propIndent + "tenancy: \"" + tenancy + "\"");
            nodeLines.add(propIndent + "region: \"" + region + "\"");
            nodeLines.add(propIndent + "key-file: \"" + keyFilePath + "\"");

            lines.addAll(insertIndex, nodeLines);
            writeYamlAtomically(ymlFile.toPath(), lines);
        }
    }

    /**
     * 预检 SnakeYAML 结构合法性，并以 0600 权限写入同目录临时文件后原子替换目标文件。
     */
    public void writeYamlAtomically(Path targetPath, List<String> lines) throws IOException {
        String content = String.join("\n", lines) + "\n";
        Object parsed;
        try {
            parsed = new Yaml().load(content);
        } catch (Exception yamlEx) {
            throw new IOException("生成的 YAML 语法校验失败，已中止写入以保护配置文件: " + yamlEx.getMessage(), yamlEx);
        }
        if (parsed != null && !(parsed instanceof Map<?, ?>)) {
            throw new IOException("生成的 YAML 根节点不是合法映射结构，已中止写入");
        }

        Path parent = targetPath.toAbsolutePath().getParent();
        if (parent != null && !Files.exists(parent)) {
            Files.createDirectories(parent);
        }
        Path tmpPath = (parent != null ? parent : Path.of("."))
                .resolve(targetPath.getFileName().toString() + ".tmp." + UUID.randomUUID().toString().substring(0, 8));
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);

        try {
            try {
                FileAttribute<Set<PosixFilePermission>> attr =
                        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"));
                try (SeekableByteChannel channel = Files.newByteChannel(
                        tmpPath,
                        EnumSet.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE),
                        attr)) {
                    channel.write(ByteBuffer.wrap(bytes));
                }
            } catch (UnsupportedOperationException e) {
                Files.write(tmpPath, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            }
            enforceStrictFilePermissions(tmpPath);
            try {
                Files.move(tmpPath, targetPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (Exception moveEx) {
                Files.move(tmpPath, targetPath, StandardCopyOption.REPLACE_EXISTING);
            }
            enforceStrictFilePermissions(targetPath);
        } finally {
            try {
                Files.deleteIfExists(tmpPath);
            } catch (Exception ignored) {}
        }
    }

    public void persistRegisterTimeToYaml(String accountName, String apiTime) {
        persistAccountPropertyToYaml(accountName, "register-time", apiTime);
    }

    public void persistClientIdentifierToYaml(String accountName, String clientIdentifier) {
        persistAccountPropertyToYaml(accountName, "client-identifier", clientIdentifier);
    }

    public void persistProxyToYaml(String accountName, String proxy) {
        persistAccountPropertyToYaml(accountName, "proxy", proxy);
    }

    public String extractIniValue(String text, String key) {
        Matcher m = Pattern.compile("(?i)^\\s*" + key + "\\s*=\\s*(.+)$", Pattern.MULTILINE).matcher(text);
        if (m.find()) return m.group(1).trim();
        return null;
    }

    /**
     * 强制收敛敏感配置文件权限为 600 (rw-------)，仅属主可读写。
     */
    public void enforceStrictFilePermissions(Path path) {
        if (path == null) return;
        try {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
        } catch (Exception ignored) {
            try {
                File f = path.toFile();
                f.setReadable(false, false);
                f.setWritable(false, false);
                f.setExecutable(false, false);
                f.setReadable(true, true);
                f.setWritable(true, true);
            } catch (Exception ignored2) {}
        }
    }

    /**
     * 清理历史遗留的磁盘明文管理员口令副本 (.admin-password / application.yml.bak.password)。
     */
    public void cleanupPlaintextPasswordArtifacts() {
        synchronized (YAML_FILE_LOCK) {
            File ymlFile = getConfigFile();
            if (ymlFile == null || ymlFile.getParentFile() == null) return;
            Path dir = ymlFile.getParentFile().toPath();
            try { Files.deleteIfExists(dir.resolve(".admin-password")); } catch (Exception ignored) {}
            try { Files.deleteIfExists(dir.resolve("application.yml.bak.password")); } catch (Exception ignored) {}
        }
    }

    /**
     * 把配置文件里的明文 admin-password 换成哈希。环境变量占位符和已经是哈希的值不动。
     */
    public boolean replacePlaintextAdminPassword(String plaintext, String hashed) {
        if (plaintext == null || plaintext.isBlank() || hashed == null || hashed.isBlank()) return false;
        if (hashed.indexOf('\'') >= 0 || hashed.indexOf('"') >= 0 || hashed.indexOf('\n') >= 0 || hashed.indexOf('\r') >= 0) return false;
        synchronized (YAML_FILE_LOCK) {
            try {
                File ymlFile = getConfigFile();
                if (ymlFile == null || !ymlFile.exists()) return false;
                List<String> lines = Files.readAllLines(ymlFile.toPath(), StandardCharsets.UTF_8);
                int target = findPlaintextAdminPasswordLine(lines, plaintext);
                if (target < 0) {
                    cleanupPlaintextPasswordArtifacts();
                    return false;
                }
                List<String> fresh = new ArrayList<>(Files.readAllLines(ymlFile.toPath(), StandardCharsets.UTF_8));
                if (target >= fresh.size() || !fresh.get(target).equals(lines.get(target))) return false;
                if (findPlaintextAdminPasswordLine(fresh, plaintext) != target) return false;
                String indent = leadingWhitespace(fresh.get(target));
                fresh.set(target, indent + "admin-password: '" + hashed + "'");
                writeYamlAtomically(ymlFile.toPath(), fresh);
                cleanupPlaintextPasswordArtifacts();
                return true;
            } catch (Exception e) {
                System.err.println("升级管理员密码哈希失败: " + e.getMessage());
                return false;
            }
        }
    }

    private static int findPlaintextAdminPasswordLine(List<String> lines, String plaintext) {
        Pattern pattern = Pattern.compile("^(\\s*)admin-password\\s*:\\s*(.*)$");
        for (int i = 0; i < lines.size(); i++) {
            Matcher matcher = pattern.matcher(lines.get(i));
            if (!matcher.matches()) continue;
            String raw = matcher.group(2).trim();
            String value = unquoteYaml(raw);
            if (PasswordHasher.isHashed(value)) return -1;
            if (!value.equals(plaintext)) return -1;
            if (value.matches("\\$\\{[^}]*}")) return -1;
            return i;
        }
        return -1;
    }

    private static String leadingWhitespace(String line) {
        int i = 0;
        while (i < line.length() && (line.charAt(i) == ' ' || line.charAt(i) == '\t')) i++;
        return line.substring(0, i);
    }

    private static String unquoteYaml(String raw) {
        if (raw == null) return "";
        String value = raw.trim();
        if (value.length() >= 2) {
            char quote = value.charAt(0);
            if ((quote == '"' || quote == '\'') && value.charAt(value.length() - 1) == quote) {
                String body = value.substring(1, value.length() - 1);
                if (quote == '\'') return body.replace("''", "'");
                StringBuilder out = new StringBuilder(body.length());
                for (int i = 0; i < body.length(); i++) {
                    char c = body.charAt(i);
                    if (c == '\\' && i + 1 < body.length()) {
                        char next = body.charAt(i + 1);
                        if (next == '\\' || next == '"') {
                            out.append(next);
                            i++;
                            continue;
                        }
                    }
                    out.append(c);
                }
                return out.toString();
            }
        }
        return value;
    }

    /**
     * 校验账号别名：禁止首尾空白、控制字符、YAML 结构元字符，以及所有系统顶层/属性保留键名。
     */
    public boolean isSafeYamlName(String name) {
        if (name == null || name.isEmpty() || name.length() > 128) return false;
        if (!name.equals(name.trim())) return false;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c < 0x20 || c == 0x7F || c == '"' || c == '\'' || c == ':' || c == '#'
                    || c == '[' || c == ']' || c == '{' || c == '}' || c == '\\'
                    || c == '&' || c == '*' || c == '!' || c == '|' || c == '>'
                    || c == '%' || c == '@' || c == '`' || c == ',') {
                return false;
            }
        }
        String normalized = normalizeAccountKey(name).toLowerCase(Locale.ROOT);
        if (normalized.isEmpty() || RESERVED_YAML_KEYS.contains(normalized)) {
            return false;
        }
        return true;
    }

    public boolean isSafeYamlValue(String v) {
        if (v == null) return false;
        if (v.length() > 4096) return false;
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (c < 0x20 || c == 0x7F || c == '"' || c == '\\') return false;
        }
        return true;
    }
}
