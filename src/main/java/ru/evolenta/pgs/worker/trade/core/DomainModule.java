package ru.evolenta.pgs.worker.trade.core;

import java.util.List;
import java.util.Set;

public interface DomainModule {
    String id();

    Set<String> writableCollections();

    ValidationReport validate(
            WorkbookDraft draft, OperationContext context, RegistryAccess registry);

    List<PlannedEntity> plan(
            WorkbookDraft draft, OperationContext context, RegistryAccess registry);

    default List<PlannedPatch> patches(
            WorkbookDraft draft, OperationContext context, RegistryAccess registry) {
        return List.of();
    }
}
