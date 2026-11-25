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

    private static final Map<String, String> REGION_DISPLAY_MAP = Map.ofEntries(
            Map.entry("ap-tokyo-1", "日本 东京"),
            Map.entry("ap-osaka-1", "日本 大阪"),
            Map.entry("ap-seoul-1", "韩国 首尔"),
            Map.entry("ap-chuncheon-1", "韩国 春川"),
            Map.entry("ap-singapore-1", "新加坡"),
            Map.entry("ap-singapore-2", "新加坡 西"),
            Map.entry("ap-batam-1", "印尼 巴淡岛"),
            Map.entry("ap-mumbai-1", "印度 孟买"),
            Map.entry("ap-hyderabad-1", "印度 海得拉巴"),
            Map.entry("ap-sydney-1", "澳大利亚 悉尼"),
            Map.entry("ap-melbourne-1", "澳大利亚 墨尔本"),
            Map.entry("us-sanjose-1", "美国 圣何塞"),
            Map.entry("us-phoenix-1", "美国 凤凰城"),
            Map.entry("us-ashburn-1", "美国 阿什本"),
            Map.entry("us-chicago-1", "美国 芝加哥"),
            Map.entry("ca-toronto-1", "加拿大 多伦多"),
            Map.entry("ca-montreal-1", "加拿大 蒙特利尔"),
            Map.entry("mx-queretaro-1", "墨西哥 克雷塔罗"),
            Map.entry("mx-monterrey-1", "墨西哥 蒙特雷"),
            Map.entry("sa-saopaulo-1", "巴西 圣保罗"),
            Map.entry("sa-vinhedo-1", "巴西 维涅杜"),
            Map.entry("sa-santiago-1", "智利 圣地亚哥"),
            Map.entry("sa-valparaiso-1", "智利 瓦尔帕莱索"),
            Map.entry("sa-bogota-1", "哥伦比亚 波哥大"),
            Map.entry("eu-frankfurt-1", "德国 法兰克福"),
            Map.entry("uk-london-1", "英国 伦敦"),
            Map.entry("uk-cardiff-1", "英国 卡迪夫"),
            Map.entry("eu-amsterdam-1", "荷兰 阿姆斯特丹"),
            Map.entry("eu-paris-1", "法国 巴黎"),
            Map.entry("eu-marseille-1", "法国 马赛"),
            Map.entry("eu-milan-1", "意大利 米兰"),
            Map.entry("eu-turin-1", "意大利 都灵"),
            Map.entry("eu-zurich-1", "瑞士 苏黎世"),
            Map.entry("eu-stockholm-1", "瑞典 斯德哥尔摩"),
            Map.entry("eu-madrid-1", "西班牙 马德里"),
            Map.entry("eu-madrid-2", "西班牙 马德里北"),
            Map.entry("me-dubai-1", "阿联酋 迪拜"),
            Map.entry("me-abudhabi-1", "阿联酋 阿布扎比"),
            Map.entry("me-jeddah-1", "沙特 吉达"),
            Map.entry("me-riyadh-1", "沙特 利雅得"),
            Map.entry("il-jerusalem-1", "以色列 耶路撒冷"),
            Map.entry("af-johannesburg-1", "南非 约翰内斯堡")
    );

    static String getRegionForResource(String ocid) {
        if (ocid != null && OciCloudService.INSTANCE_CACHE.containsKey(ocid)) {
            Map<String, String> info = OciCloudService.INSTANCE_CACHE.get(ocid);
            if (info != null && info.get("region") != null && !info.get("region").isBlank()) {
                return info.get("region");
            }
        }
        return extractRegionFromOcid(ocid);
    }

    static String getRegionDisplayLocation(String region) {
        if (region == null || region.isBlank()) {
            return "未知地区";
        }
        String normalized = region.trim().toLowerCase(java.util.Locale.ROOT);
        String direct = REGION_DISPLAY_MAP.get(normalized);
        if (direct != null) {
            return direct;
        }
        try {
            com.oracle.bmc.Region r = com.oracle.bmc.Region.fromRegionCodeOrId(region.trim());
            if (r != null && r.getRegionId() != null) {
                String canonical = r.getRegionId().toLowerCase(java.util.Locale.ROOT);
                String mapped = REGION_DISPLAY_MAP.get(canonical);
                if (mapped != null) {
                    return mapped;
                }
                return r.getRegionId();
            }
        } catch (Exception ignored) {}
        return region.trim();
    }
}
