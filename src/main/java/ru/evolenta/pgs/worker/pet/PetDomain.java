package ru.evolenta.pgs.worker.pet;

import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.*;
import ru.evolenta.pgs.worker.*;
import ru.evolenta.pgs.worker.trade.core.*;

/** Adapter from the common worker contract to PET's parser, rules and published source mapping. */
public final class PetDomain {
    public static final String VALIDATION_VERSION = "rbu-pet-validation-external-1.0";
    private final Context worker;
    public PetDomain(Context worker) { this.worker = worker; }

    public Map<String, Object> parse(byte[] bytes, Map<String, Object> file) throws Exception {
        var guard = new PetGuard(worker, false);
        var parsed = new PetXlsxParser().read(new ByteArrayInputStream(bytes),
                Util.clean(Util.first(file.get("originalName"), file.get("name"))),
                Util.clean(Util.first(file.get("_id"), file.get("id"), file.get("fileId"))), guard.operationContext(Util.now()));
        Map<String, Object> draft = Util.copy(parsed.toMap());
        draft.put("context", guard.context());
        for (Map<String, Object> sheet : Util.maps(draft.get("sheets"))) {
            sheet.put("sheetName", Util.at(draft, "sheetNames." + sheet.get("code")));
            for (Map<String, Object> row : Util.maps(sheet.get("rows"))) row.put("rowNumber", row.get("excelRow"));
        }
        return draft;
    }

    public Map<String, Object> validate(Map<String, Object> draft) {
        var guard = new PetGuard(worker, false);
        var result = new PetModule().validateAndReport(WorkbookDraft.from(draft), guard.operationContext(Util.now()), new PetRegistryAccess(worker), Instant.now());
        Map<String, Object> validated = Util.copy(result.draft().toMap());
        draft.putAll(validated);
        draft.putAll(Util.obj("context", guard.context(), "validationVersion", VALIDATION_VERSION,
                "validationBlocked", !result.validation().valid(), "validatedFileSha256", Util.at(draft, "file.sha256")));
        Map<String, Object> form = Util.copy(result.form());
        form.put("validationVersion", VALIDATION_VERSION);
        form.put("validationBlocked", !result.validation().valid());
        return form;
    }

    public List<PlannedEntity> plan(Map<String, Object> draft, String time) {
        return new PetModule().plan(WorkbookDraft.from(draft), new PetGuard(worker, true).operationContext(time), new PetRegistryAccess(worker));
    }
    public List<PlannedPatch> patches(Map<String, Object> draft, String time) {
        return new PetModule().patches(WorkbookDraft.from(draft), new PetGuard(worker, true).operationContext(time), new PetRegistryAccess(worker));
    }
}
