package ru.evolenta.pgs.worker.pet;

import static org.junit.jupiter.api.Assertions.*;
import java.io.ByteArrayInputStream;
import org.junit.jupiter.api.Test;

class PetFixtureBuilderTest {
    @Test void fullWorkbookValidatesSixCollectionsAndOneLinkedPatchWithoutAttachments() throws Exception {
        var fixture=PetFixtureBuilder.defaults();var context=fixture.context("ROIV");
        var draft=new PetXlsxParser().read(new ByteArrayInputStream(fixture.full("РОИВ")),"Реестры.xlsx","file",context);
        var module=new PetModule();var report=module.validate(draft,context,fixture.registry());
        assertTrue(report.valid(),report.issues().stream().map(issue->issue.sheet()+"/"+issue.column()+"="+issue.code()).toList().toString());
        var plan=module.plan(draft,context,fixture.registry());assertEquals(6,plan.size());
        assertEquals(6,plan.stream().map(entity->entity.collection()).distinct().count());
        assertEquals(1,module.patches(draft,context,fixture.registry()).size());
    }
}
