/*
 * Copyright (c) 2016-2024 Evolenta JSC. Система исполнения электронных регламентов и межведомственного взаимодействия (СИЭР)
 */
package ru.evolenta.server.controller;

import org.bson.Document;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST-API для проверки состояния сервера (Health Check).
 *
 */
@RestController
@RequestMapping(value = "/")
public class ServiceController {

    /**
     * Проверка состояния сервера (Health Check).
     * Возвращает текущую временную метку сервера для подтверждения его работоспособности.
     *
     * @return Текущая временная метка
     */
    @GetMapping(value = {"/healthcheck"})
    public HttpEntity<Document> HealthCheck() {
        Document result = new Document("timestamp", System.currentTimeMillis());
        return new ResponseEntity<>(result, HttpStatus.OK);
    }

}
