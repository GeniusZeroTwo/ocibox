package com.oci.ocimanager.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.oci.ocimanager.config.OciProperties;
import com.oracle.bmc.core.model.*;
import com.oracle.bmc.core.requests.*;
import com.oracle.bmc.identity.model.AvailabilityDomain;
import com.oracle.bmc.identity.requests.ListAvailabilityDomainsRequest;
import org.springframework.beans.factory.annotation.Autowired;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 启动盘域服务：引导卷列表（含 Redis 缓存）、分离/删除/扩容。
 * 结构重构自 OciCloudService（门面保留原对外方法并委托到此处）。
 */
@Service
public class OciBootVolumeService {

    @Autowired
    private OciProperties ociProperties;

    @Autowired
    private LocalCacheService redisTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private OciClientManagerService clientManager;

    public List<Map<String, String>> getBootVolumes(String accountName) throws Exception {
        String json = redisTemplate.opsForValue().get("oci:bv_cache:" + accountName);
        if (json != null) {
            return objectMapper.readValue(json, new TypeReference<List<Map<String, String>>>(){});
        }

        List<Map<String, String>> fresh = fetchBootVolumeData(accountName);
        redisTemplate.opsForValue().set("oci:bv_cache:" + accountName, objectMapper.writeValueAsString(fresh), 3, TimeUnit.MINUTES);
        return fresh;
    }

    List<Map<String, String>> fetchBootVolumeData(String accountName) throws Exception {
        OciProperties.Account accConfig = ociProperties.getAccounts().get(accountName);
        var clients = clientManager.getOciClients(accountName);
        String tenancy = accConfig.getTenancy();

        List<Map<String, String>> result = new ArrayList<>();
        java.util.List<String> regions = clientManager.getSubscribedRegions(accountName);

        for (String region : regions) {
            try {
                var identityClient = clients.getIdentityClientForRegion(region);
                var computeClient = clients.getComputeClientForRegion(region);
                var blockstorageClient = clients.getBlockstorageClientForRegion(region);

                var adRes = identityClient.listAvailabilityDomains(
                        ListAvailabilityDomainsRequest.builder().compartmentId(tenancy).build()
                );

                for (AvailabilityDomain ad : adRes.getItems()) {
                    var attachmentsRes = computeClient.listBootVolumeAttachments(
                            ListBootVolumeAttachmentsRequest.builder()
                                    .compartmentId(tenancy)
                                    .availabilityDomain(ad.getName())
                                    .build()
                    );

                    Map<String, BootVolumeAttachment> attachmentMap = new HashMap<>();
                    for (BootVolumeAttachment att : attachmentsRes.getItems()) {
                        if (att.getLifecycleState() != BootVolumeAttachment.LifecycleState.Detached) {
                            attachmentMap.put(att.getBootVolumeId(), att);
                        }
                    }

                    var bvRes = blockstorageClient.listBootVolumes(
                            ListBootVolumesRequest.builder()
                                    .compartmentId(tenancy)
                                    .availabilityDomain(ad.getName())
                                    .build()
                    );

                    for (BootVolume bv : bvRes.getItems()) {
                        if (bv.getLifecycleState() == BootVolume.LifecycleState.Terminated) {
                            continue;
                        }

                        Map<String, String> map = new HashMap<>();
                        map.put("id", bv.getId());
                        map.put("name", bv.getDisplayName());
                        map.put("state", bv.getLifecycleState().getValue());
                        map.put("size", String.valueOf(bv.getSizeInGBs()));
                        map.put("ad", ad.getName());
                        map.put("region", region);

                        BootVolumeAttachment att = attachmentMap.get(bv.getId());
                        if (att != null) {
                            map.put("attachmentId", att.getId());
                            map.put("instanceId", att.getInstanceId());
                            String instName = "未知实例或已被删除";
                            if (OciCloudService.INSTANCE_CACHE.containsKey(att.getInstanceId())) {
                                instName = OciCloudService.INSTANCE_CACHE.get(att.getInstanceId()).get("name");
                            }
                            map.put("attachedTo", instName);
                        } else {
                            map.put("attachedTo", "");
                        }
                        result.add(map);
                    }
                }
            } catch (Exception e) {
                System.err.println("获取区域引导卷失败 [" + accountName + " - " + region + "]: " + e.getMessage());
            }
        }
        return result;
    }

