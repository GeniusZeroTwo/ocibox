package com.oci.ocimanager.service;

import com.oci.ocimanager.config.OciProperties;
import com.oracle.bmc.core.VirtualNetworkClient;
import com.oracle.bmc.core.model.*;
import com.oracle.bmc.core.requests.*;
import com.oracle.bmc.networkloadbalancer.model.*;
import com.oracle.bmc.networkloadbalancer.requests.*;
import org.springframework.beans.factory.annotation.Autowired;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 网络域服务：防火墙端口放行、子网/IGW/路由自愈、实例公网 IP 查询与更换、IP 归属地、AMD 500Mbps NAT/NLB。
 * 结构重构自 OciCloudService（门面保留原对外方法并委托到此处）。
 */
@Service
public class OciNetworkService {

    @Autowired
    private OciProperties ociProperties;

    @Autowired
    private LocalCacheService redisTemplate;

    @Autowired
    private OciClientManagerService clientManager;

    @Autowired
    private OciClientFingerprintService fingerprintService;

    private String resolveInstanceCompartmentId(String ocid, String fallbackTenancy) {
        if (ocid != null) {
            Map<String, String> cached = OciCloudService.INSTANCE_CACHE.get(ocid);
            if (cached != null) {
                String compId = cached.get("compartmentId");
                if (compId != null && !compId.isBlank()) {
                    return compId;
                }
            }
        }
        return fallbackTenancy;
    }

    public void fixRouteTables(String accountName) throws Exception {
        OciProperties.Account accConfig = ociProperties.getAccounts().get(accountName);
        if (accConfig == null) {
            throw new IllegalArgumentException("账号不存在: " + accountName);
        }

        var clients = clientManager.getOciClients(accountName);
        String tenancy = accConfig.getTenancy();
        List<String> regions = clientManager.getSubscribedRegions(accountName);
        List<String> compIds = clientManager.getActiveCompartmentIds(accountName);
        if (compIds.isEmpty() && tenancy != null) {
            compIds = List.of(tenancy);
        }

        for (String region : regions) {
            var vncClient = clients.getVncClientForRegion(region);
            for (String compId : compIds) {
                try {
                    var vcnRes = vncClient.listVcns(ListVcnsRequest.builder().compartmentId(compId).build());
                    for (Vcn vcn : vcnRes.getItems()) {
                        if (vcn.getLifecycleState() != Vcn.LifecycleState.Available) continue;

                        String vcnId = vcn.getId();
                        String routeTableId = vcn.getDefaultRouteTableId();

                        try {
                            var igws = vncClient.listInternetGateways(ListInternetGatewaysRequest.builder()
                                    .compartmentId(compId).vcnId(vcnId).build()).getItems();
                            String igwId = null;
                            for (var igwCandidate : igws) {
                                if (igwCandidate.getLifecycleState() == InternetGateway.LifecycleState.Available
                                        && Boolean.TRUE.equals(igwCandidate.getIsEnabled())) {
                                    igwId = igwCandidate.getId();
                                    break;
                                }
                            }
                            if (igwId == null) {
                                String igwName = fingerprintService.getAccountIgwName(accountName, tenancy);
                                InternetGateway igw = vncClient.createInternetGateway(CreateInternetGatewayRequest.builder()
                                        .createInternetGatewayDetails(CreateInternetGatewayDetails.builder()
                                                .compartmentId(compId).vcnId(vcnId).isEnabled(true).displayName(igwName).build())
                                        .build()).getInternetGateway();
                                igwId = igw.getId();
                            }

                            if (routeTableId != null) {
                                var rt = vncClient.getRouteTable(GetRouteTableRequest.builder().rtId(routeTableId).build()).getRouteTable();
                                // 跳过 500M 专用 NAT 路由表，防止破坏高速转发通道
                                if (rt.getDisplayName() != null && "nat-route-500m".equalsIgnoreCase(rt.getDisplayName())) {
                                    continue;
                                }
                                List<RouteRule> rules = new java.util.ArrayList<>();
                                if (rt.getRouteRules() != null) rules.addAll(rt.getRouteRules());

                                final String finalIgwId = igwId;
                                boolean hasCorrectDefaultRoute = rules.stream().anyMatch(r ->
                                        ("0.0.0.0/0".equals(r.getDestination()) || "0.0.0.0/0".equals(r.getCidrBlock()))
                                                && finalIgwId.equals(r.getNetworkEntityId())
                                );

                                if (!hasCorrectDefaultRoute) {
                                    rules.removeIf(r -> "0.0.0.0/0".equals(r.getDestination()) || "0.0.0.0/0".equals(r.getCidrBlock()));
                                    rules.add(RouteRule.builder()
                                            .networkEntityId(finalIgwId)
                                            .destination("0.0.0.0/0")
                                            .destinationType(RouteRule.DestinationType.CidrBlock)
                                            .build());

                                    vncClient.updateRouteTable(UpdateRouteTableRequest.builder().rtId(routeTableId)
                                            .updateRouteTableDetails(UpdateRouteTableDetails.builder().routeRules(rules).build()).build());
                                }
                            }
                        } catch (Exception e) {
                            System.err.println("修复 VCN [" + region + " / " + vcnId + "] 失败 - " + e.getMessage());
                        }
                    }
                } catch (Exception regionEx) {
                    System.err.println("扫描区段路由失败 [" + accountName + " - " + region + " - " + compId + "]: " + regionEx.getMessage());
                }
            }
        }
    }

    public void openAllPorts(String accountName) throws Exception {
        OciProperties.Account accConfig = ociProperties.getAccounts().get(accountName);
        if (accConfig == null) {
            throw new IllegalArgumentException("账号不存在: " + accountName);
        }
        var clients = clientManager.getOciClients(accountName);
        String tenancy = accConfig.getTenancy();

        List<String> regions = clientManager.getSubscribedRegions(accountName);
        List<String> compIds = clientManager.getActiveCompartmentIds(accountName);
        if (compIds.isEmpty() && tenancy != null) {
            compIds = List.of(tenancy);
        }

        int updatedCount = 0;
        List<String> errorList = new ArrayList<>();

        for (String region : regions) {
            var vncClient = clients.getVncClientForRegion(region);
            for (String compId : compIds) {
                try {
                    var vcnRes = vncClient.listVcns(ListVcnsRequest.builder().compartmentId(compId).build());
                    for (Vcn vcn : vcnRes.getItems()) {
                        if (vcn.getLifecycleState() == Vcn.LifecycleState.Terminated || vcn.getLifecycleState() == Vcn.LifecycleState.Terminating) {
                            continue;
                        }

                        var slRes = vncClient.listSecurityLists(ListSecurityListsRequest.builder()
                                .compartmentId(compId).vcnId(vcn.getId()).build());

                        for (SecurityList sl : slRes.getItems()) {
                            if (sl.getLifecycleState() == SecurityList.LifecycleState.Terminated || sl.getLifecycleState() == SecurityList.LifecycleState.Terminating) {
                                continue;
                            }

                            // 🔒 保留并合并原有自定义安全规则，仅增量补齐全端口入站/出站规则，绝不清空客户既有精细化白名单
                            List<IngressSecurityRule> ingressRules = new ArrayList<>();
                            if (sl.getIngressSecurityRules() != null) {
                                ingressRules.addAll(sl.getIngressSecurityRules());
                            }
                            boolean hasIpv4AllIn = ingressRules.stream().anyMatch(r ->
                                    "0.0.0.0/0".equals(r.getSource()) && "all".equalsIgnoreCase(r.getProtocol()));
                            if (!hasIpv4AllIn) {
                                ingressRules.add(IngressSecurityRule.builder()
                                        .source("0.0.0.0/0")
                                        .protocol("all")
                                        .isStateless(false)
                                        .build());
                            }
                            boolean hasIpv6AllIn = ingressRules.stream().anyMatch(r ->
                                    "::/0".equals(r.getSource()) && "all".equalsIgnoreCase(r.getProtocol()));
                            if (!hasIpv6AllIn) {
                                ingressRules.add(IngressSecurityRule.builder()
                                        .source("::/0")
                                        .protocol("all")
                                        .isStateless(false)
                                        .build());
                            }

                            List<EgressSecurityRule> egressRules = new ArrayList<>();
                            if (sl.getEgressSecurityRules() != null) {
                                egressRules.addAll(sl.getEgressSecurityRules());
                            }
                            boolean hasIpv4AllEg = egressRules.stream().anyMatch(r ->
                                    "0.0.0.0/0".equals(r.getDestination()) && "all".equalsIgnoreCase(r.getProtocol()));
                            if (!hasIpv4AllEg) {
                                egressRules.add(EgressSecurityRule.builder()
                                        .destination("0.0.0.0/0")
                                        .protocol("all")
                                        .isStateless(false)
                                        .build());
                            }
                            boolean hasIpv6AllEg = egressRules.stream().anyMatch(r ->
                                    "::/0".equals(r.getDestination()) && "all".equalsIgnoreCase(r.getProtocol()));
                            if (!hasIpv6AllEg) {
                                egressRules.add(EgressSecurityRule.builder()
                                        .destination("::/0")
                                        .protocol("all")
                                        .isStateless(false)
                                        .build());
                            }

                            if (!hasIpv4AllIn || !hasIpv6AllIn || !hasIpv4AllEg || !hasIpv6AllEg) {
                                vncClient.updateSecurityList(UpdateSecurityListRequest.builder()
                                        .securityListId(sl.getId())
                                        .updateSecurityListDetails(UpdateSecurityListDetails.builder()
                                                .ingressSecurityRules(ingressRules)
                                                .egressSecurityRules(egressRules)
                                                .build())
                                        .build());
                            }

                            updatedCount++;
                        }
                    }
                } catch (Exception e) {
                    errorList.add(region + "/" + compId + " 错误: " + e.getMessage());
                }
            }
        }

        if (updatedCount == 0) {
            String errorMsg = String.join(" | ", errorList);
            throw new RuntimeException("未能更新任何防火墙规则！已扫描订阅区域 " + regions + "，可能尚无任何 VCN 网络资源。详细异常: " + errorMsg);
        }
    }

