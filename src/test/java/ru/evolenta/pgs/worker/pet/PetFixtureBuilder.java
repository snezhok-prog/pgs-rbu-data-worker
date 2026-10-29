package ru.evolenta.pgs.worker.pet;

import static ru.evolenta.pgs.worker.pet.PetData.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import ru.evolenta.pgs.worker.trade.core.*;

/** Exports ordinary source workbooks; it has no PGS client or write credentials. */
public record PetFixtureBuilder(String organizationName,String ogrn,String inn,String guid,String regionName,String municipalityName) {
    static PetFixtureBuilder defaults(){return new PetFixtureBuilder("Тестовая организация","1234567890123","1234567890","8cd09210-4b66-45f6-b422-36ee01df6e4a","Тестовый регион","Тестовое МО");}
    public static void main(String[] args) throws Exception {
        if(args.length==2){var source=ru.evolenta.pgs.worker.Util.map(ru.evolenta.pgs.worker.Json.parse(Files.readString(Path.of(args[1]))));var card=map(source.get("card"));var org=map(source.get("organization"));args=new String[]{args[0],text(card.get("organizationName")),text(card.get("ogrn")),text(first(card.get("inn"),org.get("inn"))),text(first(card.get("organizationGuid"),org.get("guid"))),text(map(source.get("passport")).get("regionName")),text(map(source.get("municipality")).get("municipalityName"))};}
        if(args.length!=7)throw new IllegalArgumentException("outputDir organizationName ogrn inn guid regionName municipalityName");
        Path output=Path.of(args[0]);Files.createDirectories(output);var builder=new PetFixtureBuilder(args[1],args[2],args[3],args[4],args[5],args[6]);
        for(String variant:List.of("РОИВ","ОМСУ","Откат"))Files.write(output.resolve("Реестры_животных_291026_"+variant+".xlsx"),builder.full(variant));
        Files.write(output.resolve("Заказ_наряды_291026_51.xlsx"),builder.orders(51,"Пакет"));
        Files.write(output.resolve("Реестры_животных_291026_50+1.xlsx"),builder.full("Связи",45));
        Files.write(output.resolve("Реестры_животных_291026_Уточнение.xlsx"),builder.invalid());
        System.out.println("Exported 3 complete workbooks (6 CREATE + 1 PATCH each, 3 rows),51 orders,50 CREATE +1 PATCH workbook, and one workbook with3 BA errors; no attached documents required: "+output.toAbsolutePath());
    }
    byte[] full(String variant) throws Exception {
        return full(variant,1);
    }
    byte[] full(String variant,int orderCount) throws Exception {
        Map<String,Map<Integer,String>> values=new LinkedHashMap<>();String suffix="291026-"+variant+"-01";
        var order=common();order.put(9,"ЗН-"+suffix);values.put("sheet2",order);
        var animal=common();animal.putAll(Map.of(9,"ЖБВ-"+suffix,10,"Собака",11,"Рыжий",12,"Средний",13,"Не была",15,"Нет",18,"г. Калининград, ул. Лесная, д. 10",19,"У входа в городской парк",21,"Отловлено",23,"ЗН-"+suffix));
        animal.putAll(Map.of(26,"29.10.2026",27,"10:00",28,"29.10.2026",29,"10:30",30,"г. Калининград, ул. Лесная, д. 10",32,"Иванов Иван Иванович",33,"АО-"+suffix,34,"29.10.2026"));values.put("sheet3",animal);
        var card=common();card.putAll(Map.of(9,"КУ-"+suffix,10,"Собака",11,"Мужской",12,"Беспородная",13,"Рыжий",14,"Средний",16,"Короткошерстное",17,"Средние",18,"Средний",21,"12"));card.put(28,"Находится в приюте");
        card.putAll(Map.of(364,"Акт выпуска животного без владельца в места обитания",365,"АВ-"+suffix,366,"29.10.2026",367,organizationName,368,"г. Калининград, ул. Приютская, д. 7",369,inn,370,ogrn,376,"г. Калининград, городской парк"));
        card.putAll(Map.of(423,"Акт приема-передачи животного без владельца с приютом",424,"АП-"+suffix,425,"29.10.2026",426,"Пункт временного содержания Центральный",427,organizationName));values.put("sheet4",card);
        return workbook(values,orderCount,variant);
    }
    byte[] invalid() throws Exception {try(var workbook=new XSSFWorkbook(new ByteArrayInputStream(full("Уточнение")));var output=new ByteArrayOutputStream()){
        workbook.getSheet(PetXlsxParser.SHEETS.get("sheet2")).getRow(5).getCell(2).setCellValue("Другая организация");
        workbook.getSheet(PetXlsxParser.SHEETS.get("sheet3")).getRow(5).getCell(3).setCellValue(ogrn.equals("1234567890123")?"9876543210987":"1234567890123");
        workbook.getSheet(PetXlsxParser.SHEETS.get("sheet4")).getRow(5).getCell(18).setBlank();workbook.write(output);return output.toByteArray();}}
    byte[] orders(int count,String variant) throws Exception {var order=common();order.put(9,"ЗН-291026-"+variant+"-01");return workbook(Map.of("sheet2",order),count,variant);}
    private Map<Integer,String> common(){Map<Integer,String> values=new LinkedHashMap<>();values.putAll(Map.of(1,regionName,2,organizationName,3,ogrn,4,inn,5,organizationName,6,ogrn,7,inn,8,guid));return values;}
    private byte[] workbook(Map<String,Map<Integer,String>> values,int count,String variant) throws Exception {
        var config=new PetConfiguration();try(var workbook=new XSSFWorkbook();var output=new ByteArrayOutputStream()){
            var title=workbook.createSheet("1. Титульный лист");title.createRow(5).createCell(3).setCellValue("Субъект РФ");title.getRow(5).createCell(4).setCellValue(regionName);title.createRow(16).createCell(3).setCellValue("Муниципальное образование");title.getRow(16).createCell(4).setCellValue(municipalityName);
            for(String code:List.of("sheet2","sheet3","sheet4")){
                var sheet=workbook.createSheet(PetXlsxParser.SHEETS.get(code));for(int row=0;row<5;row++)sheet.createRow(row);
                var columns=maps(map(map(config.profile().get("expected")).get(code)).get("columns"));
                for(var column:columns){int index=PetRuleEngine.columnOrder(text(column.get("column")))-1;String[] fields={"attribute","block","subBlock","name","requiredMarker"};for(int row=0;row<5;row++)sheet.getRow(row).createCell(index).setCellValue(text(column.get(fields[row])));}
                if(values.containsKey(code))for(int row=0;row<(code.equals("sheet2")?count:1);row++){
                    var target=sheet.createRow(5+row);for(var column:columns){String attribute=text(column.get("attribute"));if(!attribute.matches("\\d+"))continue;String value=values.get(code).get(Integer.parseInt(attribute));if(value==null)continue;if(code.equals("sheet2")&&attribute.equals("9")&&count>1)value="ЗН-291026-"+variant+"-"+String.format(Locale.ROOT,"%02d",row+1);target.createCell(PetRuleEngine.columnOrder(text(column.get("column")))-1).setCellValue(value);}
                }
            }
            var refs=workbook.createSheet("5. Значения для копирования");refs.createRow(1).createCell(0).setCellValue("Субъект РФ");refs.createRow(2).createCell(0).setCellValue(regionName);
            workbook.write(output);return output.toByteArray();
        }
    }
    OperationContext context(String scope){return new OperationContext("migration","appeal","owner","actor",scope,obj("organization",obj("organizationId","owner","organizationName",organizationName,"ogrn",ogrn),"region",obj("_id","region","regionName",regionName),"municipality",obj("_id","municipality","municipalityName",municipalityName)));}
    RegistryAccess registry(){return new RegistryAccess(){public Optional<Map<String,Object>> findById(String collection,String id){return switch(collection){case "organizations"->id.equals("owner")?Optional.of(obj("_id","owner","guid",guid,"name",organizationName,"ogrn",ogrn,"inn",inn)):Optional.empty();case "rbuPetRegionPassports"->Optional.of(obj("_id","region","regionName",regionName));case "rbuPetRegionMunicipalities"->Optional.of(obj("_id","municipality","municipalityName",municipalityName));default->Optional.empty();};}public List<Map<String,Object>> search(String collection,Map<String,Object> filter,int limit){return collection.equals("organizations")&&guid.equals(filter.get("guid"))?List.of(obj("_id","owner","guid",guid)):List.of();}};}
}
