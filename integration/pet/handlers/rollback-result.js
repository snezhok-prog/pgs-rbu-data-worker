return (async () => {
  const fresh = await window.restService.find('rbuModulesAppeals', appeal._id, false);
  if (!fresh || fresh._id !== appeal._id) throw new Error('Не удалось прочитать актуальное дело отката.');
  const candidates = (fresh.subservices || []).filter(s => s.rbuPetRollbackReport);
  const current = candidates.find(s => (subservice?.guid && s.guid === subservice.guid) || (subservice?.id && s.id === subservice.id))
    || (candidates.length === 1 ? candidates[0] : null);
  const report = current?.rbuPetRollbackReport;
  if (!report || !['ROLLED_BACK', 'PARTIAL'].includes(report.status)) throw new Error('Итоговый серверный отчёт ещё не готов.');
  const format = (raw) => {
    const date = new Date(raw);
    if (!raw || Number.isNaN(date.getTime())) throw new Error('Некорректная дата серверного отчёта отката.');
    return date.toISOString().replace('T', ' ').replace('Z', ' UTC') + ' / '
      + date.toLocaleString('ru-RU', {timeZone: 'Europe/Moscow', hour12: false}) + ' МСК';
  };
  Object.assign(model, {
    migrationNumber: report.targetAppealNumber || report.targetAppealId,
    sourceFileName: report.sourceFileName || '—',
    startedAt: format(report.startedAt), completedAt: format(report.completedAt),
    resultHeader: report.status === 'ROLLED_BACK' ? 'Удаление данных выполнено полностью' : 'Удаление данных выполнено частично',
    completedBatches: Number(report.lastCompletedBatch || 0), resumeCount: Number(report.resumeCount || 0),
    rollbackMetrics: [
      ['totalRecords', 'Всего записей'], ['deletedRecords', 'Удалено обработчиком'],
      ['alreadyDeletedRecords', 'Отсутствовали до удаления'], ['blockedRecords', 'Заблокировано'],
      ['pendingRecords', 'Ожидают выполнения'], ['technicalErrors', 'Технические ошибки'],
    ].map(([field, label]) => ({metricName: label, recordCount: Number(report[field] || 0)})),
    notDeletedItems: (report.notDeletedItems || []).map(item => ({...item,
      activeAppeals: (item.activeCases || []).map(value => value.number || value.appealId).join(', ')})),
  });
  return model;
})();
