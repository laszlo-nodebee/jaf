package com.jaf.fuzzer.nautilus.min;

import com.jaf.fuzzer.nautilus.core.DeterminismChecker;
import com.jaf.fuzzer.nautilus.exec.ExecutionResult;
import com.jaf.fuzzer.nautilus.exec.InstrumentedExecutor;
import com.jaf.fuzzer.nautilus.gen.TreeGenerators;
import com.jaf.fuzzer.nautilus.grammar.Grammar;
import com.jaf.fuzzer.nautilus.tree.DerivationTree;
import com.jaf.fuzzer.nautilus.util.TreeOps;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Objects;

/**
 * Coverage-preserving minimization that performs subtree reduction followed by recursive
 * minimization, mirroring the algorithm outlined in the Nautilus paper.
 */
public final class Minimizer {
    private static final int INF = Integer.MAX_VALUE / 4;
    private static volatile boolean debugEnabled = false;
    private final Grammar grammar;
    private final DerivationTree.Unparser unparser;
    private final TreeGenerators.TreeGenerator generator;
    private final DeterminismChecker determinismChecker;
    private final Map<Grammar.NonTerminal, DerivationTree.Node> minimalTrees;

    public Minimizer(
            Grammar grammar,
            DerivationTree.Unparser unparser,
            TreeGenerators.TreeGenerator generator,
            DeterminismChecker determinismChecker) {
        this.grammar = Objects.requireNonNull(grammar, "grammar");
        this.unparser = Objects.requireNonNull(unparser, "unparser");
        this.generator = Objects.requireNonNull(generator, "generator");
        this.determinismChecker = Objects.requireNonNull(determinismChecker, "determinismChecker");
        this.minimalTrees = computeMinimalTrees(grammar);
    }

    public static void setDebug(boolean enabled) {
        debugEnabled = enabled;
    }

    public DerivationTree run(
            DerivationTree tree,
            Set<Integer> mustCover,
            boolean mustCrash,
            InstrumentedExecutor executor) {
        Set<Integer> filteredMustCover = determinismChecker.filterKnownFlakyEdges(mustCover);
        if (filteredMustCover.isEmpty()) {
            debug("Skipping minimization: empty mustCover");
            return tree;
        }
        debug(
                "Starting minimization mustCover="
                        + filteredMustCover.size()
                        + " mustCrash="
                        + mustCrash);
        DerivationTree current = subtreeMinimize(tree, filteredMustCover, mustCrash, executor);
        DerivationTree minimized = recursiveMinimize(current, filteredMustCover, mustCrash, executor);
        debug("Finished minimization");
        return minimized;
    }

    private DerivationTree subtreeMinimize(
            DerivationTree tree,
            Set<Integer> mustCover,
            boolean mustCrash,
            InstrumentedExecutor executor) {
        boolean changed;
        DerivationTree current = tree;
        do {
            changed = false;
            List<DerivationTree.Node> nodes = current.root.postOrder();
            for (DerivationTree.Node node : nodes) {
                DerivationTree.Node minimal = minimalTrees.get(node.nt);
                if (minimal == null) {
                    continue;
                }
                if (treesEqual(node, minimal)) {
                    continue;
                }
                DerivationTree candidate = TreeOps.replace(current, node, minimal);
                if (preservesCoverage(candidate, mustCover, mustCrash, executor)) {
                    debug("Subtree minimized at " + node.nt);
                    current = candidate;
                    changed = true;
                    break;
                }
            }
        } while (changed);
        return current;
    }

