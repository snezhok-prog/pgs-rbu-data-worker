package ru.evolenta.pgs.worker.trade;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import ru.evolenta.pgs.worker.trade.core.DomainModule;
import ru.evolenta.pgs.worker.trade.core.EntityReference;
import ru.evolenta.pgs.worker.trade.core.OperationContext;
import ru.evolenta.pgs.worker.trade.core.PlannedEntity;
import ru.evolenta.pgs.worker.trade.core.RegistryAccess;
import ru.evolenta.pgs.worker.trade.core.ValidationReport;
import ru.evolenta.pgs.worker.trade.core.WorkbookDraft;
import static ru.evolenta.pgs.worker.trade.TradeData.*;

public final class TradeModule implements DomainModule {
    public static final int MAX_REGISTRY_RECORDS = 2500;
    public static final Set<String> COLLECTIONS = Set.of("NTOmesto", "nsiLocalObjectNTO", "reestrbiddingReestr", "reestrpermitsReestr", "reestrmarketReestr", "nsiLocalObjectMarket", "FairMesto", "FairPermits", "Fair", "nsiLocalObjectFair");
    @Override public String id() { return "trade"; }
    @Override public Set<String> writableCollections() { return COLLECTIONS; }
    @Override public ValidationReport validate(WorkbookDraft draft, OperationContext context, RegistryAccess registry) { return new TradeValidation().validate(draft, context, registry); }
    @Override public List<PlannedEntity> plan(WorkbookDraft draft, OperationContext context, RegistryAccess registry) {
        ValidationReport report = validate(draft, context, registry);
        if (!report.valid()) throw new IllegalArgumentException("Миграция не прошла валидацию: " + report.issues());
        operationTime(context);
        long count = recordCount(draft);
        if (count == 0) throw new IllegalArgumentException("Нет валидных строк для миграции.");
        if (count > MAX_REGISTRY_RECORDS) throw new IllegalArgumentException("Файл создаёт " + count + " реестровых записей ПГС. Допустимо не более 2500.");
        var trusted = TradeContext.load(context, registry);
        var mapper = new TradePayloadMapper(draft, context, trusted, registry);
        Map<String, String> targets = switch (draft.dataType()) {
            case "НТО" -> Map.of("sheet2", "NTOmesto", "sheet3", "reestrbiddingReestr");
            case "Рынки" -> Map.of("sheet2", "reestrpermitsReestr", "sheet3", "reestrmarketReestr");
            case "Ярмарки" -> Map.of("sheet2", "FairMesto", "sheet3", "FairPermits", "sheet4", "Fair");
            default -> throw new IllegalArgumentException("Неподдерживаемый вид данных.");
        };
        List<PlannedEntity> result = new ArrayList<>();
        for (var sheet : draft.sheets()) for (var row : sheet.rows()) {
            String collection = targets.get(sheet.code());
            if (collection == null) throw new IllegalArgumentException("Некорректный состав рабочих листов: " + sheet.code());
            String key = context.migrationId() + '/' + sheet.code() + '/' + row.excelRow();
            var payload = mapper.build(collection, sheet, row, key);
            result.add(new PlannedEntity(key, collection, payload));
            if (collection.equals("NTOmesto")) result.add(new PlannedEntity(key + "/nsi", "nsiLocalObjectNTO", TradeNsiMapper.nto(payload, new EntityReference(key, "guid"))));
            if (collection.equals("reestrmarketReestr")) result.add(new PlannedEntity(key + "/nsi", "nsiLocalObjectMarket", TradeNsiMapper.market(payload, new EntityReference(key, "guid"))));
            if (collection.equals("FairMesto")) {
                List<Map<String, Object>> markets = maps(payload.get("marketInfo")); if (markets.isEmpty()) markets = List.of(Map.of());
                for (int index = 0; index < markets.size(); index++) {
                    String part = "fair-nsi-" + (index + 1), nsiKey = key + '/' + part;
                    result.add(new PlannedEntity(nsiKey, "nsiLocalObjectFair", TradeNsiMapper.fair(payload, markets.get(index), nsiKey, mapper.source(sheet, row, part))));
                }
            }
        }
        if (result.size() > MAX_REGISTRY_RECORDS) throw new IllegalArgumentException("Достигнут предел 2500 реестровых записей ПГС.");
        if (result.size() != count) throw new IllegalStateException("Состав плана не совпадает с рассчитанным количеством реестровых записей.");
        return List.copyOf(result);
    }
    public long recordCount(WorkbookDraft draft) {
        long count = 0;
        for (var sheet : draft.sheets()) for (var row : sheet.rows()) count += switch (draft.dataType()) {
            case "НТО" -> sheet.code().equals("sheet2") ? 2 : 1;
            case "Рынки" -> sheet.code().equals("sheet3") ? 2 : 1;
            case "Ярмарки" -> {
                if (!sheet.code().equals("sheet2")) yield 1;
                int occupied = 0;
                for (int start : List.of(18, 45)) { boolean any = false; for (int attribute = start; attribute <= start + 13; attribute++) if (present(draft.value(sheet, row, attribute))) any = true; if (any) occupied++; }
                yield 1 + Math.max(1, occupied);
            }
            default -> throw new IllegalArgumentException("Не удалось определить состав реестровых записей миграции.");
        };
        return count;
    }
}
