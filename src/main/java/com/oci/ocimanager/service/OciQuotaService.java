package com.oci.ocimanager.service;

import com.oci.ocimanager.config.OciProperties;
import com.oci.ocimanager.entity.SystemConfig;
import com.oci.ocimanager.repository.SystemConfigRepository;
import com.oracle.bmc.limits.model.CreateQuotaDetails;
import com.oracle.bmc.limits.model.QuotaSummary;
import com.oracle.bmc.limits.requests.CreateQuotaRequest;
import com.oracle.bmc.limits.requests.DeleteQuotaRequest;
import com.oracle.bmc.limits.requests.ListQuotasRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

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
        try {
            SystemConfig config = new SystemConfig("QUOTA_TEMPLATE", template);
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
                    } catch (Exception e) {
                        System.err.println("清理配额策略时发生异常: " + e.getMessage());
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

        // 注入前先清理旧的同名策略
        deleteFreeTierQuota(accountName);

        String template = getQuotaTemplate();
        String processedTemplate = template.replace("{region}", region);

        List<String> statements = Arrays.stream(processedTemplate.split("\n"))
                .map(s -> s.trim())
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());

        if (statements.isEmpty()) {
            throw new IllegalArgumentException("配额策略模板为空，注入失败！请在管理面板检查模板配置。");
        }

        CreateQuotaDetails details = CreateQuotaDetails.builder()
                .compartmentId(accConfig.getTenancy())
                .name("Strict_Always_Free_Quota")
                .description("Force tenancy to use only free tier resources in " + region + " and block everything else. (Managed by OCI-Manager)")
                .statements(statements)
                .build();

        var clients = clientManager.getOciClients(accountName);
        try {
            clients.getQuotas().createQuota(CreateQuotaRequest.builder()
                    .createQuotaDetails(details)
                    .build());
        } finally {
            clients.releaseLowFrequencyClients();
        }
    }
}
