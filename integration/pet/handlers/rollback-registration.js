return (async () => {
  const raw = model?.migrationCase || appeal?.migrationCase || subservice?.xsdData?.migrationCase;
  const value = Array.isArray(raw) ? raw[0] : raw;
  const id = String(value?._id || value?.id || value || '').trim();
  if (!id || id === appeal._id) throw new Error('Выберите исходное дело миграции.');
  const confirmed = model?.confirmIrreversible ?? appeal?.confirmIrreversible ?? subservice?.xsdData?.confirmIrreversible;
  if (confirmed !== true) throw new Error('Подтвердите необратимость удаления.');
  const target = await window.restService.find('rbuModulesAppeals', id, false);
  if (!target || target._id !== id || target.status?.code !== 'complete') throw new Error('Исходная миграция должна быть успешно завершена.');
  if (!appeal.unit?.id || target.unit?.id !== appeal.unit.id) throw new Error('Исходная миграция принадлежит другому кабинету.');
  if (target.petMigrationRollback?.completedAt) throw new Error('Эта миграция уже полностью откачена.');
  // The backend verifies the owned worker journal by mainId, including cloned native process keys.
  return true;
})();
