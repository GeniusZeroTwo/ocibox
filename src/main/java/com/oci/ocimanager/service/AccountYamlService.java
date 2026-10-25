package com.oci.ocimanager.service;

import com.oci.ocimanager.config.OciProperties;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * application.yml 账号节点操作组件：行级 YAML 匹配/缩进工具、安全校验（防结构注入）、
 * register-time 回写；所有写操作须持有 {@link #lock()} 串行化。
 * 结构重构自 AdminController，供拆分后的各 Admin 控制器共享。
 */
@Component
public class AccountYamlService {

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
     * 从 application.yml 中按物理行序提取所有账号节点名称（SnakeYAML 严格保持 LinkedHashMap 解析顺序）。
     */
    public List<String> getOrderedAccountNamesFromYaml() {
        synchronized (YAML_FILE_LOCK) {
            File ymlFile = getConfigFile();
            if (ymlFile == null || !ymlFile.exists()) return Collections.emptyList();
            try (InputStream in = new FileInputStream(ymlFile)) {
                Yaml yaml = new Yaml();
                Map<String, Object> yamlMap = yaml.load(in);
                if (yamlMap != null && yamlMap.get("oci") instanceof Map) {
                    Map<?, ?> ociMap = (Map<?, ?>) yamlMap.get("oci");
                    if (ociMap != null && ociMap.get("accounts") instanceof Map) {
                        Map<?, ?> accountsNode = (Map<?, ?>) ociMap.get("accounts");
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
     * 若有新追加但尚未持久化进 YAML 的账号，则有序追加到尾部。
     */
    public Map<String, OciProperties.Account> reorderAccounts(Map<String, OciProperties.Account> currentAccounts) {
        if (currentAccounts == null || currentAccounts.isEmpty()) {
            return new LinkedHashMap<>();
        }
        List<String> yamlKeys = getOrderedAccountNamesFromYaml();
        Map<String, OciProperties.Account> orderedMap = new LinkedHashMap<>();
        Set<String> processedKeys = new HashSet<>();

        // 1. 优先按 YAML 中的物理顺序加入
        for (String yamlKey : yamlKeys) {
            String cleanYamlKey = yamlKey.replaceAll("[\"'\\s\\[\\]\\r\\n]", "");
            for (Map.Entry<String, OciProperties.Account> entry : currentAccounts.entrySet()) {
                String cleanEntryKey = entry.getKey().replaceAll("[\"'\\s\\[\\]\\r\\n]", "");
                if (cleanEntryKey.equals(cleanYamlKey) && !processedKeys.contains(entry.getKey())) {
                    orderedMap.put(entry.getKey(), entry.getValue());
                    processedKeys.add(entry.getKey());
                    break;
                }
            }
        }

        // 2. 追加内存中存在但 YAML 中尚未被解析到的新增账号
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
            if (ociProperties != null && ociProperties.getAccounts() != null && !ociProperties.getAccounts().isEmpty()) {
                Map<String, OciProperties.Account> ordered = reorderAccounts(ociProperties.getAccounts());
                ociProperties.setAccounts(ordered);
            }
        } catch (Exception e) {
            System.err.println("初始化 OCI 账户顺序失败: " + e.getMessage());
        }
    }

    public int getLeadingWhitespaceCount(String line) {
        int count = 0;
        while (count < line.length() && (line.charAt(count) == ' ' || line.charAt(count) == '\t')) {
            count++;
        }
        return count;
    }

    // 🌟 终极核武级匹配算法：无视任何标点符号、方括号和隐藏换行符，直击灵魂！
    public boolean isTargetYamlKey(String line, String targetAccount) {
        if (line == null || targetAccount == null) return false;
        int colonIndex = line.indexOf(':');
        if (colonIndex == -1) return false;
        
        // 只提取冒号前面的键部分
        String keyPart = line.substring(0, colonIndex);
        
        // 彻底砸碎所有干扰符（双引号、单引号、左中括号、右中括号、所有空白符和系统换行符）
        String cleanKey = keyPart.replaceAll("[\"'\\s\\[\\]\\r\\n]", "");
        String cleanTarget = targetAccount.replaceAll("[\"'\\s\\[\\]\\r\\n]", "");
        
        return !cleanKey.isEmpty() && cleanKey.equals(cleanTarget);
    }

    public void persistAccountPropertyToYaml(String accountName, String propKey, String propValue) {
        synchronized (YAML_FILE_LOCK) {
            try {
                File ymlFile = getConfigFile();
                if (ymlFile == null || !ymlFile.exists()) return;

                boolean shouldDelete = (propValue == null || propValue.trim().isEmpty());
                if (!shouldDelete && !isSafeYamlValue(propValue)) {
                    System.err.println("拒绝写入不安全的 YAML 值 [" + propKey + "] 账号 [" + accountName + "]");
                    return;
                }

                List<String> lines = Files.readAllLines(ymlFile.toPath());
                List<String> newLines = new ArrayList<>();
                boolean inTargetAccount = false;
                int targetIndent = -1;
                boolean propAdded = false;

                for (String line : lines) {
                    if (!inTargetAccount && isTargetYamlKey(line, accountName)) {
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

                        if (currentIndent <= targetIndent && !trimmed.startsWith("#")) {
                            if (!propAdded && !shouldDelete) {
                                String spaces = new String(new char[targetIndent + 2]).replace('\0', ' ');
                                newLines.add(spaces + propKey + ": \"" + propValue + "\"");
                                propAdded = true;
                            }
                            inTargetAccount = false;
                            newLines.add(line);
                        } else {
                            if (trimmed.startsWith(propKey + ":")) {
                                propAdded = true;
                                if (!shouldDelete) {
                                    String spaces = new String(new char[currentIndent]).replace('\0', ' ');
                                    newLines.add(spaces + propKey + ": \"" + propValue + "\"");
                                }
                                // If shouldDelete, skip adding this line
                            } else {
                                newLines.add(line);
                            }
                        }
                        continue;
                    }
                    newLines.add(line);
                }

                if (inTargetAccount && !propAdded && !shouldDelete) {
                    String spaces = new String(new char[targetIndent + 2]).replace('\0', ' ');
                    newLines.add(spaces + propKey + ": \"" + propValue + "\"");
                }

                Files.write(ymlFile.toPath(), newLines);
            } catch (Exception e) {
                System.err.println("自动写入属性 [" + propKey + "] 到配置文件失败 [" + accountName + "]: " + e.getMessage());
            }
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
     * 把配置文件里的明文 admin-password 换成哈希。环境变量占位符和已经是哈希的值不动。
     */
    public boolean replacePlaintextAdminPassword(String plaintext, String hashed) {
        if (plaintext == null || plaintext.isBlank() || hashed == null || hashed.isBlank()) return false;
        if (hashed.indexOf('"') >= 0 || hashed.indexOf('\n') >= 0 || hashed.indexOf('\r') >= 0) return false;
        synchronized (YAML_FILE_LOCK) {
            try {
                File ymlFile = getConfigFile();
                if (ymlFile == null || !ymlFile.exists()) return false;
                List<String> lines = Files.readAllLines(ymlFile.toPath());
                boolean changed = false;
                Pattern pattern = Pattern.compile("^(\\s*)admin-password\\s*:\\s*(.*)$");
                for (int i = 0; i < lines.size(); i++) {
                    Matcher matcher = pattern.matcher(lines.get(i));
                    if (!matcher.matches()) continue;
                    String raw = matcher.group(2).trim();
                    if (raw.contains("${")) return false;
                    String value = unquoteYaml(raw);
                    if (!value.equals(plaintext) || PasswordHasher.isHashed(value)) return false;
                    lines.set(i, matcher.group(1) + "admin-password: \"" + hashed + "\"");
                    changed = true;
                    break;
                }
                if (!changed) return false;
                Files.write(ymlFile.toPath(), lines, StandardCharsets.UTF_8);
                return true;
            } catch (Exception e) {
                System.err.println("升级管理员密码哈希失败: " + e.getMessage());
                return false;
            }
        }
    }

    private static String unquoteYaml(String raw) {
        if (raw.length() >= 2) {
            char quote = raw.charAt(0);
            if ((quote == '"' || quote == '\'') && raw.charAt(raw.length() - 1) == quote) {
                return raw.substring(1, raw.length() - 1);
            }
        }
        return raw;
    }

    // 🔒 账号别名将被写成 YAML 键 "[name]"，禁止会破坏 YAML 结构的元字符与控制字符。
    public boolean isSafeYamlName(String name) {
        if (name == null || name.isEmpty() || name.length() > 128) return false;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c < 0x20 || c == '"' || c == '\'' || c == ':' || c == '#'
                    || c == '[' || c == ']' || c == '{' || c == '}' || c == '\\') {
                return false;
            }
        }
        return true;
    }

    // 🔒 凭据/时间字段将被写成带双引号的 YAML 值，禁止引号、反斜杠与控制字符（含换行）。
    public boolean isSafeYamlValue(String v) {
        if (v == null) return false;
        if (v.length() > 4096) return false;
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (c < 0x20 || c == '"' || c == '\\') return false;
        }
        return true;
    }
}
