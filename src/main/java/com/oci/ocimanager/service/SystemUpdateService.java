package com.oci.ocimanager.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.oci.ocimanager.entity.SystemConfig;
import com.oci.ocimanager.repository.SystemConfigRepository;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 🚀 OCIBox 系统版本检测与一键平滑热升级服务
 * 1. 自动检测 GitHub Release 与 Checksums 摘要；
 * 2. 发现新构建版本时自动推送站内运维通知；
 * 3. 支持后台管理端一键更新：校验完整性与 SHA-256，安全替换后平滑重启。
 */
@Service
public class SystemUpdateService {

    private static final String GITHUB_REPO = "GeniusZeroTwo/ocibox";
    private static final String GITHUB_API_RELEASE_URL = "https://api.github.com/repos/" + GITHUB_REPO + "/releases/latest";
    private static final String CHECKSUMS_URL = "https://github.com/" + GITHUB_REPO + "/releases/download/latest/checksums.txt";
    private static final String DOWNLOAD_URL_PRIMARY = "https://github.com/" + GITHUB_REPO + "/releases/download/latest/ocibox.jar";
    private static final String DOWNLOAD_URL_SECONDARY = "https://github.com/" + GITHUB_REPO + "/releases/download/latest/oci-personal.jar";

    private static final long CACHE_TTL_MS = 15 * 60 * 1000L; // 15 分钟缓存，避免 GitHub 频繁调用
    private static final int CONNECT_TIMEOUT_MS = 10000;
    private static final int READ_TIMEOUT_MS = 30000;

    @Autowired
    private NotificationService notificationService;

    @Autowired
    private SystemConfigRepository systemConfigRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @Value("${app.version:v1.1.0}")
    private String currentVersion;

