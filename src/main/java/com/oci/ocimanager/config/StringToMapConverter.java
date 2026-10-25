package com.oci.ocimanager.config;

import org.springframework.boot.context.properties.ConfigurationPropertiesBinding;
import org.springframework.core.convert.converter.Converter;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 容错转换器：当 application.yml 中配置如 `accounts:` 为空或空字符串时，
 * 自动将其安全转换为空的 Map，避免 Spring Boot 抛出 ConverterNotFoundException。
 */
@Component
@ConfigurationPropertiesBinding
public class StringToMapConverter implements Converter<String, Map<?, ?>> {

    @Override
    public Map<?, ?> convert(String source) {
        if (source == null || source.trim().isEmpty() || "{}".equals(source.trim())) {
            return new LinkedHashMap<>();
        }
        return Collections.emptyMap();
    }
}
