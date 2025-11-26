package com.jaf.fuzzer.nautilus.mut;

import static org.junit.jupiter.api.Assertions.*;

import com.jaf.fuzzer.nautilus.gen.TreeGenerators;
import com.jaf.fuzzer.nautilus.grammar.Grammar;
import com.jaf.fuzzer.nautilus.grammar.Grammar.NT;
import com.jaf.fuzzer.nautilus.grammar.Grammar.NonTerminal;
import com.jaf.fuzzer.nautilus.grammar.Grammar.Rule;
import com.jaf.fuzzer.nautilus.grammar.Grammar.T;
import com.jaf.fuzzer.nautilus.tree.DerivationTree;
import com.jaf.fuzzer.nautilus.tree.DerivationTree.ConcatenationUnparser;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

final class MutatorsTest {

    @Test
    void randomSubtreeReplacementUsesGenerator() {
        NonTerminal start = new NonTerminal("S");
        Grammar grammar = new Grammar(start);
        Rule aRule = new Rule(start, List.of(new T("a")));
        grammar.add(aRule);

        DerivationTree original = new DerivationTree(new DerivationTree.Node(start, aRule));
        DerivationTree.Node replacementNode = new DerivationTree.Node(start, new Rule(start, List.of(new T("b"))));
        TreeGenerators.TreeGenerator generator =
                (nt, maxSize) -> new DerivationTree(replacementNode.deepCopy());

        Mutators.RandomSubtreeReplacement mutator =
                new Mutators.RandomSubtreeReplacement(grammar, generator);

        DerivationTree mutated = mutator.mutate(original, new Random(0));
        String text = new ConcatenationUnparser().unparse(mutated.root, new java.util.HashMap<>());
        assertEquals("b", text);
    }

    @Test
    void rulesMutationWalksAlternativesAndStops() {
        NonTerminal start = new NonTerminal("S");
        Grammar grammar = new Grammar(start);
        Rule a = new Rule(start, List.of(new T("a")));
        Rule b = new Rule(start, List.of(new T("b")));
        grammar.add(a);
        grammar.add(b);

        DerivationTree tree = new DerivationTree(new DerivationTree.Node(start, a));
        Mutators.RulesMutation mutator = new Mutators.RulesMutation(grammar, tree);

        DerivationTree first = mutator.mutate(tree, new Random(0));
        String text = new ConcatenationUnparser().unparse(first.root, new java.util.HashMap<>());
        assertEquals("b", text);
        assertNull(mutator.mutate(tree, new Random(0)), "exhausted traversal should return null");
    }

    @Test
    void randomRecursiveMutationDuplicatesRecursiveChild() {
        NonTerminal start = new NonTerminal("S");
        Rule recursive = new Rule(start, List.of(new NT(start), new T("x")));
        Rule base = new Rule(start, List.of(new T("x")));

        Grammar grammar = new Grammar(start);
        grammar.add(recursive);
        grammar.add(base);

        DerivationTree.Node child = new DerivationTree.Node(start, base);
        DerivationTree.Node root = new DerivationTree.Node(start, recursive);
        root.children.add(child);

        Mutators.RandomRecursiveMutation mutator = new Mutators.RandomRecursiveMutation();
        DerivationTree mutated = mutator.mutate(new DerivationTree(root), new Random(1));
        assertNotNull(mutated);
    }

    @Test
    void randomRecursiveMutationReturnsNullWhenNoRecursiveChild() {
        NonTerminal start = new NonTerminal("S");
        Grammar grammar = new Grammar(start);
        Rule base = new Rule(start, List.of(new T("x")));
        grammar.add(base);
        DerivationTree tree = new DerivationTree(new DerivationTree.Node(start, base));
        Mutators.RandomRecursiveMutation mutator = new Mutators.RandomRecursiveMutation();
        assertNull(mutator.mutate(tree, new Random(0)));
    }

    @Test
    void splicingMutationUsesMatchingNonTerminal() {
        NonTerminal start = new NonTerminal("S");
        Rule a = new Rule(start, List.of(new T("a")));
        Rule b = new Rule(start, List.of(new T("b")));
        DerivationTree treeA = new DerivationTree(new DerivationTree.Node(start, a));
        DerivationTree treeB = new DerivationTree(new DerivationTree.Node(start, b));

        Mutators.SplicingMutation mutator = new Mutators.SplicingMutation(() -> treeB);
        DerivationTree mutated = mutator.mutate(treeA, new Random(0));
        String text = new ConcatenationUnparser().unparse(mutated.root, new java.util.HashMap<>());
        assertEquals("b", text);
        assertNull(new Mutators.SplicingMutation(() -> null).mutate(treeA, new Random(0)));
    }

    @Test
    void aflStyleMutationPerformsRequestedStrategy() {
        byte[] input = "value123".getBytes(StandardCharsets.UTF_8);
        Mutators.AflStyleMutation mutator = new Mutators.AflStyleMutation();

        // Force the "interesting" path and deterministic replacement.
        TestRandom random = new TestRandom(new int[] {2, 0});
        byte[] mutated = mutator.mutateBytes(input, random);
        String mutatedText = new String(mutated, StandardCharsets.UTF_8);
        assertTrue(mutatedText.contains("-1"), "should replace digits with selected interesting value");
    }

    private static final class TestRandom extends Random {
        private final int[] values;
        private int index = 0;

        TestRandom(int[] values) {
            this.values = values;
        }

        @Override
        public int nextInt(int bound) {
            int value = values[index % values.length];
            index++;
            return value % bound;
        }
    }
}
