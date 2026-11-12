package com.afrotransact.inventory.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import com.afrotransact.inventory.domain.InventoryRuleViolation;
import com.afrotransact.inventory.domain.Node;
import com.afrotransact.inventory.domain.NodeKind;
import com.afrotransact.inventory.domain.StockImport;
import com.afrotransact.inventory.domain.StockImportMode;
import com.afrotransact.inventory.domain.StockImportStatus;
import com.afrotransact.inventory.domain.StockPosition;
import com.afrotransact.inventory.port.NodeRepository;
import com.afrotransact.inventory.port.PositionRepository;
import com.afrotransact.inventory.port.StockImportRepository;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.PrincipalType;
import com.afrotransact.platform.web.error.ConflictException;
import com.afrotransact.platform.web.error.ForbiddenActionException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

/** S395: who may upload, what is stored, and that a DELTA file is never applied twice. */
class StockImportServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-12T10:00:00Z");
    private static final byte[] FILE = ("Seller SKU,Location,On hand\r\nSKU-1,HOU-FC,10\r\nSKU-2,HOU-FC,5\r\n")
            .getBytes(StandardCharsets.UTF_8);

    private final StockImportRepository imports = mock(StockImportRepository.class);
    private final PositionRepository positions = mock(PositionRepository.class);
    private final NodeRepository nodes = mock(NodeRepository.class);
    private final StockImportJob job = mock(StockImportJob.class);
    private final InventoryAccess access = mock(InventoryAccess.class);
    private final AtomicReference<StockImport> stored = new AtomicReference<>();
    private StockImportServiceImpl service;

    @BeforeEach
    void setUp() {
        PlatformTransactionManager tm = mock(PlatformTransactionManager.class);
        when(tm.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        InventorySettings settings = new InventorySettings("org_retail", Duration.ofMinutes(15), 7, 200,
                BigDecimal.ONE, List.of("USD"));
        service = new StockImportServiceImpl(imports, positions, nodes, job, access, settings, Runnable::run,
                Clock.fixed(NOW, ZoneOffset.UTC), tm);
        when(nodes.findAll()).thenReturn(List.of(new Node("HOU-FC", "Houston FC", NodeKind.FULFILMENT_CENTRE, "US",
                "America/Chicago", List.of(), true)));
        when(positions.findBySkus(anyCollection(), anyString())).thenReturn(List.of(
                new StockPosition("SKU-1", "HOU-FC", "org_seller", 10, 0, 0, 0, 1)));
        when(imports.nextReference()).thenReturn("SI-000001");
        doAnswer(i -> {
            stored.set(i.getArgument(0));
            return null;
        }).when(imports).insert(any(), any());
        when(imports.findById(any())).thenAnswer(i -> Optional.ofNullable(stored.get()));
        when(imports.findLatestByHash(anyString(), any(), anyString())).thenReturn(Optional.empty());
    }

    private void asSeller(String org) {
        Actor actor = new Actor("user_seller", PrincipalType.SELLER_MEMBER, Optional.of(org), Set.of(),
                Set.of(InventoryPermissions.INBOUND, InventoryPermissions.READ));
        when(access.inboundWriter(any())).thenAnswer(i -> new InventoryAccess.InboundActor(actor, org));
        when(access.require(anyString())).thenReturn(actor);
    }

    private void asStaff() {
        Actor staff = new Actor("staff_1", PrincipalType.STAFF, Optional.empty(), Set.of(),
                Set.of(InventoryPermissions.INBOUND, InventoryPermissions.READ));
        when(access.inboundWriter(any())).thenAnswer(i -> new InventoryAccess.InboundActor(staff, i.getArgument(0)));
        when(access.require(anyString())).thenReturn(staff);
    }

    private StockImport captured() {
        ArgumentCaptor<StockImport> captor = ArgumentCaptor.forClass(StockImport.class);
        verify(imports).insert(captor.capture(), any());
        return captor.getValue();
    }

    @Test
    void aSellersUploadIsStoredForItsOwnOrganisationWithTheFileHashAndTheCounts() {
        asSeller("org_seller");

        service.upload("stock.csv", FILE, StockImportMode.ABSOLUTE, "org_someone_else");

        StockImport stored = captured();
        assertThat(stored.ownerOrg()).isEqualTo("org_seller");
        assertThat(stored.fileSha256()).hasSize(64);
        assertThat(stored.status()).isEqualTo(StockImportStatus.VALIDATED);
        assertThat(stored.totalRows()).isEqualTo(2);
        assertThat(stored.validRows()).isEqualTo(1);
        assertThat(stored.errorRows()).isEqualTo(1);
        ArgumentCaptor<List<String>> skus = ArgumentCaptor.forClass(List.class);
        verify(positions).findBySkus(skus.capture(), eq("org_seller"));
    }

    @Test
    void afroTransactRetailCannotBeTheTargetOfAFile() {
        asStaff();

        assertThatThrownBy(() -> service.upload("stock.csv", FILE, StockImportMode.ABSOLUTE, "org_retail"))
                .isInstanceOfSatisfying(ForbiddenActionException.class,
                        e -> assertThat(e.code()).isEqualTo("inventory.retail-stock"));
        verify(imports, never()).insert(any(), any());
    }

    @Test
    void staffMustNameTheOrganisation() {
        asStaff();

        assertThatThrownBy(() -> service.upload("stock.csv", FILE, StockImportMode.ABSOLUTE, null))
                .isInstanceOf(InventoryRuleViolation.class).hasMessage("Name the seller organisation");
    }

    @Test
    void anUploadWhileAnImportRunsIsRefusedAndAnUnreadableFileStoresNothing() {
        asSeller("org_seller");
        when(imports.hasActive("org_seller")).thenReturn(true);

        assertThatThrownBy(() -> service.upload("stock.csv", FILE, StockImportMode.ABSOLUTE, null))
                .isInstanceOfSatisfying(ConflictException.class,
                        e -> assertThat(e.code()).isEqualTo("inventory.import-already-running"));

        when(imports.hasActive("org_seller")).thenReturn(false);
        assertThatThrownBy(() -> service.upload("stock.csv", "Seller SKU,Location\r\nA,HOU-FC\r\n"
                .getBytes(StandardCharsets.UTF_8), StockImportMode.ABSOLUTE, null))
                .isInstanceOf(InventoryRuleViolation.class).hasMessageContaining("no On hand column");
        verify(imports, never()).insert(any(), any());
    }

    @Test
    void theSameFileUploadedAgainIsRecognised() {
        asSeller("org_seller");
        UUID earlier = UUID.randomUUID();
        StockImport old = new StockImport(earlier, "SI-000000", "org_seller", "user_seller", "stock.csv", "x", null,
                StockImportMode.ABSOLUTE, List.of(), StockImportStatus.COMPLETED, 2, 2, 0, 0, 2, 2, 0, 0, NOW, NOW, NOW,
                NOW, 3);
        when(imports.findLatestByHash(eq("org_seller"), eq(StockImportMode.ABSOLUTE), anyString()))
                .thenReturn(Optional.of(old));

        service.upload("stock.csv", FILE, StockImportMode.ABSOLUTE, null);

        assertThat(captured().sameFileAs()).isEqualTo(earlier);
    }

    @Test
    void aDeltaFileAlreadyAppliedIsNeverStartedAgain() {
        asSeller("org_seller");
        UUID id = UUID.randomUUID();
        StockImport pending = new StockImport(id, "SI-000002", "org_seller", "user_seller", "stock.csv", "samehash", null,
                StockImportMode.DELTA, List.of(), StockImportStatus.VALIDATED, 2, 2, 0, 0, 0, 0, 0, 0, NOW, null, null,
                null, 1);
        StockImport applied = new StockImport(UUID.randomUUID(), "SI-000001", "org_seller", "user_seller", "stock.csv",
                "samehash", null, StockImportMode.DELTA, List.of(), StockImportStatus.COMPLETED, 2, 2, 0, 0, 2, 2, 0, 0,
                NOW, NOW, NOW, NOW, 4);
        when(imports.lock(id)).thenReturn(Optional.of(pending));
        when(imports.findLatestByHash("org_seller", StockImportMode.DELTA, "samehash")).thenReturn(Optional.of(applied));

        assertThatThrownBy(() -> service.start(id, false)).isInstanceOfSatisfying(ConflictException.class,
                e -> assertThat(e.code()).isEqualTo("inventory.import-already-applied"));
        verify(imports, never()).update(any());
    }

    @Test
    void aSellerCannotStartAnotherSellersFile() {
        asSeller("org_seller");
        UUID id = UUID.randomUUID();
        StockImport theirs = new StockImport(id, "SI-000009", "org_other", "user_x", "stock.csv", "h", null,
                StockImportMode.ABSOLUTE, List.of(), StockImportStatus.VALIDATED, 1, 1, 0, 0, 0, 0, 0, 0, NOW, null, null,
                null, 1);
        when(imports.lock(id)).thenReturn(Optional.of(theirs));

        assertThatThrownBy(() -> service.start(id, false)).hasMessageContaining("not found");
        verify(job, never()).drive(any());
        verify(imports, never()).update(any());
    }

    @Test
    void startingAValidatedFileQueuesItAndDrivesTheJob() {
        asSeller("org_seller");
        UUID id = UUID.randomUUID();
        StockImport ok = new StockImport(id, "SI-000003", "org_seller", "user_seller", "stock.csv", "h", null,
                StockImportMode.ABSOLUTE, List.of(), StockImportStatus.VALIDATED, 2, 2, 0, 0, 0, 0, 0, 0, NOW, null, null,
                null, 1);
        when(imports.lock(id)).thenReturn(Optional.of(ok));
        when(imports.update(any())).thenAnswer(i -> i.getArgument(0));

        StockImport started = service.start(id, false);

        assertThat(started.status()).isEqualTo(StockImportStatus.QUEUED);
        verify(job).drive(id);
        verify(imports, never()).skipErrorRows(any());
        assertThat(anyInt()).isZero();
        assertThat(anyBoolean()).isFalse();
    }
}
