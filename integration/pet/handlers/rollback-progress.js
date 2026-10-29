return (async () => {
  const fresh = await window.restService.find('rbuModulesAppeals', appeal._id, false);
  if (!fresh || fresh._id !== appeal._id) throw new Error('Не удалось прочитать актуальное дело отката.');
  const candidates = (fresh.subservices || []).filter(s => s.rbuPetRollbackPlan);
  const current = candidates.find(s => (subservice?.guid && s.guid === subservice.guid) || (subservice?.id && s.id === subservice.id))
    || (candidates.length === 1 ? candidates[0] : null);
  const plan = current?.rbuPetRollbackPlan;
  if (!plan || plan.version !== 'rbu-pet-rollback-external-1.0') throw new Error('Серверный план отката ещё не подготовлен.');
  const states = new Set(['PENDING', 'DELETED', 'ALREADY_DELETED', 'BLOCKED', 'ERROR']);
  if (!Array.isArray(plan.rollbackItems) || plan.rollbackItems.some(item => !states.has(item.state))) throw new Error('Сервер вернул неподдерживаемое состояние записи.');
  const checkpoint = new Date(plan.checkpointAt);
  if (Number.isNaN(checkpoint.getTime())) throw new Error('Контрольная точка backend не содержит корректную дату.');
  if (!plan.checkpointTimeDisplay) throw new Error('Не сохранено отображение контрольной точки по московскому времени.');
  const fields = ['totalRecords', 'deletableRecords', 'blockedRecords', 'processedRecords', 'deletedRecords', 'notDeletedRecords',
    'pendingRecords', 'alreadyDeletedRecords', 'technicalErrors', 'currentBatch', 'totalBatches'];
  for (const name of fields) model[name] = Number(plan[name] || 0);
  Object.assign(model, {
    header: 'Удалить импортированные данные «Мой питомец»',
    migrationNumber: plan.targetAppealNumber || plan.targetAppealId,
    sourceFileName: plan.sourceFileName || '—',
    failedRecords: Number(plan.technicalErrors || 0),
    lastSavedAt: plan.checkpointTimeDisplay,
    checkpointAtUtc: checkpoint.toISOString(),
    rollbackItems: plan.rollbackItems.map(item => ({...item})),
    targetAppealId: plan.targetAppealId,
    journalFingerprint: plan.journalFingerprint,
    confirmFinalRollback: false,
    warningText: `Обработано ${plan.processedRecords} из ${plan.totalRecords}. Ожидают: ${plan.pendingRecords}. Уже отсутствовали: ${plan.alreadyDeletedRecords}. Заблокированы: ${plan.blockedRecords}. Ошибок: ${plan.technicalErrors}. Выполнение и продолжение сохраняются серверным обработчиком.`,
  });
  return model;
})();
