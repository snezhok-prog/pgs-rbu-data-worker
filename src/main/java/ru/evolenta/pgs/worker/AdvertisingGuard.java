package ru.evolenta.pgs.worker;
import java.util.*;import static ru.evolenta.pgs.worker.Util.*;
public final class AdvertisingGuard {
    public final Map<String,Object> organization,card,region,municipality;
    public AdvertisingGuard(Context c,boolean requireMigration)throws Exception{
        Map<String,Object> selected=c.selection("organization");String guid=clean(first(selected.get("organizationGuid"),selected.get("guid"),selected.get("id")));
        Map<String,Object> candidate=present(selected.get("organizationGuid"))?c.api.find("rbuRegionMunicipalAdvert",id(selected)):obj();
        if(candidate.isEmpty()&&!id(selected).isEmpty()){Map<String,Object> direct=c.api.find("rbuRegionMunicipalAdvert",id(selected));if(present(direct.get("organizationGuid")))candidate=direct;}
        if(candidate.isEmpty()){List<Object> f=new ArrayList<Object>();f.add(Context.filter("organizationGuid",guid));List<Map<String,Object>> cards=c.api.search("rbuRegionMunicipalAdvert",f,0,2);if(cards.size()!=1)throw new IllegalArgumentException("Организация не определена однозначно в реестре организаций рекламы");candidate=cards.get(0);}
        card=candidate;guid=require(card,"organizationGuid");List<Object> f=new ArrayList<Object>();f.add(Context.filter("guid",guid));List<Map<String,Object>> organizations=c.api.search("organizations",f,0,2);if(organizations.size()!=1)throw new IllegalArgumentException("Не найдена однозначная карточка organizations");organization=organizations.get(0);
        String regionId=clean(at(card,"passport._id")),municipalityId=clean(at(card,"municipality._id"));
        boolean flatTerritory=regionId.isEmpty()&&municipalityId.isEmpty()&&present(card.get("regionCode"))&&present(card.get("municipalityName"));
        if(flatTerritory){regionId=id(c.selection("region"));municipalityId=id(c.selection("municipality"));}
        if(regionId.isEmpty()||municipalityId.isEmpty())throw new IllegalArgumentException("У организации отсутствует действующая привязка к субъекту РФ или МО");
        region=c.api.find("rbuRegionPassportsMarketing",regionId);municipality=c.api.find("rbuRegionMunicipalitiesMarketing",municipalityId);if(!regionId.equals(id(region))||!municipalityId.equals(id(municipality))||!regionId.equals(clean(municipality.get("passportId"))))throw new IllegalArgumentException("Территориальные связи организации недействительны");
        if(flatTerritory&&(!clean(card.get("regionCode")).equals(clean(region.get("regionCode")))||!key(card.get("municipalityName")).equals(key(municipality.get("municipalityName")))))throw new IllegalArgumentException("Территория не соответствует актуальной карточке организации");
        checkSelection(c.selection("region"),regionId,"passport",clean(region.get("regionName")));
        checkSelection(c.selection("municipality"),municipalityId,"municipality",clean(municipality.get("municipalityName")));
        if(c.command.profile==Command.Profile.OMSU){Map<String,Object> owner=c.api.find("organizations",clean(at(c.appeal,"unit.id")));if(owner.isEmpty()||!guid.equals(clean(owner.get("guid"))))throw new IllegalArgumentException("ОМСУ может выполнять операцию только для своей организации");if(requireMigration&&!bool(card.get("isMigration")))throw new IllegalArgumentException("Организации не предоставлено право импорта данных");}
    }
    private void checkSelection(Map<String,Object> selected,String expected,String nested,String name){if(selected.isEmpty())throw new IllegalArgumentException("Не заполнен территориальный контекст");String direct=id(selected),linked=clean(at(selected,nested+"._id"));if(expected.equals(direct)||expected.equals(linked))return;throw new IllegalArgumentException("Выбранная территория не соответствует действующим связям организации: "+name);}
    public Map<String,Object> context(){return obj("region",region.get("regionName"),"municipality",municipality.get("municipalityName"),"organization",organization,"organizationCardId",id(card),"regionId",id(region),"municipalityId",id(municipality));}
}
