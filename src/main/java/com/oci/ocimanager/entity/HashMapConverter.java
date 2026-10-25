package com.oci.ocimanager.entity;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.util.HashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

@Converter
public class HashMapConverter implements AttributeConverter<HashMap<String, String>, String> {

    private static final Logger LOG = Logger.getLogger(HashMapConverter.class.getName());

    private final ObjectMapper objectMapper = new ObjectMapper();

    // 将 Java 的 HashMap 转换为 JSON 字符串存入数据库
    @Override
    public String convertToDatabaseColumn(HashMap<String, String> attribute) {
        if (attribute == null) {
            return "{}";
        }
        try {
            return objectMapper.writeValueAsString(attribute);
        } catch (JsonProcessingException e) {
            // 🐞 序列化失败不能静默写 "{}"，否则会把用户的 OCID 映射抹掉；抛出让上层感知。
            LOG.log(Level.SEVERE, "序列化 ocids 失败，已中止写入以避免数据丢失", e);
            throw new IllegalStateException("序列化 ocids 失败", e);
        }
    }

    // 将数据库的 JSON 字符串转换回 Java 的 HashMap
    @Override
    public HashMap<String, String> convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.trim().isEmpty()) {
            return new HashMap<>();
        }
        try {
            return objectMapper.readValue(dbData, new TypeReference<HashMap<String, String>>() {});
        } catch (JsonProcessingException e) {
            // 🐞 【数据丢失修复】原实现解析失败时返回空 Map，随后一次 save 就会把用户的机器/额度映射
            // 永久清空。改为记录原始值并抛出，让读取显式失败（AdminDashboardController 已有 JDBC 回退路径处理），
            // 绝不静默用空 Map 覆盖有效数据。
            String preview = dbData.length() > 200 ? dbData.substring(0, 200) + "..." : dbData;
            LOG.log(Level.SEVERE, "解析 ocids JSON 失败，拒绝以空 Map 覆盖。原始值: " + preview, e);
            throw new IllegalStateException("ocids 数据已损坏，已阻止静默清空", e);
        }
    }
}
