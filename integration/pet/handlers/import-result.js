return (async () => {
  const fresh = await window.restService.find('rbuModulesAppeals', appeal._id, false);
  if (!fresh || fresh._id !== appeal._id) throw new Error('Не удалось прочитать актуальное дело.');
  const candidates = (fresh.subservices || []).filter(s => s.camundaProcessInfo?.status === 'ACTIVE');
  const current = candidates.find(s => (subservice?.guid && s.guid === subservice.guid) || (subservice?.id && s.id === subservice.id))
    || (candidates.length === 1 ? candidates[0] : null);
  if (!current) throw new Error('Рабочая подуслуга не определена однозначно.');
  const result = current.rbuImportResult || current.xsdData?.rbuImportResult;
  if (!result) throw new Error('Серверный результат проверки ещё не сохранён. Обновите форму после выполнения операции.');
  const draft = fresh.importDraft || current.rbuImportDraft || current.xsdData?.rbuImportDraft || {};
  const fingerprint = draft.confirmationFingerprint || result.rbuImportConfirmation?.confirmationFingerprint || '';
  Object.assign(model, result, {
    sourceFileName: result.sourceFileName || result.fileName || draft.file?.originalName || '',
    validationErrors: result.validationErrors || result.errors || [],
    confirmApply: false,
    confirmationFingerprint: fingerprint,
    rbuImportConfirmation: {confirmApply: false, confirmationFingerprint: fingerprint},
    createdRecords: Number(result.createdRecords || 0),
    patchedRecords: Number(result.patchedRecords || 0),
  });
  const applicationStatus = result.applicationStatus || fresh.applicationStatus;
  model.migrationProgressDisplay = applicationStatus === 'APPLIED' ? 'Миграция завершена.'
    : applicationStatus === 'APPLYING' ? 'Миграция выполняется в фоне. Сохранённый прогресс доступен после обновления формы.'
    : applicationStatus === 'CANCELLED' ? 'Импорт отменён.' : 'Миграция ещё не начата.';
  if (result.processedAt) {
    const date = new Date(result.processedAt);
    if (Number.isNaN(date.getTime())) throw new Error('Некорректная дата серверной проверки.');
    model.processedAtDisplay = date.toISOString().replace('T', ' ').replace('Z', ' UTC')
      + ' / ' + date.toLocaleString('ru-RU', {timeZone: 'Europe/Moscow', hour12: false}) + ' МСК';
  }
  const total = Number(result.totalRows || 0), valid = Number(result.validRows || 0), invalid = Number(result.invalidRows || 0);
  model.validationMetrics = [
    {metricCode: 'total', metricName: 'Общее количество строк в файле', rowCount: total, percentDisplay: total ? '100%' : '0%'},
    {metricCode: 'valid', metricName: 'Правильные (валидные) строки', rowCount: valid, percentDisplay: (total ? 100 * valid / total : 0).toFixed(2) + '%'},
    {metricCode: 'invalid', metricName: 'Неправильные (ошибочные) строки', rowCount: invalid, percentDisplay: (total ? 100 * invalid / total : 0).toFixed(2) + '%'},
  ];
  model.plannedRegistryRecords = draft.plannedRegistryRecords ?? draft.recordEstimate?.totalRecords ?? null;
  model.registryRecordLimit = 2500;
  model.registryVolumeDisplay = model.plannedRegistryRecords == null ? 'Объём рассчитан серверным обработчиком.'
    : `Рассчитанный объём: ${model.plannedRegistryRecords} из 2500 реестровых записей.`;
  return model;
})();
