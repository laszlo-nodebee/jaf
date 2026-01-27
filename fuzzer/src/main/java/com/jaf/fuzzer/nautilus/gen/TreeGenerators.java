package com.jaf.fuzzer.nautilus.gen;

import com.jaf.fuzzer.nautilus.grammar.Grammar;
import com.jaf.fuzzer.nautilus.grammar.Grammar.NT;
import com.jaf.fuzzer.nautilus.grammar.Grammar.NonTerminal;
import com.jaf.fuzzer.nautilus.grammar.Grammar.Rule;
import com.jaf.fuzzer.nautilus.grammar.Grammar.Symbol;
import com.jaf.fuzzer.nautilus.tree.DerivationTree;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Tree generators used by the Nautilus pipeline. Includes both a simple random rule generator and a
 * McKenzie-style uniform generator that respects derivation tree sizes.
 */
public final class TreeGenerators {

    private TreeGenerators() {}

    private static volatile boolean debugEnabled = false;

    public static void setDebug(boolean enabled) {
        debugEnabled = enabled;
    }

    public interface TreeGenerator {
        DerivationTree generate(NonTerminal start, int maxSize);

        default DerivationTree generate(
                NonTerminal start, int maxSize, Map<Rule, Integer> minExpansions) {
            return generate(start, maxSize);
        }
    }

    /** Picks productions uniformly at random without considering tree size. */
    public static final class NaiveGenerator implements TreeGenerator {
        private final Grammar grammar;
        private final Random random;

        public NaiveGenerator(Grammar grammar, Random random) {
            this.grammar = grammar;
            this.random = random;
        }

        @Override
        public DerivationTree generate(NonTerminal start, int maxSize) {
            return new DerivationTree(expand(start, maxSize, new HashMap<>(), 0));
        }

        @Override
        public DerivationTree generate(
                NonTerminal start, int maxSize, Map<Rule, Integer> minExpansions) {
            Map<Rule, Integer> remaining = sanitizeMinExpansions(minExpansions);
            return new DerivationTree(expand(start, maxSize, remaining, 0));
        }

        private DerivationTree.Node expand(
                NonTerminal nt, int budget, Map<Rule, Integer> remaining, int depth) {
            List<Rule> rules = grammar.rules(nt);
            Rule rule = chooseRule(rules, remaining, depth);
            debug(depth, "choose " + rule);
            List<Symbol> rhs = Grammar.realizeRhs(rule.rhs, random);
            DerivationTree.Node node = new DerivationTree.Node(nt, rule, rhs);
            decrementRemaining(remaining, rule);
            for (Symbol symbol : rhs) {
                if (symbol instanceof NT ntSymbol) {
                    if (budget <= 1) {
                        break;
                    }
                    node.children.add(expand(ntSymbol.nt, budget - 1, remaining, depth + 1));
                }
            }
            return node;
        }

        private Rule chooseRule(List<Rule> rules, Map<Rule, Integer> remaining, int depth) {
            List<Rule> preferred = new ArrayList<>();
            for (Rule rule : rules) {
                if (remaining.getOrDefault(rule, 0) > 0) {
                    preferred.add(rule);
                }
            }
            List<Rule> candidates = preferred.isEmpty() ? rules : preferred;
            debug(depth, "options " + candidates);
            return candidates.get(random.nextInt(Math.max(1, candidates.size())));
        }

        private void debug(int depth, String message) {
            if (!debugEnabled) {
                return;
            }
            String indent = "  ".repeat(Math.max(0, depth));
            System.out.println("[Gen:Naive] " + indent + message);
        }
    }

    /**
     * Uniform-by-size generator following McKenzie's approach. Relies on the dynamic programming
     * counts produced by {@link Grammar#precomputeUniform(int)}.
     */
    public static final class UniformGenerator implements TreeGenerator {
        private final Grammar grammar;
        private final Grammar.UniformIndex index;
        private final Random random;

        public UniformGenerator(Grammar grammar, Grammar.UniformIndex index, Random random) {
            this.grammar = grammar;
            this.index = index;
            this.random = random;
        }

        @Override
        public DerivationTree generate(NonTerminal start, int maxSize) {
            int min = index.minSize.get(start);
            int size = sampleSize(start, min, Math.min(index.maxSize, maxSize));
            DerivationTree.Node root = sampleNode(start, size, new HashMap<>(), 0);
            return new DerivationTree(root);
        }

        @Override
        public DerivationTree generate(
                NonTerminal start, int maxSize, Map<Rule, Integer> minExpansions) {
            int min = index.minSize.get(start);
            int size = sampleSize(start, min, Math.min(index.maxSize, maxSize));
            Map<Rule, Integer> remaining = sanitizeMinExpansions(minExpansions);
            DerivationTree.Node root = sampleNode(start, size, remaining, 0);
            return new DerivationTree(root);
        }