    /**
     * 🌟 终极防黑洞重构版：灵活提取子网绑定的真实路由，强制清缴错误规则，并注入 DNS
     */
    String getOrCreateSubnetId(VirtualNetworkClient vncClient, String tenancy) {
        Object lock = OciCloudService.TENANCY_LOCKS.computeIfAbsent(tenancy, k -> new Object());
        synchronized (lock) {
            var vcnRes = vncClient.listVcns(ListVcnsRequest.builder().compartmentId(tenancy).build());
            Vcn selectedVcn = null;
            Subnet targetSubnet = null; // 🌟 升级：直接提取整个 Subnet 对象，以获取它真实绑定的路由表和防火墙

            // 寻找现存 VCN 和 可用的子网
            for (Vcn vcn : vcnRes.getItems()) {
                if (vcn.getLifecycleState() == Vcn.LifecycleState.Available) {
                    selectedVcn = vcn;
                    var subRes = vncClient.listSubnets(ListSubnetsRequest.builder().compartmentId(tenancy).vcnId(vcn.getId()).build());
                    for (Subnet subnet : subRes.getItems()) {
                        if (subnet.getLifecycleState() == Subnet.LifecycleState.Available) {
                            Boolean prohibitPublicIp = subnet.getProhibitPublicIpOnVnic();
                            if (prohibitPublicIp == null || !prohibitPublicIp) {
                                targetSubnet = subnet;
                                break;
                            }
                        }
                    }
                    // 只要找到了可用的 VCN 就跳出循环（即便里面没有合适的子网，我们也可以在这个 VCN 里新建）
                    break;
                }
            }

            // 若无 VCN，则新建一个（各账号采用独立哈希生成的官方风格 VCN 名称与 DNS 标签，彻底抹除 oci-manager-auto-vcn 特征）
            boolean createdNewVcn = false;
            if (selectedVcn == null) {
                createdNewVcn = true;
                String vcnDisplayName = fingerprintService.getAccountVcnName(null, tenancy);
                String vcnDns = fingerprintService.getAccountVcnDnsLabel(null, tenancy);
                selectedVcn = vncClient.createVcn(CreateVcnRequest.builder()
                        .createVcnDetails(CreateVcnDetails.builder()
                                .cidrBlock("10.0.0.0/16")
                                .compartmentId(tenancy)
                                .displayName(vcnDisplayName)
                                .dnsLabel(vcnDns)
                                .build())
                        .build()).getVcn();
            }

            String vcnId = selectedVcn.getId();

            // 🌟 核心防黑洞修复升级：精准打击！获取该子网【真正绑定】的路由表和安全列表，而不是盲目使用 VCN 的默认列表！
            String routeTableId = (targetSubnet != null && targetSubnet.getRouteTableId() != null)
                    ? targetSubnet.getRouteTableId()
                    : selectedVcn.getDefaultRouteTableId();

            List<String> securityListIds = (targetSubnet != null && targetSubnet.getSecurityListIds() != null && !targetSubnet.getSecurityListIds().isEmpty())
                    ? targetSubnet.getSecurityListIds()
                    : Arrays.asList(selectedVcn.getDefaultSecurityListId());

            try {
                // 1. 嗅探或创建 Internet Gateway (IGW)
                var igws = vncClient.listInternetGateways(ListInternetGatewaysRequest.builder().compartmentId(tenancy).vcnId(vcnId).build()).getItems();
                String igwId = null;
                for (var igwCandidate : igws) {
                    if (igwCandidate.getLifecycleState() == InternetGateway.LifecycleState.Available
                            && Boolean.TRUE.equals(igwCandidate.getIsEnabled())) {
                        igwId = igwCandidate.getId();
                        break;
                    }
                }
                if (igwId == null) {
                    String igwDisplayName = fingerprintService.getAccountIgwName(null, tenancy);
                    InternetGateway igw = vncClient.createInternetGateway(CreateInternetGatewayRequest.builder()
                            .createInternetGatewayDetails(CreateInternetGatewayDetails.builder()
                                    .compartmentId(tenancy).vcnId(vcnId).isEnabled(true).displayName(igwDisplayName).build())
                            .build()).getInternetGateway();
                    igwId = igw.getId();
                }

                // 2. 检查并打通出海路由表（跳过 500M 专用 NAT 路由表）
                if (routeTableId != null) {
                    var rt = vncClient.getRouteTable(GetRouteTableRequest.builder().rtId(routeTableId).build()).getRouteTable();
                    if (rt.getDisplayName() == null || !"nat-route-500m".equalsIgnoreCase(rt.getDisplayName())) {
                        List<RouteRule> rules = new ArrayList<>();
                        if (rt.getRouteRules() != null) rules.addAll(rt.getRouteRules());

                        final String finalIgwId = igwId;
                        boolean hasCorrectDefaultRoute = rules.stream().anyMatch(r ->
                                ("0.0.0.0/0".equals(r.getDestination()) || "0.0.0.0/0".equals(r.getCidrBlock()))
                                        && finalIgwId.equals(r.getNetworkEntityId())
                        );

                        if (!hasCorrectDefaultRoute) {
                            rules.removeIf(r -> "0.0.0.0/0".equals(r.getDestination()) || "0.0.0.0/0".equals(r.getCidrBlock()));
                            rules.add(RouteRule.builder()
                                    .networkEntityId(finalIgwId)
                                    .destination("0.0.0.0/0")
                                    .destinationType(RouteRule.DestinationType.CidrBlock)
                                    .build());

                            vncClient.updateRouteTable(UpdateRouteTableRequest.builder().rtId(routeTableId)
                                    .updateRouteTableDetails(UpdateRouteTableDetails.builder().routeRules(rules).build()).build());
                        }
                    }
                }

                // 3. 🔒 最小权限安全列表原则：仅在新建 VCN 时初始化默认安全列表（仅放通 TCP/22、Path MTU ICMP 与出站），绝不静默篡改已有子网的自定义 SecurityList！
                if (createdNewVcn) {
                    for (String slId : securityListIds) {
                        if (slId == null) continue;
                        var sl = vncClient.getSecurityList(GetSecurityListRequest.builder().securityListId(slId).build()).getSecurityList();
                        List<IngressSecurityRule> inRules = new ArrayList<>();
                        if (sl.getIngressSecurityRules() != null) inRules.addAll(sl.getIngressSecurityRules());

                        List<EgressSecurityRule> egRules = new ArrayList<>();
                        if (sl.getEgressSecurityRules() != null) egRules.addAll(sl.getEgressSecurityRules());

                        boolean hasSshIn = inRules.stream().anyMatch(r ->
                                "0.0.0.0/0".equals(r.getSource())
                                        && ("all".equalsIgnoreCase(r.getProtocol())
                                        || ("6".equals(r.getProtocol())
                                        && (r.getTcpOptions() == null
                                        || r.getTcpOptions().getDestinationPortRange() == null
                                        || (r.getTcpOptions().getDestinationPortRange().getMin() <= 22
                                        && r.getTcpOptions().getDestinationPortRange().getMax() >= 22)))));
                        if (!hasSshIn) {
                            inRules.add(IngressSecurityRule.builder()
                                    .source("0.0.0.0/0")
                                    .protocol("6")
                                    .isStateless(false)
                                    .tcpOptions(TcpOptions.builder()
                                            .destinationPortRange(PortRange.builder().min(22).max(22).build())
                                            .build())
                                    .build());
                        }

                        boolean hasEg = egRules.stream().anyMatch(r -> "0.0.0.0/0".equals(r.getDestination()) && "all".equalsIgnoreCase(r.getProtocol()));
                        if (!hasEg) {
                            egRules.add(EgressSecurityRule.builder().destination("0.0.0.0/0").protocol("all").isStateless(false).build());
                        }

                        if (!hasSshIn || !hasEg) {
                            vncClient.updateSecurityList(UpdateSecurityListRequest.builder().securityListId(slId)
                                    .updateSecurityListDetails(UpdateSecurityListDetails.builder()
                                            .ingressSecurityRules(inRules)
                                            .egressSecurityRules(egRules)
                                            .build())
                                    .build());
                        }
                    }
                }
            } catch (Exception e) {
                System.err.println("修复VCN网络组件(IGW/路由/安全列表)异常，这可能是权限受限所致: " + e.getMessage());
            }

            // 如果此前已找到了健康的子网，直接返回
            if (targetSubnet != null) {
                return targetSubnet.getId();
            }

            // 否则在这个打通了网络（并且带有DNS）的 VCN 下创建一个新子网
            String vcnCidr = selectedVcn.getCidrBlock();
            if (vcnCidr == null && selectedVcn.getCidrBlocks() != null && !selectedVcn.getCidrBlocks().isEmpty()) {
                vcnCidr = selectedVcn.getCidrBlocks().get(0);
            }

            String prefix = "10.0.";
            if (vcnCidr != null && vcnCidr.contains(".")) {
                String[] parts = vcnCidr.split("\\.");
                if (parts.length >= 2) prefix = parts[0] + "." + parts[1] + ".";
            }

            var existingSubnets = vncClient.listSubnets(ListSubnetsRequest.builder().compartmentId(tenancy).vcnId(vcnId).build()).getItems();
            Set<String> usedCidrs = new HashSet<>();
            for (Subnet sub : existingSubnets) {
                usedCidrs.add(sub.getCidrBlock());
            }

            String newSubnetCidr = null;
            for (int i = 0; i < 255; i++) {
                String testCidr = prefix + i + ".0/24";
                if (!usedCidrs.contains(testCidr)) {
                    newSubnetCidr = testCidr;
                    break;
                }
            }
            if (newSubnetCidr == null) newSubnetCidr = prefix + "254.0/24";

            try {
                String subnetDisplayName = fingerprintService.getAccountSubnetName(null, tenancy);
                String subnetDns = fingerprintService.getAccountSubnetDnsLabel(null, tenancy);
                Subnet subnet = vncClient.createSubnet(CreateSubnetRequest.builder()
                        .createSubnetDetails(CreateSubnetDetails.builder()
                                .compartmentId(tenancy)
                                .vcnId(vcnId)
                                .cidrBlock(newSubnetCidr)
                                .displayName(subnetDisplayName)
                                .routeTableId(routeTableId)
                                .securityListIds(securityListIds)
                                .dnsLabel(subnetDns)
                                .prohibitPublicIpOnVnic(false)
                                .build())
                        .build()).getSubnet();

                Thread.sleep(3000);
                return subnet.getId();
            } catch (Exception e) {
                throw new RuntimeException("尝试自动创建新公有子网失败: " + e.getMessage());
            }
        }
    }

