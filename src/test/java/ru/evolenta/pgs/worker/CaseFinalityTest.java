package ru.evolenta.pgs.worker;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static ru.evolenta.pgs.worker.Util.*;

class CaseFinalityTest {
    private final Set<String> terminal = Set.of("complete", "completeNegative", "cancelled");

    @Test void activeChildOverridesFinalParentFlagForEveryDomain() {
        for (String domain : List.of("ADVERTISING", "TRADE", "PET")) {
            var appeal = obj("domain", domain, "status", obj("code", "complete", "isFinish", true),
                    "subservices", List.of(obj("status", obj("code", "process"))));
            assertFalse(CaseFinality.isFinished(appeal, terminal), domain);
        }
    }
    @Test void childNeedsBothFinishedStateAndFinishDate() {
        var appeal = obj("status", obj("code", "complete"), "subservices", List.of(obj("status", obj("code", "complete"))));
        assertFalse(CaseFinality.isFinished(appeal, terminal));
        map(list(appeal.get("subservices")).getFirst()).put("dateFinish", "2026-10-29T10:00:00Z");
        assertTrue(CaseFinality.isFinished(appeal, terminal));
        map(list(appeal.get("subservices")).getFirst()).put("camundaProcessInfo", obj("status", "ACTIVE"));
        assertFalse(CaseFinality.isFinished(appeal, terminal));
    }
    @Test void contradictoryFlagsAndIncompleteMetadataAreConservative() {
        assertFalse(CaseFinality.isFinished(obj("status", obj("code", "process", "isFinish", true)), terminal));
        assertFalse(CaseFinality.isFinished(obj("status", obj("code", "complete")), terminal));
        assertTrue(CaseFinality.isFinished(obj("status", obj("code", "complete", "isFinish", true)), terminal));
        assertFalse(CaseFinality.isFinished(obj("status", obj("code", "complete", "isFinish", true), "subservices", List.of(obj())), terminal));
    }
}
