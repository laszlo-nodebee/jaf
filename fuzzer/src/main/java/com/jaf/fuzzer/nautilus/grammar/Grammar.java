package com.jaf.fuzzer.nautilus.grammar;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * In-memory representation of a context-free grammar with optional semantic actions.
 *
 * <p>This is a direct adaptation of the implementation described in the Nautilus implementation
 * plan. It powers the grammar-aware generation and mutations used by the fuzzer.
 */
public final class Grammar {

    public static final class NonTerminal {
        public final String name;

        public NonTerminal(String name) {
            this.name = Objects.requireNonNull(name, "name");
        }

        @Override
        public String toString() {
            return "<" + name + ">";
        }
    }

    public interface Symbol {}

    public static final class T implements Symbol {
        public final String literal;

        public T(String literal) {
            this.literal = Objects.requireNonNull(literal, "literal");
        }

        @Override
        public String toString() {
            return "'" + literal + "'";
        }
    }

    public static final class NT implements Symbol {
        public final NonTerminal nt;

        public NT(NonTerminal nt) {
            this.nt = Objects.requireNonNull(nt, "nt");
        }

        @Override
        public String toString() {
            return nt.toString();
        }
    }

    /**
     * Semantic actions allow grammars to emit content that depends on the current unparsing
     * context. The Nautilus pipeline treats these as terminals whose value is computed lazily.
     */
    public interface SemanticAction extends Symbol {
        String render(Map<String, Object> ctx);
    }

    public static final class Rule {
        public final NonTerminal lhs;
        public final List<Symbol> rhs;
        public final String name;
        public final boolean isCustom;

        public Rule(NonTerminal lhs, List<Symbol> rhs) {
            this(lhs, rhs, null, false);
        }

        public Rule(NonTerminal lhs, List<Symbol> rhs, String name, boolean isCustom) {
            this.lhs = Objects.requireNonNull(lhs, "lhs");
            this.rhs = List.copyOf(Objects.requireNonNull(rhs, "rhs"));
            this.name = name;
            this.isCustom = isCustom;
        }

        @Override
        public String toString() {
            return lhs + " -> " + rhs;
        }
    }

    private final NonTerminal start;
    private final Map<NonTerminal, List<Rule>> byLhs = new HashMap<>();

    public Grammar(NonTerminal start) {
        this.start = Objects.requireNonNull(start, "start");
    }

    public NonTerminal start() {
        return start;
    }

    public void add(Rule rule) {
        byLhs.computeIfAbsent(rule.lhs, key -> new ArrayList<>()).add(rule);
    }

    public List<Rule> rules(NonTerminal nt) {
        return byLhs.getOrDefault(nt, List.of());
    }

    public Set<NonTerminal> nonTerminals() {
        return Collections.unmodifiableSet(byLhs.keySet());
    }

    public List<Rule> allRules() {
        return byLhs.values().stream().flatMap(List::stream).toList();
    }

    /**
     * Computes the minimum derivation tree size for all non-terminals using a Bellman-Ford style
     * relaxation. The size metric counts non-terminal nodes.
     */
    public Map<NonTerminal, Integer> minSize() {
        Map<NonTerminal, Integer> memo = new HashMap<>();
        for (NonTerminal nt : nonTerminals()) {
            memo.put(nt, Integer.MAX_VALUE / 4);
        }
        for (int iter = 0; iter < 64; iter++) {
            boolean changed = false;
            for (Rule rule : allRules()) {
                int size = 1;
                for (Symbol symbol : rule.rhs) {
                    if (symbol instanceof NT ntSymbol) {
                        size = safeAdd(size, memo.getOrDefault(ntSymbol.nt, Integer.MAX_VALUE / 4));
                    }
                }
                Integer current = memo.get(rule.lhs);
                if (size < current) {
                    memo.put(rule.lhs, size);
                    changed = true;
                }
            }
            if (!changed) {
                break;
            }
        }
        return memo;
    }

