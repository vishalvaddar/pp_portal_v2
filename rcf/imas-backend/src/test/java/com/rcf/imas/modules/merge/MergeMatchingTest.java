package com.rcf.imas.modules.merge;

import com.rcf.imas.modules.merge.service.MergeMatching;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MergeMatchingTest {

    private final MergeMatching m = new MergeMatching();

    @Test
    void normalizeTextUppercasesAndKeepsOnlyLetters() {
        assertThat(m.normalizeText("Belagavi City-1")).isEqualTo("BELAGAVICITY");  // drops space, dash, digit
        assertThat(m.normalizeText("bagalkot.")).isEqualTo("BAGALKOT");
        assertThat(m.normalizeText("")).isEqualTo("");
        assertThat(m.normalizeText(null)).isNull();
    }

    @Test
    void studentNameKeyLowercasesAndKeepsAlphanumeric() {
        assertThat(m.generateStudentNameKey("Asha  Rani.")).isEqualTo("asharani");
        assertThat(m.generateStudentNameKey("R@vi-123")).isEqualTo("rvi123");  // keeps digits, drops symbols
        assertThat(m.generateStudentNameKey(null)).isEqualTo("");
        assertThat(m.generateStudentNameKey("")).isEqualTo("");
    }

    @Test
    void suggestValueReturnsBestPrefixMatchAboveThreshold() {
        // options are pre-normalized block keys (as loadBlocks stores them)
        List<String> opts = List.of("BELAGAVI", "BAILHONGAL", "GOKAK");
        // "BELGAVI" vs "BELAGAVI": prefix matches B,E,L then A!=G ... compute: key=BELGAVI(7), opt=BELAGAVI(8)
        // positions 0..6: B=B,E=E,L=L,G!=A,A!=G,V!=A,I!=V,... wait min len 7 → i0 B,i1 E,i2 L,i3 G vs A(no)...
        // matches=3, ratio=3/8=0.375 → NOT > 0.4 → for this input best stays null unless another beats it
        assertThat(m.suggestValue("BELGAVI", opts)).isNull();
    }

    @Test
    void suggestValueMatchesOnStrongPrefix() {
        List<String> opts = List.of("BELAGAVI", "GOKAK");
        // "BELAGAVIX"(9) vs "BELAGAVI"(8): min 8, all 8 match → ratio 8/9=0.888 > 0.4 → BELAGAVI
        assertThat(m.suggestValue("BELAGAVIX", opts)).isEqualTo("BELAGAVI");
    }

    @Test
    void suggestValueNoOptionsOrNoMatchIsNull() {
        assertThat(m.suggestValue("ANYTHING", List.of())).isNull();
        assertThat(m.suggestValue("ZZZZ", List.of("AAAA"))).isNull();  // 0 matches → 0.0 not > 0.4
    }
}