    public String getCachedIp(String ocid) {
        String ip = redisTemplate.opsForValue().get("oci:ip:" + ocid);
        return ip != null ? ip : "待获取 (管理员 /traffic 刷新)";
    }

    public String getInstanceIp(String ocid, String accountName, boolean forceFetch) {
        if (!forceFetch) {
            String cachedIp = redisTemplate.opsForValue().get("oci:ip:" + ocid);
            if (cachedIp != null) {
                return cachedIp;
            }
        }

        try {
            OciProperties.Account accConfig = ociProperties.getAccounts().get(accountName);
            if (accConfig == null) {
                return "未知账号";
            }
            var clients = clientManager.getOciClients(accountName);
            String region = OciRegionUtil.getRegionForResource(ocid);
            var computeClient = region != null ? clients.getComputeClientForRegion(region) : clients.getCompute();
            var vncClient = region != null ? clients.getVncClientForRegion(region) : clients.getVnc();
            String compId = resolveInstanceCompartmentId(ocid, accConfig.getTenancy());

            var vnicResponse = computeClient.listVnicAttachments(ListVnicAttachmentsRequest.builder().compartmentId(compId).instanceId(ocid).build());
            if (vnicResponse.getItems().isEmpty()) {
                redisTemplate.opsForValue().set("oci:ip:" + ocid, "无网卡", 10, TimeUnit.MINUTES);
                return "无网卡";
            }
            String vnicId = vnicResponse.getItems().get(0).getVnicId();

            var pIps = vncClient.listPrivateIps(ListPrivateIpsRequest.builder().vnicId(vnicId).build());
            if (pIps.getItems().isEmpty()) {
                redisTemplate.opsForValue().set("oci:ip:" + ocid, "无私网IP", 10, TimeUnit.MINUTES);
                return "无私网IP";
            }
            String privateIpId = pIps.getItems().get(0).getId();

            try {
                var ip6Res = vncClient.listIpv6s(ListIpv6sRequest.builder().vnicId(vnicId).build());
                for (var ip6 : ip6Res.getItems()) {
                    if (ip6.getLifecycleState() == Ipv6.LifecycleState.Available) {
                        redisTemplate.opsForValue().set("oci:ipv6:" + ocid, ip6.getIpAddress(), 30, TimeUnit.DAYS);
                        break;
                    }
                }
            } catch (Exception ignored) {}

            var pubIpResp = vncClient.getPublicIpByPrivateIpId(GetPublicIpByPrivateIpIdRequest.builder().getPublicIpByPrivateIpIdDetails(GetPublicIpByPrivateIpIdDetails.builder().privateIpId(privateIpId).build()).build());
            String ip = pubIpResp.getPublicIp().getIpAddress();
            String combinedIp = ip;
            try {
                var nlbClient = region != null ? clients.getNetworkLoadBalancerClientForRegion(region) : clients.getNetworkLoadBalancer();
                var nlbs = nlbClient.listNetworkLoadBalancers(com.oracle.bmc.networkloadbalancer.requests.ListNetworkLoadBalancersRequest.builder()
                        .compartmentId(compId).build())
                        .getNetworkLoadBalancerCollection().getItems();
                for (var nlb : nlbs) {
                    if (nlb.getLifecycleState() == com.oracle.bmc.networkloadbalancer.model.LifecycleState.Active && nlb.getDisplayName() != null && nlb.getDisplayName().startsWith("nlb-500m")) {
                        var finalNlb = nlbClient.getNetworkLoadBalancer(com.oracle.bmc.networkloadbalancer.requests.GetNetworkLoadBalancerRequest.builder().networkLoadBalancerId(nlb.getId()).build()).getNetworkLoadBalancer();
                        boolean matches = false;
                        if (finalNlb.getBackendSets() != null) {
                            for (var bs : finalNlb.getBackendSets().values()) {
                                if (bs.getBackends() != null) {
                                    for (var backend : bs.getBackends()) {
                                        if (ocid.equals(backend.getTargetId())) {
                                            matches = true;
                                            break;
                                        }
                                    }
                                }
                            }
                        }
                        if (matches) {
                            String nlbIp = null;
                            for (var nip : finalNlb.getIpAddresses()) {
                                if (nip.getIpAddress() != null && !nip.getIpAddress().startsWith("10.") && !nip.getIpAddress().startsWith("172.") && !nip.getIpAddress().startsWith("192.168.")) {
                                    nlbIp = nip.getIpAddress();
                                    break;
                                }
                            }
                            if (nlbIp != null) {
                                combinedIp = nlbIp + " (NAT) / " + ip + " (原)";
                                break;
                            }
                        }
                    }
                }
            } catch (Exception ignored) {}

            if (combinedIp.equals(ip)) {
                String oldIp = redisTemplate.opsForValue().get("oci:ip:" + ocid);
                if (oldIp != null && oldIp.startsWith("出站")) {
                    combinedIp = "出站500M(限额) (NAT) / " + ip + " (原)";
                }
            }

            redisTemplate.opsForValue().set("oci:ip:" + ocid, combinedIp, 30, TimeUnit.DAYS);
            return combinedIp;
        } catch (Exception e) {
            String existingIp = redisTemplate.opsForValue().get("oci:ip:" + ocid);
            if (existingIp != null && (existingIp.matches("\\d+\\.\\d+\\.\\d+\\.\\d+.*") || existingIp.startsWith("出站"))) {
                return existingIp;
            }
            // 🚀 异常/关机为瞬时状态，短期缓存即可，避免长期屏蔽真实 IP。
            redisTemplate.opsForValue().set("oci:ip:" + ocid, "未分配/关机", 10, TimeUnit.MINUTES);
            return "未分配/关机";
        }
    }

    public String getInstanceIp(String ocid, String accountName) {
        return getInstanceIp(ocid, accountName, false);
    }

