/*
 * Copyright (c) 2016-2024 Evolenta JSC. Система исполнения электронных регламентов и межведомственного взаимодействия (СИЭР)
 */
package ru.evolenta.server.service;

import ru.evolenta.server.dto.response.ExternalTaskFetchAndLockRespDTO;

public interface ExternalTaskExecutorService {

    public void processExternalTask(ExternalTaskFetchAndLockRespDTO externalTask);

}
