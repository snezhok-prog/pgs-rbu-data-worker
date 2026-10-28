package ru.evolenta.pgs.worker.trade;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import static ru.evolenta.pgs.worker.trade.TradeData.*;
import static ru.evolenta.pgs.worker.trade.TradePayloadMapper.m;

final class TradeNsiMapper {
    private TradeNsiMapper() {}
    static Map<String, Object> nto(Map<String, Object> place, Object guid) {
        var info = map(place.get("ntoInformation")); var general = map(place.get("generalInformation"));
        Map<String, Object> operator = map(place.get("operatorInformationFL"));
        for (String suffix : List.of("UL", "IP", "FL")) { var candidate = map(place.get("operatorInformation" + suffix)); if (present(candidate.get("OperatorName"))) { operator = candidate; break; } }
        List<String> assortment = new ArrayList<>(); for (var item : maps(info.get("AssortBlock"))) { String value = text(item.getOrDefault("assortOther", at(item, "Assort.name"))); if (!value.isEmpty()) assortment.add(value); }
        String specialization = text(at(info, "Specialization.name")); if (!assortment.isEmpty()) specialization += ": " + String.join("; ", assortment);
        String schedule = text(at(info, "PlacementSchedule.name"));
        if (key(schedule).equals("по графику") && present(info.get("Scheduled"))) schedule += ": " + text(info.get("Scheduled"));
        if (key(schedule).equals("сезонно")) schedule += ": " + String.join(", ", maps(info.get("seasonPlacementSchedule")).stream().map(item -> text(item.get("name"))).filter(TradeData::present).toList());
        String project = join(": ", List.of(text(at(info, "ProjectStatus.name")), text(at(info, "SpecProjectStatus.0.name"))));
        var result = m("guid", guid, "LayerId", "1", "Layer", "Нестационарные торговые объекты", "Subject", out(general.get("Subject")), "Disctrict", out(general.get("Disctrict")),
                "NtoType", out(List.of("иной", "иное").contains(key(at(info, "NtoType.name"))) ? info.get("ntoTypeOther") : at(info, "NtoType.name")), "Specialization", out(specialization),
                "NumberNTO", out(info.get("ntoInfoID")), "GeoCoordinates", out(info.get("GeoCoordinates")), "CadNumber", out(info.get("cadNumber")), "FullAddress", out(at(info, "FullAddress.fullAddress")),
                "ProjectStatus", out(project), "PlacementSchedule", out(schedule), "TradingData", out(info.get("TradingData")), "ContractNumber", out(at(place, "NTOContract.ContractNumber")),
                "ContractStartDate", out(TradeMappings.ruDate(at(place, "NTOContract.ContractStartDate"))), "ContractEndDate", out(TradeMappings.ruDate(at(place, "NTOContract.ContractEndDate"))),
                "OperatorName", out(operator.get("OperatorName")), "OperatorINN", out(operator.get("OperatorINN")), "OperatorOGRN", out(operator.getOrDefault("OperatorOGRN", operator.get("OperatorOGRNIP"))),
                "OperatorNumber", out(operator.get("OperatorNumber")), "OperatorEmail", out(operator.get("OperatorEmail")), "OtherInfo", out(info.get("OtherInfo")));
        return common(result, guid, "nsiLocalObjectNTO", at(place, "unit.id"), place.get("migrationSource"));
    }
    static Map<String, Object> market(Map<String, Object> market, Object guid) {
        var info = map(market.get("InfoRetailMarket")); var total = map(market.get("TotalInfo")); var operator = map(market.get("InfoCompanyManagerMarket"));
        var addresses = maps(info.get("blockMarketAddress")).stream().map(item -> addressLine(item.get("marketAddress"))).filter(v -> !v.equals("-")).toList();
        var cadasters = maps(info.get("cadsObjects")).stream().map(item -> text(item.getOrDefault("CadNumber", item.get("cadNumber")))).filter(TradeData::present).toList();
        String specialization = key(at(info, "marketSpecialization.name")).equals("иная") ? text(info.get("marketOtherSpecialization")) : text(at(info, "marketSpecialization.name"));
        var result = m("guid", guid, "LayerId", "2", "Layer", "Розничные рынки", "Subject", out(total.get("Subject")), "Disctrict", out(total.get("Disctrict")),
                "FullAddress", out(String.join("; ", addresses)), "GeoCoordinates", out(info.get("GeoCoordinates")), "CadNumber", out(String.join("; ", cadasters)),
                "PermissionNumber", out(info.get("PermissionNumber")), "PermissionStartDate", out(TradeMappings.ruDate(info.get("PermissionStartDate"))), "PermissionEndDate", out(TradeMappings.ruDate(info.get("PermissionEndDate"))),
                "PermissionStatus", out(at(info, "PermissionStatus.name")), "TitleMarket", out(info.get("RetailName")), "TypeMarket", out(at(info, "marketType.name")), "MarketSpecialization", out(specialization),
                "OperatingPeriod", out(at(info, "operationPeriod.name")), "StartTimeMarket", out(info.get("marketOpeningTime")), "EndTimeMarket", out(info.get("marketClosingTime")), "PlaceNumber", out(info.get("PlaceNumber")),
                "OperatorName", out(operator.get("OperatorName")), "OperatorINN", out(operator.get("OperatorINN")), "OperatorOGRN", out(operator.get("OperatorOGRN")), "OperatorNumber", out(operator.get("OperatorNumber")), "OperatorEmail", out(operator.get("OperatorEmail")));
        return common(result, guid, "nsiLocalObjectMarket", at(market, "unit.id"), market.get("migrationSource"));
    }
    static Map<String, Object> fair(Map<String, Object> place, Map<String, Object> item, String entityKey, Object source) {
        var info = map(place.get("placeMarketInfo"));
        var land = maps(info.get("blockCadNumber")); var objects = maps(info.get("cadsObjects"));
        List<String> cad = objects.stream().map(v -> text(v.get("cadObjNum"))).filter(TradeData::present).toList();
        if (cad.isEmpty()) cad = land.stream().map(v -> text(v.get("cadNumber"))).filter(TradeData::present).toList();
        List<String> addresses = objects.stream().map(v -> addressLine(v.get("objectAddress"))).filter(v -> !v.equals("-")).toList();
        if (addresses.isEmpty()) addresses = land.stream().map(v -> addressLine(v.get("landAddress"))).filter(v -> !v.equals("-")).toList();
        Map<String, Object> organizer = Map.of(); for (String name : List.of("blockOrganizerULInfo", "blockOrganizerIPInfo", "blockOrganizerAuthority")) if (!map(item.get(name)).isEmpty()) { organizer = map(item.get(name)); break; }
        String specialization = key(at(info, "marketSpecialization.name")).equals("иная") ? text(info.get("marketSpecializationOther")) : text(at(info, "marketSpecialization.name"));
        String guid = TradePayloadMapper.guid(entityKey);
        var result = m("guid", guid, "LayerId", "3", "Layer", "Ярмарки", "Subject", out(at(place, "generalInformation.subject")), "Disctrict", out(at(place, "generalInformation.disctrict")),
                "FairType", out(at(info, "marketType.name")), "SpecializationFair", out(specialization), "NumberFair", out(item.get("marketNumber")), "GeoCoordinates", out(info.get("coordinates")), "CadNumber", out(String.join("; ", cad)),
                "PermissionNumber", out(item.get("permissionNumber")), "PermissionStartDate", "-", "PermissionEndDate", "-", "TitleFair", out(item.get("marketName")), "NumberFairLocation", out(info.get("marketSchemeNumber")), "FullAddress", out(String.join("; ", addresses)),
                "ProjectStatus", out(join(": ", List.of(text(at(info, "statusFairPlace.name")), text(at(info, "statusFairPlaceSpecific.name"))))), "LandArea", out(info.get("landArea")), "FairStatus", out(at(item, "marketStatus.name")),
                "FairStartDate", out(TradeMappings.ruDate(at(item, "blockMarketDates.startDate"))), "FairEndDate", out(TradeMappings.ruDate(at(item, "blockMarketDates.endDate"))), "StartTime", out(at(item, "blockMarketOperatingTime.marketOpeningTime")), "EndTime", out(at(item, "blockMarketOperatingTime.marketClosingTime")),
                "OrganizerName", out(organizer.getOrDefault("fullName", organizer.get("nameIP"))), "OrganizerINN", out(organizer.get("inn")), "OrganizerOGRN", out(organizer.get("ogrn")), "OrganizerOGRNIP", out(organizer.get("ogrnIP")), "OrganizerNumber", out(organizer.get("phoneNumber")), "OrganizerEmail", out(organizer.get("email")),
                "FrequencyFair", out(at(item, "marketFrequency.name")), "FairKind", out(at(item, "marketVariation.name")), "NumberPlace", out(item.get("placeCount")));
        return common(result, guid, "nsiLocalObjectFair", at(place, "unit.id"), source);
    }
    private static Map<String, Object> common(Map<String, Object> data, Object guid, String collection, Object unit, Object source) {
        data.putAll(m("code", guid, "autokey", guid, "ObjectID", guid, "parentEntries", collection, "dictionaryType", "local", "dictionaryUnitId", out(unit), "migrationSource", source)); return data;
    }
    private static String addressLine(Object value) { if (!(value instanceof Map<?, ?>)) return out(value); var address = map(value); return out(join(", ", List.of(text(address.get("postalCode")), text(address.get("fullAddress"))))); }
    private static String out(Object value) { return present(value) ? text(value).replace('\u00A0', ' ').replaceAll("\\s+", " ") : "-"; }
    private static String join(String separator, List<String> values) { return String.join(separator, values.stream().filter(TradeData::present).toList()); }
}
