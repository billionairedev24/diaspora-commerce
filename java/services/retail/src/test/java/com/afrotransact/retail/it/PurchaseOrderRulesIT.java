package com.afrotransact.retail.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.net.http.HttpResponse;
import java.util.List;

import com.afrotransact.platform.web.error.ConflictException;
import com.afrotransact.platform.web.error.ForbiddenActionException;
import com.afrotransact.retail.application.CreatePoCommand;
import com.afrotransact.retail.application.PoLineInput;
import com.afrotransact.retail.domain.ApprovalTier;
import com.afrotransact.retail.domain.PoStatus;
import com.afrotransact.retail.domain.PurchaseOrder;
import com.afrotransact.retail.domain.RetailItem;
import com.afrotransact.retail.domain.RetailRuleViolation;
import com.afrotransact.retail.domain.Supplier;
import com.afrotransact.retail.domain.SupplierStatus;
import com.afrotransact.retail.port.PurchaseOrderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;

/** S070 rules: Finance threshold via permissions, optimistic locking, supplier status, cancel, ATIN checks. */
class PurchaseOrderRulesIT extends RetailIntegrationTest {

    @LocalServerPort
    int port;
    PurchaseOrderRepository repository;

    @BeforeEach
    void wireServices(@Autowired PurchaseOrderRepository repositoryBean) {
        this.repository = repositoryBean;
    }

    private PurchaseOrder draft(Supplier supplier, RetailItem item, int qty, long unitCost) {
        as(TestActors.BUYER);
        return orders.create(new CreatePoCommand(supplier.id(), "HOU-FC", "FOB Tema", TODAY, null,
                new BigDecimal("15.42"), List.of(new PoLineInput(item.id(), qty, unitCost))));
    }

    @Test
    void ordersAboveTenThousandDollarsNeedFinance() {
        Supplier supplier = approvedSupplier("GHS");
        RetailItem rice = item(supplier, "Aged Basmati Rice, 10 lb", null, 12_800);
        PurchaseOrder small = orders.submit(draft(supplier, rice, 100, 12_800).id(), null);
        assertThat(small.approvalTier()).isEqualTo(ApprovalTier.STANDARD);
        as(TestActors.LEAD);
        assertThat(orders.approve(small.id(), null, null).status()).isEqualTo(PoStatus.APPROVED);

        PurchaseOrder big = draft(supplier, rice, 2000, 12_800); // GHS 256,000 ≈ USD 16,601
        assertThat(big.approvalTier()).isEqualTo(ApprovalTier.FINANCE);
        assertThat(big.totalBaseMinor()).isGreaterThan(1_000_000);
        orders.submit(big.id(), null);
        as(TestActors.LEAD);
        assertThatThrownBy(() -> orders.approve(big.id(), null, null)).isInstanceOf(ForbiddenActionException.class)
                .extracting("code").isEqualTo("retail.finance-approval-required");
        as(TestActors.BUYER);
        assertThatThrownBy(() -> orders.approve(big.id(), null, null)).isInstanceOf(ForbiddenActionException.class);
        as(TestActors.FINANCE);
        PurchaseOrder approved = orders.approve(big.id(), "within Q4 budget", null);
        assertThat(approved.approvedBy()).isEqualTo("kwesi.finance");
        assertThat(outboxTypes(big.id())).contains("retail.po.approved");
    }

    @Test
    void staleVersionsAreRejectedOverHttpAndConcurrentWritesConflict() {
        Supplier supplier = approvedSupplier("GHS");
        RetailItem soap = item(supplier, "African Black Soap, 8 oz", null, 2_540);
        PurchaseOrder po = draft(supplier, soap, 100, 2_540);
        TestActors.reset();

        HttpResponse<String> get = HttpSupport.get(port, "/v1/purchase-orders/" + po.id());
        assertThat(get.statusCode()).isEqualTo(200);
        assertThat(get.headers().firstValue("ETag")).contains("\"0\"");
        HttpResponse<String> submit = HttpSupport.post(port, "/v1/purchase-orders/" + po.id() + "/submit", null,
                "If-Match", "\"0\"");
        assertThat(submit.statusCode()).isEqualTo(200);
        assertThat(submit.headers().firstValue("ETag")).contains("\"1\"");
        HttpResponse<String> stale = HttpSupport.post(port, "/v1/purchase-orders/" + po.id() + "/approve", "{}",
                "If-Match", "\"0\"");
        assertThat(stale.statusCode()).isEqualTo(409);
        assertThat(stale.body()).contains("retail.stale-version");

        PurchaseOrder first = repository.findById(po.id()).orElseThrow();
        PurchaseOrder second = repository.findById(po.id()).orElseThrow();
        repository.update(first.approve("a", null, first.updatedAt()));
        assertThatThrownBy(() -> repository.update(second.sendBack("b", "no", second.updatedAt())))
                .isInstanceOf(ConflictException.class).extracting("code").isEqualTo("retail.concurrent-update");
    }

