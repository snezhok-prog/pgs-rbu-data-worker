/*
 * Copyright (c) 2016-2024 Evolenta JSC. Система исполнения электронных регламентов и межведомственного взаимодействия (СИЭР)
 */
package ru.evolenta.server.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NonNull;

@AllArgsConstructor
@Data
public class ObjectDTO {

    @NonNull
    private String mainId;
    @NonNull
    private String guid;
    @NonNull
    private String processDefinitionId;
    private String appealId;
    private String subserviceGuid;
    private String collectionName;
    private String kndKindId;
    private String unitId;
    // обязательные параметры для групповой операции расчета рисков
    private String arrayElementId;
    private String operationType;
    private boolean isGroupOperation;

    public ObjectDTO() {
        isGroupOperation = false;
    }

}