    public String changeInstanceIp(String ocid, String accountName) {
        if (ocid == null || ocid.isBlank()) {
            throw new IllegalArgumentException("缺少实例 OCID");
        }
        OciProperties.Account accConfig = ociProperties.getAccounts().get(accountName);
        if (accConfig == null) {
            throw new IllegalArgumentException("账号配置不存在: " + accountName);
        }

        boolean removedOldEphemeral = false;
        String newIp = null;
        try {
            var clients = clientManager.getOciClients(accountName);
            String region = OciRegionUtil.getRegionForResource(ocid);
            var computeClient = region != null ? clients.getComputeClientForRegion(region) : clients.getCompute();
            var vncClient = region != null ? clients.getVncClientForRegion(region) : clients.getVnc();
            String compId = resolveInstanceCompartmentId(ocid, accConfig.getTenancy());

            var vnicResponse = computeClient.listVnicAttachments(
                    ListVnicAttachmentsRequest.builder()
                            .compartmentId(compId)
                            .instanceId(ocid).build());
            if (vnicResponse.getItems().isEmpty()) {
                return null;
            }
            String vnicId = vnicResponse.getItems().get(0).getVnicId();

            var pIps = vncClient.listPrivateIps(
                    ListPrivateIpsRequest.builder().vnicId(vnicId).build());
            if (pIps.getItems().isEmpty()) {
                return null;
            }
            PrivateIp primaryPrivateIp = pIps.getItems().get(0);
            String privateIpId = primaryPrivateIp.getId();
            String privateIpCompId = (primaryPrivateIp.getCompartmentId() != null && !primaryPrivateIp.getCompartmentId().isBlank())
                    ? primaryPrivateIp.getCompartmentId() : compId;

            try {
                var pubIpResp = vncClient.getPublicIpByPrivateIpId(
                        GetPublicIpByPrivateIpIdRequest.builder()
                                .getPublicIpByPrivateIpIdDetails(
                                        GetPublicIpByPrivateIpIdDetails.builder()
                                                .privateIpId(privateIpId).build()).build());
                PublicIp existingPubIp = pubIpResp.getPublicIp();
                if (existingPubIp != null) {
                    if (existingPubIp.getLifetime() == PublicIp.Lifetime.Reserved) {
                        // 🔒 保护预留静态公网 IP (Reserved IP)，严禁在自动换 IP 流程中解绑降级为临时 IP！
                        throw new IllegalStateException("该实例当前绑定的是预留静态公网 IP (Reserved IP: " + existingPubIp.getIpAddress() + ")，系统已自动拦截解绑与降级操作；如需更换请前往 OCI 控制台管理。");
                    }
                    vncClient.deletePublicIp(
                            DeletePublicIpRequest.builder().publicIpId(existingPubIp.getId()).build());
                    removedOldEphemeral = true;
                }
            } catch (IllegalStateException ise) {
                throw ise;
            } catch (com.oracle.bmc.model.BmcException bmcEx) {
                if (bmcEx.getStatusCode() != 404) {
                    throw new RuntimeException("释放旧临时公网 IP 失败: " + bmcEx.getMessage(), bmcEx);
                }
            }

            if (removedOldEphemeral) {
                for (int i = 0; i < 16; i++) {
                    try {
                        vncClient.getPublicIpByPrivateIpId(
                                GetPublicIpByPrivateIpIdRequest.builder()
                                        .getPublicIpByPrivateIpIdDetails(
                                                GetPublicIpByPrivateIpIdDetails.builder()
                                                        .privateIpId(privateIpId).build()).build());
                        Thread.sleep(600);
                    } catch (com.oracle.bmc.model.BmcException bmcEx) {
                        if (bmcEx.getStatusCode() == 404) {
                            break;
                        }
                        Thread.sleep(600);
                    }
                }
            }

            // 带退避重试申请新临时公网 IP，规避 OCI 后端释放结算瞬时延迟 (LimitExceeded / Conflict 409)
            Exception lastCreateError = null;
            int[] backoffMs = {0, 2000, 4000, 6000};
            for (int attempt = 0; attempt < backoffMs.length; attempt++) {
                if (backoffMs[attempt] > 0) {
                    Thread.sleep(backoffMs[attempt]);
                }
                try {
                    var createResp = vncClient.createPublicIp(
                            CreatePublicIpRequest.builder()
                                    .createPublicIpDetails(
                                            CreatePublicIpDetails.builder()
                                                    .compartmentId(privateIpCompId)
                                                    .lifetime(CreatePublicIpDetails.Lifetime.Ephemeral)
                                                    .privateIpId(privateIpId).build()).build());
                    newIp = createResp.getPublicIp().getIpAddress();
                    if (newIp != null && !newIp.isBlank()) {
                        break;
                    }
                } catch (Exception ce) {
                    lastCreateError = ce;
                    System.err.println("申请新公网 IP 第 " + (attempt + 1) + " 次尝试异常: " + ce.getMessage());
                }
            }
            if (newIp == null && lastCreateError != null) {
                System.err.println("申请新公网 IP 最终失败: " + lastCreateError.getMessage());
            }
            return newIp;
        } catch (IllegalStateException ise) {
            throw ise;
        } catch (Exception e) {
            System.err.println("换IP执行过程中发生异常: " + e.getMessage());
            return null;
        } finally {
            if (newIp != null && !newIp.isBlank()) {
                redisTemplate.opsForValue().set("oci:ip:" + ocid, newIp, 30, TimeUnit.DAYS);
                Map<String, String> inst = OciCloudService.INSTANCE_CACHE.get(ocid);
                if (inst != null) {
                    inst.put("ip", newIp);
                }
            } else if (removedOldEphemeral) {
                // 旧 IP 已释放但新 IP 创建失败：立即清除旧 IP 缓存，杜绝面板继续展示失效死 IP 达 30 天！
                redisTemplate.delete("oci:ip:" + ocid);
                Map<String, String> inst = OciCloudService.INSTANCE_CACHE.get(ocid);
                if (inst != null) {
                    inst.put("ip", "未分配 IP");
                }
            }
        }
    }

    // 🚀 预编译正则，避免每次调用重复编译。
    private static final java.util.regex.Pattern IPV4_PATTERN = java.util.regex.Pattern.compile("\\d+\\.\\d+\\.\\d+\\.\\d+");

    /**
     * 零外联本地归属地解析：直接基于 OCI 权威区域元数据推导地理归属，彻底切断明文 HTTP ip-api.com 隐私泄漏与 429 阻塞！
     */
    public String getInstanceLocation(String ip, String region) {
        if (region != null && !region.isBlank()) {
            return OciRegionUtil.getRegionDisplayLocation(region);
        }
        return getIpLocation(ip);
    }

    public String getIpLocation(String ip) {
        if (ip == null || ip.isEmpty()) {
            return "未知地区";
        }
        String searchIp = ip.contains(" ") ? ip.split(" ")[0].trim() : ip.trim();
        if (!IPV4_PATTERN.matcher(searchIp).matches() && !ip.startsWith("出站")) {
            return "未知地区";
        }

        String cachedLoc = redisTemplate.opsForValue().get("oci:ip_loc:" + searchIp);
        if (cachedLoc != null && !cachedLoc.isBlank() && !cachedLoc.contains("API限流")) {
            return cachedLoc;
        }

        for (Map<String, String> info : OciCloudService.INSTANCE_CACHE.values()) {
            if (info == null) continue;
            String instIp = info.get("ip");
            String region = info.get("region");
            if (region != null && !region.isBlank() && instIp != null && instIp.contains(searchIp)) {
                String loc = OciRegionUtil.getRegionDisplayLocation(region);
                redisTemplate.opsForValue().set("oci:ip_loc:" + searchIp, loc, 24, TimeUnit.HOURS);
                return loc;
            }
        }
        return "未知地区";
    }

