package com.oci.ocimanager.service;

import com.oci.ocimanager.config.OciProperties;
import com.oci.ocimanager.entity.SystemConfig;
import com.oci.ocimanager.repository.SystemConfigRepository;
import com.oracle.bmc.limits.model.CreateQuotaDetails;
import com.oracle.bmc.limits.model.QuotaSummary;
import com.oracle.bmc.limits.model.UpdateQuotaDetails;
import com.oracle.bmc.limits.requests.CreateQuotaRequest;
import com.oracle.bmc.limits.requests.DeleteQuotaRequest;
import com.oracle.bmc.limits.requests.ListQuotasRequest;
import com.oracle.bmc.limits.requests.UpdateQuotaRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 配额域服务：免费层配额策略的注入/删除与配额模板的读写。
 * 结构重构自 OciCloudService（门面保留原对外方法并委托到此处）。
 */
@Service
public class OciQuotaService {

    @Autowired
    private OciProperties ociProperties;

    @Autowired
    private SystemConfigRepository systemConfigRepository;

    @Autowired
    private OciClientManagerService clientManager;

    // 🌟 默认配额模板 (已修复由于部分区域淘汰 E3、淘汰functions 及细粒度网络限制导致的 InvalidParameter 400 报错)
    private static final String DEFAULT_QUOTA_TEMPLATE =
            "zero compute-core quota standard-e4-core-count in tenancy\n" +
            "zero compute-core quota standard-e5-core-count in tenancy\n" +
            "zero compute-core quota gpu2-count in tenancy\n" +
            "zero compute-core quota gpu3-count in tenancy\n" +
            "zero compute-core quota gpu4-count in tenancy\n" +
            "zero compute-management quotas in tenancy\n" +
            "set compute-core quota standard-a1-core-count to 4 in tenancy where request.region = '{region}'\n" +
            "set compute-core quota standard-a1-core-regional-count to 4 in tenancy where request.region = '{region}'\n" +
            "set compute-memory quota standard-a1-memory-count to 24 in tenancy where request.region = '{region}'\n" +
            "set compute-memory quota standard-a1-memory-regional-count to 24 in tenancy where request.region = '{region}'\n" +
            "zero block-storage quotas in tenancy\n" +
            "set block-storage quota total-storage-gb to 200 in tenancy where request.region = '{region}'\n" +
            "set block-storage quota backup-count to 5 in tenancy where request.region = '{region}'\n" +
            "zero vcn quota reserved-public-ip-count in tenancy\n" +
            "zero load-balancer quotas in tenancy\n" +
            "zero object-storage quota storage-bytes in tenancy\n" +
            "zero email-delivery quotas in tenancy\n" +
            "zero dns quotas in tenancy\n" +
            "zero health-checks quotas in tenancy\n" +
            "zero database quotas in tenancy\n" +
            "zero analytics quotas in tenancy\n" +
            "zero streaming quotas in tenancy";

    private static final Pattern QUOTA_STATEMENT_PATTERN = Pattern.compile(
            "(?i)^(zero|set|unset)\\s+[a-z0-9_.-]+\\s+(quota|quotas)\\b.*\\bin\\s+(tenancy|compartment)\\b.*$");

    public static List<String> validateAndParseQuotaStatements(String template, String region) {
        if (template == null || template.trim().isEmpty()) {
            throw new IllegalArgumentException("配额策略模板为空，请填写至少一条合法的 OCI Quota 语句");
        }
        String effectiveRegion = (region != null && !region.trim().isEmpty()) ? region.trim() : "ap-tokyo-1";
        String processed = template.replace("{region}", effectiveRegion);
        String[] rawLines = processed.split("\\r?\\n");
        List<String> statements = new ArrayList<>();
        for (int i = 0; i < rawLines.length; i++) {
            String line = rawLines[i].trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            if (line.length() > 512) {
                throw new IllegalArgumentException("配额模板第 " + (i + 1) + " 行超出最大长度限制 (512 字符)");
            }
            for (int cIdx = 0; cIdx < line.length(); cIdx++) {
                char c = line.charAt(cIdx);
                if (c < 0x20 || c == 0x7F) {
                    throw new IllegalArgumentException("配额模板第 " + (i + 1) + " 行包含非法控制字符");
                }
            }
            if (!QUOTA_STATEMENT_PATTERN.matcher(line).matches()) {
                throw new IllegalArgumentException("配额模板第 " + (i + 1) + " 行语法不合法 (需以 zero/set/unset <family> quota(s) ... in tenancy/compartment 开头): " + line);
            }
            statements.add(line);
        }
        if (statements.isEmpty()) {
            throw new IllegalArgumentException("配额策略模板不包含任何有效策略语句");
        }
        return statements;
    }