    @Test
    void inactiveSuppliersCannotReceiveNewOrders() {
        Supplier supplier = approvedSupplier("GHS");
        RetailItem fonio = item(supplier, "Organic Fonio, 2 lb", null, 3_600);
        as(TestActors.BUYER);
        suppliers.changeStatus(supplier.id(), SupplierStatus.INACTIVE, "Failed audit", null);
        assertThatThrownBy(() -> draft(supplier, fonio, 10, 3_600)).isInstanceOf(RetailRuleViolation.class)
                .extracting("code").isEqualTo("retail.supplier-inactive");
        HttpResponse<String> http = HttpSupport.post(port, "/v1/purchase-orders", "{\"supplierId\":\"" + supplier.id()
                + "\",\"destinationNodeId\":\"HOU-FC\",\"incoterm\":\"FOB Tema\",\"fxRate\":15.42,\"lines\":"
                + "[{\"itemId\":\"" + fonio.id() + "\",\"qty\":10}]}");
        assertThat(http.statusCode()).isEqualTo(422);
        assertThat(http.body()).contains("retail.supplier-inactive");
    }

    @Test
    void cancelBeforeShipmentAndEditOnlyDrafts() {
        Supplier supplier = approvedSupplier("GHS");
        RetailItem gari = item(supplier, "Ijebu Gari, 5 lb", null, 4_500);
        PurchaseOrder po = draft(supplier, gari, 100, 4_500);
        PurchaseOrder edited = orders.replaceLines(po.id(), List.of(new PoLineInput(gari.id(), 150, null)),
                po.version());
        assertThat(edited.line(1).qtyOrdered()).isEqualTo(150);
        assertThat(edited.line(1).unitCostMinor()).isEqualTo(4_500);
        PurchaseOrder submitted = orders.submit(po.id(), edited.version());
        assertThatThrownBy(() -> orders.replaceLines(po.id(), List.of(new PoLineInput(gari.id(), 1, null)),
                submitted.version())).hasMessageContaining("PENDING_APPROVAL");
        assertThat(orders.cancel(po.id(), "Supplier out of stock", submitted.version()).status())
                .isEqualTo(PoStatus.CANCELLED);
        assertThat(outboxTypes(po.id())).contains("retail.po.cancelled");
    }

    @Test
    void itemsMustBeSuppliedByTheSupplierAndLinkedToPlatformAtins() {
        Supplier supplier = approvedSupplier("GHS");
        Supplier other = approvedSupplier("GHS");
        RetailItem foreign = item(other, "Red Palm Oil, 1 L", null, 4_480);
        assertThatThrownBy(() -> draft(supplier, foreign, 10, 4_480)).isInstanceOf(RetailRuleViolation.class)
                .extracting("code").isEqualTo("retail.item-not-supplied");

        RetailItem item = item(supplier, "Jollof Seasoning Refill", null, 2_005);
        as(TestActors.BUYER);
        assertThatThrownBy(() -> items.linkAtin(item.id(), Stubs.SELLER_ATIN, null))
                .isInstanceOf(RetailRuleViolation.class).extracting("code").isEqualTo("retail.atin-not-platform");
        assertThatThrownBy(() -> items.linkAtin(item.id(), "AT9UNKNOWN", null))
                .isInstanceOf(RetailRuleViolation.class).extracting("code").isEqualTo("retail.atin-unknown");
        String atin = Stubs.platformAtin();
        RetailItem linked = items.linkAtin(item.id(), atin, null);
        assertThat(linked.atin()).isEqualTo(atin);
        RetailItem another = item(supplier, "Jollof Seasoning Twin", null, 2_005);
        as(TestActors.BUYER);
        assertThatThrownBy(() -> items.linkAtin(another.id(), atin, null)).isInstanceOf(ConflictException.class)
                .extracting("code").isEqualTo("retail.atin-linked");
    }

    @Test
    void buyersWithoutPermissionAreForbidden() {
        Supplier supplier = approvedSupplier("GHS");
        RetailItem item = item(supplier, "Shito Black Pepper Sauce", null, 3_470);
        as(TestActors.RECEIVER);
        assertThatThrownBy(() -> orders.create(new CreatePoCommand(supplier.id(), "HOU-FC", "FOB Tema", TODAY, null,
                null, List.of(new PoLineInput(item.id(), 1, null))))).isInstanceOf(ForbiddenActionException.class);
    }
}
