package com.oci.ocimanager.controller;

import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/** 接口失败时只返回固定说明，细节留在服务端日志。 */
public final class ApiErrors {

    private static final Logger LOG = Logger.getLogger(ApiErrors.class.getName());

    private ApiErrors() {}

    public static Map<String, Object> fail(String publicMessage, Exception e) {
        LOG.log(Level.WARNING, publicMessage, e);
        return Map.of("success", false, "error", publicMessage);
    }
}
