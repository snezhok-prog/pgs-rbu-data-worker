/*
 * Copyright (c) 2016-2024 Evolenta JSC. Система исполнения электронных регламентов и межведомственного взаимодействия (СИЭР)
 */
package ru.evolenta.server.service.impl;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import ru.evolenta.server.dto.request.CompleteExternalTaskReqDTO;
import ru.evolenta.server.dto.request.ExternalTaskFetchAndLockReqDTO;
import ru.evolenta.server.dto.response.ExternalTaskFetchAndLockRespDTO;
import ru.evolenta.server.service.CamundaIntegrationTaskService;
import ru.evolenta.server.service.ServiceUrlHelper;

import static ru.evolenta.server.service.ServiceUrlHelper.SERVICE_CAMUNDA_INTEGRATION;

@Service
public class CamundaIntegrationTaskServiceImpl implements CamundaIntegrationTaskService {

    private static final Logger LOG = LoggerFactory.getLogger(CamundaIntegrationTaskServiceImpl.class);

    private final RestTemplate restTemplate;
    private final ServiceUrlHelper urlHelper;

    public CamundaIntegrationTaskServiceImpl(RestTemplate restTemplate, ServiceUrlHelper urlHelper) {
        this.restTemplate = restTemplate;
        this.urlHelper = urlHelper;
    }

    private String getCamundaIntegrationUri() {
        return urlHelper.getServiceUrl(SERVICE_CAMUNDA_INTEGRATION) + "int/api/v1/camunda";
    }

    /**
     * Получает и блокирует объекты ExternalTask по заданным параметрам
     *
     * @param requestBody параметры выборки объектов ExternalTask
     * @return
     */
    @Override
    public ResponseEntity<List<ExternalTaskFetchAndLockRespDTO>> fetchAndLockExternalTasks(
            ExternalTaskFetchAndLockReqDTO requestBody) {

        HttpEntity<ExternalTaskFetchAndLockReqDTO> entity = new HttpEntity<>(requestBody, getDefaultHttpHeaders());
        ResponseEntity<List<ExternalTaskFetchAndLockRespDTO>> result = restTemplate.exchange(
                getCamundaIntegrationUri() + "/external-task/fetchAndLock",
                HttpMethod.POST,
                entity,
                new ParameterizedTypeReference<List<ExternalTaskFetchAndLockRespDTO>>() {}
        );
        List<ExternalTaskFetchAndLockRespDTO> externalTasksList = result.getBody();
        if (externalTasksList != null) {
            for (ExternalTaskFetchAndLockRespDTO element: externalTasksList) {
                LOG.debug(element.toString());
            }
        }
        return result;
    }

    /**
     * Завершает внешний ExternalTask по его id и обновляет переменные инстанса
     *
     * @param externalTaskId идентификатор ExternalTask для завершения
     * @param requestBody тело запроса
     * @return
     */
    @Override
    public ResponseEntity<?> completeExternalTask(String externalTaskId, CompleteExternalTaskReqDTO requestBody) {
        HttpEntity<CompleteExternalTaskReqDTO> entity = new HttpEntity<>(requestBody, getDefaultHttpHeaders());
        ResponseEntity responseEntity = restTemplate.postForEntity(
                getCamundaIntegrationUri() + "/external-task/" + externalTaskId + "/complete",
                entity,
                Object.class
        );
        LOG.debug(responseEntity.toString());
        return responseEntity;
    }

    /**
     * Сообщает о бизнес-ошибке в контексте запущенной внешней задачи по идентификатору.
     * Код ошибки должен быть указан для идентификации обработчика ошибок в BPMN-процессе.
     *
     * @param externalTaskId идентификатор внешней задачи
     * @param requestBody тело запроса, которое содержит идентификатор worker'а и код ошибки
     * @return
     */
    @Override
    public ResponseEntity<?> handleExternalTaskBPMNError(String externalTaskId, Document requestBody) {
        return restTemplate.postForEntity(getCamundaIntegrationUri() + "/external-task/" + externalTaskId + "/bpmnError",
                new HttpEntity<>(requestBody, getDefaultHttpHeaders()),
                Void.class);
    }

    /**
     * Передает в процесс camunda новое значение переменной
     *
     * @param instanceId идентификатор инстанса процесса
     * @param varName имя переменной
     * @param value  значение переменной
     */
    @Override
    public void updateProcessInstanceVariables(String instanceId, String varName, Object value) {
        Map<String, Object> variables = new HashMap<>();
        variables.put(varName, value);
        updateProcessInstanceVariables(instanceId, variables);
    }

    /**
     * Передает в процесс camunda набор переменных
     *
     * @param instanceId идентификатор инстанса процесса
     * @param parameters переменные
     */
    @Override
    public void updateProcessInstanceVariables(String instanceId, Map<String, Object> parameters) {
        restTemplate.postForEntity(
                getCamundaIntegrationUri() + "/process-instance/" + instanceId + "/variables",
                new HttpEntity<>(parameters, getDefaultHttpHeaders()),
                Object.class
        );
    }

    /**
     * Формирует экземляр класса HttpHeaders с заданным в заголовке ContentType APPLICATION_JSON
     *
     * @return экземляр класса HttpHeaders
     */
    private HttpHeaders getDefaultHttpHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

}
