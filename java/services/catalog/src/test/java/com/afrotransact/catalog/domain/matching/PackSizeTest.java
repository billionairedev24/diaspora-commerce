package com.afrotransact.catalog.domain.matching;

import static org.assertj.core.api.Assertions.assertThat;

import com.afrotransact.catalog.domain.matching.PackSize.Agreement;
import org.junit.jupiter.api.Test;

class PackSizeTest {

    private static PackSize size(String text) {
        return PackSize.find(text).orElseThrow().size();
    }

    @Test
    void readsMetricAndImperialSizesInOneUnit() {
        assertThat(size("Basmati rice 5kg").each()).isEqualTo(5000);
        assertThat(size("Basmati rice 5 KG").each()).isEqualTo(5000);
        assertThat(size("Rice 500 g").each()).isEqualTo(500);
        assertThat(size("Palm oil 1.5 L").each()).isEqualTo(1500);
        assertThat(size("Juice 330ml").each()).isEqualTo(330);
        assertThat(size("Flour 2 lb").each()).isEqualTo(907.184);
    }

    @Test
    void readsPacksAndMultiPacks() {
        PackSize multi = size("Cola 6 x 330 ml");
        assertThat(multi.count()).isEqualTo(6);
        assertThat(multi.each()).isEqualTo(330);
        assertThat(size("Noodles 500g 12 pack").count()).isEqualTo(12);
        assertThat(size("Water 24 pack").dimension()).isNull();
    }

    @Test
    void cutsTheSizeOutOfTheText() {
        assertThat(PackSize.find("Tilda basmati rice 5kg").orElseThrow().rest()).isEqualTo("Tilda basmati rice");
        assertThat(PackSize.find("Tilda basmati rice").isEmpty()).isTrue();
    }

    @Test
    void aWordWithADigitIsNotASize() {
        assertThat(PackSize.find("Vitamin B12 capsules").isEmpty()).isTrue();
        assertThat(PackSize.find("Model X5g2").isEmpty()).isTrue();
    }

    @Test
    void sameSizeInOtherWordsIsTheSame() {
        assertThat(size("5kg").against(size("5000 g"))).isEqualTo(Agreement.SAME);
        assertThat(size("500 g").against(size("0.5 kg"))).isEqualTo(Agreement.SAME);
    }

    @Test
    void aDifferentAmountOrPackCountIsDifferent() {
        assertThat(size("5kg").against(size("1 kg"))).isEqualTo(Agreement.DIFFERENT);
        assertThat(size("6 x 330 ml").against(size("330 ml"))).isEqualTo(Agreement.DIFFERENT);
        assertThat(size("1 L").against(size("1 kg"))).isEqualTo(Agreement.DIFFERENT);
        assertThat(size("500 ml").against(null)).isEqualTo(Agreement.UNKNOWN);
    }

    @Test
    void labelsTheSizeInTheSellersWords() {
        assertThat(size("rice 5kg").label()).isEqualTo("5 kg");
        assertThat(size("rice 500 g").label()).isEqualTo("500 g");
        assertThat(size("oil 1500 ml").label()).isEqualTo("1.5 L");
        assertThat(size("cola 6 x 330 ml").label()).isEqualTo("6 x 330 ml");
        assertThat(size("water 24 pack").label()).isEqualTo("24 pack");
    }
}