    private volatile long lastCheckTime = 0L;
    private final AtomicBoolean isChecking = new AtomicBoolean(false);
    private final AtomicBoolean isUpgrading = new AtomicBoolean(false);
    private final Map<String, Object> cachedUpdateInfo = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        try {
            cachedUpdateInfo.put("hasUpdate", false);
            cachedUpdateInfo.put("currentVersion", currentVersion);
            cachedUpdateInfo.put("latestVersion", currentVersion);
            cachedUpdateInfo.put("releaseTitle", "");
            cachedUpdateInfo.put("releaseBody", "");
            cachedUpdateInfo.put("releaseUrl", "https://github.com/" + GITHUB_REPO + "/releases");
            cachedUpdateInfo.put("publishedAt", "");
            cachedUpdateInfo.put("buildTime", "");
            cachedUpdateInfo.put("remoteSha", "");
            cachedUpdateInfo.put("localSha", "");
            cachedUpdateInfo.put("checking", false);
            cachedUpdateInfo.put("upgrading", false);

            File targetJar = getTargetJarFile();
            if (targetJar.exists()) {
                String sha = calculateSha256(targetJar);
                if (sha != null) {
                    cachedUpdateInfo.put("localSha", sha);
                }
            }
        } catch (Exception e) {
            System.err.println("ℹ️ [SystemUpdateService] 启动初始化提示: " + e.getMessage());
        }
    }

    /**
     * 获取缓存的最新版本信息
     */
    public Map<String, Object> getCachedUpdateInfo() {
        Map<String, Object> copy = new HashMap<>(cachedUpdateInfo);
        copy.put("checking", isChecking.get());
        copy.put("upgrading", isUpgrading.get());
        return copy;
    }

    /**
     * 检查系统新版本
     *
     * @param force 是否强制跳过缓存刷新
     * @return 最新的更新信息
     */
    public Map<String, Object> checkUpdate(boolean force) {
        long now = System.currentTimeMillis();
        if (!force && (now - lastCheckTime < CACHE_TTL_MS) && cachedUpdateInfo.containsKey("hasUpdate")) {
            return getCachedUpdateInfo();
        }

        if (isChecking.compareAndSet(false, true)) {
            try {
                performUpdateCheck();
                lastCheckTime = System.currentTimeMillis();
            } catch (Exception e) {
                System.err.println("⚠️ [SystemUpdateService] 检查更新失败: " + e.getMessage());
                cachedUpdateInfo.put("error", "检查更新失败，请稍后重试");
            } finally {
                isChecking.set(false);
            }
        }

        return getCachedUpdateInfo();
    }

    /**
     * 执行具体的远程版本拉取与比对
     */
    private void performUpdateCheck() {
        String remoteSha = fetchRemoteChecksum();
        String releaseTitle = "";
        String releaseBody = "";
        String releaseUrl = "https://github.com/" + GITHUB_REPO + "/releases/tag/latest";
        String publishedAt = "";
        String buildTime = "";
        String tagName = "latest";

        // 尝试从 GitHub Releases API 获取详细 Release 说明
        try {
            HttpURLConnection conn = connectWithRedirects(GITHUB_API_RELEASE_URL, 8000, 8000);
            if (conn.getResponseCode() == 200) {
                try (InputStream is = conn.getInputStream()) {
                    JsonNode root = objectMapper.readTree(is);
                    tagName = root.path("tag_name").asText("latest");
                    releaseTitle = root.path("name").asText("🚀 最新自动编译版 (Latest)");
                    releaseBody = root.path("body").asText("");
                    releaseUrl = root.path("html_url").asText(releaseUrl);
                    publishedAt = root.path("published_at").asText("");

                    // 提取 Release 说明中的构建时间
                    if (releaseBody != null && !releaseBody.isBlank()) {
                        Matcher m = Pattern.compile("构建时间[*: ]*([^\\r\\n]+)", Pattern.CASE_INSENSITIVE).matcher(releaseBody);
                        if (m.find()) {
                            buildTime = m.group(1).replaceAll("[*`]", "").trim();
                        }
                    }

                    // 如果 API 中直接带了 assets 的 digest
                    if (remoteSha == null || remoteSha.isBlank()) {
                        JsonNode assets = root.path("assets");
                        if (assets.isArray()) {
                            for (JsonNode asset : assets) {
                                String name = asset.path("name").asText();
                                String digest = asset.path("digest").asText();
                                if (("ocibox.jar".equalsIgnoreCase(name) || "oci-personal.jar".equalsIgnoreCase(name)) && digest.startsWith("sha256:")) {
                                    remoteSha = digest.substring(7).trim().toLowerCase();
                                    break;
                                }
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            // API 被限流或网络抖动时容错
            System.err.println("ℹ️ [SystemUpdateService] GitHub API 访问提示: " + e.getMessage());
        }

        File targetJar = getTargetJarFile();
        String localSha = null;
        if (targetJar.exists()) {
            localSha = calculateSha256(targetJar);
        }

        boolean hasUpdate = false;
        if (remoteSha != null && !remoteSha.isBlank()) {
            if (localSha != null && !localSha.isBlank()) {
                hasUpdate = !localSha.equalsIgnoreCase(remoteSha);
            } else {
                String savedSha = getSavedConfig("INSTALLED_VERSION_SHA");
                hasUpdate = savedSha == null || !savedSha.equalsIgnoreCase(remoteSha);
            }
        }

        cachedUpdateInfo.put("hasUpdate", hasUpdate);
        cachedUpdateInfo.put("currentVersion", currentVersion);
        cachedUpdateInfo.put("latestVersion", (releaseTitle != null && !releaseTitle.isBlank()) ? releaseTitle : "Latest");
        cachedUpdateInfo.put("tag", tagName);
        cachedUpdateInfo.put("buildTime", buildTime != null ? buildTime : "");
        cachedUpdateInfo.put("publishedAt", publishedAt != null ? publishedAt : "");
        cachedUpdateInfo.put("releaseTitle", releaseTitle != null ? releaseTitle : "");
        cachedUpdateInfo.put("releaseBody", releaseBody != null ? releaseBody : "");
        cachedUpdateInfo.put("releaseUrl", releaseUrl);
        cachedUpdateInfo.put("remoteSha", remoteSha != null ? remoteSha : "");
        cachedUpdateInfo.put("localSha", localSha != null ? localSha : "");
        cachedUpdateInfo.put("lastCheckTime", DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").format(LocalDateTime.now()));
        cachedUpdateInfo.remove("error");

        // 如果检测到新版本，且之前尚未就此 SHA 推送过通知，则自动推送站内通知
        if (hasUpdate && remoteSha != null && !remoteSha.isBlank()) {
            String lastNotified = getSavedConfig("LAST_NOTIFIED_UPDATE_SHA");
            if (lastNotified == null || !lastNotified.equalsIgnoreCase(remoteSha)) {
                sendUpdateNotice(tagName, releaseTitle, buildTime, remoteSha);
                saveConfig("LAST_NOTIFIED_UPDATE_SHA", remoteSha);
            }
        }
    }

    /**
     * 发送版本更新站内通知
     */
    private void sendUpdateNotice(String tag, String title, String buildTime, String sha) {
        try {
            String noticeTitle = "🚀 发现 OCIBox 新版本可用";
            StringBuilder sb = new StringBuilder();
            sb.append("<b>检测到 OCIBox 核心程序包有新版本发布！</b><br>");
            sb.append("• 当前运行版本：<code>").append(escapeHtml(currentVersion)).append("</code><br>");
            sb.append("• 最新发布版本：<b>").append(escapeHtml(title != null && !title.isBlank() ? title : tag)).append("</b><br>");
            if (buildTime != null && !buildTime.isBlank()) {
                sb.append("• 自动构建时间：<code>").append(escapeHtml(buildTime)).append("</code><br>");
            }
            if (sha != null && sha.length() >= 16) {
                sb.append("• 校验 SHA-256：<code>").append(sha.substring(0, 16)).append("...</code><br>");
            }
            sb.append("<br>⚡ <b>您可直接在消息中心或本卡片点击【立即一键更新】，系统将全自动下载并平滑重启。</b>");

            // 🌟 纯网页控制台展示，不向 Telegram 推送造成多余打扰
            notificationService.send(noticeTitle, sb.toString(), "SUCCESS", "UPDATE", false);
        } catch (Exception e) {
            System.err.println("⚠️ [SystemUpdateService] 推送更新通知失败: " + e.getMessage());
        }
    }

    /**
     * 执行一键系统平滑升级
     */
    public Map<String, Object> executeUpdate() {
        if (!isUpgrading.compareAndSet(false, true)) {
            return Map.of("success", false, "error", "系统更新正在进行中，请勿重复操作！");
        }

        try {
            cachedUpdateInfo.put("upgrading", true);
            File targetJar = getTargetJarFile();
            File installDir = targetJar.getParentFile();
            if (installDir == null || !installDir.exists()) {
                installDir = new File("/root/ocibox");
                if (!installDir.exists()) installDir = new File("/root/oci-personal");
                if (!installDir.exists()) installDir = new File(System.getProperty("user.dir"));
            }

            File tmpJar = new File(installDir, "app.jar.download");
            if (tmpJar.exists()) {
                tmpJar.delete();
            }

            // 1. 获取预期的 SHA-256（强制要求合法 64 位十六进制摘要，Fail-Closed）
            String expectedSha = (String) cachedUpdateInfo.get("remoteSha");
            if (expectedSha == null || !expectedSha.matches("(?i)^[0-9a-f]{64}$")) {
                expectedSha = fetchRemoteChecksum();
            }
            if (expectedSha == null || !expectedSha.matches("(?i)^[0-9a-f]{64}$")) {
                throw new SecurityException("未能获取到官方 SHA-256 校验摘要，为保障系统安全已中止升级");
            }

            // 2. 下载核心 JAR 包（带自动重试与双 URL 故障转移）
            boolean downloaded = false;
            try {
                downloadFileWithRedirects(DOWNLOAD_URL_PRIMARY, tmpJar);
                downloaded = true;
            } catch (Exception e1) {
                System.err.println("⚠️ 主下载源失败，尝试备用源: " + e1.getMessage());
                try {
                    downloadFileWithRedirects(DOWNLOAD_URL_SECONDARY, tmpJar);
                    downloaded = true;
                } catch (Exception e2) {
                    throw new IOException("无法从 GitHub 下载核心程序包: " + e2.getMessage());
                }
            }

            if (!downloaded || !tmpJar.exists() || tmpJar.length() < 10 * 1024 * 1024) {
                if (tmpJar.exists()) tmpJar.delete();
                throw new IOException("下载的文件大小异常 (小于 10MB)，可能下载不完整或网络受阻");
            }

            // 3. 校验 JAR 归档结构合法性
            try (JarFile jf = new JarFile(tmpJar)) {
                if (jf.size() == 0) {
                    throw new IOException("下载的文件不是合法的 JAR 归档");
                }
            } catch (Exception e) {
                if (tmpJar.exists()) tmpJar.delete();
                throw new IOException("下载程序包结构损坏，非合法 Java JAR: " + e.getMessage());
            }

            // 4. SHA-256 完整性摘要强制闭环校验 (Fail-Closed)
            String actualSha = calculateSha256(tmpJar);
            if (actualSha == null || !expectedSha.equalsIgnoreCase(actualSha)) {
                if (tmpJar.exists()) tmpJar.delete();
                throw new SecurityException("核心程序包 SHA-256 校验不匹配，已拒绝替换并删除临时文件");
            }

            // 5. 原子替换物理 JAR 文件
            try {
                Files.move(tmpJar.toPath(), targetJar.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (Exception e) {
                Files.move(tmpJar.toPath(), targetJar.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }

            try {
                targetJar.setReadable(true, false);
                targetJar.setWritable(true, true);
                targetJar.setExecutable(true, false);
            } catch (Exception ignored) {}

            // 6. 持久化升级记录
            if (actualSha != null) {
                saveConfig("INSTALLED_VERSION_SHA", actualSha);
            }
            saveConfig("LAST_UPGRADE_TIME", DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").format(LocalDateTime.now()));

            cachedUpdateInfo.put("hasUpdate", false);
            if (actualSha != null) {
                cachedUpdateInfo.put("localSha", actualSha);
            }

            // 7. 发送升级成功通知 (仅本地后台记录与 Toast，不推 TG 免打扰)
            notificationService.send(
                "✅ OCIBox 核心程序更新就绪",
                "最新核心程序包已成功校验并替换，系统正在执行平滑安全重启（约 5~10 秒内恢复响应）...",
                "SUCCESS",
                "UPDATE",
                false
            );

            // 8. 异步触发无感守护进程重启
            triggerRestartAsync();

            return Map.of(
                "success", true,
                "message", "🚀 核心程序包已成功更新！系统正在平滑重启生效，约 5~10 秒后将自动恢复响应...",
                "sha", actualSha != null ? actualSha : ""
            );

        } catch (SecurityException se) {
            System.err.println("❌ [SystemUpdateService] 安全校验拦截: " + se.getMessage());
            return Map.of("success", false, "error", se.getMessage());
        } catch (Exception e) {
            System.err.println("❌ [SystemUpdateService] 一键升级失败: " + e.getMessage());
            return Map.of("success", false, "error", "升级失败，请检查服务器网络连接或稍后重试");
        } finally {
            cachedUpdateInfo.put("upgrading", false);
            isUpgrading.set(false);
        }
    }

    /**
     * 异步触发系统平滑重启
     * 采用 systemd-run 脱离当前 cgroup，防止父服务停止时连带杀死重启进程
     */
    private void triggerRestartAsync() {
        CompletableFuture.runAsync(() -> {
            try {
                // 等待 1.5 秒确保当前 HTTP 响应已完整写回客户端浏览器
                Thread.sleep(1500);

                String os = System.getProperty("os.name", "").toLowerCase();
                if (os.contains("linux")) {
                    boolean hasSystemdRun = new File("/usr/bin/systemd-run").exists() || new File("/bin/systemd-run").exists();
                    String restartCmd = "sleep 1 && (systemctl restart ocibox || systemctl restart oci-personal || true)";

                    String[] cmd;
                    if (hasSystemdRun) {
                        cmd = new String[]{"systemd-run", "--unit=ocibox-auto-restart", "bash", "-c", restartCmd};
                    } else {
                        cmd = new String[]{"nohup", "bash", "-c", restartCmd, ">/dev/null", "2>&1", "&"};
                    }

                    ProcessBuilder pb = new ProcessBuilder(cmd);
                    pb.redirectErrorStream(true);
                    Process p = pb.start();
                    p.waitFor();
                    System.out.println("⚡ [SystemUpdateService] 已下发平滑重启指令: " + String.join(" ", cmd));
                } else {
                    System.out.println("ℹ️ [SystemUpdateService] 非 Linux 环境，跳过 systemd 重启操作。");
                }
            } catch (Exception e) {
                System.err.println("⚠️ [SystemUpdateService] 触发平滑重启异常: " + e.getMessage());
            }
        });
    }

    /**
     * 下载文件并支持任意层级 301/302/307/308 重定向
     */
    private void downloadFileWithRedirects(String urlString, File targetFile) throws IOException {
        HttpURLConnection conn = connectWithRedirects(urlString, CONNECT_TIMEOUT_MS, READ_TIMEOUT_MS);
        if (conn.getResponseCode() != 200) {
            throw new IOException("HTTP 状态异常: " + conn.getResponseCode() + " " + conn.getResponseMessage());
        }

        try (InputStream in = new BufferedInputStream(conn.getInputStream());
             OutputStream out = new BufferedOutputStream(new FileOutputStream(targetFile))) {
            byte[] buffer = new byte[65536];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            out.flush();
        } finally {
            conn.disconnect();
        }
    }

    /**
     * 获取远程校验和 checksums.txt（若直接下载受阻则回退读取 GitHub Releases API digest）
     */
    private String fetchRemoteChecksum() {
        try {
            HttpURLConnection conn = connectWithRedirects(CHECKSUMS_URL, 8000, 8000);
            if (conn.getResponseCode() == 200) {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        String[] parts = line.trim().split("\\s+");
                        if (parts.length >= 2 && parts[0].matches("(?i)^[0-9a-f]{64}$")) {
                            String filename = parts[1].toLowerCase();
                            if (filename.contains("ocibox.jar") || filename.contains("oci-personal.jar")) {
                                return parts[0].toLowerCase();
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("ℹ️ [SystemUpdateService] 拉取 checksums.txt 提示: " + e.getMessage());
        }

        // 回退：从 GitHub Releases API assets[].digest 提取 SHA-256
        try {
            HttpURLConnection conn = connectWithRedirects(GITHUB_API_RELEASE_URL, 8000, 8000);
            if (conn.getResponseCode() == 200) {
                try (InputStream is = conn.getInputStream()) {
                    JsonNode root = objectMapper.readTree(is);
                    JsonNode assets = root.path("assets");
                    if (assets.isArray()) {
                        for (JsonNode asset : assets) {
                            String name = asset.path("name").asText();
                            String digest = asset.path("digest").asText();
                            if (("ocibox.jar".equalsIgnoreCase(name) || "oci-personal.jar".equalsIgnoreCase(name))
                                    && digest.toLowerCase().startsWith("sha256:")) {
                                String sha = digest.substring(7).trim().toLowerCase();
                                if (sha.matches("^[0-9a-f]{64}$")) {
                                    return sha;
                                }
                            }
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    /**
     * 智能定位当前系统中应当被替换的目标 JAR 路径
     */
    public File getTargetJarFile() {
        // 1. 优先检测 /root/ocibox/app.jar
        File f1 = new File("/root/ocibox/app.jar");
        if (f1.exists()) return f1;

        // 2. 检测 /root/oci-personal/app.jar
        File f2 = new File("/root/oci-personal/app.jar");
        if (f2.exists()) return f2;

        // 3. 检测工作目录下的 app.jar
        File f3 = new File(System.getProperty("user.dir"), "app.jar");
        if (f3.exists()) return f3;

        // 4. 检测当前运行的代码源是否为 jar
        try {
            File codeSource = new File(SystemUpdateService.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            if (codeSource.isFile() && codeSource.getName().endsWith(".jar")) {
                return codeSource;
            }
        } catch (Exception ignored) {}

        // 5. 兜底回退
        if (new File("/root/ocibox").isDirectory()) return f1;
        if (new File("/root/oci-personal").isDirectory()) return f2;
        return f3;
    }

    /**
     * 校验更新源 URI 是否严格为 HTTPS 且属于受信任的 GitHub 官方域名
     */
    private void validateTrustedUpdateUri(URI uri) throws IOException {
        if (uri == null || !"https".equalsIgnoreCase(uri.getScheme())) {
            throw new IOException("拒绝非 HTTPS 的不安全更新链接");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new IOException("更新链接缺少有效主机名");
        }
        String lowerHost = host.toLowerCase(Locale.ROOT);
        boolean trusted = lowerHost.equals("github.com")
                || lowerHost.equals("api.github.com")
                || lowerHost.endsWith(".github.com")
                || lowerHost.endsWith(".githubusercontent.com")
                || lowerHost.endsWith(".githubassets.com");
        if (!trusted) {
            throw new IOException("拒绝跳转到非官方受信任域名: " + lowerHost);
        }
    }

    /**
     * 建立支持多跳跨域重定向的 HTTPS 链接（强制 HTTPS 与 GitHub 官方域名白名单）
     */
    private HttpURLConnection connectWithRedirects(String urlString, int connectTimeout, int readTimeout) throws IOException {
        String currentUrl = urlString;
        int redirectCount = 0;
        final int MAX_REDIRECTS = 8;

        while (redirectCount < MAX_REDIRECTS) {
            URI currentUri = URI.create(currentUrl);
            validateTrustedUpdateUri(currentUri);
            URL url = currentUri.toURL();
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(connectTimeout);
            conn.setReadTimeout(readTimeout);
            conn.setRequestProperty("User-Agent", "OCIBox-SystemUpdater/1.0 (Linux; x86_64)");
            conn.setRequestProperty("Accept", "*/*");
            conn.setInstanceFollowRedirects(false);

            int status = conn.getResponseCode();
            if (status == HttpURLConnection.HTTP_MOVED_TEMP || status == HttpURLConnection.HTTP_MOVED_PERM
                    || status == HttpURLConnection.HTTP_SEE_OTHER || status == 307 || status == 308) {
                String location = conn.getHeaderField("Location");
                conn.disconnect();
                if (location == null || location.isBlank()) {
                    throw new IOException("重定向状态 " + status + " 但缺失 Location 响应头");
                }
                URI nextUri = currentUri.resolve(location.trim());
                validateTrustedUpdateUri(nextUri);
                currentUrl = nextUri.toString();
                redirectCount++;
            } else {
                return conn;
            }
        }
        throw new IOException("重定向次数过多 (超过 " + MAX_REDIRECTS + " 次)");
    }

    /**
     * 计算指定文件的 SHA-256 摘要值
     */
    public static String calculateSha256(File file) {
        if (file == null || !file.exists() || !file.isFile()) return null;
        try (InputStream is = new BufferedInputStream(new FileInputStream(file))) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[65536];
            int read;
            while ((read = is.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
            byte[] hash = digest.digest();
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString().toLowerCase();
        } catch (Exception e) {
            return null;
        }
    }

    private String getSavedConfig(String key) {
        try {
            return systemConfigRepository.findById(key).map(SystemConfig::getConfigValue).orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    private void saveConfig(String key, String value) {
        try {
            systemConfigRepository.save(new SystemConfig(key, value));
        } catch (Exception ignored) {}
    }

    private String escapeHtml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;")
                   .replace("<", "&lt;")
                   .replace(">", "&gt;")
                   .replace("\"", "&quot;")
                   .replace("'", "&#39;");
    }
}

