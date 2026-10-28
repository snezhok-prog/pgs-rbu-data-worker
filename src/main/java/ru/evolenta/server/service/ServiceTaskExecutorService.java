/*
 * Copyright (c) 2016-2024 Evolenta JSC. Система исполнения электронных регламентов и межведомственного взаимодействия (СИЭР)
 */
package ru.evolenta.server.service;

import org.bson.Document;

/**
 *
 * @author Майринов Дмитрий
 */
public interface ServiceTaskExecutorService {

    /**
     * Обработчик вызова из service-task camunda
     *
     * @param event
     * @return
     */
    public Document executeServiceEvent(Document event);

}