    public String enableAmd500Mbps(String ocid, String account) throws Exception {
        var clients = clientManager.getOciClients(account);
        String region = OciRegionUtil.getRegionForResource(ocid);
        var vncClient = region != null ? clients.getVncClientForRegion(region) : clients.getVnc();
        var computeClient = region != null ? clients.getComputeClientForRegion(region) : clients.getCompute();
        var nlbClient = region != null ? clients.getNetworkLoadBalancerClientForRegion(region) : clients.getNetworkLoadBalancer();
        
        OciProperties.Account accConfig = ociProperties.getAccounts().get(account);
        if (accConfig == null) throw new RuntimeException("账号不存在");
        String compartmentId = resolveInstanceCompartmentId(ocid, accConfig.getTenancy());

        var instanceInfo = computeClient.getInstance(GetInstanceRequest.builder().instanceId(ocid).build()).getInstance();
        if (instanceInfo.getCompartmentId() != null && !instanceInfo.getCompartmentId().isBlank()) {
            compartmentId = instanceInfo.getCompartmentId();
        }
        if (instanceInfo.getShape() == null || (!instanceInfo.getShape().contains("E2.1.Micro") && !instanceInfo.getShape().contains("A1.Flex") && !instanceInfo.getShape().contains("A1"))) {
            throw new RuntimeException("该实例不支持一键开启下行500Mbps (仅支持 AMD Micro 和 ARM Flex 免费实例)");
        }

        var vnics = computeClient.listVnicAttachments(ListVnicAttachmentsRequest.builder()
                .compartmentId(compartmentId).instanceId(ocid).build()).getItems();
        if (vnics.isEmpty()) throw new RuntimeException("实例没有附着VNIC");
        String vnicId = vnics.get(0).getVnicId();
        var vnic = vncClient.getVnic(GetVnicRequest.builder().vnicId(vnicId).build()).getVnic();
        String subnetId = vnic.getSubnetId();
        var subnet = vncClient.getSubnet(GetSubnetRequest.builder().subnetId(subnetId).build()).getSubnet();
        String vcnId = subnet.getVcnId();
        String vcnCompId = subnet.getCompartmentId() != null ? subnet.getCompartmentId() : compartmentId;

        NatGateway natGateway = null;
        var natGateways = vncClient.listNatGateways(ListNatGatewaysRequest.builder()
                .compartmentId(vcnCompId).vcnId(vcnId).lifecycleState(NatGateway.LifecycleState.Available).build()).getItems();
        if (!natGateways.isEmpty()) {
            natGateway = natGateways.get(0);
        } else {
            natGateway = vncClient.createNatGateway(CreateNatGatewayRequest.builder()
                    .createNatGatewayDetails(CreateNatGatewayDetails.builder()
                            .compartmentId(vcnCompId).vcnId(vcnId).displayName("nat-gateway-500m").build()).build()).getNatGateway();
            long natDeadline = System.currentTimeMillis() + 90_000L;
            boolean natReady = false;
            while (System.currentTimeMillis() < natDeadline) {
                var natState = vncClient.getNatGateway(GetNatGatewayRequest.builder().natGatewayId(natGateway.getId()).build())
                        .getNatGateway().getLifecycleState();
                if (natState == NatGateway.LifecycleState.Available) {
                    natReady = true;
                    break;
                }
                if (natState == NatGateway.LifecycleState.Terminated || natState == NatGateway.LifecycleState.Terminating) {
                    throw new RuntimeException("NAT 网关创建异常终止，状态: " + natState);
                }
                Thread.sleep(1500);
            }
            if (!natReady) {
                throw new RuntimeException("等待 NAT 网关就绪超时 (90s)");
            }
        }

        RouteTable routeTable = null;
        var rts = vncClient.listRouteTables(ListRouteTablesRequest.builder()
                .compartmentId(vcnCompId).vcnId(vcnId).lifecycleState(RouteTable.LifecycleState.Available).build()).getItems();
        for (RouteTable rt : rts) {
            if (rt.getRouteRules() == null) continue;
            for (RouteRule rr : rt.getRouteRules()) {
                if (rr != null && natGateway.getId().equals(rr.getNetworkEntityId())
                        && ("0.0.0.0/0".equals(rr.getDestination()) || "0.0.0.0/0".equals(rr.getCidrBlock()))) {
                    routeTable = rt;
                    break;
                }
            }
            if (routeTable != null) break;
        }

        if (routeTable == null) {
            routeTable = vncClient.createRouteTable(CreateRouteTableRequest.builder()
                    .createRouteTableDetails(CreateRouteTableDetails.builder()
                            .compartmentId(vcnCompId).vcnId(vcnId).displayName("nat-route-500m")
                            .routeRules(java.util.Collections.singletonList(RouteRule.builder()
                                    .destination("0.0.0.0/0")
                                    .networkEntityId(natGateway.getId())
                                    .destinationType(RouteRule.DestinationType.CidrBlock).build()))
                            .build()).build()).getRouteTable();
            long rtDeadline = System.currentTimeMillis() + 60_000L;
            boolean rtReady = false;
            while (System.currentTimeMillis() < rtDeadline) {
                var rtState = vncClient.getRouteTable(GetRouteTableRequest.builder().rtId(routeTable.getId()).build())
                        .getRouteTable().getLifecycleState();
                if (rtState == RouteTable.LifecycleState.Available) {
                    rtReady = true;
                    break;
                }
                if (rtState == RouteTable.LifecycleState.Terminated || rtState == RouteTable.LifecycleState.Terminating) {
                    throw new RuntimeException("500M 专用路由表创建异常终止，状态: " + rtState);
                }
                Thread.sleep(1500);
            }
            if (!rtReady) {
                throw new RuntimeException("等待 500M 专用路由表就绪超时 (60s)");
            }
        }

        String suffix = ocid.length() > 6 ? ocid.substring(ocid.length() - 6) : ocid;
        String nlbName = "nlb-500m-" + suffix;

        var nlbs = nlbClient.listNetworkLoadBalancers(ListNetworkLoadBalancersRequest.builder()
                .compartmentId(compartmentId).build())
                .getNetworkLoadBalancerCollection().getItems();
        for (var existingNlb : nlbs) {
            if (existingNlb.getLifecycleState() == com.oracle.bmc.networkloadbalancer.model.LifecycleState.Deleted) {
                continue;
            }
            // 🔒 仅清理归属【当前实例】的历史同名/同后端 NLB，严禁按 startsWith("nlb-500m") 误删同账号其他机器的活动 NLB！
            boolean belongsToCurrentInstance = false;
            if (existingNlb.getDisplayName() != null) {
                if (nlbName.equals(existingNlb.getDisplayName())) {
                    belongsToCurrentInstance = true;
                } else if (existingNlb.getDisplayName().startsWith("nlb-500m")) {
                    try {
                        var detailedNlb = nlbClient.getNetworkLoadBalancer(GetNetworkLoadBalancerRequest.builder()
                                .networkLoadBalancerId(existingNlb.getId()).build()).getNetworkLoadBalancer();
                        if (detailedNlb.getBackendSets() != null) {
                            for (var bs : detailedNlb.getBackendSets().values()) {
                                if (bs.getBackends() != null) {
                                    for (var backend : bs.getBackends()) {
                                        if (ocid.equals(backend.getTargetId())
                                                || (vnic.getPrivateIp() != null && vnic.getPrivateIp().equals(backend.getIpAddress()))) {
                                            belongsToCurrentInstance = true;
                                            break;
                                        }
                                    }
                                }
                                if (belongsToCurrentInstance) break;
                            }
                        }
                    } catch (Exception ignored) {}
                }
            }

            if (belongsToCurrentInstance) {
                try {
                    nlbClient.deleteNetworkLoadBalancer(DeleteNetworkLoadBalancerRequest.builder()
                            .networkLoadBalancerId(existingNlb.getId()).build());
                } catch (Exception e) {}

                int nlbWaitAttempts = 0;
                final int NLB_MAX_ATTEMPTS = 60;
                while (nlbWaitAttempts++ < NLB_MAX_ATTEMPTS) {
                    try {
                        var state = nlbClient.getNetworkLoadBalancer(GetNetworkLoadBalancerRequest.builder()
                                .networkLoadBalancerId(existingNlb.getId()).build())
                                .getNetworkLoadBalancer().getLifecycleState();
                        if (state == com.oracle.bmc.networkloadbalancer.model.LifecycleState.Deleted) {
                            Thread.sleep(3000);
                            break;
                        }
                        Thread.sleep(2000);
                    } catch (com.oracle.bmc.model.BmcException e) {
                        if (e.getStatusCode() == 404) {
                            Thread.sleep(3000);
                            break;
                        }
                        Thread.sleep(2000);
                    } catch (Exception e) {
                        try { Thread.sleep(2000); } catch (Exception ignored) {}
                    }
                }
            }
        }

        com.oracle.bmc.networkloadbalancer.model.NetworkLoadBalancer nlb = null;
        boolean nlbCreated = false;
        int retries = 0;
        while (retries < 3) {
            try {
                nlb = nlbClient.createNetworkLoadBalancer(CreateNetworkLoadBalancerRequest.builder()
                        .createNetworkLoadBalancerDetails(CreateNetworkLoadBalancerDetails.builder()
                                .displayName(nlbName).compartmentId(compartmentId).isPrivate(false).subnetId(subnetId)
                                .listeners(java.util.Map.of("listener1", ListenerDetails.builder()
                                        .name("listener1").defaultBackendSetName("backend1").protocol(ListenerProtocols.TcpAndUdp).port(0).build()))
                                .backendSets(java.util.Map.of("backend1", BackendSetDetails.builder()
                                        .isPreserveSource(false).isFailOpen(true).policy(NetworkLoadBalancingPolicy.TwoTuple)
                                        .healthChecker(HealthChecker.builder().protocol(HealthCheckProtocols.Tcp).port(22).build())
                                        .backends(java.util.Collections.singletonList(Backend.builder()
                                                .targetId(ocid).ipAddress(vnic.getPrivateIp()).port(0).weight(1).build()))
                                        .build()))
                                .build()).build()).getNetworkLoadBalancer();
                nlbCreated = true;
                break;
            } catch (com.oracle.bmc.model.BmcException e) {
                if (e.getStatusCode() == 400 && e.getMessage() != null && e.getMessage().contains("LimitExceeded")) {
                    if (retries < 2) {
                        retries++;
                        System.out.println("甲骨文后端配额刷新延迟，重试创建 NLB (" + retries + "/2) ...");
                        try { Thread.sleep(6000); } catch (Exception ignored) {}
                    } else {
                        System.out.println("NLB quota exceeded (likely Always Free limit of 1). Skipping NLB creation. Outbound NAT will still be configured.");
                        break;
                    }
                } else {
                    throw e;
                }
            }
        }

        if (nlbCreated && nlb != null) {
            long nlbActiveDeadline = System.currentTimeMillis() + 180_000L;
            boolean nlbActive = false;
            while (System.currentTimeMillis() < nlbActiveDeadline) {
                var nlbState = nlbClient.getNetworkLoadBalancer(GetNetworkLoadBalancerRequest.builder().networkLoadBalancerId(nlb.getId()).build())
                        .getNetworkLoadBalancer().getLifecycleState();
                if (nlbState == com.oracle.bmc.networkloadbalancer.model.LifecycleState.Active) {
                    nlbActive = true;
                    break;
                }
                if (nlbState == com.oracle.bmc.networkloadbalancer.model.LifecycleState.Failed
                        || nlbState == com.oracle.bmc.networkloadbalancer.model.LifecycleState.Deleted
                        || nlbState == com.oracle.bmc.networkloadbalancer.model.LifecycleState.Deleting) {
                    throw new RuntimeException("NLB 创建进入异常终态: " + nlbState);
                }
                Thread.sleep(2000);
            }
            if (!nlbActive) {
                throw new RuntimeException("等待 NLB 激活超时 (180s)");
            }
        }

        vncClient.updateVnic(UpdateVnicRequest.builder().vnicId(vnicId)
                .updateVnicDetails(UpdateVnicDetails.builder().skipSourceDestCheck(true).routeTableId(routeTable.getId()).build())
                .build());

        String publicIp = "出站500M(限额)";
        if (nlbCreated && nlb != null) {
            publicIp = "未分配";
            var finalNlb = nlbClient.getNetworkLoadBalancer(GetNetworkLoadBalancerRequest.builder().networkLoadBalancerId(nlb.getId()).build()).getNetworkLoadBalancer();
            for (var ip : finalNlb.getIpAddresses()) {
                if (ip.getIpAddress() != null && !ip.getIpAddress().startsWith("10.") && !ip.getIpAddress().startsWith("172.") && !ip.getIpAddress().startsWith("192.168.")) {
                    publicIp = ip.getIpAddress();
                    break;
                }
            }
        }
        
        OciCloudService.INSTANCE_CACHE.remove(ocid);
        
        String originalIp = vnic.getPublicIp();
        if (originalIp == null || originalIp.isEmpty()) {
            originalIp = "未知网卡IP";
        }
        String combinedIp = publicIp + " (NAT) / " + originalIp + " (原)";
        redisTemplate.opsForValue().set("oci:ip:" + ocid, combinedIp, 30, TimeUnit.DAYS);

        return combinedIp;
    }