    public String getQuotaTemplate() {
        try {
            return systemConfigRepository.findById("QUOTA_TEMPLATE")
                    .map(cfg -> cfg.getConfigValue())
                    .orElse(DEFAULT_QUOTA_TEMPLATE);
        } catch (Exception e) {
            System.err.println("从数据库读取配额模板失败，将回退到默认模板: " + e.getMessage());
            return DEFAULT_QUOTA_TEMPLATE;
        }
    }

    public void saveQuotaTemplate(String template) {
        validateAndParseQuotaStatements(template, "ap-tokyo-1");
        try {
            SystemConfig config = new SystemConfig("QUOTA_TEMPLATE", template.trim());
            systemConfigRepository.saveAndFlush(config);
        } catch (Exception e) {
            System.err.println("保存配额模板到数据库失败: " + e.getMessage());
            throw new RuntimeException("写入数据库失败", e);
        }
    }

    public void deleteFreeTierQuota(String accountName) throws Exception {
        OciProperties.Account accConfig = ociProperties.getAccounts().get(accountName);
        if (accConfig == null) {
            throw new IllegalArgumentException("账号不存在");
        }
        var clients = clientManager.getOciClients(accountName);
        String tenancy = accConfig.getTenancy();

        try {
            var listResponse = clients.getQuotas().listQuotas(ListQuotasRequest.builder()
                    .compartmentId(tenancy)
                    .name("Strict_Always_Free_Quota")
                    .build());

            boolean deletedAny = false;
            for (QuotaSummary q : listResponse.getItems()) {
                if (q.getLifecycleState() != null && !"DELETED".equalsIgnoreCase(q.getLifecycleState().getValue())) {
                    try {
                        clients.getQuotas().deleteQuota(DeleteQuotaRequest.builder()
                                .quotaId(q.getId())
                                .build());
                        deletedAny = true;
                    } catch (com.oracle.bmc.model.BmcException bmcEx) {
                        if (bmcEx.getStatusCode() != 404) {
                            throw bmcEx;
                        }
                    }
                }
            }

            if (deletedAny) {
                Thread.sleep(2000);
            }
        } finally {
            clients.releaseLowFrequencyClients();
        }
    }

    public void injectFreeTierQuota(String accountName) throws Exception {
        OciProperties.Account accConfig = ociProperties.getAccounts().get(accountName);
        if (accConfig == null) {
            throw new IllegalArgumentException("账号不存在");
        }

        String region = accConfig.getRegion();
        if (region == null || region.trim().isEmpty()) {
            throw new IllegalArgumentException("该账号未配置区域(region)，无法生成区域绑定配额。");
        }
        region = region.trim();

        // 🔒 先在本地严格校验配额模板语句，绝不在校验通过前触碰或删除云端已有防扣费配额锁！
        List<String> statements = validateAndParseQuotaStatements(getQuotaTemplate(), region);
        String description = "Force tenancy to use only free tier resources in " + region + " and block everything else. (Managed by OCI-Manager)";
        String tenancy = accConfig.getTenancy();

        var clients = clientManager.getOciClients(accountName);
        try {
            var listResponse = clients.getQuotas().listQuotas(ListQuotasRequest.builder()
                    .compartmentId(tenancy)
                    .name("Strict_Always_Free_Quota")
                    .build());

            List<QuotaSummary> activeQuotas = new ArrayList<>();
            if (listResponse != null && listResponse.getItems() != null) {
                for (QuotaSummary q : listResponse.getItems()) {
                    if (q != null && q.getId() != null && q.getLifecycleState() != null
                            && !"DELETED".equalsIgnoreCase(q.getLifecycleState().getValue())) {
                        activeQuotas.add(q);
                    }
                }
            }

            if (!activeQuotas.isEmpty()) {
                // 🌟 原位原子更新已有防扣费配额锁：若新语句被 OCI 拒绝 (400 InvalidParameter)，原配额锁保持完好无损！
                QuotaSummary primary = activeQuotas.get(0);
                clients.getQuotas().updateQuota(UpdateQuotaRequest.builder()
                        .quotaId(primary.getId())
                        .updateQuotaDetails(UpdateQuotaDetails.builder()
                                .description(description)
                                .statements(statements)
                                .build())
                        .build());

                for (int i = 1; i < activeQuotas.size(); i++) {
                    try {
                        clients.getQuotas().deleteQuota(DeleteQuotaRequest.builder()
                                .quotaId(activeQuotas.get(i).getId())
                                .build());
                    } catch (Exception ignored) {}
                }
            } else {
                CreateQuotaDetails details = CreateQuotaDetails.builder()
                        .compartmentId(tenancy)
                        .name("Strict_Always_Free_Quota")
                        .description(description)
                        .statements(statements)
                        .build();
                clients.getQuotas().createQuota(CreateQuotaRequest.builder()
                        .createQuotaDetails(details)
                        .build());
            }
        } finally {
            clients.releaseLowFrequencyClients();
        }
    }
}
