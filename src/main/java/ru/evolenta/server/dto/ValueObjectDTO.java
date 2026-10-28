/*
 * Copyright (c) 2016-2024 Evolenta JSC. Система исполнения электронных регламентов и межведомственного взаимодействия (СИЭР)
 */
package ru.evolenta.server.dto;

import java.util.Map;

public class ValueObjectDTO {

    private String type;
    private Object value;
    private Map<String, Object> valueInfo;

    public ValueObjectDTO() {
    }

    public ValueObjectDTO(String value) {
        this.value = value;
        this.type = "String";
    }

    public ValueObjectDTO(String type, String value, Map<String, Object> valueInfo) {
        this.type = type;
        this.value = value;
        this.valueInfo = valueInfo;
    }

    public ValueObjectDTO(String type, Object value, Map<String, Object> valueInfo) {
        this.type = type;
        this.value = value;
        this.valueInfo = valueInfo;
    }

    public String getType() {
        return type;
    }

    public Object getValue() {
        return value;
    }

    public Map<String, Object> getValueInfo() {
        return valueInfo;
    }

    @Override
    public String toString() {
        return "ValueObjectDTO{" +
                "type='" + type + '\'' +
                ", value=" + value +
                ", valueInfo=" + valueInfo +
                '}';
    }
}