    /**
     * Precomputes the combinatorial counts required for uniform-by-size derivation tree sampling.
     */
    public UniformIndex precomputeUniform(int maxSize) {
        Map<NonTerminal, Integer> min = minSize();
        Map<NonTerminal, Integer> index = new HashMap<>();
        int next = 0;
        for (NonTerminal nt : nonTerminals()) {
            index.put(nt, next++);
        }
        BigInteger[][] count = new BigInteger[index.size()][maxSize + 1];
        Map<Rule, BigInteger[]> perRule = new HashMap<>();

        for (NonTerminal nt : nonTerminals()) {
            int ntIdx = index.get(nt);
            Arrays.fill(count[ntIdx], BigInteger.ZERO);
            for (Rule rule : rules(nt)) {
                perRule.put(rule, new BigInteger[maxSize + 1]);
            }
        }

        for (int size = 1; size <= maxSize; size++) {
            for (NonTerminal nt : nonTerminals()) {
                int ntIdx = index.get(nt);
                BigInteger total = BigInteger.ZERO;
                for (Rule rule : rules(nt)) {
                    BigInteger ways = countForRule(rule, size, min, index, count);
                    perRule.get(rule)[size] = ways;
                    total = total.add(ways);
                }
                count[ntIdx][size] = total;
            }
        }

        return new UniformIndex(this, index, min, count, perRule, maxSize);
    }

    private static BigInteger countForRule(
            Rule rule,
            int targetSize,
            Map<NonTerminal, Integer> min,
            Map<NonTerminal, Integer> index,
            BigInteger[][] count) {

        List<NonTerminal> children = new ArrayList<>();
        for (Symbol symbol : rule.rhs) {
            if (symbol instanceof NT ntSymbol) {
                children.add(ntSymbol.nt);
            }
        }

        if (children.isEmpty()) {
            return targetSize == 1 ? BigInteger.ONE : BigInteger.ZERO;
        }

        int minSum = 0;
        for (NonTerminal child : children) {
            minSum = safeAdd(minSum, min.getOrDefault(child, Integer.MAX_VALUE / 4));
        }

        int remaining = targetSize - 1 - minSum;
        if (remaining < 0) {
            return BigInteger.ZERO;
        }

        final BigInteger[] total = {BigInteger.ZERO};
        enumerateCompositions(
                remaining,
                children.size(),
                composition -> {
                    BigInteger product = BigInteger.ONE;
                    for (int i = 0; i < children.size(); i++) {
                        NonTerminal child = children.get(i);
                        int childSize = min.get(child) + composition[i];
                        BigInteger value = count[index.get(child)][childSize];
                        if (value.equals(BigInteger.ZERO)) {
                            product = BigInteger.ZERO;
                            break;
                        }
                        product = product.multiply(value);
                    }
                    total[0] = total[0].add(product);
                });
        return total[0];
    }

    private static void enumerateCompositions(int remainder, int parts, ComboConsumer consumer) {
        int[] composition = new int[parts];
        enumerateRec(remainder, 0, composition, consumer);
    }

    private static void enumerateRec(int remainder, int position, int[] composition, ComboConsumer consumer) {
        if (position == composition.length - 1) {
            composition[position] = remainder;
            consumer.accept(composition.clone());
            return;
        }
        for (int value = 0; value <= remainder; value++) {
            composition[position] = value;
            enumerateRec(remainder - value, position + 1, composition, consumer);
        }
    }

    private static int safeAdd(int a, int b) {
        long sum = (long) a + (long) b;
        return (int) Math.min(sum, Integer.MAX_VALUE / 4);
    }

    private interface ComboConsumer {
        void accept(int[] combo);
    }

    public static final class UniformIndex {
        public final Grammar grammar;
        public final Map<NonTerminal, Integer> index;
        public final Map<NonTerminal, Integer> minSize;
        public final BigInteger[][] counts;
        public final Map<Rule, BigInteger[]> perRule;
        public final int maxSize;

        UniformIndex(
                Grammar grammar,
                Map<NonTerminal, Integer> index,
                Map<NonTerminal, Integer> minSize,
                BigInteger[][] counts,
                Map<Rule, BigInteger[]> perRule,
                int maxSize) {
            this.grammar = grammar;
            this.index = index;
            this.minSize = minSize;
            this.counts = counts;
            this.perRule = perRule;
            this.maxSize = maxSize;
        }
    }
}
