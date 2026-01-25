package com.jaf.fuzzer.nautilus.min;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jaf.fuzzer.coverage.CoverageBitmap;
import com.jaf.fuzzer.nautilus.core.DeterminismChecker;
import com.jaf.fuzzer.nautilus.exec.ExecutionResult;
import com.jaf.fuzzer.nautilus.exec.InstrumentedExecutor;
import com.jaf.fuzzer.nautilus.gen.TreeGenerators;
import com.jaf.fuzzer.nautilus.grammar.Grammar;
import com.jaf.fuzzer.nautilus.grammar.Grammar.NT;
import com.jaf.fuzzer.nautilus.grammar.Grammar.NonTerminal;
import com.jaf.fuzzer.nautilus.grammar.Grammar.Rule;
import com.jaf.fuzzer.nautilus.grammar.Grammar.T;
import com.jaf.fuzzer.nautilus.tree.DerivationTree;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class MinimizerTest {

    @Test
    void preservesCrashesWhileMinimizing() {
        NonTerminal start = new NonTerminal("S");
        Grammar grammar = new Grammar(start);
        Rule recurse = new Rule(start, List.of(new NT(start)));
        Rule crash = new Rule(start, List.of(new T("CRASH")));
        Rule safe = new Rule(start, List.of(new T("SAFE")));
        grammar.add(recurse);
        grammar.add(crash);
        grammar.add(safe);

        DerivationTree.Node root = new DerivationTree.Node(start, recurse);
        root.children.add(new DerivationTree.Node(start, crash));
        DerivationTree tree = new DerivationTree(root);

        TreeGenerators.TreeGenerator generator =
                (startSymbol, maxSize) -> new DerivationTree(new DerivationTree.Node(start, safe));
        DerivationTree.Unparser unparser = new DerivationTree.ConcatenationUnparser();
        InstrumentedExecutor executor = new CrashPreservingExecutor();

        DeterminismChecker checker =
                new DeterminismChecker(
                        input -> new ExecutionResult(false, CoverageBitmap.empty(), new byte[0]), 1);
        Minimizer minimizer = new Minimizer(grammar, unparser, generator, checker);
        DerivationTree minimized =
                minimizer.run(tree, CoverageBitmap.fromIndices(1), true, executor);

        String minimizedInput = unparser.unparse(minimized.root, Map.of());
        assertEquals("CRASH", minimizedInput, "minimizer should keep crashing input");
        boolean crashed = runExecutor(executor, minimizedInput);
        assertTrue(crashed, "minimized input should still crash");
        assertEquals(1, countNodes(minimized.root));
    }

    @Test
    void usesPrecomputedMinimalSubtree() {
        NonTerminal start = new NonTerminal("S");
        Grammar grammar = new Grammar(start);
        Rule viaChild = new Rule(start, List.of(new NT(start)));
        Rule minimal = new Rule(start, List.of(new T("MIN")));
        grammar.add(viaChild);
        grammar.add(minimal);

        DerivationTree.Node root = new DerivationTree.Node(start, viaChild);
        root.children.add(new DerivationTree.Node(start, minimal));
        DerivationTree tree = new DerivationTree(root);

        TreeGenerators.TreeGenerator generator = (startSymbol, maxSize) -> tree;
        DerivationTree.Unparser unparser = new DerivationTree.ConcatenationUnparser();
        InstrumentedExecutor executor =
                input -> new ExecutionResult(false, CoverageBitmap.fromIndices(1), new byte[0]);

        DeterminismChecker checker =
                new DeterminismChecker(
                        input -> new ExecutionResult(false, CoverageBitmap.empty(), new byte[0]), 1);
        Minimizer minimizer = new Minimizer(grammar, unparser, generator, checker);
        DerivationTree minimized =
                minimizer.run(tree, CoverageBitmap.fromIndices(1), false, executor);

        String minimizedInput = unparser.unparse(minimized.root, Map.of());
        assertEquals("MIN", minimizedInput, "should replace with minimal subtree");
        assertEquals(1, countNodes(minimized.root));
    }

    private static int countNodes(DerivationTree.Node node) {
        return node.preOrder().size();
    }

    private static boolean runExecutor(InstrumentedExecutor executor, String input) {
        try {
            return executor.run(input.getBytes(StandardCharsets.UTF_8)).crashed;
        } catch (Exception e) {
            throw new AssertionError("executor threw unexpectedly", e);
        }
    }

    private static final class CrashPreservingExecutor implements InstrumentedExecutor {
        @Override
        public ExecutionResult run(byte[] input) {
            String value = new String(input, StandardCharsets.UTF_8);
            boolean crashed = value.contains("CRASH");
            return new ExecutionResult(crashed, CoverageBitmap.fromIndices(1), new byte[0]);
        }
    }
}
