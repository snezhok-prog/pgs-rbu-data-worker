package ru.evolenta.pgs.worker.trade;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import ru.evolenta.pgs.worker.*;
import static ru.evolenta.pgs.worker.Util.*;

class TradeBusinessHashTest {
    @Test void numericEncodingDoesNotChangeBusinessContent(){
        var sent=map(Json.parse("{\"NTOContract\":{\"PeriodicContractFee\":7.8E+3,\"TotalContractFee\":4.68E+5},\"values\":[1,0,-2.500]}"));
        var stored=map(Json.parse("{\"NTOContract\":{\"PeriodicContractFee\":7800.0,\"TotalContractFee\":468000.0},\"values\":[1.0,-0.0,-2.5]}"));
        assertEquals(TradeImportOperations.businessHash(sent),TradeImportOperations.businessHash(stored));
    }
    @Test void actualValueTypeAndArrayOrderChangesRemainVisible(){
        var original=obj("price",7800,"ids",java.util.List.of(1,2));
        assertNotEquals(TradeImportOperations.businessHash(original),TradeImportOperations.businessHash(obj("price",7800.01,"ids",java.util.List.of(1,2))));
        assertNotEquals(TradeImportOperations.businessHash(original),TradeImportOperations.businessHash(obj("price","7800","ids",java.util.List.of(1,2))));
        assertNotEquals(TradeImportOperations.businessHash(original),TradeImportOperations.businessHash(obj("price",7800,"ids",java.util.List.of(2,1))));
    }
}
