/*
 * Copyright (c) 2016-2024 Evolenta JSC. Система исполнения электронных регламентов и межведомственного взаимодействия (СИЭР)
 */
package ru.evolenta.server.dto.request;

import java.util.Map;

public class ExternalTaskTopicReqDTO {

    private String topicName;
    private Integer lockDuration;
    private String[] variables;
    private Boolean localVariables;
    private String businessKey;
    private String processDefinitionId;
    private String[] processDefinitionIdIn;
    private String processDefinitionKey;
    private String[] processDefinitionKeyIn;
    private Boolean withoutTenantId;
    private String[] tenantIdIn;
    private Map<String, Object> processVariables;
    private Boolean deserializeValues = false;

    public String getTopicName() {
        return topicName;
    }

    public void setTopicName(String topicName) {
        this.topicName = topicName;
    }

    public Integer getLockDuration() {
        return lockDuration;
    }

    public void setLockDuration(Integer lockDuration) {
        this.lockDuration = lockDuration;
    }

    public String[] getVariables() {
        return variables;
    }

    public void setVariables(String[] variables) {
        this.variables = variables;
    }

    public Boolean getLocalVariables() {
        return localVariables;
    }

    public void setLocalVariables(Boolean localVariables) {
        this.localVariables = localVariables;
    }

    public String getBusinessKey() {
        return businessKey;
    }

    public void setBusinessKey(String businessKey) {
        this.businessKey = businessKey;
    }

    public String getProcessDefinitionId() {
        return processDefinitionId;
    }

    public void setProcessDefinitionId(String processDefinitionId) {
        this.processDefinitionId = processDefinitionId;
    }

    public String[] getProcessDefinitionIdIn() {
        return processDefinitionIdIn;
    }

    public void setProcessDefinitionIdIn(String[] processDefinitionIdIn) {
        this.processDefinitionIdIn = processDefinitionIdIn;
    }

    public String getProcessDefinitionKey() {
        return processDefinitionKey;
    }

    public void setProcessDefinitionKey(String processDefinitionKey) {
        this.processDefinitionKey = processDefinitionKey;
    }

    public String[] getProcessDefinitionKeyIn() {
        return processDefinitionKeyIn;
    }

    public void setProcessDefinitionKeyIn(String[] processDefinitionKeyIn) {
        this.processDefinitionKeyIn = processDefinitionKeyIn;
    }

    public Boolean getWithoutTenantId() {
        return withoutTenantId;
    }

    public void setWithoutTenantId(Boolean withoutTenantId) {
        this.withoutTenantId = withoutTenantId;
    }

    public String[] getTenantIdIn() {
        return tenantIdIn;
    }

    public void setTenantIdIn(String[] tenantIdIn) {
        this.tenantIdIn = tenantIdIn;
    }

    public Map<String, Object> getProcessVariables() {
        return processVariables;
    }

    public void setProcessVariables(Map<String, Object> processVariables) {
        this.processVariables = processVariables;
    }

    public Boolean getDeserializeValues() {
        return deserializeValues;
    }

    public void setDeserializeValues(Boolean deserializeValues) {
        this.deserializeValues = deserializeValues;
    }
}
