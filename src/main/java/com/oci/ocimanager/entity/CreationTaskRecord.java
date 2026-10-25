package com.oci.ocimanager.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "creation_tasks")
public class CreationTaskRecord {
    @Id
    private String id;
    private String account;
    // 🌟 新增字段：用于在数据库中持久化自定义的服务器实例名称
    private String instanceName;
    private String region;
    private String shape;
    private String imageId;
    private float ocpus;
    private float memory;
    private long disk;
    private int instances;
    private int successCount = 0;
    private int retryInterval;
    private boolean publicIp;
    private boolean ipv6;
    private boolean autoStop;
    private String tgId;
    private String password;
    private String status;
    private long startTime;
    private int attemptCount;

    @Column(columnDefinition = "TEXT")
    private String log;

    // 🌟 修复：新增系统与公钥持久化字段，防止重启后由于变 null 导致线程崩溃
    private String osName;
    private String osVersion;
    @Column(columnDefinition = "TEXT")
    private String sshKey;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getAccount() { return account; }
    public void setAccount(String account) { this.account = account; }

    public String getInstanceName() { return instanceName; }
    public void setInstanceName(String instanceName) { this.instanceName = instanceName; }

    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }

    public String getShape() { return shape; }
    public void setShape(String shape) { this.shape = shape; }

    public String getImageId() { return imageId; }
    public void setImageId(String imageId) { this.imageId = imageId; }

    public float getOcpus() { return ocpus; }
    public void setOcpus(float ocpus) { this.ocpus = ocpus; }

    public float getMemory() { return memory; }
    public void setMemory(float memory) { this.memory = memory; }

    public long getDisk() { return disk; }
    public void setDisk(long disk) { this.disk = disk; }

    public int getInstances() { return instances; }
    public void setInstances(int instances) { this.instances = instances; }

    public int getSuccessCount() { return successCount; }
    public void setSuccessCount(int successCount) { this.successCount = successCount; }

    public int getRetryInterval() { return retryInterval; }
    public void setRetryInterval(int retryInterval) { this.retryInterval = retryInterval; }

    public boolean isPublicIp() { return publicIp; }
    public void setPublicIp(boolean publicIp) { this.publicIp = publicIp; }

    public boolean isIpv6() { return ipv6; }
    public void setIpv6(boolean ipv6) { this.ipv6 = ipv6; }

    public boolean isAutoStop() { return autoStop; }
    public void setAutoStop(boolean autoStop) { this.autoStop = autoStop; }

    public String getTgId() { return tgId; }
    public void setTgId(String tgId) { this.tgId = tgId; }

    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public long getStartTime() { return startTime; }
    public void setStartTime(long startTime) { this.startTime = startTime; }

    public int getAttemptCount() { return attemptCount; }
    public void setAttemptCount(int attemptCount) { this.attemptCount = attemptCount; }

    public String getLog() { return log; }
    public void setLog(String log) { this.log = log; }

    public String getOsName() { return osName; }
    public void setOsName(String osName) { this.osName = osName; }

    public String getOsVersion() { return osVersion; }
    public void setOsVersion(String osVersion) { this.osVersion = osVersion; }

    public String getSshKey() { return sshKey; }
    public void setSshKey(String sshKey) { this.sshKey = sshKey; }
}
