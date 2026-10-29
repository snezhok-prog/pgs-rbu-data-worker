const firstFile = (value) => {
  for (let depth = 0; depth < 6; depth++) {
    if (Array.isArray(value)) {
      if (value.length !== 1) throw new Error('Выберите один файл XLSX.');
      value = value[0];
    } else if (value && typeof value === 'object') {
      if (value._id || value.id || value.fileId) return value;
      value = value.uploadedFiles || value.files || value.file || value.value || value.data;
    } else break;
  }
  throw new Error('Выберите загруженный в хранилище файл XLSX.');
};
const file = firstFile(model?.importFile || appeal?.importFile || subservice?.xsdData?.importFile);
if (!/\.xlsx$/i.test(String(file.originalName || file.name || ''))) throw new Error('Необходимо загрузить файл .xlsx.');
if (file.size != null && (!Number.isFinite(Number(file.size)) || Number(file.size) <= 0 || Number(file.size) > 20 * 1024 * 1024)) {
  throw new Error('Допустим непустой XLSX размером не более 20 МБ.');
}
// Native form persistence saves metadata; PARSE_VALIDATE reads the bytes on the backend.
return true;
