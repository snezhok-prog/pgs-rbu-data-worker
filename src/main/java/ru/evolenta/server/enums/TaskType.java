/*
 * Copyright (c) 2016-2024 Evolenta JSC. Система исполнения электронных регламентов и межведомственного взаимодействия (СИЭР)
 */
package ru.evolenta.server.enums;

public enum TaskType {

    TASK("task"),
    SCRIPT_TASK("scriptTask"),
    SERVICE_TASK("serviceTask"),
    BUSINESS_RULE_TASK("businessRuleTask"),
    MANUAL_TASK("manualTask"),
    USER_TASK("userTask"),
    SEND_TASK("sendTask"),
    RECEIVE_TASK("receiveTask");

    TaskType(String name) {
        this.name = name;
    }

    private final String name;

    public String getName() {
        return name;
    }

}