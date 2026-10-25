package com.oci.ocimanager.service;

import java.util.Map;

/**
 * 区域解析工具：从 OCID / 实例缓存推导资源所属 region。
 * 结构重构自 OciCloudService（门面保留原实例方法并委托到此处）。
 */
final class OciRegionUtil {

    private OciRegionUtil() {}

    // 🌟 Helper method to extract region directly from any OCI OCID (e.g. instance, volume, attachment)
    static String extractRegionFromOcid(String ocid) {
        if (ocid != null && ocid.contains(".")) {
            String[] parts = ocid.split("\\.");
            if (parts.length >= 4 && !parts[3].isEmpty()) {
                String rawRegion = parts[3];
                try {
                    com.oracle.bmc.Region r = com.oracle.bmc.Region.fromRegionCodeOrId(rawRegion);
                    if (r != null && r.getRegionId() != null) {
                        return r.getRegionId();
                    }
                } catch (Exception ignored) {}
                return rawRegion;
            }
        }
        return null;
    }

    static String getRegionForResource(String ocid) {
        if (ocid != null && OciCloudService.INSTANCE_CACHE.containsKey(ocid)) {
            Map<String, String> info = OciCloudService.INSTANCE_CACHE.get(ocid);
            if (info != null && info.get("region") != null && !info.get("region").isBlank()) {
                return info.get("region");
            }
        }
        return extractRegionFromOcid(ocid);
    }
}