        private int sampleSize(NonTerminal nt, int min, int max) {
            int ntIndex = index.index.get(nt);
            BigInteger total = BigInteger.ZERO;
            List<BigInteger> weights = new ArrayList<>();
            for (int size = min; size <= max; size++) {
                BigInteger weight = index.counts[ntIndex][size];
                weights.add(weight);
                total = total.add(weight);
            }
            if (total.equals(BigInteger.ZERO)) {
                return Math.max(1, min);
            }
            BigInteger pick = randomBelow(total);
            BigInteger cumulative = BigInteger.ZERO;
            for (int i = 0; i < weights.size(); i++) {
                cumulative = cumulative.add(weights.get(i));
                if (pick.compareTo(cumulative) < 0) {
                    return min + i;
                }
            }
            return Math.max(1, min);
        }

        private DerivationTree.Node sampleNode(
                NonTerminal nt, int size, Map<Rule, Integer> remaining, int depth) {
            int ntIndex = index.index.get(nt);
            debug(depth, "sample " + nt + " size=" + size);
            BigInteger denominator = index.counts[ntIndex][size];
            if (denominator.equals(BigInteger.ZERO)) {
                // Fallback to naive sampling when no combinatorial data is available.
                List<Rule> rules = grammar.rules(nt);
                Rule rule = chooseRule(rules, remaining, depth);
                debug(depth, "choose " + rule);
                List<Symbol> rhs = Grammar.realizeRhs(rule.rhs, random);
                DerivationTree.Node node = new DerivationTree.Node(nt, rule, rhs);
                decrementRemaining(remaining, rule);
                for (Symbol symbol : rhs) {
                    if (symbol instanceof NT ntSymbol) {
                        node.children.add(
                                sampleNode(
                                        ntSymbol.nt,
                                        Math.max(1, index.minSize.get(ntSymbol.nt)),
                                        remaining,
                                        depth + 1));
                    }
                }
                return node;
            }

            Rule chosen = null;
            BigInteger cumulative = BigInteger.ZERO;
            boolean preferOnly = hasPreferredRules(grammar.rules(nt), size, remaining);
            List<Rule> candidates = new ArrayList<>();
            for (Rule rule : grammar.rules(nt)) {
                BigInteger weight = index.perRule.get(rule)[size];
                if (weight.equals(BigInteger.ZERO)) {
                    continue;
                }
                if (preferOnly && remaining.getOrDefault(rule, 0) <= 0) {
                    continue;
                }
                candidates.add(rule);
            }
            debug(depth, "options " + candidates);
            BigInteger pick = randomBelow(denominatorForSize(nt, size, remaining, preferOnly));
            for (Rule rule : grammar.rules(nt)) {
                BigInteger weight = index.perRule.get(rule)[size];
                if (weight.equals(BigInteger.ZERO)) {
                    continue;
                }
                if (preferOnly && remaining.getOrDefault(rule, 0) <= 0) {
                    continue;
                }
                cumulative = cumulative.add(weight);
                if (pick.compareTo(cumulative) < 0) {
                    chosen = rule;
                    break;
                }
            }
            if (chosen == null) {
                chosen = grammar.rules(nt).get(0);
            }

            debug(depth, "choose " + chosen);
            List<Symbol> rhs = Grammar.realizeRhs(chosen.rhs, random);
            DerivationTree.Node node = new DerivationTree.Node(nt, chosen, rhs);
            decrementRemaining(remaining, chosen);
            List<NonTerminal> children = new ArrayList<>();
            for (Symbol symbol : rhs) {
                if (symbol instanceof NT ntSymbol) {
                    children.add(ntSymbol.nt);
                }
            }
            if (children.isEmpty()) {
                return node;
            }

            int base = children.stream().mapToInt(child -> index.minSize.get(child)).sum();
            int remainingSize = size - 1 - base;
            List<int[]> options = new ArrayList<>();
            List<BigInteger> weights = new ArrayList<>();
            final BigInteger[] total = {BigInteger.ZERO};
            enumerateCompositions(
                    remainingSize,
                    children.size(),
                    composition -> {
                        BigInteger product = BigInteger.ONE;
                        for (int i = 0; i < children.size(); i++) {
                            NonTerminal child = children.get(i);
                            int childSize = index.minSize.get(child) + composition[i];
                            BigInteger count = index.counts[index.index.get(child)][childSize];
                            if (count.equals(BigInteger.ZERO)) {
                                product = BigInteger.ZERO;
                                break;
                            }
                            product = product.multiply(count);
                        }
                        if (!product.equals(BigInteger.ZERO)) {
                            options.add(composition.clone());
                            weights.add(product);
                            total[0] = total[0].add(product);
                        }
                    });

            int[] chosenComposition;
            if (options.isEmpty()) {
                chosenComposition = new int[children.size()];
            } else {
                BigInteger pick2 = randomBelow(total[0]);
                BigInteger cumulative2 = BigInteger.ZERO;
                int idx = 0;
                for (; idx < options.size(); idx++) {
                    cumulative2 = cumulative2.add(weights.get(idx));
                    if (pick2.compareTo(cumulative2) < 0) {
                        break;
                    }
                }
                chosenComposition = options.get(Math.min(idx, options.size() - 1));
            }

            for (int i = 0; i < children.size(); i++) {
                int childSize = index.minSize.get(children.get(i)) + chosenComposition[i];
                node.children.add(sampleNode(children.get(i), childSize, remaining, depth + 1));
            }
            return node;
        }

