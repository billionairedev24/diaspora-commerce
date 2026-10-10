package com.afrotransact.catalog.domain.barcode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;

import com.afrotransact.catalog.domain.CatalogRuleViolation;
import org.junit.jupiter.api.Test;

/** S032: which barcode each label kind gets, and the validation behind it. */
class DefaultLabelPolicyTest {

    private final LabelPolicy policy = new DefaultLabelPolicy(new DefaultBarcodeEncoder());

    private static LabelDraft draft(LabelKind kind, String data, String gtin) {
        return new LabelDraft(kind, null, data, gtin, null, null, null, null, null, null, null, null, "Gari 2 lb",
                List.of(), null);
    }

    @Test
    void unitLabelWithoutGtinIsCode128OfTheAtFnCode() {
        Label label = policy.build(draft(LabelKind.UNIT, "ATF3XK8P2QL", null));
        assertThat(label.symbol().symbology()).isEqualTo(Symbology.CODE_128);
        assertThat(label.symbol().humanReadable()).isEqualTo("ATF3XK8P2QL");
        assertThat(label.caption()).isEqualTo("AFROTRANSACT UNIT LABEL");
        assertThat(label.copies()).isEqualTo(1);
    }

    @Test
    void retailLabelsUseEan13AndFallBackToGs1128ForGtin8And14() {
        assertThat(policy.build(draft(LabelKind.RETAIL, null, "036000291452")).symbol().symbology())
                .isEqualTo(Symbology.EAN_13);
        Label gtin8 = policy.build(draft(LabelKind.RETAIL, null, "96385074"));
        assertThat(gtin8.symbol().symbology()).isEqualTo(Symbology.GS1_128);
        assertThat(gtin8.symbol().humanReadable()).isEqualTo("(01)00000096385074");
        assertThat(policy.build(draft(LabelKind.UNIT, null, "4006381333931")).caption())
                .isEqualTo("MANUFACTURER BARCODE");
    }

    @Test
    void caseLabelEncodesGtinLotProductionAndExpiry() {
        Label label = policy.build(new LabelDraft(LabelKind.CASE, null, null, "9501101530003", null, "L2610-07",
                LocalDate.of(2026, 10, 1), null, LocalDate.of(2027, 3, 31), null, null, null, "Ijebu gari 12 × 2 lb",
                List.of(), 2));
        assertThat(label.symbol().symbology()).isEqualTo(Symbology.GS1_128);
        assertThat(label.symbol().humanReadable()).isEqualTo("(01)09501101530003(11)261001(17)270331(10)L2610-07");
        assertThat(label.lines()).containsExactly("LOT L2610-07 · PROD 2026-10-01 · USE BY 2027-03-31");
        assertThat(label.copies()).isEqualTo(2);
    }

    @Test
    void palletLabelEncodesSsccContentAndCount() {
        Label label = policy.build(new LabelDraft(LabelKind.PALLET, null, null, "09501101530003",
                "106141411234567897", null, null, null, null, null, 40, null, "Inbound HOU", List.of(), null));
        assertThat(label.symbol().humanReadable()).isEqualTo("(00)106141411234567897(02)09501101530003(37)40");
    }

    @Test
    void invalidDraftsNameTheField() {
        assertThatThrownBy(() -> policy.build(draft(LabelKind.CASE, null, "9501101530004")))
                .isInstanceOfSatisfying(CatalogRuleViolation.class, e -> assertThat(e.fieldErrors().get("gtin"))
                        .contains("9501101530003"));
        assertThatThrownBy(() -> policy.build(new LabelDraft(LabelKind.CASE, null, null, "9501101530003", null,
                "LOT#1", null, null, null, null, null, null, null, List.of(), null)))
                .isInstanceOfSatisfying(CatalogRuleViolation.class,
                        e -> assertThat(e.fieldErrors()).containsOnlyKeys("lot"));
        assertThatThrownBy(() -> policy.build(draft(LabelKind.PALLET, null, null)))
                .isInstanceOfSatisfying(CatalogRuleViolation.class,
                        e -> assertThat(e.fieldErrors()).containsOnlyKeys("sscc"));
        assertThatThrownBy(() -> policy.build(draft(LabelKind.UNIT, null, null)))
                .isInstanceOfSatisfying(CatalogRuleViolation.class,
                        e -> assertThat(e.fieldErrors()).containsOnlyKeys("data"));
        assertThatThrownBy(() -> policy.build(draft(LabelKind.UNIT, "X".repeat(40), null)))
                .isInstanceOfSatisfying(CatalogRuleViolation.class,
                        e -> assertThat(e.fieldErrors().get("data")).contains("Too long"));
        assertThatThrownBy(() -> policy.build(new LabelDraft(LabelKind.UNIT, null, "ATF3XK8P2QL", null, null, null,
                null, null, null, null, null, null, null, List.of(), 0)))
                .isInstanceOfSatisfying(CatalogRuleViolation.class,
                        e -> assertThat(e.fieldErrors()).containsOnlyKeys("copies"));
    }

    @Test
    void layoutKeepsTheBarcodeInsideTheLabel() {
        for (LabelKind kind : LabelKind.values()) {
            Label label = policy.build(new LabelDraft(kind, Symbology.CODE_128, "AT0K7Q2M9H", null, null, null, null,
                    null, null, null, null, "CAPTION", "A long product title that will not fit on a small label",
                    List.of("Line one", "Line two"), null));
            LabelLayout l = LabelLayout.of(label);
            double barsEnd = l.barsX() + label.symbol().modules().length() * l.moduleWidth();
            assertThat(l.barsX()).isGreaterThanOrEqualTo(l.margin());
            assertThat(barsEnd).isLessThanOrEqualTo(l.width() - l.margin());
            assertThat(l.barHeight()).isGreaterThanOrEqualTo(5);
            assertThat(l.humanReadable().baseline()).isLessThanOrEqualTo(l.height());
        }
    }
}
