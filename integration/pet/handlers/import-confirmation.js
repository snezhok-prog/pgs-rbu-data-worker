return (async () => {
  const fresh = await window.restService.find('rbuModulesAppeals', appeal._id, false);
  if (!fresh || fresh._id !== appeal._id) throw new Error('Не удалось прочитать актуальное дело.');
  const candidates = (fresh.subservices || []).filter(s => s.camundaProcessInfo?.status === 'ACTIVE');
  const current = candidates.find(s => (subservice?.guid && s.guid === subservice.guid) || (subservice?.id && s.id === subservice.id))
    || (candidates.length === 1 ? candidates[0] : null);
  if (!current) throw new Error('Рабочая подуслуга не определена однозначно.');
  const draft = fresh.importDraft || current.rbuImportDraft || {};
  const result = current.rbuImportResult || current.xsdData?.rbuImportResult || {};
  if (draft.status !== 'VALIDATED' || draft.validationBlocked === true || Number(draft.validRows) <= 0
      || Number(draft.invalidRows) !== 0 || Number(result.totalErrors) !== 0) throw new Error('Миграция недоступна: выполните серверную проверку полностью корректного файла.');
  const expected = String(draft.confirmationFingerprint || '');
  const saved = current.xsdData?.rbuImportConfirmation || {};
  if (!/^[0-9a-f]{64}$/.test(expected) || model?.confirmApply !== true || saved.confirmApply !== true
      || model.confirmationFingerprint !== expected || saved.confirmationFingerprint !== expected) throw new Error('Подтвердите текущий проверенный файл. Отпечаток подтверждения не совпадает.');
  return true;
})();
