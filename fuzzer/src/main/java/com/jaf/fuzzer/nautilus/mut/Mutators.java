package com.jaf.fuzzer.nautilus.mut;

import com.jaf.fuzzer.nautilus.gen.TreeGenerators;
import com.jaf.fuzzer.nautilus.grammar.Grammar;
import com.jaf.fuzzer.nautilus.grammar.Grammar.NT;
import com.jaf.fuzzer.nautilus.grammar.Grammar.NonTerminal;
import com.jaf.fuzzer.nautilus.grammar.Grammar.Rule;
import com.jaf.fuzzer.nautilus.grammar.Grammar.Symbol;
import com.jaf.fuzzer.nautilus.grammar.Grammar.T;
import com.jaf.fuzzer.nautilus.tree.DerivationTree;
import com.jaf.fuzzer.nautilus.util.TreeOps;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * Grammar-aware mutators adapted from the Nautilus implementation plan. These operate on derivation
 * trees rather than raw bytes and closely follow the stages outlined in the paper.
 */
public final class Mutators {

    private Mutators() {}

    public interface Mutator {
        DerivationTree mutate(DerivationTree tree, Random random);
    }

    /** Replaces a randomly selected subtree with a freshly generated subtree of the same NT. */
    public static final class RandomSubtreeReplacement implements Mutator {
        private final Grammar grammar;
        private final TreeGenerators.TreeGenerator generator;

        public RandomSubtreeReplacement(Grammar grammar, TreeGenerators.TreeGenerator generator) {
            this.grammar = grammar;
            this.generator = generator;
        }

        @Override
        public DerivationTree mutate(DerivationTree tree, Random random) {
            List<DerivationTree.Node> nodes = tree.root.preOrder();
            DerivationTree.Node target = nodes.get(random.nextInt(nodes.size()));
            DerivationTree.Node replacement = generator.generate(target.nt, 32).root;
            return TreeOps.replace(tree, target, replacement);
        }
    }

    /**
     * Deterministically walks nodes and replaces their production with alternative rules of the
     * same NT. This covers the "rules" stage from the Nautilus paper.
     */
    public static final class RulesMutation implements Mutator {
        private final Grammar grammar;
        private final List<DerivationTree.Node> traversal;
        private int index = 0;

        public RulesMutation(Grammar grammar, DerivationTree tree) {
            this.grammar = grammar;
            this.traversal = tree.root.preOrder();
        }

        @Override
        public DerivationTree mutate(DerivationTree tree, Random random) {
            while (index < traversal.size()) {
                DerivationTree.Node node = traversal.get(index++);
                List<Rule> alternatives = grammar.rules(node.nt);
                for (Rule rule : alternatives) {
                    if (rule == node.rule) {
                        continue;
                    }
                    DerivationTree.Node replacement = new DerivationTree.Node(node.nt, rule);
                    for (Symbol symbol : rule.rhs) {
                        if (symbol instanceof NT ntSymbol) {
                            List<Rule> childRules = grammar.rules(ntSymbol.nt);
                            if (childRules.isEmpty()) {
                                continue;
                            }
                            replacement.children.add(
                                    new DerivationTree.Node(ntSymbol.nt, childRules.get(0)));
                        }
                    }
                    return TreeOps.replace(tree, node, replacement);
                }
            }
            return null;
        }
    }

    /** Attempts to amplify recursive structures by duplicating recursively defined nodes. */
    public static final class RandomRecursiveMutation implements Mutator {
        @Override
        public DerivationTree mutate(DerivationTree tree, Random random) {
            List<DerivationTree.Node> nodes = tree.root.preOrder();
            Collections.shuffle(nodes, random);
            for (DerivationTree.Node node : nodes) {
                for (DerivationTree.Node child : node.children) {
                    if (child.nt.equals(node.nt)) {
                        int repeats = 1 << (1 + random.nextInt(4));
                        DerivationTree.Node current = node;
                        for (int i = 0; i < repeats; i++) {
                            DerivationTree.Node clone = current.deepCopy();
                            DerivationTree.Node parent = new DerivationTree.Node(clone.nt, clone.rule);
                            parent.children.add(clone);
                            current = parent;
                        }
                        return TreeOps.replace(tree, node, current);
                    }
                }
            }
            return null;
        }
    }

    /** Splices a subtree from a donor tree when the non-terminal matches. */
    public static final class SplicingMutation implements Mutator {
        @FunctionalInterface
        public interface Supplier<T> {
            T get();
        }

        private final Supplier<DerivationTree> donorSupplier;

        public SplicingMutation(Supplier<DerivationTree> donorSupplier) {
            this.donorSupplier = donorSupplier;
        }

        @Override
        public DerivationTree mutate(DerivationTree tree, Random random) {
            DerivationTree donor = donorSupplier.get();
            if (donor == null) {
                return null;
            }
            List<DerivationTree.Node> nodesA = tree.root.preOrder();
            List<DerivationTree.Node> nodesB = donor.root.preOrder();
            Collections.shuffle(nodesA, random);
            Collections.shuffle(nodesB, random);
            for (DerivationTree.Node a : nodesA) {
                for (DerivationTree.Node b : nodesB) {
                    if (a.nt.equals(b.nt)) {
                        return TreeOps.replace(tree, a, b);
                    }
                }
            }
            return null;
        }
    }

    /**
     * Byte-level AFL-style mutation used in the deterministic AFL stage. Operates on the string
     * representation of derivation subtrees.
     */
    public static final class AflStyleMutation {
        private static final int[] INTERESTING = {-1, 0, 1, 16, 32, 64, 127, 128, 255, 256, 512, 1024, 4096};

        public byte[] mutateBytes(byte[] input, Random random) {
            byte[] copy = Arrays.copyOf(input, input.length);
            switch (random.nextInt(3)) {
                case 0 -> bitFlip(copy, random);
                case 1 -> arithmetic(copy, random);
                default -> interesting(copy, random);
            }
            return copy;
        }

        private void bitFlip(byte[] data, Random random) {
            int flips = 1 + random.nextInt(Math.max(1, data.length));
            for (int i = 0; i < flips; i++) {
                int index = random.nextInt(data.length);
                int bit = random.nextInt(8);
                data[index] ^= (1 << bit);
            }
        }

        private void arithmetic(byte[] data, Random random) {
            if (data.length == 0) {
                return;
            }
            int index = random.nextInt(data.length);
            data[index] += (byte) (random.nextBoolean() ? 1 : -1);
        }

        private void interesting(byte[] data, Random random) {
            String text = new String(data, StandardCharsets.UTF_8);
            var matcher = java.util.regex.Pattern.compile("\\d+").matcher(text);
            if (matcher.find()) {
                int value = INTERESTING[random.nextInt(INTERESTING.length)];
                String mutated =
                        text.substring(0, matcher.start())
                                + value
                                + text.substring(matcher.end());
                byte[] replacement = mutated.getBytes(StandardCharsets.UTF_8);
                System.arraycopy(replacement, 0, data, 0, Math.min(data.length, replacement.length));
            }
        }
    }

    /** Adds a custom literal rule for a non-terminal to re-use mutated terminal strings. */
    public static Grammar.Rule addCustomTerminalRule(
            Grammar grammar, NonTerminal nonTerminal, String literal) {
        Rule rule = new Rule(nonTerminal, List.of(new T(literal)), "custom", true);
        grammar.add(rule);
        return rule;
    }
}