    public String enable500Mbps(String ocid, String account) throws Exception {
        return enableAmd500Mbps(ocid, account);
    }

    public String disable500Mbps(String ocid, String account) throws Exception {
        var clients = clientManager.getOciClients(account);
        String region = OciRegionUtil.getRegionForResource(ocid);
        var vncClient = region != null ? clients.getVncClientForRegion(region) : clients.getVnc();
        var computeClient = region != null ? clients.getComputeClientForRegion(region) : clients.getCompute();
        var nlbClient = region != null ? clients.getNetworkLoadBalancerClientForRegion(region) : clients.getNetworkLoadBalancer();

        OciProperties.Account accConfig = ociProperties.getAccounts().get(account);
        if (accConfig == null) throw new RuntimeException("账号不存在");
        String compartmentId = resolveInstanceCompartmentId(ocid, accConfig.getTenancy());

        var vnics = computeClient.listVnicAttachments(ListVnicAttachmentsRequest.builder()
                .compartmentId(compartmentId).instanceId(ocid).build()).getItems();
        if (vnics.isEmpty()) throw new RuntimeException("实例没有附着VNIC");
        String vnicId = vnics.get(0).getVnicId();
        var vnic = vncClient.getVnic(GetVnicRequest.builder().vnicId(vnicId).build()).getVnic();
        String oldVnicRouteTableId = vnic.getRouteTableId();
        var subnet = vncClient.getSubnet(GetSubnetRequest.builder().subnetId(vnic.getSubnetId()).build()).getSubnet();
        String vcnId = subnet.getVcnId();
        String vcnCompId = subnet.getCompartmentId() != null ? subnet.getCompartmentId() : compartmentId;

        // 1. 严格还原 VNIC 路由表与源目检查：OCI UpdateVnicDetails 需显式序列化 "routeTableId": null 以解除单网卡路由覆盖并回退至子网路由表
        try {
            vncClient.updateVnic(UpdateVnicRequest.builder().vnicId(vnicId)
                    .updateVnicDetails(UpdateVnicDetails.builder()
                            .skipSourceDestCheck(false)
                            .routeTableId(null)
                            .build())
                    .build());
        } catch (com.oracle.bmc.model.BmcException vnicEx) {
            if (vnicEx.getStatusCode() == 400 && subnet.getRouteTableId() != null && !subnet.getRouteTableId().isBlank()) {
                vncClient.updateVnic(UpdateVnicRequest.builder().vnicId(vnicId)
                        .updateVnicDetails(UpdateVnicDetails.builder()
                                .skipSourceDestCheck(false)
                                .routeTableId(subnet.getRouteTableId())
                                .build())
                        .build());
            } else {
                throw vnicEx;
            }
        }

        Set<String> vcnSubnetIds = new HashSet<>();
        vcnSubnetIds.add(vnic.getSubnetId());
        try {
            var vcnSubs = vncClient.listSubnets(ListSubnetsRequest.builder()
                    .compartmentId(vcnCompId).vcnId(vcnId).build()).getItems();
            if (vcnSubs != null) {
                for (Subnet s : vcnSubs) {
                    if (s != null && s.getId() != null) vcnSubnetIds.add(s.getId());
                }
            }
        } catch (Exception ignored) {}

        // 2. 查找并删除关联该实例的 NLB，等待删除生效（不吞除 404 外的删除失败，防止伪成功）
        String suffix = ocid.length() > 6 ? ocid.substring(ocid.length() - 6) : ocid;
        String targetNlbName = "nlb-500m-" + suffix;
        List<String> deletingNlbIds = new ArrayList<>();
        boolean other500mNlbActiveInVcn = false;

        var nlbs = nlbClient.listNetworkLoadBalancers(ListNetworkLoadBalancersRequest.builder()
                .compartmentId(compartmentId).build())
                .getNetworkLoadBalancerCollection().getItems();
        for (var existingNlb : nlbs) {
            if (existingNlb.getLifecycleState() == com.oracle.bmc.networkloadbalancer.model.LifecycleState.Deleted
                    || existingNlb.getLifecycleState() == com.oracle.bmc.networkloadbalancer.model.LifecycleState.Deleting) {
                continue;
            }
            boolean isTarget = false;
            if (existingNlb.getDisplayName() != null) {
                if (existingNlb.getDisplayName().equals(targetNlbName)) {
                    isTarget = true;
                } else if (existingNlb.getDisplayName().startsWith("nlb-500m")) {
                    try {
                        var detailedNlb = nlbClient.getNetworkLoadBalancer(GetNetworkLoadBalancerRequest.builder()
                                .networkLoadBalancerId(existingNlb.getId()).build()).getNetworkLoadBalancer();
                        if (detailedNlb.getBackendSets() != null) {
                            for (var bs : detailedNlb.getBackendSets().values()) {
                                if (bs.getBackends() != null) {
                                    for (var backend : bs.getBackends()) {
                                        if (ocid.equals(backend.getTargetId()) || (vnic.getPrivateIp() != null && vnic.getPrivateIp().equals(backend.getIpAddress()))) {
                                            isTarget = true;
                                            break;
                                        }
                                    }
                                }
                                if (isTarget) break;
                            }
                        }
                    } catch (Exception ignored) {}
                    if (!isTarget && existingNlb.getSubnetId() != null && vcnSubnetIds.contains(existingNlb.getSubnetId())) {
                        other500mNlbActiveInVcn = true;
                    }
                }
            }

            if (isTarget) {
                try {
                    nlbClient.deleteNetworkLoadBalancer(DeleteNetworkLoadBalancerRequest.builder()
                            .networkLoadBalancerId(existingNlb.getId()).build());
                    deletingNlbIds.add(existingNlb.getId());
                } catch (com.oracle.bmc.model.BmcException delEx) {
                    if (delEx.getStatusCode() != 404) {
                        throw delEx;
                    }
                }
            }
        }

        for (String nlbId : deletingNlbIds) {
            long nlbDelDeadline = System.currentTimeMillis() + 90_000L;
            while (System.currentTimeMillis() < nlbDelDeadline) {
                try {
                    var st = nlbClient.getNetworkLoadBalancer(GetNetworkLoadBalancerRequest.builder()
                            .networkLoadBalancerId(nlbId).build()).getNetworkLoadBalancer().getLifecycleState();
                    if (st == com.oracle.bmc.networkloadbalancer.model.LifecycleState.Deleted) {
                        break;
                    }
                    Thread.sleep(2000);
                } catch (com.oracle.bmc.model.BmcException bmcEx) {
                    if (bmcEx.getStatusCode() == 404) {
                        break;
                    }
                    Thread.sleep(2000);
                }
            }
        }

        // 3. 若同 VCN 下（含所有子区间实例）已无其他 500M 实例/NLB 占用，自动回收专属 nat-route-500m 与 nat-gateway-500m
        if (!other500mNlbActiveInVcn) {
            try {
                Set<String> natRouteIdsInVcn = new HashSet<>();
                if (oldVnicRouteTableId != null && !oldVnicRouteTableId.isBlank()) {
                    natRouteIdsInVcn.add(oldVnicRouteTableId);
                }
                var rts = vncClient.listRouteTables(ListRouteTablesRequest.builder()
                        .compartmentId(vcnCompId).vcnId(vcnId).build()).getItems();
                for (RouteTable rt : rts) {
                    if (rt.getLifecycleState() == RouteTable.LifecycleState.Available
                            && "nat-route-500m".equalsIgnoreCase(rt.getDisplayName())) {
                        natRouteIdsInVcn.add(rt.getId());
                    }
                }

                boolean anyOtherVnicUsingNatRoute = false;
                List<String> checkComps = clientManager.getActiveCompartmentIds(account);
                if (checkComps == null || checkComps.isEmpty()) {
                    checkComps = List.of(compartmentId);
                }
                for (String cid : checkComps) {
                    try {
                        var allVnicAtts = computeClient.listVnicAttachments(ListVnicAttachmentsRequest.builder()
                                .compartmentId(cid).build()).getItems();
                        for (var va : allVnicAtts) {
                            if (va.getLifecycleState() != VnicAttachment.LifecycleState.Attached || vnicId.equals(va.getVnicId())) {
                                continue;
                            }
                            try {
                                var otherVnic = vncClient.getVnic(GetVnicRequest.builder().vnicId(va.getVnicId()).build()).getVnic();
                                if (otherVnic.getRouteTableId() != null && natRouteIdsInVcn.contains(otherVnic.getRouteTableId())) {
                                    anyOtherVnicUsingNatRoute = true;
                                    break;
                                }
                            } catch (Exception ignored) {}
                        }
                    } catch (Exception ignored) {}
                    if (anyOtherVnicUsingNatRoute) break;
                }

                if (!anyOtherVnicUsingNatRoute) {
                    for (RouteTable rt : rts) {
                        if (rt.getLifecycleState() == RouteTable.LifecycleState.Available
                                && "nat-route-500m".equalsIgnoreCase(rt.getDisplayName())
                                && !rt.getId().equals(subnet.getRouteTableId())) {
                            try {
                                // 先清空指向 NAT 网关的路由规则再删除路由表，彻底解除对 nat-gateway-500m 的引用锁
                                vncClient.updateRouteTable(UpdateRouteTableRequest.builder()
                                        .rtId(rt.getId())
                                        .updateRouteTableDetails(UpdateRouteTableDetails.builder()
                                                .routeRules(java.util.Collections.emptyList())
                                                .build())
                                        .build());
                            } catch (Exception ignored) {}
                            try {
                                vncClient.deleteRouteTable(DeleteRouteTableRequest.builder().rtId(rt.getId()).build());
                            } catch (Exception ignored) {}
                        }
                    }

                    var natGateways = vncClient.listNatGateways(ListNatGatewaysRequest.builder()
                            .compartmentId(vcnCompId).vcnId(vcnId).build()).getItems();
                    for (NatGateway ng : natGateways) {
                        if (ng.getLifecycleState() == NatGateway.LifecycleState.Available
                                && "nat-gateway-500m".equalsIgnoreCase(ng.getDisplayName())) {
                            for (int delAttempt = 0; delAttempt < 3; delAttempt++) {
                                try {
                                    vncClient.deleteNatGateway(DeleteNatGatewayRequest.builder().natGatewayId(ng.getId()).build());
                                    break;
                                } catch (com.oracle.bmc.model.BmcException ngEx) {
                                    if (ngEx.getStatusCode() == 404) break;
                                    if (delAttempt < 2) {
                                        try { Thread.sleep(1500); } catch (InterruptedException ignored) {}
                                    }
                                } catch (Exception ignored) {
                                    break;
                                }
                            }
                        }
                    }
                }
            } catch (Exception cleanupEx) {
                System.err.println("自动检查/清理闲置 500M NAT 网关警告: " + cleanupEx.getMessage());
            }
        }

        // 4. 清除缓存并重新拉取原生公网 IP
        OciCloudService.INSTANCE_CACHE.remove(ocid);
        redisTemplate.delete("oci:ip:" + ocid);

        String cleanIp = getInstanceIp(ocid, account, true);
        if (cleanIp == null || cleanIp.contains("无私网IP") || cleanIp.contains("关机")) {
            cleanIp = "未分配 IP";
        }
        return cleanIp;
    }

