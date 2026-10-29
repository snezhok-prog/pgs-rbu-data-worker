import fs from 'node:fs/promises';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
import {createHash} from 'node:crypto';
import assert from 'node:assert/strict';

const root = path.dirname(fileURLToPath(import.meta.url));
const read = name => fs.readFile(path.join(root, name), 'utf8');
const sha = text => createHash('sha256').update(text).digest('hex');
const manifest = JSON.parse(await read('manifest.json'));
const bodies = new Map();
let checks = 0;
const check = (condition, message) => { assert.ok(condition, message); checks++; };

for (const file of await fs.readdir(path.join(root, 'handlers'))) {
  const body = await read('handlers/' + file);
  new Function('window', 'appeal', 'subservice', 'model', body);
  check(!/\b(?:fetch|XMLHttpRequest|localStorage|sessionStorage)\b|\.(?:update|remove|create|bulkUpdate|bulkCreate)\s*\(/.test(body), file + ' must only inspect form/read state');
  bodies.set(file, body);
}
for (const item of manifest.standards) {
  const bpmn = await read(item.patchedBpmn);
  check(sha(bpmn) === item.patchedBpmnSha256, 'BPMN hash ' + item.standardCode);
  const ids = [...bpmn.matchAll(/\bid="([^"]+)"/g)].map(m => m[1]);
  check(new Set(ids).size === ids.length, 'Unique IDs ' + item.standardCode);
  check(item.verification.originalIdsRemoved === 0 && item.verification.sequenceFlowReferencesValid, 'Native IDs/flows ' + item.standardCode);
  check(!/Base64|getDecoder\(|decodeBase64/.test(bpmn), 'No encoded business payload ' + item.standardCode);
  if (item.standardCode === 47615) {
    check(!bpmn.includes('multiInstanceLoopCharacteristics') && !/rbuPetBatchIndices|rbuPetRowSlot|rbuPetBatchFailed/.test(bpmn), 'Latest-copy per-row iteration removed');
    const applies = [...bpmn.matchAll(/name="rbuOperation">IMPORT_APPLY<\/camunda:inputParameter>/g)];
    check(applies.length === 1 && item.replacedTasks.some(t => t.id === 'Activity_PetProcessOneRow' && t.operation === 'IMPORT_APPLY'), 'One backend apply per latest-copy iteration');
    check(item.lightweightGuards.length === 2 && item.lightweightGuards.every(g => g.scriptBytesAfter < 1024), 'Latest-copy preparation/finalization only guard server state');
    for (const guard of item.lightweightGuards) {
      const text = bpmn.match(new RegExp('<bpmn:scriptTask\\b[^>]*\\bid="' + guard.id + '"[^>]*>[\\s\\S]*?<bpmn:script>([\\s\\S]*?)<\\/bpmn:script>'))?.[1];
      check(text && !/Core\.|JsonSlurper|JsonOutput|rbuPetRowSlot/.test(text), 'Latest-copy guard has no registry or row processing');
    }
  }
  for (const task of item.replacedTasks) {
    const tag = bpmn.match(new RegExp('<bpmn:serviceTask\\b[^>]*\\bid="' + task.id + '"[^>]*>[\\s\\S]*?<\\/bpmn:serviceTask>'))?.[0];
    check(tag && tag.includes('camunda:type="external"') && tag.includes('camunda:topic="' + manifest.topic + '"')
      && tag.includes('name="rbuOperation">' + task.operation + '</camunda:inputParameter>'), 'External task ' + task.id);
  }
  for (const file of item.handlerPatches) {
    const patch = JSON.parse(await read(file));
    check(patch.standardCode === item.standardCode && patch.changes.length > 0, 'Patch identity ' + file);
    for (const change of patch.changes) {
      check(/^[0-9a-f]{64}$/.test(change.expectedSha256) && change.path.endsWith('/content'), 'Guarded content path ' + file);
      check(change.value === await read(change.valueFile), 'Handler diff matches file ' + file);
    }
  }
}
for (const form of manifest.forms) {
  const patch = JSON.parse(await read(form.patch));
  check(patch.id === form.id && /^[0-9a-f]{64}$/.test(patch.expectedXsdContentSha256), 'Form baseline guard');
  if (form.standardCode === 47615) {
    const rows = JSON.stringify(patch.changes);
    check(rows.includes('migrationProgressDisplay') && rows.includes('createdRecords') && rows.includes('patchedRecords'), 'Latest-copy forms read backend progress counts');
  } else check(patch.changes.some(c => c.value === 'Последняя сохранённая контрольная точка (МСК)'), 'Checkpoint label is explicit Moscow');
}

const fingerprint = 'a'.repeat(64);
const currentSub = {guid: 'sub', camundaProcessInfo: {status: 'ACTIVE'}};
const appeal = {_id: 'rollback', unit: {id: 'cabinet'}};
const context = {window: {restService: {find: async () => ({...appeal, subservices: [currentSub]})}}, appeal, subservice: {guid: 'sub'}};
const run = async (name, model = {}, overrides = {}) => {
  const c = {...context, ...overrides};
  return new Function('window', 'appeal', 'subservice', 'model', bodies.get(name))(c.window, c.appeal, c.subservice, model);
};
check(await run('file-metadata.js', {importFile: {_id: 'file', originalName: 'data.xlsx', size: 10}}), 'Metadata accepts uploaded XLSX');
await assert.rejects(run('file-metadata.js', {importFile: {_id: 'file', originalName: 'data.csv'}})); checks++;
await assert.rejects(run('file-metadata.js', {importFile: {_id: 'file', originalName: 'data.xlsx', size: 0}})); checks++;
await assert.rejects(run('reload-file-metadata.js', {importFile: {_id: 'file', originalName: 'data.xlsx'}}, {appeal: {...appeal, importDraft: {file: {id: 'file'}}}})); checks++;
currentSub.rbuImportResult = {totalRows: 5, validRows: 5, invalidRows: 0, totalErrors: 0, processedAt: '2026-10-29T10:11:12Z', rbuImportConfirmation: {confirmationFingerprint: fingerprint}};
currentSub.rbuImportDraft = {status: 'VALIDATED', validRows: 5, invalidRows: 0, confirmationFingerprint: fingerprint};
currentSub.xsdData = {rbuImportConfirmation: {confirmApply: true, confirmationFingerprint: fingerprint}};
const shown = await run('import-result.js');
check(shown.confirmApply === false && shown.confirmationFingerprint === fingerprint && shown.validationMetrics[1].rowCount === 5, 'Display resets confirmation and reads backend counts');
currentSub.rbuImportResult.applicationStatus = 'APPLYING';
currentSub.rbuImportResult.createdRecords = 50;
currentSub.rbuImportResult.patchedRecords = 2;
const applying = await run('import-result.js');
check(applying.createdRecords === 50 && applying.patchedRecords === 2 && applying.migrationProgressDisplay.includes('в фоне'), 'Latest-copy progress comes from backend result');
check(await run('import-confirmation.js', {confirmApply: true, confirmationFingerprint: fingerprint}), 'Current import fingerprint accepted');
await assert.rejects(run('import-confirmation.js', {confirmApply: true, confirmationFingerprint: 'b'.repeat(64)})); checks++;
currentSub.rbuPetRollbackPlan = {version: 'rbu-pet-rollback-external-1.0', status: 'PREPARED', targetAppealId: 'source', journalFingerprint: fingerprint,
  checkpointAt: '2026-10-29T10:11:12Z', checkpointTimeDisplay: '29.10.2026 13:11:12 +03:00[Europe/Moscow]',
  totalRecords: 5, deletedRecords: 1, alreadyDeletedRecords: 1, blockedRecords: 1, pendingRecords: 1, technicalErrors: 1, processedRecords: 4,
  rollbackItems: ['PENDING', 'DELETED', 'ALREADY_DELETED', 'BLOCKED', 'ERROR'].map(state => ({state, status: state, reason: 'reason ' + state}))};
currentSub.xsdData.rbuPetRollbackConfirmation = {confirmFinalRollback: true, targetAppealId: 'source', journalFingerprint: fingerprint};
const progress = await run('rollback-progress.js');
check(progress.rollbackItems.length === 5 && new Set(progress.rollbackItems.map(i => i.state)).size === 5, 'All five backend states remain distinct');
check(progress.rollbackItems.every(i => i.reason === 'reason ' + i.state), 'Backend reasons remain intact');
check(progress.lastSavedAt === currentSub.rbuPetRollbackPlan.checkpointTimeDisplay && progress.checkpointAtUtc === '2026-10-29T10:11:12.000Z', 'Moscow display and raw UTC remain distinct');
check(progress.confirmFinalRollback === false && progress.alreadyDeletedRecords === 1 && progress.pendingRecords === 1, 'Reload resets explicit confirmation, reads durable counts');
check(await run('rollback-confirmation.js', {confirmFinalRollback: true, targetAppealId: 'source', journalFingerprint: fingerprint}), 'Current rollback fingerprint accepted');
await assert.rejects(run('rollback-confirmation.js', {confirmFinalRollback: true, targetAppealId: 'different', journalFingerprint: fingerprint})); checks++;
currentSub.rbuPetRollbackPlan.rollbackItems[0].state = 'UNKNOWN';
await assert.rejects(run('rollback-progress.js')); checks++;
currentSub.rbuPetRollbackReport = {...currentSub.rbuPetRollbackPlan, status: 'PARTIAL', startedAt: '2026-10-29T10:00:00Z', completedAt: '2026-10-29T10:12:00Z', notDeletedItems: [{state: 'BLOCKED', reason: 'active case', activeCases: [{number: 'case-1'}]}]};
const result = await run('rollback-result.js');
check(result.rollbackMetrics.length === 6 && result.notDeletedItems[0].reason === 'active case', 'Final metrics and reasons read from backend');
check(await run('rollback-review.js'), 'Partial backend report can be reviewed');
currentSub.rbuPetRollbackReport.status = 'EXECUTING';
await assert.rejects(run('rollback-review.js')); checks++;
check(await run('rollback-registration.js', {migrationCase: {_id: 'source'}, confirmIrreversible: true}, {window: {restService: {find: async () => ({_id: 'source', status: {code: 'complete'}, unit: {id: 'cabinet'}})}}}), 'Same-cabinet complete source selected by mainId');
await assert.rejects(run('rollback-registration.js', {migrationCase: {_id: 'source'}, confirmIrreversible: true}, {window: {restService: {find: async () => ({_id: 'source', status: {code: 'process'}, unit: {id: 'cabinet'}})}}})); checks++;
console.log(JSON.stringify({checks, standards: manifest.standards.length, replacedTasks: manifest.standards.reduce((sum, s) => sum + s.replacedTasks.length, 0), nativeRuntimeVerified: false}));
