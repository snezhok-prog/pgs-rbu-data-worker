/*
 * Copyright (c) 2016-2022 Evolenta JSC. Система исполнения электронных регламентов и межведомственного взаимодействия (СИЭР)
 */
package ru.evolenta.server.service;

import org.bson.Document;
import org.springframework.http.ResponseEntity;
import ru.evolenta.server.dto.request.CompleteExternalTaskReqDTO;
import ru.evolenta.server.dto.request.ExternalTaskFetchAndLockReqDTO;
import ru.evolenta.server.dto.response.ExternalTaskFetchAndLockRespDTO;

import java.util.List;
import java.util.Map;

public interface CamundaIntegrationTaskService {

    /**
     * Получает и блокирует в camunda объекты ExternalTask по заданным параметрам
     *
     * @param requestBody параметры выборки объектов ExternalTask
     * @return
     */
    ResponseEntity<List<ExternalTaskFetchAndLockRespDTO>> fetchAndLockExternalTasks(
            ExternalTaskFetchAndLockReqDTO requestBody);

    /**
     * Завершает внешний ExternalTask по его id и обновляет переменные инстанса
     *
     * @param externalTaskId идентификатор ExternalTask для завершения
     * @param requestBody
     * @return
     */
    ResponseEntity completeExternalTask(String externalTaskId, CompleteExternalTaskReqDTO requestBody);

    /**
     * Сообщает о бизнес-ошибке в контексте запущенной внешней задачи по идентификатору.
     * Код ошибки должен быть указан для идентификации обработчика ошибок в BPMN-процессе.
     *
     * @param externalTaskId идентификатор внешней задачи
     * @param requestBody тело запроса, которое содержит идентификатор worker'а и код ошибки
     * @return
     */
    ResponseEntity handleExternalTaskBPMNError(String externalTaskId, Document requestBody);

    /**
     * Передает в процесс camunda новое значение переменной
     *
     * @param instanceId идентификатор инстанса процесса
     * @param varName имя переменной
     * @param value  значение переменной
     */
    void updateProcessInstanceVariables(String instanceId, String varName, Object value);

    /**
     * Передает в процесс camunda набор переменных
     *
     * @param instanceId идентификатор инстанса процесса
     * @param parameters переменные
     */
    void updateProcessInstanceVariables(String instanceId, Map<String, Object> parameters);

}
