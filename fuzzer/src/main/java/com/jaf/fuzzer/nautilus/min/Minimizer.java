package com.jaf.fuzzer.nautilus.min;

import com.jaf.fuzzer.coverage.CoverageBitmap;
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
            CoverageBitmap mustCover,
            boolean mustCrash,
            InstrumentedExecutor executor) {
        CoverageBitmap filteredMustCover = determinismChecker.filterKnownFlakyEdges(mustCover);
        if (filteredMustCover.isEmpty()) {
            debug("Skipping minimization: empty mustCover");
            return tree;
        }
        debug(
                "Starting minimization mustCover="
                        + filteredMustCover.countNonZero()
                        + " mustCrash="
                        + mustCrash);
        DerivationTree current = subtreeMinimize(tree, filteredMustCover, mustCrash, executor);
        DerivationTree minimized = recursiveMinimize(current, filteredMustCover, mustCrash, executor);
        minimized = terminalMinimize(minimized, filteredMustCover, mustCrash, executor);
        debug("Finished minimization");
        return minimized;
    }

    private DerivationTree subtreeMinimize(
            DerivationTree tree,
            CoverageBitmap mustCover,
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
            CoverageBitmap mustCover,
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

    private DerivationTree terminalMinimize(
            DerivationTree tree,
            CoverageBitmap mustCover,
            boolean mustCrash,
            InstrumentedExecutor executor) {
        boolean changed;
        DerivationTree current = tree;
        do {
            changed = false;
            List<DerivationTree.Node> nodes = current.root.preOrder();
            for (DerivationTree.Node node : nodes) {
                for (int i = 0; i < node.rhs.size(); i++) {
                    Grammar.Symbol symbol = node.rhs.get(i);
                    if (!(symbol instanceof Grammar.StringValue value)) {
                        continue;
                    }
                    String minimized =
                            minimizeStringValue(
                                    value, mustCover, mustCrash, executor, current, node, i);
                    if (minimized != null && !minimized.equals(value.value)) {
                        DerivationTree candidate = replaceStringValue(current, node, i, value, minimized);
                        current = candidate;
                        changed = true;
                        break;
                    }
                }
                if (changed) {
                    break;
                }
            }
        } while (changed);
        return current;
    }

    private String minimizeStringValue(
            Grammar.StringValue value,
            CoverageBitmap mustCover,
            boolean mustCrash,
            InstrumentedExecutor executor,
            DerivationTree current,
            DerivationTree.Node node,
            int index) {
        String minimal = value.terminal.minimalString();
        if (!minimal.equals(value.value)) {
            DerivationTree candidate = replaceStringValue(current, node, index, value, minimal);
            if (preservesCoverage(candidate, mustCover, mustCrash, executor)) {
                return minimal;
            }
        }
        String truncated = value.value;
        while (truncated.length() > value.terminal.minLength) {
            truncated = truncated.substring(0, truncated.length() - 1);
            DerivationTree candidate = replaceStringValue(current, node, index, value, truncated);
            if (preservesCoverage(candidate, mustCover, mustCrash, executor)) {
                return truncated;
            }
        }
        char replacement = value.terminal.charset.first();
        char[] chars = value.value.toCharArray();
        for (int i = 0; i < chars.length; i++) {
            if (chars[i] == replacement) {
                continue;
            }
            chars[i] = replacement;
            DerivationTree candidate =
                    replaceStringValue(current, node, index, value, new String(chars));
            if (preservesCoverage(candidate, mustCover, mustCrash, executor)) {
                return new String(chars);
            }
            chars[i] = value.value.charAt(i);
        }
        return null;
    }

    private DerivationTree replaceStringValue(
            DerivationTree tree,
            DerivationTree.Node node,
            int index,
            Grammar.StringValue value,
            String replacement) {
        List<Grammar.Symbol> rhs = new java.util.ArrayList<>(node.rhs);
        rhs.set(index, new Grammar.StringValue(value.terminal, replacement));
        DerivationTree.Node replacementNode = node.copyWithRhs(rhs);
        return TreeOps.replace(tree, node, replacementNode);
    }

    private boolean preservesCoverage(
            DerivationTree tree,
            CoverageBitmap mustCover,
            boolean mustCrash,
            InstrumentedExecutor executor) {
        try {
            String input = unparser.unparse(tree.root, new HashMap<>());
            ExecutionResult result = executor.run(input.getBytes(StandardCharsets.UTF_8));
            if (mustCrash && !result.crashed) {
                return false;
            }
            CoverageBitmap edges = determinismChecker.filterKnownFlakyEdges(result.edges);
            return edges.covers(mustCover);
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
        if (!left.rhs.equals(right.rhs)) {
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
            List<Grammar.Symbol> rhs = new java.util.ArrayList<>(bestRule.rhs.size());
            for (Grammar.Symbol symbol : bestRule.rhs) {
                if (symbol instanceof Grammar.StringTerminal terminal) {
                    rhs.add(new Grammar.StringValue(terminal, terminal.minimalString()));
                } else {
                    rhs.add(symbol);
                }
            }
            node = new DerivationTree.Node(nt, bestRule, rhs);
            for (Grammar.Symbol symbol : rhs) {
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
