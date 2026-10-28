/*
 * Copyright (c) 2016-2024 Evolenta JSC. Система исполнения электронных регламентов и межведомственного взаимодействия (СИЭР)
 */
package ru.evolenta.server.controller;

import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import ru.evolenta.server.service.ServiceTaskExecutorService;

@RestController
public class ServiceTaskController {

    private static final Logger LOG = LoggerFactory.getLogger(ServiceTaskController.class);

    @Autowired
    private ServiceTaskExecutorService serviceTaskExecutorService;

    /**
     * Метод, вызываемый service-task camunda
     *
     * @param content
     * @return
     */
    @PostMapping("/exchange/event")
    public HttpEntity<Document> sendExchangeEvent(
            @RequestBody Document content)
    {
        try {
            //в content приходит набор обязательных переменных из процесса:
            // - "type" - тип сервис-таска
            // - "mainId" - идентификатор записи в таблице дела, из которого запущен bpmn-процесс
            // - "collectionName" - (ВНИМАНИЕ НА ПРИМЕЧАНИЕ НИЖЕ) имя коллекции дела, из которого запущен bpmn-процесс
            // - "camundaBusinessInfoId" - идентификатор связанной записи в таблице camundaBusinessInfo
            // - все остальные переменные, заданные в invocation_properties сервис-таска
            //
            // ВНИМАНИЕ: collectionName приходит, вида "appeals.subservices", поэтому
            // для использования его значения в качестве имени таблицы, обрезайте все, что приходит после точки

            LOG.info("Legacy exchange event received");
            Document result = serviceTaskExecutorService.executeServiceEvent(content);
            return new ResponseEntity<>(result, HttpStatus.OK);
        } catch (Throwable e) {
            LOG.error(e.getLocalizedMessage(), e);
            return new ResponseEntity<>(new Document("status","error").append("description", "Internal server error: " + e.getLocalizedMessage()), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

}
