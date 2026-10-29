return (async () => {
  const fresh = await window.restService.find('rbuModulesAppeals', appeal._id, false);
  if (!fresh || fresh._id !== appeal._id) throw new Error('Не удалось прочитать актуальное дело отката.');
  const candidates = (fresh.subservices || []).filter(s => s.rbuPetRollbackReport);
  const current = candidates.find(s => (subservice?.guid && s.guid === subservice.guid) || (subservice?.id && s.id === subservice.id))
    || (candidates.length === 1 ? candidates[0] : null);
  if (!['ROLLED_BACK', 'PARTIAL'].includes(current?.rbuPetRollbackReport?.status)) throw new Error('Серверный откат ещё не завершён.');
  return true;
})();