    /**
     * 🧹 清理指定账号下所有未挂载的引导卷（状态为 AVAILABLE 且无挂载实例），
     * 用于在重装系统前释放 bootVolume 配额，避免 QuotaExceeded 错误。
     *
     * @return 成功删除的引导卷数量
     */
    public int cleanUnattachedBootVolumes(String accountName) {
        int deleted = 0;
        try {
            // 强制刷新，不要用缓存
            List<Map<String, String>> bootVolumes = fetchBootVolumeData(accountName);
            for (Map<String, String> bv : bootVolumes) {
                String attachedTo = bv.get("attachedTo");
                String state = bv.get("state");
                // 未挂载到任何实例 且 状态为 AVAILABLE 的引导卷是孤立卷，可以安全删除
                if ((attachedTo == null || attachedTo.isEmpty()) && "AVAILABLE".equalsIgnoreCase(state)) {
                    String bvId = bv.get("id");
                    System.out.println("🧹 [重装前清理] 正在删除未挂载的引导卷: " + bvId + " (" + bv.get("name") + ", " + bv.get("size") + "GB)");
                    boolean ok = deleteBootVolume(bvId, accountName);
                    if (ok) {
                        deleted++;
                        System.out.println("✅ [重装前清理] 成功删除引导卷: " + bvId);
                    } else {
                        System.err.println("⚠️ [重装前清理] 删除引导卷失败: " + bvId);
                    }
                }
            }
            if (deleted > 0) {
                invalidateBootVolumeCache(accountName);
            }
        } catch (Exception e) {
            System.err.println("⚠️ [重装前清理] 清理未挂载引导卷时异常: " + e.getMessage());
        }
        return deleted;
    }

    public void invalidateBootVolumeCache(String accountName) {
        redisTemplate.delete("oci:bv_cache:" + accountName);
    }

    public boolean detachBootVolume(String attachmentId, String accountName) {
        try {
            var clients = clientManager.getOciClients(accountName);
            String region = OciRegionUtil.getRegionForResource(attachmentId);
            var computeClient = region != null ? clients.getComputeClientForRegion(region) : clients.getCompute();
            computeClient.detachBootVolume(DetachBootVolumeRequest.builder()
                    .bootVolumeAttachmentId(attachmentId)
                    .build());
            invalidateBootVolumeCache(accountName);
            return true;
        } catch (com.oracle.bmc.model.BmcException e) {
            if (e.getStatusCode() == 409 && e.getMessage() != null && e.getMessage().contains("Terminated state")) {
                System.out.println("实例已被终止，引导卷已自动分离。视作分离成功。");
                invalidateBootVolumeCache(accountName);
                return true;
            }
            System.err.println("分离引导卷失败(API异常): " + e.getMessage());
            return false;
        } catch (Exception e) {
            System.err.println("分离引导卷失败: " + e.getMessage());
            return false;
        }
    }

    public boolean deleteBootVolume(String bootVolumeId, String accountName) {
        try {
            var clients = clientManager.getOciClients(accountName);
            String region = OciRegionUtil.getRegionForResource(bootVolumeId);
            var blockClient = region != null ? clients.getBlockstorageClientForRegion(region) : clients.getBlockstorage();
            blockClient.deleteBootVolume(DeleteBootVolumeRequest.builder()
                    .bootVolumeId(bootVolumeId)
                    .build());
            invalidateBootVolumeCache(accountName);
            return true;
        } catch (Exception e) {
            System.err.println("删除引导卷失败: " + e.getMessage());
            return false;
        }
    }

    public boolean expandBootVolume(String bootVolumeId, Long sizeInGBs, String accountName) {
        try {
            var clients = clientManager.getOciClients(accountName);
            String region = OciRegionUtil.getRegionForResource(bootVolumeId);
            var blockClient = region != null ? clients.getBlockstorageClientForRegion(region) : clients.getBlockstorage();
            blockClient.updateBootVolume(UpdateBootVolumeRequest.builder()
                    .bootVolumeId(bootVolumeId)
                    .updateBootVolumeDetails(UpdateBootVolumeDetails.builder()
                            .sizeInGBs(sizeInGBs)
                            .build())
                    .build());
            invalidateBootVolumeCache(accountName);
            return true;
        } catch (Exception e) {
            System.err.println("扩容引导卷失败: " + e.getMessage());
            return false;
        }
    }
}
