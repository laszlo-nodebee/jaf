package com.jaf.fuzzer.nautilus.core;

import static org.junit.jupiter.api.Assertions.*;

import com.jaf.fuzzer.nautilus.exec.ExecutionResult;
import com.jaf.fuzzer.nautilus.exec.InstrumentedExecutor;
import com.jaf.fuzzer.nautilus.grammar.Grammar;
import com.jaf.fuzzer.nautilus.grammar.Grammar.NonTerminal;
import com.jaf.fuzzer.nautilus.grammar.Grammar.Rule;
import com.jaf.fuzzer.nautilus.grammar.Grammar.T;
import com.jaf.fuzzer.nautilus.tree.DerivationTree;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Queue;
import java.util.Set;

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
        executor.enqueue(new ExecutionResult(false, Set.of(1), new byte[0]));
        executor.enqueue(new ExecutionResult(false, Set.of(), new byte[0]));

        NautilusFuzzer.Config config = new NautilusFuzzer.Config();
        config.initialSeeds = 0;
        config.enableUniformGeneration = false;

        NautilusFuzzer fuzzer = new NautilusFuzzer(grammar, start, executor, config);

        DerivationTree treeA = new DerivationTree(new DerivationTree.Node(start, aRule));
        DerivationTree treeB = new DerivationTree(new DerivationTree.Node(start, bRule));

        fuzzer.triageForTesting(treeA, NautilusFuzzer.Stage.INIT);
        assertEquals(1, fuzzer.corpus().size());
        assertTrue(fuzzer.coverage().contains(1));

        fuzzer.triageForTesting(treeB, NautilusFuzzer.Stage.INIT);
        assertEquals(1, fuzzer.corpus().size(), "no new coverage should not grow corpus");
        assertEquals(1, fuzzer.coverage().size());
    }

    private static final class StubExecutor implements InstrumentedExecutor {
        private final Queue<ExecutionResult> responses = new ArrayDeque<>();

        void enqueue(ExecutionResult result) {
            responses.add(result);
        }

        @Override
        public ExecutionResult run(byte[] input) {
            ExecutionResult result = responses.poll();
            if (result == null) {
                return new ExecutionResult(false, new HashSet<>(), new byte[0]);
            }
            return result;
        }
    }
}
