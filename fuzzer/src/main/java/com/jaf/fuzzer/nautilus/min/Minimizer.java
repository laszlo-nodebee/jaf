package com.jaf.fuzzer.nautilus.min;

import com.jaf.fuzzer.nautilus.exec.ExecutionResult;
import com.jaf.fuzzer.nautilus.exec.InstrumentedExecutor;
import com.jaf.fuzzer.nautilus.gen.TreeGenerators;
import com.jaf.fuzzer.nautilus.grammar.Grammar;
import com.jaf.fuzzer.nautilus.tree.DerivationTree;
import com.jaf.fuzzer.nautilus.util.TreeOps;
import java.util.HashMap;
import java.util.List;
import java.util.Set;

/**
 * Coverage-preserving minimization that performs subtree reduction followed by recursive
 * minimization, mirroring the algorithm outlined in the Nautilus paper.
 */
public final class Minimizer {
    private final Grammar grammar;
    private final DerivationTree.Unparser unparser;
    private final TreeGenerators.TreeGenerator generator;

    public Minimizer(
            Grammar grammar,
            DerivationTree.Unparser unparser,
            TreeGenerators.TreeGenerator generator) {
        this.grammar = grammar;
        this.unparser = unparser;
        this.generator = generator;
    }

    public DerivationTree run(
            DerivationTree tree, Set<Integer> mustCover, InstrumentedExecutor executor) {
        DerivationTree current = subtreeMinimize(tree, mustCover, executor);
        return recursiveMinimize(current, mustCover, executor);
    }

    private DerivationTree subtreeMinimize(
            DerivationTree tree, Set<Integer> mustCover, InstrumentedExecutor executor) {
        boolean changed;
        DerivationTree current = tree;
        do {
            changed = false;
            List<DerivationTree.Node> nodes = current.root.preOrder();
            for (DerivationTree.Node node : nodes) {
                DerivationTree replacement =
                        new DerivationTree(generator.generate(node.nt, /*maxSize*/ 8).root);
                DerivationTree candidate = TreeOps.replace(current, node, replacement.root);
                if (preservesCoverage(candidate, mustCover, executor)) {
                    current = candidate;
                    changed = true;
                }
            }
        } while (changed);
        return current;
    }

    private DerivationTree recursiveMinimize(
            DerivationTree tree, Set<Integer> mustCover, InstrumentedExecutor executor) {
        boolean changed;
        DerivationTree current = tree;
        do {
            changed = false;
            List<DerivationTree.Node> nodes = current.root.preOrder();
            for (DerivationTree.Node node : nodes) {
                for (DerivationTree.Node child : List.copyOf(node.children)) {
                    if (child.nt.equals(node.nt)) {
                        DerivationTree candidate = TreeOps.replace(current, node, child);
                        if (preservesCoverage(candidate, mustCover, executor)) {
                            current = candidate;
                            changed = true;
                            break;
                        }
                    }
                }
                if (changed) {
                    break;
                }
            }
        } while (changed);
        return current;
    }

    private boolean preservesCoverage(
            DerivationTree tree, Set<Integer> mustCover, InstrumentedExecutor executor) {
        try {
            String input = unparser.unparse(tree.root, new HashMap<>());
            ExecutionResult result = executor.run(input.getBytes());
            return result.edges.containsAll(mustCover);
        } catch (Exception ignored) {
            return false;
        }
    }
}