    private DerivationTree recursiveMinimize(
            DerivationTree tree,
            Set<Integer> mustCover,
            boolean mustCrash,
            InstrumentedExecutor executor) {
        boolean changed;
        DerivationTree current = tree;
        do {
            changed = false;
            List<DerivationTree.Node> nodes = current.root.preOrder();
            for (DerivationTree.Node node : nodes) {
                for (DerivationTree.Node child : List.copyOf(node.children)) {
                    if (child.nt.equals(node.nt)) {
                        DerivationTree candidate = TreeOps.replace(current, node, child);
                        if (preservesCoverage(candidate, mustCover, mustCrash, executor)) {
                            debug("Recursive minimized at " + node.nt);
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
            DerivationTree tree,
            Set<Integer> mustCover,
            boolean mustCrash,
            InstrumentedExecutor executor) {
        try {
            String input = unparser.unparse(tree.root, new HashMap<>());
            ExecutionResult result = executor.run(input.getBytes(StandardCharsets.UTF_8));
            if (mustCrash && !result.crashed) {
                return false;
            }
            Set<Integer> edges = determinismChecker.filterKnownFlakyEdges(result.edges);
            return edges.containsAll(mustCover);
        } catch (Exception ignored) {
            return false;
        }
    }

    private boolean treesEqual(DerivationTree.Node left, DerivationTree.Node right) {
        if (left == right) {
            return true;
        }
        if (left == null || right == null) {
            return false;
        }
        if (!left.nt.equals(right.nt)) {
            return false;
        }
        if (left.rule != right.rule) {
            return false;
        }
        if (left.children.size() != right.children.size()) {
            return false;
        }
        for (int i = 0; i < left.children.size(); i++) {
            if (!treesEqual(left.children.get(i), right.children.get(i))) {
                return false;
            }
        }
        return true;
    }

    private Map<Grammar.NonTerminal, DerivationTree.Node> computeMinimalTrees(Grammar grammar) {
        Map<Grammar.NonTerminal, Integer> minSizes = grammar.minSize();
        Map<Grammar.NonTerminal, DerivationTree.Node> memo = new HashMap<>();
        Set<Grammar.NonTerminal> visiting = new HashSet<>();
        for (Grammar.NonTerminal nt : grammar.nonTerminals()) {
            buildMinimalTree(grammar, minSizes, memo, visiting, nt);
        }
        return memo;
    }

    private DerivationTree.Node buildMinimalTree(
            Grammar grammar,
            Map<Grammar.NonTerminal, Integer> minSizes,
            Map<Grammar.NonTerminal, DerivationTree.Node> memo,
            Set<Grammar.NonTerminal> visiting,
            Grammar.NonTerminal nt) {
        if (memo.containsKey(nt)) {
            return memo.get(nt);
        }
        if (visiting.contains(nt)) {
            return null;
        }
        visiting.add(nt);
        Grammar.Rule bestRule = null;
        int bestSize = INF;
        for (Grammar.Rule rule : grammar.rules(nt)) {
            int size = 1;
            boolean valid = true;
            for (Grammar.Symbol symbol : rule.rhs) {
                if (symbol instanceof Grammar.NT ntSymbol) {
                    int childSize = minSizes.getOrDefault(ntSymbol.nt, INF);
                    if (childSize >= INF) {
                        valid = false;
                        break;
                    }
                    size = safeAdd(size, childSize);
                    if (size >= INF) {
                        valid = false;
                        break;
                    }
                }
            }
            if (valid && size < bestSize) {
                bestSize = size;
                bestRule = rule;
            }
        }
        DerivationTree.Node node = null;
        if (bestRule != null) {
            node = new DerivationTree.Node(nt, bestRule);
            for (Grammar.Symbol symbol : bestRule.rhs) {
                if (symbol instanceof Grammar.NT ntSymbol) {
                    DerivationTree.Node child =
                            buildMinimalTree(grammar, minSizes, memo, visiting, ntSymbol.nt);
                    if (child == null) {
                        node = null;
                        break;
                    }
                    node.children.add(child.deepCopy());
                }
            }
        }
        visiting.remove(nt);
        memo.put(nt, node);
        return node;
    }

    private int safeAdd(int a, int b) {
        long sum = (long) a + (long) b;
        if (sum >= INF) {
            return INF;
        }
        return (int) sum;
    }

    private static void debug(String message) {
        if (!debugEnabled) {
            return;
        }
        System.out.println("[Minimizer] " + message);
    }
}
