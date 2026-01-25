package com.jaf.fuzzer.nautilus.core;

import static org.junit.jupiter.api.Assertions.*;

import com.jaf.fuzzer.coverage.CoverageBitmap;
import com.jaf.fuzzer.nautilus.exec.ExecutionResult;
import com.jaf.fuzzer.nautilus.exec.InstrumentedExecutor;
import com.jaf.fuzzer.nautilus.grammar.Grammar;
import com.jaf.fuzzer.nautilus.grammar.Grammar.NonTerminal;
import com.jaf.fuzzer.nautilus.grammar.Grammar.Rule;
import com.jaf.fuzzer.nautilus.grammar.Grammar.T;
import com.jaf.fuzzer.nautilus.tree.DerivationTree;
import java.nio.charset.StandardCharsets;

final class NautilusFuzzerTest {

    @org.junit.jupiter.api.Test
    void addsToCorpusOnlyOnNewCoverage() {
        NonTerminal start = new NonTerminal("START");
        Grammar grammar = new Grammar(start);
        Rule aRule = new Rule(start, java.util.List.of(new T("a")));
        Rule bRule = new Rule(start, java.util.List.of(new T("b")));
        grammar.add(aRule);
        grammar.add(bRule);

        StubExecutor executor = new StubExecutor();

        NautilusFuzzer.Config config = new NautilusFuzzer.Config();
        config.initialSeeds = 0;
        config.enableUniformGeneration = false;

        NautilusFuzzer fuzzer = new NautilusFuzzer(grammar, start, executor, config);

        DerivationTree treeA = new DerivationTree(new DerivationTree.Node(start, aRule));
        DerivationTree treeB = new DerivationTree(new DerivationTree.Node(start, bRule));

        fuzzer.triageForTesting(treeA);
        assertEquals(1, fuzzer.corpus().size());
        assertTrue(fuzzer.coverage().covers(CoverageBitmap.fromIndices(1)));

        fuzzer.triageForTesting(treeB);
        assertEquals(1, fuzzer.corpus().size(), "no new coverage should not grow corpus");
        assertEquals(1, fuzzer.coverageCount());
    }

    private static final class StubExecutor implements InstrumentedExecutor {
        @Override
        public ExecutionResult run(byte[] input) {
            String value = new String(input, StandardCharsets.UTF_8);
            if ("a".equals(value)) {
                return new ExecutionResult(false, CoverageBitmap.fromIndices(1), new byte[0]);
            }
            return new ExecutionResult(false, CoverageBitmap.empty(), new byte[0]);
        }
    }
}
