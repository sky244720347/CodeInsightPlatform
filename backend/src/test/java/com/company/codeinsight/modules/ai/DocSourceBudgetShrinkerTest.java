package com.company.codeinsight.modules.ai;

import com.company.codeinsight.modules.ai.support.DocSourceBudgetShrinker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DocSourceBudgetShrinkerTest {

    @Test
    void keepsShortSourceUnchanged() {
        String src = "// === Class: a.Foo ===\nvoid a() {}\n\n// === Class: b.Bar ===\nvoid b() {}\n";
        DocSourceBudgetShrinker.ShrinkResult r = DocSourceBudgetShrinker.shrink(src, 100_000, 12_000);
        assertFalse(r.changed());
        assertEquals(src, r.text());
        assertEquals(2, r.keptBlocks());
        assertEquals(2, r.totalBlocks());
    }

    @Test
    void dropsLaterClassBlocksWhenOverBudget() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 5; i++) {
            sb.append("// === Class: c.C").append(i).append(" ===\n");
            sb.append("x".repeat(1000)).append("\n\n");
        }
        DocSourceBudgetShrinker.ShrinkResult r = DocSourceBudgetShrinker.shrink(sb.toString(), 2500, 12_000);
        assertTrue(r.changed());
        assertTrue(r.keptBlocks() < 5);
        assertTrue(r.text().contains("[已截断：classes="));
        assertTrue(r.text().contains("// === Class: c.C0 ==="));
    }

    @Test
    void truncatesOversizedSingleBlock() {
        String body = "y".repeat(5000);
        String src = "// === Class: big.One ===\n" + body + "\n";
        DocSourceBudgetShrinker.ShrinkResult r = DocSourceBudgetShrinker.shrink(src, 100_000, 1000);
        assertTrue(r.changed());
        assertTrue(r.text().contains("truncated"));
        assertTrue(r.text().length() < src.length());
    }

    @Test
    void shrinkFurtherReducesLength() {
        String src = "// === Class: a.A ===\n" + "z".repeat(8000) + "\n\n"
                + "// === Class: b.B ===\n" + "z".repeat(8000) + "\n";
        DocSourceBudgetShrinker.ShrinkResult r = DocSourceBudgetShrinker.shrinkFurther(src, 12_000, 0.5);
        assertTrue(r.changed());
        assertTrue(r.toChars() < r.fromChars());
    }
}