        private void debug(int depth, String message) {
            if (!debugEnabled) {
                return;
            }
            String indent = "  ".repeat(Math.max(0, depth));
            System.out.println("[Gen:Uniform] " + indent + message);
        }

        private BigInteger randomBelow(BigInteger bound) {
            BigInteger value = new BigInteger(bound.bitLength(), random);
            while (value.compareTo(bound) >= 0) {
                value = new BigInteger(bound.bitLength(), random);
            }
            return value;
        }

        private Rule chooseRule(List<Rule> rules, Map<Rule, Integer> remaining, int depth) {
            List<Rule> preferred = new ArrayList<>();
            for (Rule rule : rules) {
                if (remaining.getOrDefault(rule, 0) > 0) {
                    preferred.add(rule);
                }
            }
            List<Rule> candidates = preferred.isEmpty() ? rules : preferred;
            debug(depth, "options " + candidates);
            return candidates.get(random.nextInt(Math.max(1, candidates.size())));
        }

        private boolean hasPreferredRules(
                List<Rule> rules, int size, Map<Rule, Integer> remaining) {
            for (Rule rule : rules) {
                if (remaining.getOrDefault(rule, 0) > 0
                        && !index.perRule.get(rule)[size].equals(BigInteger.ZERO)) {
                    return true;
                }
            }
            return false;
        }

        private BigInteger denominatorForSize(
                NonTerminal nt, int size, Map<Rule, Integer> remaining, boolean preferOnly) {
            if (!preferOnly) {
                return index.counts[index.index.get(nt)][size];
            }
            BigInteger total = BigInteger.ZERO;
            for (Rule rule : grammar.rules(nt)) {
                if (remaining.getOrDefault(rule, 0) <= 0) {
                    continue;
                }
                BigInteger weight = index.perRule.get(rule)[size];
                if (!weight.equals(BigInteger.ZERO)) {
                    total = total.add(weight);
                }
            }
            return total.equals(BigInteger.ZERO) ? index.counts[index.index.get(nt)][size] : total;
        }

        private interface CompositionConsumer {
            void accept(int[] composition);
        }

        private static void enumerateCompositions(
                int remainder, int parts, CompositionConsumer consumer) {
            int[] array = new int[parts];
            enumerateRec(remainder, 0, array, consumer);
        }

        private static void enumerateRec(
                int remainder, int position, int[] array, CompositionConsumer consumer) {
            if (position == array.length - 1) {
                array[position] = remainder;
                consumer.accept(array.clone());
                return;
            }
            for (int value = 0; value <= remainder; value++) {
                array[position] = value;
                enumerateRec(remainder - value, position + 1, array, consumer);
            }
        }
    }

    private static Map<Rule, Integer> sanitizeMinExpansions(Map<Rule, Integer> minExpansions) {
        Map<Rule, Integer> remaining = new HashMap<>();
        if (minExpansions == null || minExpansions.isEmpty()) {
            return remaining;
        }
        for (Map.Entry<Rule, Integer> entry : minExpansions.entrySet()) {
            Integer value = entry.getValue();
            int count = value == null ? 0 : value;
            if (count > 0) {
                remaining.put(entry.getKey(), count);
            }
        }
        return remaining;
    }

    private static void decrementRemaining(Map<Rule, Integer> remaining, Rule rule) {
        Integer count = remaining.get(rule);
        if (count == null || count <= 0) {
            return;
        }
        if (count == 1) {
            remaining.remove(rule);
        } else {
            remaining.put(rule, count - 1);
        }
    }
}
