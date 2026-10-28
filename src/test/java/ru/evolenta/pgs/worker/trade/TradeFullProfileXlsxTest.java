package ru.evolenta.pgs.worker.trade;

import java.io.*;
import java.util.*;
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import ru.evolenta.pgs.worker.trade.core.OperationContext;
import static org.junit.jupiter.api.Assertions.*;
import static ru.evolenta.pgs.worker.trade.TradeData.*;

/** Every market/fair business sheet is validated from actual XLSX with unchanged headers. */
class TradeFullProfileXlsxTest {
    @ParameterizedTest
    @CsvSource({"Рынки,ROIV","Рынки,OMSU","Ярмарки,ROIV","Ярмарки,OMSU"})
    void validatesAndPlansEveryPrimaryAndMirrorCollection(String type,String scope)throws Exception{
        var base=TradeValidationTest.context(scope);var attributes=new LinkedHashMap<>(base.attributes());attributes.put("documentFiles",List.of());
        var context=new OperationContext(base.migrationId(),base.appealId(),base.unitId(),base.actorId(),scope,attributes);
        byte[] bytes=TradeProfileXlsxIntegrationTest.workbook(type,context);
        try(var workbook=new XSSFWorkbook(new ByteArrayInputStream(bytes));var output=new ByteArrayOutputStream()){
            var sheets=resource("positive-extra.json").path(type).fields();
            while(sheets.hasNext()){
                var entry=sheets.next();String name=TradeResources.profile(type).path("structure").path(entry.getKey()).path("sheetNames").get(0).asText();var row=workbook.getSheet(name).createRow(5);
                Map<String,String> values=new LinkedHashMap<>();values.put("B",text(at(attributes,"region.name")));values.put("C",text(at(attributes,"municipality.name")));values.put("D","1111111111111");values.put("E","1111111111");entry.getValue().path("columns").fields().forEachRemaining(x->values.put(x.getKey(),x.getValue().asText()));
                values.forEach((column,value)->row.createCell(CellReference.convertColStringToIndex(column)).setCellValue(value));
            }workbook.write(output);bytes=output.toByteArray();
        }
        var draft=new TradeXlsxParser().read(new ByteArrayInputStream(bytes),type,Map.of("id","full-profile-storage","originalName","full-profile.xlsx"));
        var registry=TradeModuleTest.dictionaryRegistry();var module=new TradeModule();var validation=module.validate(draft,context,registry);assertTrue(validation.valid(),validation.issues().toString());
        var plan=module.plan(draft,context,registry);assertEquals(type.equals("Рынки")?List.of("reestrpermitsReestr","reestrmarketReestr","nsiLocalObjectMarket"):List.of("FairMesto","nsiLocalObjectFair","FairPermits","Fair"),plan.stream().map(x->x.collection()).toList());
        assertEquals(module.recordCount(draft),plan.size());assertEquals(type.equals("Рынки")?2:3,draft.sheets().stream().mapToInt(x->x.rows().size()).sum());
    }
}
