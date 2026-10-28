package ru.evolenta.pgs.worker.trade;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.nio.charset.StandardCharsets;
import ru.evolenta.pgs.worker.trade.core.OperationContext;
import ru.evolenta.pgs.worker.trade.core.RegistryAccess;
import ru.evolenta.pgs.worker.trade.core.WorkbookDraft;
import ru.evolenta.pgs.worker.trade.core.WorkbookDraft.Row;
import ru.evolenta.pgs.worker.trade.core.WorkbookDraft.Sheet;
import static ru.evolenta.pgs.worker.trade.TradeData.*;

/** Uses the audited field paths as data, with explicit import-only shape rules. */
final class TradePayloadMapper {
    private static final JsonNode POLICY = resource("mapping-policy.json");
    private final TradeWorkbook workbook;
    private final OperationContext context;
    private final TradeContext trusted;
    private final TradeMappings mappings;
    TradePayloadMapper(WorkbookDraft draft, OperationContext context, TradeContext trusted, RegistryAccess registry) {
        this.workbook = new TradeWorkbook(draft); this.context = context; this.trusted = trusted; this.mappings = new TradeMappings(registry);
    }

    Map<String, Object> build(String collection, Sheet sheet, Row row, String entityKey) {
        JsonNode config = TradeResources.SINGLE.path(collection);
        if (!config.isObject()) throw new IllegalArgumentException("Нет схемы маппинга для " + collection);
        ObjectNode result = JSON.createObjectNode();
        for (JsonNode field : config.path("fields")) {
            String path = field.path("paths").path(0).asText();
            if (path.startsWith("unit.") || path.contains(".*.")) continue;
            var column = TradeWorkbook.column(sheet, field);
            if (column == null) throw new IllegalArgumentException("Отсутствует поле маппинга " + field.path("name").asText());
            Object value = TradeWorkbook.value(row, column);
            if (path.endsWith(".fullAddress")) put(result, parent(path), TradeMappings.address(value));
            else if (path.endsWith(".originalName")) put(result, parent(path), file(value, parent(path), false));
            else {
                String target = path.endsWith(".name") ? parent(path) : path;
                String policy = POLICY.path(collection).path(target.replaceAll("\\.\\d+(?=\\.|$)", ".*")).asText();
                if (!policy.isEmpty()) {
                    Object mapped = policy.equals("FAIR_FREQUENCY") && key(value).equals("сезонная") ? m("name", "Сезонная", "code", "seasonal") : mappings.mapped(policy, value);
                    put(result, target, mapped);
                } else put(result, path, scalar(path, field, value));
            }
        }
        put(result, "guid", guid(entityKey)); put(result, "parentEntries", collection); put(result, "unit", unit());
        put(result, "migrationSource", source(sheet, row, ""));
        if (collection.equals("NTOmesto")) nto(result, sheet, row);
        if (collection.equals("reestrbiddingReestr")) bidding(result, sheet, row);
        if (collection.equals("reestrpermitsReestr")) marketPermit(result);
        if (collection.equals("reestrmarketReestr")) { photos(result, sheet, row, "InfoRetailMarket"); daysOff(result, "InfoRetailMarket", false); }
        if (collection.equals("FairMesto")) {
            JsonNode markets = result.path("marketInfo");
            ArrayNode selected = JSON.createArrayNode();
            for (int index = 0; index < markets.size(); index++) {
                int start = index == 0 ? 18 : 45; boolean signal = false;
                for (int attribute = start; attribute < start + 14; attribute++) if (present(workbook.draft.value(sheet, row, attribute))) signal = true;
                if (signal) { ObjectNode item = (ObjectNode) markets.get(index); frequency(item, "marketFrequency", "marketVariation"); organizer(item); selected.add(item); }
            }
            result.set("marketInfo", selected);
            statusPair(result.path("placeMarketInfo"));
        }
        if (collection.equals("Fair")) {
            frequency((ObjectNode) result.path("marketInfo"), "marketVariation", "marketFrequency"); organizer(result); statusPair(result.path("marketInfo"));
            photos(result, sheet, row, "marketInfo"); daysOff(result, "marketInfo", true);
        }
        pruneLists(result);
        return JSON.convertValue(result, new TypeReference<Map<String, Object>>() {});
    }

