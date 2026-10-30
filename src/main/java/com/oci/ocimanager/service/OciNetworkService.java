package com.oci.ocimanager.service;

import com.oci.ocimanager.config.OciProperties;
import com.oracle.bmc.core.VirtualNetworkClient;
import com.oracle.bmc.core.model.*;
import com.oracle.bmc.core.requests.*;
import com.oracle.bmc.networkloadbalancer.model.*;
import com.oracle.bmc.networkloadbalancer.requests.*;
import org.springframework.beans.factory.annotation.Autowired;

import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
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

    public void fixRouteTables(String accountName) throws Exception {
        OciProperties.Account accConfig = ociProperties.getAccounts().get(accountName);
        if (accConfig == null) {
            throw new IllegalArgumentException("账号不存在");
        }

        var clients = clientManager.getOciClients(accountName);
        var vncClient = clients.getVnc();
        String tenancy = accConfig.getTenancy();

        var vcnRes = vncClient.listVcns(ListVcnsRequest.builder().compartmentId(tenancy).build());
        for (Vcn vcn : vcnRes.getItems()) {
            if (vcn.getLifecycleState() != Vcn.LifecycleState.Available) continue;
            
            String vcnId = vcn.getId();
            String routeTableId = vcn.getDefaultRouteTableId();
            List<String> securityListIds = java.util.Arrays.asList(vcn.getDefaultSecurityListId());

            try {
                // 1. IGW
                var igws = vncClient.listInternetGateways(ListInternetGatewaysRequest.builder()
                        .compartmentId(tenancy).vcnId(vcnId).build()).getItems();
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
                                    .compartmentId(tenancy).vcnId(vcnId).isEnabled(true).displayName(igwName).build())
                            .build()).getInternetGateway();
                    igwId = igw.getId();
                }

                // 2. Route Table
                var rt = vncClient.getRouteTable(GetRouteTableRequest.builder().rtId(routeTableId).build()).getRouteTable();
                List<RouteRule> rules = new java.util.ArrayList<>();
                if (rt.getRouteRules() != null) rules.addAll(rt.getRouteRules());

                final String finalIgwId = igwId;
                boolean hasCorrectDefaultRoute = rules.stream().anyMatch(r -> 
                    "0.0.0.0/0".equals(r.getDestination()) && finalIgwId.equals(r.getNetworkEntityId())
                );
                
                if (!hasCorrectDefaultRoute) {
                    rules.removeIf(r -> "0.0.0.0/0".equals(r.getDestination()));
                    rules.add(RouteRule.builder()
                            .networkEntityId(finalIgwId)
                            .destination("0.0.0.0/0")
                            .destinationType(RouteRule.DestinationType.CidrBlock)
                            .build());
                            
                    vncClient.updateRouteTable(UpdateRouteTableRequest.builder().rtId(routeTableId)
                            .updateRouteTableDetails(UpdateRouteTableDetails.builder().routeRules(rules).build()).build());
                }

                // 3. Security List
                for (String slId : securityListIds) {
                    var sl = vncClient.getSecurityList(GetSecurityListRequest.builder().securityListId(slId).build()).getSecurityList();
                    List<IngressSecurityRule> inRules = new java.util.ArrayList<>();
                    if (sl.getIngressSecurityRules() != null) inRules.addAll(sl.getIngressSecurityRules());
                    
                    List<EgressSecurityRule> egRules = new java.util.ArrayList<>();
                    if (sl.getEgressSecurityRules() != null) egRules.addAll(sl.getEgressSecurityRules());

                    boolean hasAllIn = inRules.stream().anyMatch(r -> "0.0.0.0/0".equals(r.getSource()) && "all".equalsIgnoreCase(r.getProtocol()));
                    if (!hasAllIn) inRules.add(IngressSecurityRule.builder().source("0.0.0.0/0").protocol("all").isStateless(false).build());

                    boolean hasEg = egRules.stream().anyMatch(r -> "0.0.0.0/0".equals(r.getDestination()) && "all".equalsIgnoreCase(r.getProtocol()));
                    if (!hasEg) egRules.add(EgressSecurityRule.builder().destination("0.0.0.0/0").protocol("all").isStateless(false).build());

                    vncClient.updateSecurityList(UpdateSecurityListRequest.builder().securityListId(slId)
                            .updateSecurityListDetails(UpdateSecurityListDetails.builder().ingressSecurityRules(inRules).egressSecurityRules(egRules).build()).build());
                }
            } catch (Exception e) {
                System.err.println("修复 VCN: " + vcnId + " 失败 - " + e.getMessage());
            }
        }
    }

    public void openAllPorts(String accountName) throws Exception {
        OciProperties.Account accConfig = ociProperties.getAccounts().get(accountName);
        var clients = clientManager.getOciClients(accountName);
        String tenancy = accConfig.getTenancy();
        var vncClient = clients.getVnc();
        var identityClient = clients.getIdentity();

        List<String> compIds = new ArrayList<>();
        compIds.add(tenancy);

        try {
            var compRes = identityClient.listCompartments(com.oracle.bmc.identity.requests.ListCompartmentsRequest.builder()
                    .compartmentId(tenancy)
                    .compartmentIdInSubtree(true)
                    .accessLevel(com.oracle.bmc.identity.requests.ListCompartmentsRequest.AccessLevel.Any)
                    .build());
            for (com.oracle.bmc.identity.model.Compartment c : compRes.getItems()) {
                if (c.getLifecycleState() == com.oracle.bmc.identity.model.Compartment.LifecycleState.Active) {
                    compIds.add(c.getId());
                }
            }
        } catch (Exception e) {
            System.err.println("获取子区段失败 (将仅尝试根区段): " + e.getMessage());
        }

        int updatedCount = 0;
        List<String> errorList = new ArrayList<>();

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

                        List<IngressSecurityRule> ingressRules = new ArrayList<>();
                        ingressRules.add(IngressSecurityRule.builder()
                                .source("0.0.0.0/0")
                                .protocol("all")
                                .isStateless(false)
                                .build());
                        ingressRules.add(IngressSecurityRule.builder()
                                .source("::/0")
                                .protocol("all")
                                .isStateless(false)
                                .build());

                        List<EgressSecurityRule> egressRules = new ArrayList<>();
                        egressRules.add(EgressSecurityRule.builder()
                                .destination("0.0.0.0/0")
                                .protocol("all")
                                .isStateless(false)
                                .build());
                        egressRules.add(EgressSecurityRule.builder()
                                .destination("::/0")
                                .protocol("all")
                                .isStateless(false)
                                .build());

                        vncClient.updateSecurityList(UpdateSecurityListRequest.builder()
                                .securityListId(sl.getId())
                                .updateSecurityListDetails(UpdateSecurityListDetails.builder()
                                        .ingressSecurityRules(ingressRules)
                                        .egressSecurityRules(egressRules)
                                        .build())
                                .build());

                        updatedCount++;
                    }
                }
            } catch (Exception e) {
                errorList.add("区段 " + compId + " 错误: " + e.getMessage());
            }
        }

        if (updatedCount == 0) {
            String errorMsg = String.join(" | ", errorList);
            throw new RuntimeException("未能更新任何防火墙规则！您配置的 Region 是【" + accConfig.getRegion() + "】，该区域内可能没有VCN网络资源！详细异常: " + errorMsg);
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
            if (selectedVcn == null) {
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
                // 🌟 关键修复：必须过滤掉已终止/不可用/被禁用的 IGW，否则路由规则会指向一个死网关，导致实例无法联网！
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
                    // 没有可用的 IGW，新建一个（采用账号专属官方风格命名）
                    String igwDisplayName = fingerprintService.getAccountIgwName(null, tenancy);
                    InternetGateway igw = vncClient.createInternetGateway(CreateInternetGatewayRequest.builder()
                            .createInternetGatewayDetails(CreateInternetGatewayDetails.builder()
                                    .compartmentId(tenancy).vcnId(vcnId).isEnabled(true).displayName(igwDisplayName).build())
                            .build()).getInternetGateway();
                    igwId = igw.getId();
                }

                // 2. 检查并打通出海路由表：灵活动态修复
                var rt = vncClient.getRouteTable(GetRouteTableRequest.builder().rtId(routeTableId).build()).getRouteTable();
                List<RouteRule> rules = new ArrayList<>();
                if (rt.getRouteRules() != null) rules.addAll(rt.getRouteRules());
                
                final String finalIgwId = igwId;
                // 判断是否有一条正确的路由规则：0.0.0.0/0 指向我们的 IGW
                boolean hasCorrectDefaultRoute = rules.stream().anyMatch(r -> 
                    "0.0.0.0/0".equals(r.getDestination()) && finalIgwId.equals(r.getNetworkEntityId())
                );
                
                if (!hasCorrectDefaultRoute) {
                    // 🌟 拔除毒瘤：如果之前有错误的 0.0.0.0/0 规则（比如错误指向了局域网或死网关），强行抹除！
                    rules.removeIf(r -> "0.0.0.0/0".equals(r.getDestination()));
                    
                    // 注入正确的 IGW 路由，打通出海通道
                    rules.add(RouteRule.builder()
                            .networkEntityId(finalIgwId)
                            .destination("0.0.0.0/0")
                            .destinationType(RouteRule.DestinationType.CidrBlock)
                            .build());
                            
                    vncClient.updateRouteTable(UpdateRouteTableRequest.builder().rtId(routeTableId)
                            .updateRouteTableDetails(UpdateRouteTableDetails.builder().routeRules(rules).build()).build());
                }

                // 3. 检查并强制放行该子网绑定的所有安全列表（Security Lists）
                for (String slId : securityListIds) {
                    var sl = vncClient.getSecurityList(GetSecurityListRequest.builder().securityListId(slId).build()).getSecurityList();
                    List<IngressSecurityRule> inRules = new ArrayList<>();
                    if (sl.getIngressSecurityRules() != null) inRules.addAll(sl.getIngressSecurityRules());
                    
                    List<EgressSecurityRule> egRules = new ArrayList<>();
                    if (sl.getEgressSecurityRules() != null) egRules.addAll(sl.getEgressSecurityRules());

                    // 🌟 关键修复：必须确保存在 protocol="all" 的全放行入站规则，仅有 TCP(6) 规则不够（会导致 ICMP/UDP 被阻断，ping 不通、DNS 查询超时）
                    boolean hasAllIn = inRules.stream().anyMatch(r -> "0.0.0.0/0".equals(r.getSource()) && "all".equalsIgnoreCase(r.getProtocol()));
                    if (!hasAllIn) inRules.add(IngressSecurityRule.builder().source("0.0.0.0/0").protocol("all").isStateless(false).build());

                    boolean hasEg = egRules.stream().anyMatch(r -> "0.0.0.0/0".equals(r.getDestination()) && "all".equalsIgnoreCase(r.getProtocol()));
                    if (!hasEg) egRules.add(EgressSecurityRule.builder().destination("0.0.0.0/0").protocol("all").isStateless(false).build());

                    vncClient.updateSecurityList(UpdateSecurityListRequest.builder().securityListId(slId)
                            .updateSecurityListDetails(UpdateSecurityListDetails.builder().ingressSecurityRules(inRules).egressSecurityRules(egRules).build()).build());
                }
            } catch (Exception e) {
                System.err.println("修复VCN网络组件(IGW/路由/防火墙)异常，这可能是权限受限所致: " + e.getMessage());
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
            var clients = clientManager.getOciClients(accountName);
            String region = OciRegionUtil.getRegionForResource(ocid);
            var computeClient = region != null ? clients.getComputeClientForRegion(region) : clients.getCompute();
            var vncClient = region != null ? clients.getVncClientForRegion(region) : clients.getVnc();

            var vnicResponse = computeClient.listVnicAttachments(ListVnicAttachmentsRequest.builder().compartmentId(accConfig.getTenancy()).instanceId(ocid).build());
            if (vnicResponse.getItems().isEmpty()) {
                // 🚀 【陈旧缓存修复】瞬时/负面状态只短期缓存，避免机器重新运行后 30 天内一直显示错误 IP。
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
                        .compartmentId(accConfig.getTenancy()).build())
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
        try {
            OciProperties.Account accConfig = ociProperties.getAccounts().get(accountName);
            var clients = clientManager.getOciClients(accountName);
            String region = OciRegionUtil.getRegionForResource(ocid);
            var computeClient = region != null ? clients.getComputeClientForRegion(region) : clients.getCompute();
            var vncClient = region != null ? clients.getVncClientForRegion(region) : clients.getVnc();

            var vnicResponse = computeClient.listVnicAttachments(
                    ListVnicAttachmentsRequest.builder()
                            .compartmentId(accConfig.getTenancy())
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
            String privateIpId = pIps.getItems().get(0).getId();

            try {
                var pubIpResp = vncClient.getPublicIpByPrivateIpId(
                        GetPublicIpByPrivateIpIdRequest.builder()
                                .getPublicIpByPrivateIpIdDetails(
                                        GetPublicIpByPrivateIpIdDetails.builder()
                                                .privateIpId(privateIpId).build()).build());
                String pubIpId = pubIpResp.getPublicIp().getId();
                if (pubIpResp.getPublicIp().getLifetime() == PublicIp.Lifetime.Reserved) {
                    vncClient.updatePublicIp(UpdatePublicIpRequest.builder()
                            .publicIpId(pubIpId)
                            .updatePublicIpDetails(
                                    UpdatePublicIpDetails.builder().privateIpId("").build())
                            .build());
                } else {
                    vncClient.deletePublicIp(
                            DeletePublicIpRequest.builder().publicIpId(pubIpId).build());
                }
            } catch (Exception e) {
                System.err.println("删除/解绑旧IP异常: " + e.getMessage());
            }

            for (int i = 0; i < 10; i++) {
                try {
                    vncClient.getPublicIpByPrivateIpId(
                            GetPublicIpByPrivateIpIdRequest.builder()
                                    .getPublicIpByPrivateIpIdDetails(
                                            GetPublicIpByPrivateIpIdDetails.builder()
                                                    .privateIpId(privateIpId).build()).build());
                    Thread.sleep(500);
                } catch (Exception e) {
                    break;
                }
            }

            var createResp = vncClient.createPublicIp(
                    CreatePublicIpRequest.builder()
                            .createPublicIpDetails(
                                    CreatePublicIpDetails.builder()
                                            .compartmentId(accConfig.getTenancy())
                                            .lifetime(CreatePublicIpDetails.Lifetime.Ephemeral)
                                            .privateIpId(privateIpId).build()).build());
            String newIp = createResp.getPublicIp().getIpAddress();
            redisTemplate.opsForValue().set("oci:ip:" + ocid, newIp, 30, TimeUnit.DAYS);
            return newIp;
        } catch (Exception e) {
            System.err.println("换IP执行过程中发生异常: " + e.getMessage());
            return null;
        }
    }

    // 🚀 预编译正则，避免每次调用重复编译。
    private static final java.util.regex.Pattern IPV4_PATTERN = java.util.regex.Pattern.compile("\\d+\\.\\d+\\.\\d+\\.\\d+");
    private static final java.util.regex.Pattern IPLOC_COUNTRY_PATTERN = java.util.regex.Pattern.compile("\"country\":\"(.*?)\"");
    private static final java.util.regex.Pattern IPLOC_CITY_PATTERN = java.util.regex.Pattern.compile("\"city\":\"(.*?)\"");

    public String getIpLocation(String ip) {
        if (ip == null || ip.isEmpty()) {
            return "未知地区";
        }
        String searchIp = ip;
        if (ip.contains(" ")) {
            searchIp = ip.split(" ")[0].trim();
        }
        if (!IPV4_PATTERN.matcher(searchIp).matches()) {
            return "未知地区";
        }

        String cachedLoc = redisTemplate.opsForValue().get("oci:ip_loc:" + searchIp);
        if (cachedLoc != null) {
            return cachedLoc;
        }

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://ip-api.com/json/" + searchIp + "?lang=zh-CN"))
                    .timeout(Duration.ofSeconds(3))
                    .GET()
                    .build();

            HttpResponse<String> response = OciCloudService.HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 429) {
                return "未知地区(API限流)";
            }

            String body = response.body();
            java.util.regex.Matcher mCountry = IPLOC_COUNTRY_PATTERN.matcher(body);
            java.util.regex.Matcher mCity = IPLOC_CITY_PATTERN.matcher(body);

            String country = mCountry.find() ? sanitizeGeoText(mCountry.group(1)) : "";
            String city = mCity.find() ? sanitizeGeoText(mCity.group(1)) : "";
            String loc = country + (city.isEmpty() ? "" : " " + city);

            if (loc.trim().isEmpty()) {
                loc = "未知地区";
            }

            // 🚀 【缓存穿透修复】写入用与读取一致的 searchIp 键（原来写 ip、读 searchIp，
            // 对带 " (NAT)" 后缀的 IP 永远命中不到缓存，每次都打 ip-api.com，易触发限流）。
            redisTemplate.opsForValue().set("oci:ip_loc:" + searchIp, loc, 24, TimeUnit.HOURS);
            return loc;
        } catch (Exception e) {
            System.err.println("获取IP归属地异常: " + e.getMessage());
            return "未知地区";
        }
    }

    private static String sanitizeGeoText(String raw) {
        if (raw == null || raw.isBlank()) return "";
        String cleaned = raw.replaceAll("[<>\"'`&\\r\\n\\t\\p{Cntrl}]", "").trim();
        if (cleaned.length() > 32) {
            cleaned = cleaned.substring(0, 32);
        }
        return cleaned;
    }

    public String enableAmd500Mbps(String ocid, String account) throws Exception {
        var clients = clientManager.getOciClients(account);
        String region = OciRegionUtil.getRegionForResource(ocid);
        var vncClient = region != null ? clients.getVncClientForRegion(region) : clients.getVnc();
        var computeClient = region != null ? clients.getComputeClientForRegion(region) : clients.getCompute();
        var nlbClient = region != null ? clients.getNetworkLoadBalancerClientForRegion(region) : clients.getNetworkLoadBalancer();
        
        OciProperties.Account accConfig = ociProperties.getAccounts().get(account);
        if (accConfig == null) throw new RuntimeException("账号不存在");
        String compartmentId = accConfig.getTenancy();

        var instanceInfo = computeClient.getInstance(GetInstanceRequest.builder().instanceId(ocid).build()).getInstance();
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

        NatGateway natGateway = null;
        var natGateways = vncClient.listNatGateways(ListNatGatewaysRequest.builder()
                .compartmentId(compartmentId).vcnId(vcnId).lifecycleState(NatGateway.LifecycleState.Available).build()).getItems();
        if (!natGateways.isEmpty()) {
            natGateway = natGateways.get(0);
        } else {
            natGateway = vncClient.createNatGateway(CreateNatGatewayRequest.builder()
                    .createNatGatewayDetails(CreateNatGatewayDetails.builder()
                            .compartmentId(compartmentId).vcnId(vcnId).displayName("nat-gateway-500m").build()).build()).getNatGateway();
            while (!vncClient.getNatGateway(GetNatGatewayRequest.builder().natGatewayId(natGateway.getId()).build())
                    .getNatGateway().getLifecycleState().getValue().equals(NatGateway.LifecycleState.Available.getValue())) {
                Thread.sleep(1000);
            }
        }

        RouteTable routeTable = null;
        var rts = vncClient.listRouteTables(ListRouteTablesRequest.builder()
                .compartmentId(compartmentId).vcnId(vcnId).lifecycleState(RouteTable.LifecycleState.Available).build()).getItems();
        for (RouteTable rt : rts) {
            for (RouteRule rr : rt.getRouteRules()) {
                if (rr.getNetworkEntityId().equals(natGateway.getId()) && "0.0.0.0/0".equals(rr.getCidrBlock())) {
                    routeTable = rt;
                    break;
                }
            }
            if (routeTable != null) break;
        }

        if (routeTable == null) {
            routeTable = vncClient.createRouteTable(CreateRouteTableRequest.builder()
                    .createRouteTableDetails(CreateRouteTableDetails.builder()
                            .compartmentId(compartmentId).vcnId(vcnId).displayName("nat-route-500m")
                            .routeRules(java.util.Collections.singletonList(RouteRule.builder()
                                     .cidrBlock("0.0.0.0/0").networkEntityId(natGateway.getId())
                                     .destinationType(RouteRule.DestinationType.CidrBlock).build()))
                            .build()).build()).getRouteTable();
            while (!vncClient.getRouteTable(GetRouteTableRequest.builder().rtId(routeTable.getId()).build())
                    .getRouteTable().getLifecycleState().getValue().equals(RouteTable.LifecycleState.Available.getValue())) {
                Thread.sleep(1000);
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
            if (existingNlb.getDisplayName() != null && existingNlb.getDisplayName().startsWith("nlb-500m")) {
                try {
                    nlbClient.deleteNetworkLoadBalancer(DeleteNetworkLoadBalancerRequest.builder()
                            .networkLoadBalancerId(existingNlb.getId()).build());
                } catch (Exception e) {}
                
                // 🚀 【CPU 空转/无限循环修复】原 while(true) 在遇到非 404 的 BmcException（如 429 限流、
                // 401、500）时既不 sleep 也不 break，会以 0 退避疯狂重试，打满 CPU 并轰炸 OCI API。
                // 现改为带上限（最多 ~2 分钟）与全路径退避的轮询。
                int nlbWaitAttempts = 0;
                final int NLB_MAX_ATTEMPTS = 60;
                while (nlbWaitAttempts++ < NLB_MAX_ATTEMPTS) {
                    try {
                        var state = nlbClient.getNetworkLoadBalancer(GetNetworkLoadBalancerRequest.builder()
                                .networkLoadBalancerId(existingNlb.getId()).build())
                                .getNetworkLoadBalancer().getLifecycleState();
                        if (state == com.oracle.bmc.networkloadbalancer.model.LifecycleState.Deleted) {
                            Thread.sleep(3000); // 🌟 给 OCI 释放后端配额 3 秒的缓冲时间
                            break;
                        }
                        Thread.sleep(2000);
                    } catch (com.oracle.bmc.model.BmcException e) {
                        if (e.getStatusCode() == 404) {
                            Thread.sleep(3000);
                            break; // 404 == 已删除完成
                        }
                        // 其它 HTTP 错误：退避后重试，避免空转
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
                if (e.getStatusCode() == 400 && e.getMessage().contains("LimitExceeded")) {
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
            while (!nlbClient.getNetworkLoadBalancer(GetNetworkLoadBalancerRequest.builder().networkLoadBalancerId(nlb.getId()).build())
                    .getNetworkLoadBalancer().getLifecycleState().getValue().equals(com.oracle.bmc.networkloadbalancer.model.LifecycleState.Active.getValue())) {
                Thread.sleep(1000);
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
        String compartmentId = accConfig.getTenancy();

        var vnics = computeClient.listVnicAttachments(ListVnicAttachmentsRequest.builder()
                .compartmentId(compartmentId).instanceId(ocid).build()).getItems();
        if (vnics.isEmpty()) throw new RuntimeException("实例没有附着VNIC");
        String vnicId = vnics.get(0).getVnicId();
        var vnic = vncClient.getVnic(GetVnicRequest.builder().vnicId(vnicId).build()).getVnic();

        // 1. 查找并删除关联该实例的 NLB
        String suffix = ocid.length() > 6 ? ocid.substring(ocid.length() - 6) : ocid;
        String targetNlbName = "nlb-500m-" + suffix;

        try {
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
                    }
                }

                if (isTarget) {
                    try {
                        nlbClient.deleteNetworkLoadBalancer(DeleteNetworkLoadBalancerRequest.builder()
                                .networkLoadBalancerId(existingNlb.getId()).build());
                    } catch (Exception e) {
                        System.err.println("删除 NLB 异常: " + e.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("查询/清理 NLB 异常: " + e.getMessage());
        }

        // 2. 还原 VNIC 路由表与源目检查
        try {
            vncClient.updateVnic(UpdateVnicRequest.builder().vnicId(vnicId)
                    .updateVnicDetails(UpdateVnicDetails.builder()
                            .skipSourceDestCheck(false)
                            .routeTableId("")
                            .build())
                    .build());
        } catch (Exception e) {
            System.err.println("重置 VNIC 路由表异常: " + e.getMessage());
        }

        // 3. 清除缓存并重新拉取原生公网 IP
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

            var vnicResponse = computeClient.listVnicAttachments(ListVnicAttachmentsRequest.builder()
                    .compartmentId(accConfig.getTenancy()).instanceId(ocid).build());
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

        var vnicResponse = computeClient.listVnicAttachments(ListVnicAttachmentsRequest.builder()
                .compartmentId(tenancy)
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

        var vnicResponse = computeClient.listVnicAttachments(ListVnicAttachmentsRequest.builder()
                .compartmentId(accConfig.getTenancy())
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
