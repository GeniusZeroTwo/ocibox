package com.oci.ocimanager.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.lingala.zip4j.ZipFile;
import net.lingala.zip4j.model.ZipParameters;
import net.lingala.zip4j.model.enums.AesKeyStrength;
import net.lingala.zip4j.model.enums.EncryptionMethod;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

@Service
public class GitHubService {

    @Value("${app.github-token}")
    private String githubToken;

    @Value("${app.github-repo}")
    private String githubRepo;

    @Autowired
    private ObjectMapper objectMapper;

    // 🌟 定义最大保留的备份数量
    private static final int MAX_BACKUPS = 5;

    // 🌟 【内存优化】全局复用单例 HttpClient，避免每次请求新建 SelectorManager 线程与连接池
    private static final HttpClient GITHUB_HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .executor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor())
            .build();

    /**
     * 将文件或整个文件夹加密打包并上传到 GitHub（并发互斥 + 流式零拷贝 Base64 写入 + 权限收紧 600 + finally 必定清理临时文件）
     */
    public synchronized void backupFilesWithPassword(List<String> filePaths, String zipName, String password) throws Exception {
        File zipFile = new File(zipName);
        File payloadJsonFile = new File(zipName + ".payload.json");
        if (zipFile.exists()) {
            zipFile.delete();
        }
        if (payloadJsonFile.exists()) {
            payloadJsonFile.delete();
        }

        try {
            // 🌟 设置 Zip4j AES-256 加密参数
            ZipParameters zipParameters = new ZipParameters();
            zipParameters.setEncryptFiles(true);
            zipParameters.setEncryptionMethod(EncryptionMethod.AES);
            zipParameters.setAesKeyStrength(AesKeyStrength.KEY_STRENGTH_256);

            // 🌟 核心改进：智能识别文件或文件夹并递归打包
            try (ZipFile zip = new ZipFile(zipName, password.toCharArray())) {
                boolean addedAny = false;
                for (String path : filePaths) {
                    File fileOrFolder = new File(path);
                    if (fileOrFolder.exists()) {
                        if (fileOrFolder.isDirectory()) {
                            // 如果是 keys 或 data 这种文件夹，支持递归完全打包
                            zip.addFolder(fileOrFolder, zipParameters);
                        } else {
                            // 如果是单纯的文件，直接打包
                            zip.addFile(fileOrFolder, zipParameters);
                        }
                        addedAny = true;
                    } else {
                        System.out.println("⚠️ 警告: 备份路径不存在，已跳过 -> " + path);
                    }
                }

                if (!addedAny) {
                    throw new RuntimeException("所有的备份源路径均不存在，已中止本次空备份。");
                }
            }

            // 🔒 收紧临时备份压缩包权限为 600，防止上传期间被同机非特权用户读取
            try {
                if (zipFile.exists()) {
                    Files.setPosixFilePermissions(zipFile.toPath(), java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
                }
            } catch (Exception ignored) {}

            // 🌟 核心防御：防止超大文件导致的内存 OOM 雪崩和 504 网页假死
            long fileSize = zipFile.length();
            if (fileSize > 40 * 1024 * 1024) { // GitHub REST API 的稳定上传极值为 50MB
                throw new RuntimeException("备份文件过大 (" + (fileSize / 1024 / 1024) + "MB)，为保护系统内存与避免 GitHub API 熔断，已取消上传。请手动清理日志或无用数据。");
            }

            String timestamp = ZonedDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
            String remoteFileName = "oci_backup_" + timestamp + ".zip";

            // 🌟 【内存优化】流式将 zipFile 编码为 Base64 JSON 临时文件，再通过 BodyPublishers.ofFile 流式上传，
            // 彻底消除原 Files.readAllBytes + encodeToString + jsonBody 造成的 4 倍文件体积堆内存峰值！
            writeStreamingGitHubPayload(zipFile, payloadJsonFile, remoteFileName);
            uploadPayloadFileToGitHub(remoteFileName, payloadJsonFile);
        } finally {
            // 无论上传成功或异常中断，必定清理本地生成的临时压缩包与 JSON 载荷文件
            if (zipFile.exists()) {
                zipFile.delete();
            }
            if (payloadJsonFile.exists()) {
                payloadJsonFile.delete();
            }
        }

        // 🌟 新增：上传成功后，异步触发旧备份清理逻辑，不阻塞主线程
        Thread.ofVirtual().name("github-cleanup-thread").start(this::cleanUpOldBackups);
    }

    private void writeStreamingGitHubPayload(File zipFile, File payloadJsonFile, String remoteFileName) throws Exception {
        byte[] prefix = ("{\n  \"message\": \"自动备份 OCI Manager 数据和私钥: " + remoteFileName + "\",\n  \"content\": \"").getBytes(StandardCharsets.UTF_8);
        byte[] suffix = "\"\n}".getBytes(StandardCharsets.UTF_8);

        try (OutputStream fos = new BufferedOutputStream(new FileOutputStream(payloadJsonFile), 16384)) {
            try {
                Files.setPosixFilePermissions(payloadJsonFile.toPath(), java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
            } catch (Exception ignored) {}
            fos.write(prefix);
            OutputStream b64Out = Base64.getEncoder().wrap(new java.io.FilterOutputStream(fos) {
                @Override
                public void close() throws java.io.IOException {
                    flush(); // 仅刷出 Base64 尾部填充，不提前关闭底层 fos
                }
            });
            try (InputStream fis = new FileInputStream(zipFile)) {
                byte[] buf = new byte[8192];
                int read;
                while ((read = fis.read(buf)) != -1) {
                    b64Out.write(buf, 0, read);
                }
            }
            b64Out.close();
            fos.write(suffix);
        }
    }

    private void uploadPayloadFileToGitHub(String fileName, File payloadJsonFile) throws Exception {
        String apiUrl = "https://api.github.com/repos/" + githubRepo + "/contents/" + fileName;

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(apiUrl))
                .timeout(Duration.ofSeconds(120)) // 读写超时设为 120 秒
                .header("Authorization", "Bearer " + githubToken)
                .header("Accept", "application/vnd.github.v3+json")
                .PUT(HttpRequest.BodyPublishers.ofFile(payloadJsonFile.toPath()))
                .build();

        HttpResponse<String> response = GITHUB_HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() >= 300) {
            throw new RuntimeException("GitHub 上传遭遇异常 (HTTP " + response.statusCode() + "): " + response.body());
        } else {
            System.out.println("✅ 成功推送到 GitHub 仓库: " + fileName);
        }
    }

    /**
     * 🌟 新增：拉取仓库文件列表，保留最近的 5 个备份，删除其余旧备份
     */
    private void cleanUpOldBackups() {
        try {
            System.out.println("🧹 正在检查并清理旧备份，系统设定最多保留 " + MAX_BACKUPS + " 个备份文件...");
            String apiUrl = "https://api.github.com/repos/" + githubRepo + "/contents/";

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(apiUrl))
                    .timeout(Duration.ofSeconds(30))
                    .header("Authorization", "Bearer " + githubToken)
                    .header("Accept", "application/vnd.github.v3+json")
                    .GET()
                    .build();

            HttpResponse<String> response = GITHUB_HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                JsonNode root = objectMapper.readTree(response.body());
                List<JsonNode> backupNodes = new ArrayList<>();

                // 筛选出所有的 oci_backup_*.zip 文件
                for (JsonNode node : root) {
                    if (node.has("name")) {
                        String name = node.get("name").asText();
                        if (name.startsWith("oci_backup_") && name.endsWith(".zip")) {
                            backupNodes.add(node);
                        }
                    }
                }

                // 按文件名倒序排列 (名字包含时间戳，倒序后即为最新文件排在最前面)
                backupNodes.sort((n1, n2) -> n2.get("name").asText().compareTo(n1.get("name").asText()));

                // 如果文件数量超过了最大保留数，就开始执行删除操作
                if (backupNodes.size() > MAX_BACKUPS) {
                    System.out.println("🔍 发现共有 " + backupNodes.size() + " 个备份，准备删除 " + (backupNodes.size() - MAX_BACKUPS) + " 个旧文件...");
                    
                    for (int i = MAX_BACKUPS; i < backupNodes.size(); i++) {
                        JsonNode oldNode = backupNodes.get(i);
                        String fileName = oldNode.get("name").asText();
                        String fileSha = oldNode.get("sha").asText();
                        deleteFromGitHub(fileName, fileSha);
                        // 轻微休眠，防止触发 GitHub API 的并发限流机制
                        Thread.sleep(1000);
                    }
                } else {
                    System.out.println("✅ 当前 GitHub 仓库中仅有 " + backupNodes.size() + " 个备份文件，无需清理。");
                }
            } else {
                System.err.println("⚠️ 获取 GitHub 文件列表失败 (HTTP " + response.statusCode() + "): " + response.body());
            }
        } catch (Exception e) {
            System.err.println("❌ 清理旧备份时发生严重异常: " + e.getMessage());
        }
    }

    /**
     * 🌟 新增：调用 GitHub API 物理删除指定文件
     */
    private void deleteFromGitHub(String fileName, String sha) {
        try {
            String apiUrl = "https://api.github.com/repos/" + githubRepo + "/contents/" + fileName;
            String jsonBody = "{\n" +
                    "  \"message\": \"自动清理过期备份: " + fileName + "\",\n" +
                    "  \"sha\": \"" + sha + "\"\n" +
                    "}";

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(apiUrl))
                    .timeout(Duration.ofSeconds(30))
                    .header("Authorization", "Bearer " + githubToken)
                    .header("Accept", "application/vnd.github.v3+json")
                    .method("DELETE", HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build();

            HttpResponse<String> response = GITHUB_HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() >= 300) {
                System.err.println("⚠️ 删除旧备份 [" + fileName + "] 失败 (HTTP " + response.statusCode() + "): " + response.body());
            } else {
                System.out.println("🗑️ 已成功从 GitHub 永久删除过期的旧备份: " + fileName);
            }
        } catch (Exception e) {
            System.err.println("❌ 删除旧备份 [" + fileName + "] 时发生网络异常: " + e.getMessage());
        }
    }
}
