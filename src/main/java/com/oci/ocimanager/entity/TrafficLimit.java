package com.oci.ocimanager.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;

@Entity
public class TrafficLimit {

    @Id
    private String accountName;
    
    private Integer limitGb;
    
    // 🌟 新增持久化字段：用于记录最后一次发送流量警告的月份 (格式如 "2026-05")
    // 防止应用重启、代码更新后丢失内存状态导致疯狂重复推送通知
    private String lastWarningMonth; 

    public String getAccountName() {
        return accountName;
    }

    public void setAccountName(String accountName) {
        this.accountName = accountName;
    }

    public Integer getLimitGb() {
        return limitGb;
    }

    public void setLimitGb(Integer limitGb) {
        this.limitGb = limitGb;
    }

    public String getLastWarningMonth() {
        return lastWarningMonth;
    }

    public void setLastWarningMonth(String lastWarningMonth) {
        this.lastWarningMonth = lastWarningMonth;
    }
}
