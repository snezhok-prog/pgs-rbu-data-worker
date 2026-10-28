/*
 * Copyright (c) 2016-2024 Evolenta JSC. Система исполнения электронных регламентов и межведомственного взаимодействия (СИЭР)
 */
package ru.evolenta.server.service.impl;

import org.bson.Document;
import org.springframework.stereotype.Service;
import ru.evolenta.server.service.ServiceTaskExecutorService;

/** The legacy push endpoint does not accept the pull worker contract. */
@Service
public class ServiceTaskExecutorServiceImpl implements ServiceTaskExecutorService {
    @Override
    public Document executeServiceEvent(Document event) {
        if (event == null || event.getString("type") == null) {
            throw new IllegalArgumentException("Не указан тип сервисной задачи");
        }
        throw new UnsupportedOperationException(
            "Этот сервис выполняет операции РБУ через external-task topic; push-операция не поддерживается");
    }
}
