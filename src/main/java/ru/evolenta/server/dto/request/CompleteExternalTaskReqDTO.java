/*
 * Copyright (c) 2016-2024 Evolenta JSC. Система исполнения электронных регламентов и межведомственного взаимодействия (СИЭР)
 */
package ru.evolenta.server.dto.request;

import ru.evolenta.server.dto.ValueObjectDTO;

import java.util.Map;

public class CompleteExternalTaskReqDTO {

    private String workerId;
    private Map<String, ValueObjectDTO> variables;
    private Map<String, ValueObjectDTO> localVariables;

    public String getWorkerId() {
        return workerId;
    }

    public void setWorkerId(String workerId) {
        this.workerId = workerId;
    }

    public Map<String, ValueObjectDTO> getVariables() {
        return variables;
    }

    public void setVariables(Map<String, ValueObjectDTO> variables) {
        this.variables = variables;
    }

    public Map<String, ValueObjectDTO> getLocalVariables() {
        return localVariables;
    }

    public void setLocalVariables(
            Map<String, ValueObjectDTO> localVariables) {
        this.localVariables = localVariables;
    }
}
