package com.jaf.fuzzer.nautilus.mut;

import static org.junit.jupiter.api.Assertions.*;

import com.jaf.fuzzer.nautilus.gen.TreeGenerators;
import com.jaf.fuzzer.nautilus.grammar.Grammar;
import com.jaf.fuzzer.nautilus.grammar.Grammar.NT;
import com.jaf.fuzzer.nautilus.grammar.Grammar.NonTerminal;
import com.jaf.fuzzer.nautilus.grammar.Grammar.Rule;
import com.jaf.fuzzer.nautilus.grammar.Grammar.StringTerminal;
import com.jaf.fuzzer.nautilus.grammar.Grammar.StringValue;
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
    void expansionMutationAddsContentWhileKeepingOriginalSubtree() {
        NonTerminal list = new NonTerminal("LIST");
        NonTerminal item = new NonTerminal("ITEM");
        Grammar grammar = new Grammar(list);
        Rule base = new Rule(list, List.of(new NT(item)));
        Rule recursive = new Rule(list, List.of(new NT(list), new T(","), new NT(item)));
        Rule a = new Rule(item, List.of(new T("a")));
        Rule b = new Rule(item, List.of(new T("b")));
        grammar.add(base);
        grammar.add(recursive);
        grammar.add(a);
        grammar.add(b);

        DerivationTree.Node itemNode = new DerivationTree.Node(item, a);
        DerivationTree.Node listNode = new DerivationTree.Node(list, base);
        listNode.children.add(itemNode);

        TreeGenerators.TreeGenerator generator =
                (nt, maxSize) -> {
                    if (nt.equals(item)) {
                        return new DerivationTree(new DerivationTree.Node(item, b));
                    }
                    DerivationTree.Node fallback = new DerivationTree.Node(nt, new Rule(nt, List.of()));
                    return new DerivationTree(fallback);
                };

        DerivationTree baseTree = new DerivationTree(listNode);
        Mutators.ExpansionMutation mutator =
                new Mutators.ExpansionMutation(grammar, generator, /*maxSize*/ 8, baseTree);
        DerivationTree mutated = mutator.mutate(baseTree, new Random(0));
        assertNotNull(mutated);
        String text = new ConcatenationUnparser().unparse(mutated.root, new java.util.HashMap<>());
        assertEquals("a,b", text);
    }

    @Test
    void expansionMutationReturnsNullWhenNoRecursiveRule() {
        NonTerminal start = new NonTerminal("S");
        Grammar grammar = new Grammar(start);
        Rule base = new Rule(start, List.of(new T("x")));
        grammar.add(base);
        DerivationTree tree = new DerivationTree(new DerivationTree.Node(start, base));
        Mutators.ExpansionMutation mutator =
                new Mutators.ExpansionMutation(
                        grammar,
                        (nt, maxSize) -> new DerivationTree(new DerivationTree.Node(nt, base)),
                        4,
                        tree);
        assertNull(mutator.mutate(tree, new Random(0)));
    }

    @Test
    void expansionMutationHandlesNestedStructures() {
        NonTerminal obj = new NonTerminal("OBJ");
        NonTerminal pairs = new NonTerminal("PAIRS");
        NonTerminal pair = new NonTerminal("PAIR");
        NonTerminal key = new NonTerminal("KEY");
        NonTerminal value = new NonTerminal("VALUE");
        NonTerminal array = new NonTerminal("ARRAY");
        NonTerminal list = new NonTerminal("LIST");
        NonTerminal item = new NonTerminal("ITEM");
        NonTerminal string = new NonTerminal("STRING");

        Grammar grammar = new Grammar(obj);
        Rule objRule = new Rule(obj, List.of(new T("{"), new NT(pairs), new T("}")));
        Rule pairsBase = new Rule(pairs, List.of(new NT(pair)));
        Rule pairsRecursive = new Rule(pairs, List.of(new NT(pairs), new T(","), new NT(pair)));
        Rule pairRule = new Rule(pair, List.of(new NT(key), new T(":"), new NT(value)));
        Rule keyRule = new Rule(key, List.of(new T("\""), new NT(string), new T("\"")));
        Rule valueArray = new Rule(value, List.of(new NT(array)));
        Rule valueStr = new Rule(value, List.of(new T("\""), new NT(string), new T("\"")));
        Rule arrayRule = new Rule(array, List.of(new T("["), new NT(list), new T("]")));
        Rule listBase = new Rule(list, List.of(new NT(item)));
        Rule listRecursive = new Rule(list, List.of(new NT(item), new T(","), new NT(list)));
        Rule itemRule = new Rule(item, List.of(new T("\""), new NT(string), new T("\"")));
        Rule stringA = new Rule(string, List.of(new T("a")));
        Rule stringB = new Rule(string, List.of(new T("b")));
        Rule stringC = new Rule(string, List.of(new T("c")));

        grammar.add(objRule);
        grammar.add(pairsBase);
        grammar.add(pairsRecursive);
        grammar.add(pairRule);
        grammar.add(keyRule);
        grammar.add(valueArray);
        grammar.add(valueStr);
        grammar.add(arrayRule);
        grammar.add(listBase);
        grammar.add(listRecursive);
        grammar.add(itemRule);
        grammar.add(stringA);
        grammar.add(stringB);
        grammar.add(stringC);

        DerivationTree.Node stringANode = new DerivationTree.Node(string, stringA);
        DerivationTree.Node keyNode = new DerivationTree.Node(key, keyRule);
        keyNode.children.add(stringANode);
        DerivationTree.Node stringCNode = new DerivationTree.Node(string, stringC);
        DerivationTree.Node itemNode = new DerivationTree.Node(item, itemRule);
        itemNode.children.add(stringCNode);
        DerivationTree.Node listNode = new DerivationTree.Node(list, listBase);
        listNode.children.add(itemNode);
        DerivationTree.Node arrayNode = new DerivationTree.Node(array, arrayRule);
        arrayNode.children.add(listNode);

        DerivationTree.Node valueNode = new DerivationTree.Node(value, valueArray);
        valueNode.children.add(arrayNode);

        DerivationTree.Node pairNode = new DerivationTree.Node(pair, pairRule);
        pairNode.children.add(keyNode);
        pairNode.children.add(valueNode);

        DerivationTree.Node pairsNode = new DerivationTree.Node(pairs, pairsBase);
        pairsNode.children.add(pairNode);

        DerivationTree.Node objNode = new DerivationTree.Node(obj, objRule);
        objNode.children.add(pairsNode);

        TreeGenerators.TreeGenerator generator =
                (nt, maxSize) -> {
                    if (nt.equals(pair)) {
                        DerivationTree.Node stringBNode = new DerivationTree.Node(string, stringB);
                        DerivationTree.Node keyBNode = new DerivationTree.Node(key, keyRule);
                        keyBNode.children.add(stringBNode);
                        DerivationTree.Node valueStringCNode = new DerivationTree.Node(string, stringC);
                        DerivationTree.Node valueCNode = new DerivationTree.Node(value, valueStr);
                        valueCNode.children.add(valueStringCNode);
                        DerivationTree.Node pairBNode = new DerivationTree.Node(pair, pairRule);
                        pairBNode.children.add(keyBNode);
                        pairBNode.children.add(valueCNode);
                        return new DerivationTree(pairBNode);
                    }
                    if (nt.equals(item)) {
                        DerivationTree.Node stringBNode = new DerivationTree.Node(string, stringB);
                        DerivationTree.Node itemBNode = new DerivationTree.Node(item, itemRule);
                        itemBNode.children.add(stringBNode);
                        return new DerivationTree(itemBNode);
                    }
                    // Fallback to an empty rule so unexpected paths still succeed.
                    return new DerivationTree(new DerivationTree.Node(nt, new Rule(nt, List.of())));
                };

        DerivationTree baseTree = new DerivationTree(objNode);
        Mutators.ExpansionMutation mutator =
                new Mutators.ExpansionMutation(grammar, generator, /*maxSize*/ 8, baseTree);
        var unparser = new ConcatenationUnparser();

        java.util.Set<String> results = new java.util.HashSet<>();
        DerivationTree current = baseTree;
        DerivationTree mutated;
        while ((mutated = mutator.mutate(current, new Random(0))) != null) {
            String text = unparser.unparse(mutated.root, new java.util.HashMap<>());
            if (text.equals("{\"a\":[\"c\"],\"b\":\"c\"}") || text.equals("{\"a\":[\"b\",\"c\"]}")) {
                results.add(text);
            }
        }
        assertEquals(java.util.Set.of("{\"a\":[\"c\"],\"b\":\"c\"}", "{\"a\":[\"b\",\"c\"]}"), results);
    }

    @Test
    void expansionMutationDoesNotSkipNonTerminatingRecursion() {
        NonTerminal loop = new NonTerminal("LOOP");
        Grammar grammar = new Grammar(loop);
        Rule recursive = new Rule(loop, List.of(new NT(loop), new NT(loop)));
        grammar.add(recursive);

        DerivationTree tree = new DerivationTree(new DerivationTree.Node(loop, recursive));
        Mutators.ExpansionMutation mutator =
                new Mutators.ExpansionMutation(
                        grammar,
                        (nt, maxSize) -> new DerivationTree(new DerivationTree.Node(loop, recursive)),
                        8,
                        tree);

        assertNotNull(mutator.mutate(tree, new Random(0)));
    }

    @Test
    void stringTerminalMutationEditsValueWithinBounds() {
        NonTerminal start = new NonTerminal("S");
        Grammar.CharSet charset = Grammar.CharSet.of("ab");
        StringTerminal terminal = new StringTerminal(charset, 1, 3);
        Grammar grammar = new Grammar(start);
        Rule rule = new Rule(start, List.of(terminal));
        grammar.add(rule);

        List<Grammar.Symbol> rhs = List.of(new StringValue(terminal, "ab"));
        DerivationTree tree = new DerivationTree(new DerivationTree.Node(start, rule, rhs));

        Mutators.StringTerminalMutation mutator = new Mutators.StringTerminalMutation();
        DerivationTree mutated = mutator.mutate(tree, new TestRandom(new int[] {0, 0, 2}));
        assertNotNull(mutated);
        String value =
                new ConcatenationUnparser().unparse(mutated.root, new java.util.HashMap<>());
        assertTrue(value.length() >= 1 && value.length() <= 3);
        for (char c : value.toCharArray()) {
            assertTrue(charset.contains(c));
        }
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
