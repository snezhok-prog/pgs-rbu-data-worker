return (async () => {
  const fresh = await window.restService.find('rbuModulesAppeals', appeal._id, false);
  if (!fresh || fresh._id !== appeal._id) throw new Error('Не удалось прочитать актуальное дело отката.');
  const candidates = (fresh.subservices || []).filter(s => s.rbuPetRollbackPlan);
  const current = candidates.find(s => (subservice?.guid && s.guid === subservice.guid) || (subservice?.id && s.id === subservice.id))
    || (candidates.length === 1 ? candidates[0] : null);
  const plan = current?.rbuPetRollbackPlan;
  const saved = current?.xsdData?.rbuPetRollbackConfirmation || {};
  if (!plan || !['PREPARED', 'EXECUTING', 'EXECUTED'].includes(plan.status)) throw new Error('Серверный план отката ещё не готов.');
  if (model?.confirmFinalRollback !== true || saved.confirmFinalRollback !== true
      || model.targetAppealId !== plan.targetAppealId || saved.targetAppealId !== plan.targetAppealId
      || model.journalFingerprint !== plan.journalFingerprint || saved.journalFingerprint !== plan.journalFingerprint) {
    throw new Error('Повторно подтвердите выбранную миграцию и текущий отпечаток журнала.');
  }
  // PGS persists the form; the following external task performs bounded backend deletion.
  return true;
})();
