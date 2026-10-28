# Контракт обработчика

Camunda serviceTask имеет `camunda:type="external"` и согласованный `camunda:topic`. Java забирает задачи, получает файл/контекст, выполняет операцию, сохраняет результат в ПГС, подтверждает задачу. Платформа не вызывает адрес пользовательского ПК.

## Входные переменные Camunda

| Переменная | Значение |
|---|---|
| mainId | ID дела в rbuModulesAppeals |
| collectionName | rbuModulesAppeals.subservices |
| camundaBusinessInfoId | ID бизнес-процесса/связи, совпадающий с подуслугой дела |
| rbuDomain | ADVERTISING |
| rbuProfile | ROIV или OMSU |
| rbuOperation | Операция из перечня ниже |
| rbuContractVersion | Строка `1` |

На каждой external task `rbuOperation` задаётся входным параметром. Для поиска подуслуги используется пара `camundaProcessInfo.id` = реальный processInstanceId и `camundaProcessInfo.camundaBusinessInfoId` = переменная процесса. Одного mainId недостаточно. Разрешённый standardCode задаётся в worker.standardCodes.

## Операции и данные

- `PARSE_VALIDATE`: файл из importFile либо rbuImportReload; организация/регион/МО из контекста дела. На выходе результат валидации и серверная контрольная точка с хешем файла. В Camunda не передаётся содержимое XLSX.
- `IMPORT_APPLY`: только актуальная успешная валидация и `xsdData.rbuImportConfirmation.confirmApply=true`. Не более50 физических записей за вызов; общий лимит2500. Возвращает `rbuApplyHasMoreBatches`, счётчики и состояние. При true вызывается следующий пакет.
- `IMPORT_FINALIZE`: подтверждает уже завершённый импорт по серверному журналу. Не заменяет штатное выставление финального статуса дела ПГС.
- `IMPORT_CANCEL`: отмена подготовки импорта.
- `ROLLBACK_PREPARE`: исходное дело из migrationCase; необходимы тот же владелец, корневой статус complete и достоверный журнал завершённой миграции. Формирует план и отпечаток.
- `ROLLBACK_EXECUTE`: подтверждение rbuRollbackDecision, совпадение целевого дела/отпечатка; повторные проверки принадлежности, изменения записей и активных связей; удаление пакетами.
- `ROLLBACK_FINALIZE`: завершение по фактическому журналу отката.

Основные результаты хранятся в importDraft/applicationStatus и данных подуслуги: rbuImportResult, advertisingMigrationSummary, migrationCreatedRecords. У созданных записей — migrationSource с ID дела, хешем файла и устойчивым operationKey. Промышленное хранение журнала должно быть постоянным, согласованным и пригодным для выбранной модели параллельности.

## Целевые коллекции

placeReestrRKCollection, nsiUnifiedPGS_object_marketing, biddingReestrRKCollection, contractReestrRKCollection, obligationReestrRKCollection, marketingLicenses, marketingRegistry, socialAdReestrRKCollection, unauthorizedReestrRKCollection, dismantleReestrRKCollection.

## Что должен обеспечить платформенный сервис

Получение и безопасное завершение external task, достаточный срок блокировки/его продление, технические повторы и инциденты, доверенный контекст организации, файловый SDK и доступы к коллекциям. Успех операции не должен возвращаться при ошибке записи. При повторной выдаче задачи нельзя создавать дубли; нельзя терять журнал при перезапуске/смене реплики. Общие ресурсы маппинга хранятся отдельными JSON, а не Base64 в BPMN.
