package com.jaf.fuzzer.nautilus.grammar;

import static org.junit.jupiter.api.Assertions.*;

import com.jaf.fuzzer.nautilus.grammar.Grammar.NonTerminal;
import com.jaf.fuzzer.nautilus.grammar.Grammar.Rule;
import com.jaf.fuzzer.nautilus.grammar.Grammar.T;
import java.math.BigInteger;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

final class GrammarTest {

    @Test
    void minSizeComputesNonTerminalWeights() {
        NonTerminal s = new NonTerminal("S");
        NonTerminal t = new NonTerminal("T");
        Grammar grammar = new Grammar(s);
        grammar.add(new Rule(s, java.util.List.of(new Grammar.NT(t))));
        grammar.add(new Rule(t, java.util.List.of(new T("a"))));

        Map<NonTerminal, Integer> min = grammar.minSize();
        assertEquals(2, min.get(s));
        assertEquals(1, min.get(t));
    }

    @Test
    void uniformIndexHasCounts() {
        NonTerminal s = new NonTerminal("S");
        Grammar grammar = new Grammar(s);
        grammar.add(new Rule(s, java.util.List.of(new T("a"))));
        grammar.add(new Rule(s, java.util.List.of(new T("b"))));

        Grammar.UniformIndex index = grammar.precomputeUniform(4);
        int startIdx = index.index.get(s);
        assertTrue(index.counts[startIdx][1].compareTo(BigInteger.ZERO) > 0);
    }

    @Test
    void uniformGeneratorProducesBoundedTrees() {
        NonTerminal s = new NonTerminal("S");
        Grammar grammar = new Grammar(s);
        grammar.add(new Rule(s, java.util.List.of(new T("a"))));
        grammar.add(new Rule(s, java.util.List.of(new T("b"))));

        Grammar.UniformIndex index = grammar.precomputeUniform(2);
        var generator = new com.jaf.fuzzer.nautilus.gen.TreeGenerators.UniformGenerator(grammar, index, new Random(0));
        var tree = generator.generate(s, 2);
        String text = new com.jaf.fuzzer.nautilus.tree.DerivationTree.ConcatenationUnparser()
                .unparse(tree.root, new java.util.HashMap<>());
        assertEquals(1, text.length());
        assertTrue(text.equals("a") || text.equals("b"));
    }
}
