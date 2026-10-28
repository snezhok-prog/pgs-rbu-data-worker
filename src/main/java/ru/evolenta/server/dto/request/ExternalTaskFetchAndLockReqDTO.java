/*
 * Copyright (c) 2016-2024 Evolenta JSC. Система исполнения электронных регламентов и межведомственного взаимодействия (СИЭР)
 */
package ru.evolenta.server.dto.request;

import java.util.List;

public class ExternalTaskFetchAndLockReqDTO {

    private String workerId;
    private Integer maxTasks;
    private Boolean usePriority;
    private Integer asyncResponseTimeout;
    private List<ExternalTaskTopicReqDTO> topics;

    public String getWorkerId() {
        return workerId;
    }

    public void setWorkerId(String workerId) {
        this.workerId = workerId;
    }

    public Integer getMaxTasks() {
        return maxTasks;
    }

    public void setMaxTasks(Integer maxTasks) {
        this.maxTasks = maxTasks;
    }

    public Boolean getUsePriority() {
        return usePriority;
    }

    public void setUsePriority(Boolean usePriority) {
        this.usePriority = usePriority;
    }

    public Integer getAsyncResponseTimeout() {
        return asyncResponseTimeout;
    }

    public void setAsyncResponseTimeout(Integer asyncResponseTimeout) {
        this.asyncResponseTimeout = asyncResponseTimeout;
    }

    public List<ExternalTaskTopicReqDTO> getTopics() {
        return topics;
    }

    public void setTopics(List<ExternalTaskTopicReqDTO> topics) {
        this.topics = topics;
    }
}
