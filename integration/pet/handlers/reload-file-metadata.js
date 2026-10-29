let value = model?.importFile || model?.rbuImportReload?.importFile || subservice?.xsdData?.rbuImportReload?.importFile;
let file;
for (let depth = 0; depth < 6; depth++) {
  if (Array.isArray(value)) {
    if (value.length !== 1) throw new Error('Выберите один исправленный XLSX.');
    value = value[0];
  } else if (value && typeof value === 'object') {
    if (value._id || value.id || value.fileId) { file = value; break; }
    value = value.uploadedFiles || value.files || value.file || value.value || value.data;
  } else break;
}
if (!file || !/\.xlsx$/i.test(String(file.originalName || file.name || ''))) throw new Error('Загрузите исправленный файл .xlsx.');
const previousId = appeal?.importDraft?.file?.id;
if (previousId && String(file._id || file.id || file.fileId) === String(previousId)) throw new Error('Выбран прежний файл. Загрузите исправленный XLSX.');
if (file.size != null && (!Number.isFinite(Number(file.size)) || Number(file.size) <= 0 || Number(file.size) > 20 * 1024 * 1024)) throw new Error('Допустим непустой XLSX размером не более 20 МБ.');
// rbuImportReload is persisted by PGS before the existing reload event resumes the process.
return true;
