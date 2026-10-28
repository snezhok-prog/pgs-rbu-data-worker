/*
 * Copyright (c) 2016-2024 Evolenta JSC. Система исполнения электронных регламентов и межведомственного взаимодействия (СИЭР)
 */
package ru.evolenta.server.dto.response;

import ru.evolenta.server.dto.BaseTaskDTO;
import ru.evolenta.server.dto.ValueObjectDTO;

import java.util.Date;
import java.util.Map;

public class ExternalTaskFetchAndLockRespDTO extends BaseTaskDTO {

    private String activityId;
    private String activityInstanceId;
    private String errorMessage;
    private String errorDetails;
    private String executionId;
    private Date lockExpirationTime;
    private String processDefinitionKey;
    private String tenantId;
    private Integer retries;
    private Boolean suspended;
    private String workerId;
    private Integer priority;
    private String topicName;
    private String businessKey;
    private Map<String, ValueObjectDTO> variables;

    public String getActivityId() {
        return activityId;
    }

    public void setActivityId(String activityId) {
        this.activityId = activityId;
    }

    public String getActivityInstanceId() {
        return activityInstanceId;
    }

    public void setActivityInstanceId(String activityInstanceId) {
        this.activityInstanceId = activityInstanceId;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public String getErrorDetails() {
        return errorDetails;
    }

    public void setErrorDetails(String errorDetails) {
        this.errorDetails = errorDetails;
    }

    public String getExecutionId() {
        return executionId;
    }

    public void setExecutionId(String executionId) {
        this.executionId = executionId;
    }

    public Date getLockExpirationTime() {
        return lockExpirationTime;
    }

    public void setLockExpirationTime(Date lockExpirationTime) {
        this.lockExpirationTime = lockExpirationTime;
    }

    public String getProcessDefinitionKey() {
        return processDefinitionKey;
    }

    public void setProcessDefinitionKey(String processDefinitionKey) {
        this.processDefinitionKey = processDefinitionKey;
    }

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    public Integer getRetries() {
        return retries;
    }

    public void setRetries(Integer retries) {
        this.retries = retries;
    }

    public Boolean getSuspended() {
        return suspended;
    }

    public void setSuspended(Boolean suspended) {
        this.suspended = suspended;
    }

    public String getWorkerId() {
        return workerId;
    }

    public void setWorkerId(String workerId) {
        this.workerId = workerId;
    }

    public Integer getPriority() {
        return priority;
    }

    public void setPriority(Integer priority) {
        this.priority = priority;
    }

    public String getTopicName() {
        return topicName;
    }

    public void setTopicName(String topicName) {
        this.topicName = topicName;
    }

    public String getBusinessKey() {
        return businessKey;
    }

    public void setBusinessKey(String businessKey) {
        this.businessKey = businessKey;
    }

    public Map<String, ValueObjectDTO> getVariables() {
        return variables;
    }

    public void setVariables(Map<String, ValueObjectDTO> variables) {
        this.variables = variables;
    }

    @Override
    public String toString() {
        return "ExternalTaskFetchAndLockRespDTO{" +
                "activityId='" + activityId + '\'' +
                ", activityInstanceId='" + activityInstanceId + '\'' +
                ", errorMessage='" + errorMessage + '\'' +
                ", errorDetails='" + errorDetails + '\'' +
                ", executionId='" + executionId + '\'' +
                ", lockExpirationTime=" + lockExpirationTime +
                ", processDefinitionKey='" + processDefinitionKey + '\'' +
                ", tenantId='" + tenantId + '\'' +
                ", retries=" + retries +
                ", suspended=" + suspended +
                ", workerId='" + workerId + '\'' +
                ", priority=" + priority +
                ", topicName='" + topicName + '\'' +
                ", businessKey='" + businessKey + '\'' +
                ", variables=" + variables +
                '}';
    }
}