    private Object scalar(String path, JsonNode field, Object value) {
        if (path.endsWith("chooseAuthorityAsOrganizator") || path.endsWith("dayOffDayOff") || path.endsWith(".nds")) return TradeMappings.yesNo(value);
        for (JsonNode rule : field.path("rules")) {
            if (rule.path("method").asText().equals("is_valid_date")) return TradeMappings.date(value);
            if (rule.path("method").asText().equals("is_valid_time")) return TradeMappings.time(value);
            if (rule.path("method").asText().equals("is_valid_decimal")) return TradeMappings.number(value);
        }
        if (path.endsWith(".PlaceNumber") || path.endsWith(".placeCount") || path.endsWith(".placeCountFree")) return TradeMappings.number(value);
        return text(value).replace('\u00A0', ' ').replaceAll("\\s+", " ");
    }
    private void nto(ObjectNode payload, Sheet sheet, Row row) {
        put(payload, "organization", organizationSnapshot()); put(payload, "approvalDocuments", List.of());
        put(payload, "ntoInformation.ntoInfoID", payload.path("ntoInformation").path("ntoInfoSchemeNumber").asText());
        put(payload, "ntoInformation.OtherInfo", payload.path("ntoInformation").path("infoAddress").asText());
        for (int point = 6; point <= 9; point++) put(payload, "ntoInformation.GeoCoord" + point, text(TradeWorkbook.namedValue(sheet, row, "Геокоординаты полигона размещения НТО (точка " + point + ")", "")));
        for (int season = 1; season <= 4; season++) put(payload, "ntoInformation.seasonPlacementSchedule." + (season - 1), mappings.mapped("SEASON", TradeWorkbook.namedValue(sheet, row, season + ". Сезон", "")));
        Object specialization = at(JSON.convertValue(payload, new TypeReference<Map<String, Object>>() {}), "ntoInformation.Specialization");
        Object assortment = TradeWorkbook.namedValue(sheet, row, "Ассортимент", ""), other = TradeWorkbook.namedValue(sheet, row, "Иной ассортимент", "");
        put(payload, "ntoInformation.AssortBlock", assortment(map(specialization), assortment, other));
        put(payload, "ntoInformation.rawAssortment", text(assortment)); put(payload, "ntoInformation.rawAssortmentOther", text(other));
        put(payload, "resolutionBlock.statusOperator", text(TradeWorkbook.namedValue(sheet, row, "Правовой статус оператора", "")));
        for (String kind : List.of("UL", "IP", "FL")) if (!payload.path("operatorInformation" + kind).path("OperatorName").asText().isEmpty()) { put(payload, "OperatorList", Map.of("UL", "Юридическое лицо", "IP", "Индивидуальный предприниматель", "FL", "Физическое лицо").get(kind)); break; }
        photos(payload, sheet, row, "ntoInformation");
        if (!present(TradeWorkbook.namedValue(sheet, row, "Номер дополнительного соглашения", "")) && !present(TradeWorkbook.namedValue(sheet, row, "Дата заключения дополнительного соглашения", "")) && !present(TradeWorkbook.namedValue(sheet, row, "Причина заключения дополнительного соглашения", ""))) put(payload, "blockDopSogl", List.of());
    }
    private List<Map<String, Object>> assortment(Map<String, Object> specialization, Object raw, Object otherRaw) {
        List<Map<String, Object>> result = new ArrayList<>();
        String code = text(specialization.get("code"));
        String group = code.equals("SpecificProd") ? "PROD" : code.equals("SpecificNoProd") ? "NO_PROD" : "";
        List<String> others = java.util.Arrays.stream(text(otherRaw).split("[;\\r\\n]+", -1)).map(String::trim).filter(v -> !v.isEmpty()).toList();
        for (String value : text(raw).split("[;\\r\\n]+", -1)) if (!value.isBlank()) {
            var item = m("Assort", mappings.mapped(group, value));
            if (List.of("иной", "иное").contains(key(value)) && !others.isEmpty()) item.put("assortOther", others.getFirst());
            result.add(item);
        }
        for (String other : others) if (result.stream().noneMatch(item -> other.equals(text(item.get("assortOther"))))) {
            if (group.isEmpty()) throw new IllegalArgumentException("Иной ассортимент требует допустимую специализацию.");
            result.add(m("Assort", m("name", "Иной", "code", code + "Other"), "assortOther", other));
        }
        return result;
    }
    private static void bidding(ObjectNode payload, Sheet sheet, Row row) {
        JsonNode lots = payload.path("InformationOnLots").path("LotInfo");
        if (lots.isArray()) for (JsonNode lot : lots) {
            put((ObjectNode) lot, "LotName", text(TradeWorkbook.namedValue(sheet, row, "Наименование лота", "Информация о лотах для выставления на торги")));
            put((ObjectNode) lot, "InfoLot", text(TradeWorkbook.namedValue(sheet, row, "Описание лота", "Информация о лотах для выставления на торги")));
            put((ObjectNode) lot, "nds", TradeMappings.yesNo(TradeWorkbook.namedValue(sheet, row, "Наличие НДС", "Информация о лотах для выставления на торги")));
            put((ObjectNode) lot, "ndsSize", TradeMappings.number(TradeWorkbook.namedValue(sheet, row, "Ставка НДС", "Информация о лотах для выставления на торги")));
        }
        JsonNode results = payload.path("InformationOnLots").path("AuctionResults");
        if (results.isArray()) for (JsonNode result : results) {
            put((ObjectNode) result, "participants", List.of());
            put((ObjectNode) result, "NumberNTO", text(TradeWorkbook.namedValue(sheet, row, "Номер НТО в схеме", "Информация о лотах для выставления на торги")));
        }
        if (!payload.path("InformationOnLots").path("AuctionResults").path(0).path("BiddingStatus").isObject()) put(payload, "InformationOnLots.AuctionResults", List.of());
    }
    private static void marketPermit(ObjectNode payload) {
        var permission = payload.path("permission");
        if (!permission.path("permissionExtensionDate").asText().isEmpty()) put(payload, "permission.PermissionEndDate", permission.path("permissionExtensionDate").asText());
        for (String field : List.of("MeetingProtocol", "resolution", "positiveDecisionNotification", "denialNotification", "suspensionDocumentScourtDecision", "violationCorrectionSuspensionDocuments", "omsuPetitionSuspensionDocuments")) put(payload, field, Map.of());
    }
    private void daysOff(ObjectNode payload, String root, boolean fair) {
        boolean noDaysOff = payload.path(root).path("dayOffDayOff").asBoolean();
        if (noDaysOff) { put(payload, root + ".BlockDayOff", List.of()); if (fair) put(payload, root + ".dayOffDayOff", mappings.mapped("FAIR_DAY_OF_WEEK", "Без выходных")); }
        else if (payload.path(root) instanceof ObjectNode object) object.remove("dayOffDayOff");
    }
    private void frequency(ObjectNode node, String parentName, String childName) {
        JsonNode parent = node.path(parentName), child = node.path(childName);
        if (child.isObject() && child.path("name").asText().equals("Сезонная")) {
            String code = parent.path("code").asText();
            if (!List.of("regular", "single").contains(code)) throw new IllegalArgumentException("Сезонная ярмарка требует периодичность Регулярная или Разовая.");
            put(node, childName, m("code", code + "Seasonal", "name", "Сезонная", "parentCode", code));
        } else if (child.hasNonNull("parentCode") && !child.path("parentCode").asText().equals(parent.path("code").asText())) throw new IllegalArgumentException("Вид ярмарки не соответствует периодичности.");
    }
    private static void statusPair(JsonNode info) {
        JsonNode status = info.path("statusFairPlace"), specific = info.path("statusFairPlaceSpecific");
        if (specific.isObject() && !specific.path("parentCode").asText().equals(status.path("code").asText())) throw new IllegalArgumentException("Спецификация места не соответствует статусу.");
    }
    private static void organizer(ObjectNode payload) {
        boolean authority = payload.path("chooseAuthorityAsOrganizator").asBoolean();
        put(payload, "chooseAuthorityAsOrganizator", authority);
        if (authority) {
            if (payload.path("statusLegal").asText().isEmpty()) put(payload, "statusLegal", null);
            defaults(payload, "blockOrganizerAuthority", List.of("fullName", "phoneNumber", "email"), List.of());
            payload.remove("blockOrganizerULInfo"); payload.remove("blockOrganizerIPInfo"); return;
        }
        String legal = key(payload.path("statusLegal").asText());
        boolean ul = meaningful(payload.path("blockOrganizerULInfo")), ip = meaningful(payload.path("blockOrganizerIPInfo"));
        if (legal.contains("предприним") || (legal.isEmpty() && ip && !ul)) {
            put(payload, "statusLegal", legal.isEmpty() ? "Индивидуальный предприниматель" : payload.path("statusLegal").asText());
            defaults(payload, "blockOrganizerIPInfo", List.of("nameIP", "fioIP", "inn", "ogrnIP", "phoneNumber", "email"), List.of()); payload.remove("blockOrganizerULInfo");
        } else if (legal.contains("юрид") || ul) {
            put(payload, "statusLegal", legal.isEmpty() ? "Юридическое лицо" : payload.path("statusLegal").asText());
            defaults(payload, "blockOrganizerULInfo", List.of("fullName", "shortName", "ogrn", "inn", "kpp", "fioUL", "phoneNumber", "email"), List.of("orgStateForm", "addressUL", "addressFact")); payload.remove("blockOrganizerIPInfo");
        }
        else if (!legal.isEmpty()) throw new IllegalArgumentException("Не поддержан правовой статус организатора.");
        else { payload.remove("statusLegal"); payload.remove("blockOrganizerULInfo"); payload.remove("blockOrganizerIPInfo"); }
        payload.remove("blockOrganizerAuthority");
    }
    private static void defaults(ObjectNode payload, String block, List<String> textFields, List<String> nullableFields) {
        for (String field : textFields) if (!payload.path(block).has(field)) put(payload, block + '.' + field, "");
        for (String field : nullableFields) if (!payload.path(block).has(field)) put(payload, block + '.' + field, null);
    }
    private void photos(ObjectNode payload, Sheet sheet, Row row, String root) {
        var columns = sheet.columns().stream().filter(c -> TradeWorkbook.name(c).startsWith("Фотография объекта")).toList();
        put(payload, root + ".mainPhotoObject", null); List<Map<String, Object>> extras = new ArrayList<>();
        for (int index = 0; index < columns.size(); index++) {
            Object value = TradeWorkbook.value(row, columns.get(index)); if (!present(value)) continue;
            if (index == 0) put(payload, root + ".mainPhotoObject", file(value, root + ".mainPhotoObject", true));
            else extras.add(m("photoObject", file(value, root + ".photosObject[" + extras.size() + "].photoObject", true)));
        }
        put(payload, root + ".photosObject", extras);
    }
    private Map<String, Object> file(Object value, String path, boolean photo) {
        if (!present(value)) return null;
        List<Map<String, Object>> matches = trusted.files().stream().filter(file -> photo ? TradePhotoRules.name(TradePhotoRules.fileName(file)).equals(TradePhotoRules.name(value)) : TradeRuleEngine.fileKey(file.getOrDefault("originalName", file.get("name"))).equals(TradeRuleEngine.fileKey(value))).toList();
        if (matches.size() != 1) throw new IllegalArgumentException("Для файла «" + value + "» найдено соответствий: " + matches.size());
        Map<String, Object> file = new LinkedHashMap<>(matches.getFirst());
        String id = text(file.getOrDefault("_id", file.getOrDefault("id", file.get("fileId"))));
        if (id.isEmpty()) throw new IllegalArgumentException("У файла отсутствует идентификатор.");
        file.put("_id", id); file.put("originalName", file.getOrDefault("originalName", file.get("name"))); file.put("isFile", true); file.put("allowExternal", Boolean.TRUE.equals(file.get("allowExternal"))); file.put("entityFieldPath", path.replaceAll("\\.(\\d+)(?=\\.|$)", "[$1]")); return file;
    }
    Map<String, Object> source(Sheet sheet, Row row, String part) {
        var file = map(workbook.draft.metadata().get("file"));
        return m("appealId", context.appealId(), "appealNumber", context.attributes().get("appealNumber"), "parserVersion", workbook.draft.metadata().get("parserVersion"),
                "validationVersion", "rbu-trade-validation-4.3", "sourceFile", file.get("originalName"), "sourceFileId", file.getOrDefault("id", file.getOrDefault("_id", file.get("fileId"))),
                "sourceFileSha256", workbook.draft.fileSha256(), "rowHash", TradeHashes.row(sheet, row, true), "rowContentHash", TradeHashes.row(sheet, row, false),
                "sheetCode", sheet.code(), "sheetName", workbook.sheetName(sheet.code()), "rowNumber", row.excelRow(), "recordPart", part.isEmpty() ? null : part,
                "appliedAt", operationTime(context));
    }
    Map<String, Object> unit() {
        var org = trusted.organization(); var extra = map(org.get("additionalData"));
        return m("id", org.get("_id"), "name", org.get("name"), "shortName", org.getOrDefault("shortName", org.get("name")), "inn", org.getOrDefault("inn", extra.get("inn")), "ogrn", org.getOrDefault("ogrn", extra.get("ogrn")), "kpp", org.getOrDefault("kpp", extra.get("kpp")));
    }
    private Map<String, Object> organizationSnapshot() { var result = new LinkedHashMap<>(unit()); var org = trusted.organization(); result.put("_id", org.get("_id")); result.put("id", org.getOrDefault("id", org.get("guid"))); result.put("guid", org.getOrDefault("guid", org.get("id"))); result.put("shortName", org.get("shortName")); return result; }
    static String guid(String key) { return UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)).toString(); }
    static Map<String, Object> m(Object... pairs) { Map<String, Object> result = new LinkedHashMap<>(); for (int index = 0; index < pairs.length; index += 2) result.put(pairs[index].toString(), pairs[index + 1]); return result; }
    static void put(ObjectNode root, String path, Object value) { set(root, path.split("\\."), 0, JSON.valueToTree(value)); }
    private static void set(JsonNode current, String[] parts, int index, JsonNode value) {
        String part = parts[index]; boolean last = index == parts.length - 1;
        JsonNode child;
        if (current instanceof ArrayNode array) {
            int slot = Integer.parseInt(part); while (array.size() <= slot) array.addNull();
            if (last) { array.set(slot, value); return; } child = array.get(slot);
            if (!child.isContainerNode()) { child = parts[index + 1].matches("\\d+") ? JSON.createArrayNode() : JSON.createObjectNode(); array.set(slot, child); }
        } else if (current instanceof ObjectNode object) {
            if (last) { object.set(part, value); return; } child = object.path(part);
            if (!child.isContainerNode()) { child = parts[index + 1].matches("\\d+") ? JSON.createArrayNode() : JSON.createObjectNode(); object.set(part, child); }
        } else throw new IllegalArgumentException("Недопустимый путь маппинга: " + String.join(".", parts));
        set(child, parts, index + 1, value);
    }
    private static String parent(String path) { return path.substring(0, path.lastIndexOf('.')); }
    private static boolean meaningful(JsonNode node) { if (node.isContainerNode()) { for (JsonNode item : node) if (meaningful(item)) return true; return false; } return !node.isNull() && !node.isMissingNode() && !node.asText().isEmpty(); }
    private static void pruneLists(JsonNode node) {
        if (node instanceof ArrayNode array) { for (int index = array.size() - 1; index >= 0; index--) { pruneLists(array.get(index)); if (!meaningful(array.get(index))) array.remove(index); } }
        else if (node.isObject()) node.forEach(TradePayloadMapper::pruneLists);
    }
}
