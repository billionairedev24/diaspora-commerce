package com.afrotransact.catalog.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.afrotransact.catalog.adapter.out.taxonomy.ClasspathTaxonomySource;
import com.afrotransact.catalog.domain.AttributeDefinition;
import com.afrotransact.catalog.domain.CatalogRuleViolation;
import com.afrotransact.catalog.domain.CategoryNode;
import com.afrotransact.catalog.domain.ProductType;
import com.afrotransact.catalog.domain.taxonomy.StoredProductType;
import com.afrotransact.catalog.domain.taxonomy.TaxonomyAuditEntry;
import com.afrotransact.catalog.port.CategoryRepository;
import com.afrotransact.catalog.port.TaxonomyAuditRepository;
import com.afrotransact.platform.events.DomainEventPublisher;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.PermissionChecker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class TaxonomyImportServiceTest {

    private final Map<String, CategoryNode> nodes = new HashMap<>();
    private final Map<String, StoredProductType> types = new HashMap<>();
    private final CategoryRepository categories = mock(CategoryRepository.class);
    private final TaxonomyAuditRepository audit = mock(TaxonomyAuditRepository.class);
    private final DomainEventPublisher events = mock(DomainEventPublisher.class);
    private final PermissionChecker permissions = mock(PermissionChecker.class);
    private TaxonomyImportServiceImpl service;

    @BeforeEach
    void setUp() {
        when(categories.findByCode(anyString())).thenAnswer(i -> Optional.ofNullable(nodes.get(i.<String>getArgument(0))));
        when(categories.insert(any())).thenAnswer(i -> {
            CategoryNode n = i.getArgument(0);
            nodes.put(n.code(), n);
            return n;
        });
        when(categories.update(any())).thenAnswer(i -> {
            CategoryNode n = i.getArgument(0);
            nodes.put(n.code(), n);
            return n;
        });
        when(categories.findStoredProductType(anyString())).thenAnswer(i -> Optional.ofNullable(types.get(i.<String>getArgument(0))));
        when(categories.insertProductType(any())).thenAnswer(i -> {
            ProductType t = i.getArgument(0);
            StoredProductType s = new StoredProductType(t, 0, null);
            types.put(t.code(), s);
            return s;
        });
        when(categories.updateProductType(any(), anyLong())).thenAnswer(i -> {
            ProductType t = i.getArgument(0);
            StoredProductType s = new StoredProductType(t, 1, null);
            types.put(t.code(), s);
            return s;
        });
        Actor actor = mock(Actor.class);
        when(actor.subject()).thenReturn("staff-1");
        when(permissions.require(CatalogPermissions.TAXONOMY)).thenReturn(actor);
        service = new TaxonomyImportServiceImpl(categories, audit, new ClasspathTaxonomySource(), permissions, events,
                Clock.systemUTC());
    }

    @Test
    void theFirstImportCreatesTheWholeTreeAndAuditsIt() {
        var report = service.run(null, false, false, "Initial load");
        assertThat(report.departments()).isEqualTo(13);
        assertThat(report.categories()).isEqualTo(74);
        assertThat(report.createdNodes()).isGreaterThan(87);
        assertThat(report.createdTypes()).isEqualTo(types.size());
        assertThat(nodes.get("nuts-seeds").taxProfileId()).isEqualTo("nuts-seeds");
        assertThat(nodes.get("nuts-seeds").taxStatus()).isEqualTo("ACTIVE");
        assertThat(nodes.get("beauty-personal-care").compliance()).containsExactly("BEAUTY_LABELLING");
        ArgumentCaptor<TaxonomyAuditEntry> entry = ArgumentCaptor.forClass(TaxonomyAuditEntry.class);
        verify(audit).append(entry.capture());
        assertThat(entry.getValue().actorId()).isEqualTo("staff-1");
        assertThat(entry.getValue().reason()).isEqualTo("Initial load");
        verify(events).publish(eq(CatalogEvents.TAXONOMY_CHANGED), anyString(), anyString(), any());
    }

    @Test
    void importingTwiceChangesNothingTheSecondTime() {
        service.run(null, false, false, "first");
        var again = service.run(null, false, false, "second");
        assertThat(again.createdNodes()).isZero();
        assertThat(again.updatedNodes()).isZero();
        assertThat(again.createdTypes()).isZero();
        assertThat(again.updatedTypes()).isZero();
        assertThat(again.unchangedNodes()).isEqualTo(nodes.size());
    }

    @Test
    void aDryRunReportsAndWritesNothing() {
        var report = service.run(null, true, false, "preview");
        assertThat(report.createdNodes()).isGreaterThan(0);
        assertThat(nodes).isEmpty();
        assertThat(types).isEmpty();
    }

    @Test
    void staffChoicesSurviveAReimportUnlessForced() {
        service.run(null, false, false, "first");
        CategoryNode renamed = nodes.get("rice-grains").withName("Rice and grains", "Groceries & Food › Rice and grains");
        nodes.put("rice-grains", renamed.withTax("rice-grains", "NEEDS_APPROVAL"));
        var again = service.run(null, false, false, "again");
        assertThat(nodes.get("rice-grains").name()).isEqualTo("Rice and grains");
        assertThat(nodes.get("rice-grains").taxStatus()).isEqualTo("NEEDS_APPROVAL");
        assertThat(again.notes()).anyMatch(n -> n.startsWith("rice-grains: status NEEDS_APPROVAL kept"));
        service.run(null, false, true, "reset");
        assertThat(nodes.get("rice-grains").name()).isEqualTo("Rice & grains");
        assertThat(nodes.get("rice-grains").taxStatus()).isEqualTo("ACTIVE");
    }

    @Test
    void theTaxQuestionsFollowTheDatasetButStaffEditedDetailsStay() {
        service.run(null, false, false, "first");
        StoredProductType stored = types.get("packaged-snacks");
        List<AttributeDefinition> edited = new ArrayList<>(
                stored.type().attributes().stream().filter(a -> !a.taxFact()).toList());
        edited.add(new AttributeDefinition("flavour", "Flavour", List.of(), false, false));
        types.put("packaged-snacks", new StoredProductType(new ProductType("packaged-snacks", "Packaged snacks", edited,
                stored.type().themes(), stored.type().identifierRule(), stored.type().traits()), 0, null));
        var again = service.run(null, false, false, "again");
        assertThat(again.updatedTypes()).isEqualTo(1);
        ProductType now = types.get("packaged-snacks").type();
        assertThat(now.attributes()).anyMatch(a -> a.key().equals("flavour"));
        assertThat(now.taxFacts()).extracting(a -> a.key()).contains("net_weight_oz", "servings_per_package");
    }

    @Test
    void aReasonIsNeeded() {
        assertThatThrownBy(() -> service.run(null, false, false, " ")).isInstanceOf(CatalogRuleViolation.class);
    }
}