    public String getCachedIpv6(String ocid) {
        return redisTemplate.opsForValue().get("oci:ipv6:" + ocid);
    }

    public String getInstanceIpv6(String ocid, String accountName) {
        return getInstanceIpv6(ocid, accountName, false);
    }

    public String getInstanceIpv6(String ocid, String accountName, boolean forceRefresh) {
        if (!forceRefresh) {
            String cached = redisTemplate.opsForValue().get("oci:ipv6:" + ocid);
            if (cached != null) return cached;
        }

        try {
            OciProperties.Account accConfig = ociProperties.getAccounts().get(accountName);
            if (accConfig == null) return null;
            var clients = clientManager.getOciClients(accountName);
            String region = OciRegionUtil.getRegionForResource(ocid);
            var computeClient = region != null ? clients.getComputeClientForRegion(region) : clients.getCompute();
            var vncClient = region != null ? clients.getVncClientForRegion(region) : clients.getVnc();

            String compId = resolveInstanceCompartmentId(ocid, accConfig.getTenancy());
            var vnicResponse = computeClient.listVnicAttachments(ListVnicAttachmentsRequest.builder()
                    .compartmentId(compId).instanceId(ocid).build());
            if (vnicResponse.getItems().isEmpty()) {
                return null;
            }
            String vnicId = vnicResponse.getItems().get(0).getVnicId();

            var ip6Res = vncClient.listIpv6s(ListIpv6sRequest.builder().vnicId(vnicId).build());
            for (var ip6 : ip6Res.getItems()) {
                if (ip6.getLifecycleState() == Ipv6.LifecycleState.Available) {
                    String ip6Addr = ip6.getIpAddress();
                    redisTemplate.opsForValue().set("oci:ipv6:" + ocid, ip6Addr, 30, TimeUnit.DAYS);
                    return ip6Addr;
                }
            }
            redisTemplate.delete("oci:ipv6:" + ocid);
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    public String enableIpv6(String ocid, String accountName) throws Exception {
        OciProperties.Account accConfig = ociProperties.getAccounts().get(accountName);
        if (accConfig == null) {
            throw new RuntimeException("账号不存在: " + accountName);
        }

        String region = OciRegionUtil.getRegionForResource(ocid);
        var clients = clientManager.getOciClients(accountName);
        var computeClient = region != null ? clients.getComputeClientForRegion(region) : clients.getCompute();
        var vncClient = region != null ? clients.getVncClientForRegion(region) : clients.getVnc();

        String tenancy = accConfig.getTenancy();
        String compId = resolveInstanceCompartmentId(ocid, tenancy);

        var vnicResponse = computeClient.listVnicAttachments(ListVnicAttachmentsRequest.builder()
                .compartmentId(compId)
                .instanceId(ocid)
                .build());
        if (vnicResponse.getItems().isEmpty()) {
            throw new RuntimeException("未找到该实例绑定的虚拟网卡(VNIC)");
        }
        String vnicId = vnicResponse.getItems().get(0).getVnicId();
        Vnic vnic = vncClient.getVnic(GetVnicRequest.builder().vnicId(vnicId).build()).getVnic();

        // 1. 检查 VNIC 是否已存在可用 IPv6
        var existingIpv6s = vncClient.listIpv6s(ListIpv6sRequest.builder().vnicId(vnicId).build());
        for (Ipv6 ip6 : existingIpv6s.getItems()) {
            if (ip6.getLifecycleState() == Ipv6.LifecycleState.Available) {
                redisTemplate.opsForValue().set("oci:ipv6:" + ocid, ip6.getIpAddress(), 30, TimeUnit.DAYS);
                return ip6.getIpAddress();
            }
        }

        String subnetId = vnic.getSubnetId();
        Subnet subnet = vncClient.getSubnet(GetSubnetRequest.builder().subnetId(subnetId).build()).getSubnet();
        String vcnId = subnet.getVcnId();
        Vcn vcn = vncClient.getVcn(GetVcnRequest.builder().vcnId(vcnId).build()).getVcn();

        // 2. 检查并为 VCN 分配 Oracle IPv6 GUA (/56) 前缀
        List<String> vcnIpv6Cidrs = vcn.getIpv6CidrBlocks();
        if (vcnIpv6Cidrs == null || vcnIpv6Cidrs.isEmpty()) {
            vncClient.addIpv6VcnCidr(AddIpv6VcnCidrRequest.builder()
                    .vcnId(vcnId)
                    .addVcnIpv6CidrDetails(AddVcnIpv6CidrDetails.builder()
                            .isOracleGuaAllocationEnabled(true)
                            .build())
                    .build());

            long deadline = System.currentTimeMillis() + 15000;
            while (System.currentTimeMillis() < deadline) {
                Thread.sleep(1500);
                vcn = vncClient.getVcn(GetVcnRequest.builder().vcnId(vcnId).build()).getVcn();
                if (vcn.getIpv6CidrBlocks() != null && !vcn.getIpv6CidrBlocks().isEmpty()) {
                    vcnIpv6Cidrs = vcn.getIpv6CidrBlocks();
                    break;
                }
            }
            if (vcnIpv6Cidrs == null || vcnIpv6Cidrs.isEmpty()) {
                throw new RuntimeException("为 VCN 分配 Oracle IPv6 前缀超时，请稍后重试");
            }
        }
        String vcnIpv6Prefix = vcnIpv6Cidrs.get(0);

        // 3. 检查并为 Subnet 分配 /64 IPv6 CIDR
        List<String> subnetIpv6Cidrs = subnet.getIpv6CidrBlocks();
        if (subnetIpv6Cidrs == null || subnetIpv6Cidrs.isEmpty()) {
            var allSubnets = vncClient.listSubnets(ListSubnetsRequest.builder()
                    .compartmentId(tenancy)
                    .vcnId(vcnId)
                    .build()).getItems();
            Set<String> usedSubnetIpv6Cidrs = new HashSet<>();
            for (Subnet s : allSubnets) {
                if (s.getIpv6CidrBlocks() != null) {
                    usedSubnetIpv6Cidrs.addAll(s.getIpv6CidrBlocks());
                }
            }

            String targetSubnetCidr = calculateAvailableSubnetIpv6Cidr(vcnIpv6Prefix, usedSubnetIpv6Cidrs);
            if (targetSubnetCidr == null) {
                throw new RuntimeException("无法在 VCN IPv6 前缀 (" + vcnIpv6Prefix + ") 下分配空闲的 /64 子网");
            }

            vncClient.addIpv6SubnetCidr(AddIpv6SubnetCidrRequest.builder()
                    .subnetId(subnetId)
                    .addSubnetIpv6CidrDetails(AddSubnetIpv6CidrDetails.builder()
                            .ipv6CidrBlock(targetSubnetCidr)
                            .build())
                    .build());

            long deadline = System.currentTimeMillis() + 20000;
            while (System.currentTimeMillis() < deadline) {
                Thread.sleep(1500);
                subnet = vncClient.getSubnet(GetSubnetRequest.builder().subnetId(subnetId).build()).getSubnet();
                if (subnet.getLifecycleState() == Subnet.LifecycleState.Available
                        && subnet.getIpv6CidrBlocks() != null && !subnet.getIpv6CidrBlocks().isEmpty()) {
                    break;
                }
            }
        }

        // 4. 检查并确保出海路由表 (::/0 -> Internet Gateway)
        try {
            String routeTableId = subnet.getRouteTableId() != null ? subnet.getRouteTableId() : vcn.getDefaultRouteTableId();
            var igws = vncClient.listInternetGateways(ListInternetGatewaysRequest.builder()
                    .compartmentId(tenancy)
                    .vcnId(vcnId)
                    .build()).getItems();
            String igwId = null;
            for (var igwCandidate : igws) {
                if (igwCandidate.getLifecycleState() == InternetGateway.LifecycleState.Available
                        && Boolean.TRUE.equals(igwCandidate.getIsEnabled())) {
                    igwId = igwCandidate.getId();
                    break;
                }
            }
            if (igwId != null) {
                var rt = vncClient.getRouteTable(GetRouteTableRequest.builder().rtId(routeTableId).build()).getRouteTable();
                List<RouteRule> rules = new ArrayList<>();
                if (rt.getRouteRules() != null) rules.addAll(rt.getRouteRules());

                final String finalIgwId = igwId;
                boolean hasIpv6DefaultRoute = rules.stream().anyMatch(r ->
                        "::/0".equals(r.getDestination()) && finalIgwId.equals(r.getNetworkEntityId())
                );
                if (!hasIpv6DefaultRoute) {
                    rules.removeIf(r -> "::/0".equals(r.getDestination()));
                    rules.add(RouteRule.builder()
                            .networkEntityId(finalIgwId)
                            .destination("::/0")
                            .destinationType(RouteRule.DestinationType.CidrBlock)
                            .build());
                    vncClient.updateRouteTable(UpdateRouteTableRequest.builder()
                            .rtId(routeTableId)
                            .updateRouteTableDetails(UpdateRouteTableDetails.builder().routeRules(rules).build())
                            .build());
                }
            }
        } catch (Exception e) {
            System.err.println("配置 IPv6 路由规则警告 (可能受权限限制): " + e.getMessage());
        }

        // 5. 检查并确保安全列表 (放行 ::/0 全协议入站与出站)
        try {
            List<String> securityListIds = (subnet.getSecurityListIds() != null && !subnet.getSecurityListIds().isEmpty())
                    ? subnet.getSecurityListIds()
                    : Arrays.asList(vcn.getDefaultSecurityListId());
            for (String slId : securityListIds) {
                var sl = vncClient.getSecurityList(GetSecurityListRequest.builder().securityListId(slId).build()).getSecurityList();
                List<IngressSecurityRule> inRules = new ArrayList<>();
                if (sl.getIngressSecurityRules() != null) inRules.addAll(sl.getIngressSecurityRules());

                List<EgressSecurityRule> egRules = new ArrayList<>();
                if (sl.getEgressSecurityRules() != null) egRules.addAll(sl.getEgressSecurityRules());

                boolean hasIpv6In = inRules.stream().anyMatch(r -> "::/0".equals(r.getSource()) && "all".equalsIgnoreCase(r.getProtocol()));
                boolean hasIpv6Eg = egRules.stream().anyMatch(r -> "::/0".equals(r.getDestination()) && "all".equalsIgnoreCase(r.getProtocol()));

                if (!hasIpv6In || !hasIpv6Eg) {
                    if (!hasIpv6In) {
                        inRules.add(IngressSecurityRule.builder().source("::/0").protocol("all").isStateless(false).build());
                    }
                    if (!hasIpv6Eg) {
                        egRules.add(EgressSecurityRule.builder().destination("::/0").protocol("all").isStateless(false).build());
                    }
                    vncClient.updateSecurityList(UpdateSecurityListRequest.builder()
                            .securityListId(slId)
                            .updateSecurityListDetails(UpdateSecurityListDetails.builder()
                                    .ingressSecurityRules(inRules)
                                    .egressSecurityRules(egRules)
                                    .build())
                            .build());
                }
            }
        } catch (Exception e) {
            System.err.println("配置 IPv6 安全规则警告 (可能受权限限制): " + e.getMessage());
        }

        // 6. 向 VNIC 分配 IPv6 地址
        var createIpv6Res = vncClient.createIpv6(CreateIpv6Request.builder()
                .createIpv6Details(CreateIpv6Details.builder()
                        .vnicId(vnicId)
                        .displayName("ipv6-" + (System.currentTimeMillis() % 10000))
                        .build())
                .build());
        String assignedIp = createIpv6Res.getIpv6().getIpAddress();
        redisTemplate.opsForValue().set("oci:ipv6:" + ocid, assignedIp, 30, TimeUnit.DAYS);
        return assignedIp;
    }

    public boolean disableIpv6(String ocid, String accountName) throws Exception {
        OciProperties.Account accConfig = ociProperties.getAccounts().get(accountName);
        if (accConfig == null) {
            throw new RuntimeException("账号不存在: " + accountName);
        }

        String region = OciRegionUtil.getRegionForResource(ocid);
        var clients = clientManager.getOciClients(accountName);
        var computeClient = region != null ? clients.getComputeClientForRegion(region) : clients.getCompute();
        var vncClient = region != null ? clients.getVncClientForRegion(region) : clients.getVnc();

        String compId = resolveInstanceCompartmentId(ocid, accConfig.getTenancy());
        var vnicResponse = computeClient.listVnicAttachments(ListVnicAttachmentsRequest.builder()
                .compartmentId(compId)
                .instanceId(ocid)
                .build());
        if (vnicResponse.getItems().isEmpty()) {
            throw new RuntimeException("未找到该实例绑定的虚拟网卡(VNIC)");
        }
        String vnicId = vnicResponse.getItems().get(0).getVnicId();

        var ip6Res = vncClient.listIpv6s(ListIpv6sRequest.builder().vnicId(vnicId).build());
        for (var ip6 : ip6Res.getItems()) {
            try {
                vncClient.deleteIpv6(DeleteIpv6Request.builder().ipv6Id(ip6.getId()).build());
            } catch (Exception e) {
                System.err.println("删除 IPv6 [" + ip6.getId() + "] 异常: " + e.getMessage());
            }
        }
        redisTemplate.delete("oci:ipv6:" + ocid);
        return true;
    }

    public static String calculateAvailableSubnetIpv6Cidr(String vcnCidr, Set<String> usedSubnetCidrs) {
        try {
            if (vcnCidr == null || !vcnCidr.contains("/")) {
                return null;
            }
            String baseIpStr = vcnCidr.split("/")[0];
            java.net.InetAddress addr = java.net.InetAddress.getByName(baseIpStr);
            byte[] bytes = addr.getAddress();
            java.math.BigInteger baseInt = new java.math.BigInteger(1, bytes);

            // 56 位前缀掩码 (高 56 位为 1，低 72 位为 0)
            java.math.BigInteger mask = java.math.BigInteger.ONE
                    .shiftLeft(56).subtract(java.math.BigInteger.ONE)
                    .shiftLeft(72);
            baseInt = baseInt.and(mask);

            // 子网为 /64，增加 8 位子网编号 (0..255)
            for (int i = 0; i < 256; i++) {
                java.math.BigInteger subInt = baseInt.or(java.math.BigInteger.valueOf(i).shiftLeft(64));
                byte[] subBytes = new byte[16];
                byte[] raw = subInt.toByteArray();
                int srcPos = Math.max(0, raw.length - 16);
                int dstPos = Math.max(0, 16 - raw.length);
                int length = Math.min(16, raw.length);
                System.arraycopy(raw, srcPos, subBytes, dstPos, length);

                java.net.InetAddress subAddr = java.net.InetAddress.getByAddress(subBytes);
                String candidateCidr = subAddr.getHostAddress() + "/64";

                boolean used = false;
                if (usedSubnetCidrs != null) {
                    for (String u : usedSubnetCidrs) {
                        if (u != null) {
                            try {
                                String uIp = u.split("/")[0];
                                if (java.net.InetAddress.getByName(uIp).equals(subAddr)) {
                                    used = true;
                                    break;
                                }
                            } catch (Exception ignored) {}
                        }
                    }
                }
                if (!used) {
                    return candidateCidr;
                }
            }
        } catch (Exception e) {
            System.err.println("计算 IPv6 子网 CIDR 异常: " + e.getMessage());
        }
        return null;
    }
}
