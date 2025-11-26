package com.jaf.fuzzer.nautilus.tree;

import static org.junit.jupiter.api.Assertions.*;

import com.jaf.fuzzer.nautilus.grammar.Grammar.NonTerminal;
import com.jaf.fuzzer.nautilus.grammar.Grammar.Rule;
import com.jaf.fuzzer.nautilus.grammar.Grammar.T;
import com.jaf.fuzzer.nautilus.tree.DerivationTree.ConcatenationUnparser;
import com.jaf.fuzzer.nautilus.util.TreeOps;
import java.util.HashMap;
import java.util.List;
import org.junit.jupiter.api.Test;

final class TreeOpsTest {

    @Test
    void replaceSwapsOnlyTargetSubtree() {
        NonTerminal s = new NonTerminal("S");
        Rule a = new Rule(s, List.of(new T("a")));
        Rule b = new Rule(s, List.of(new T("b")));
        DerivationTree.Node target = new DerivationTree.Node(s, a);
        DerivationTree original = new DerivationTree(target);

        DerivationTree.Node replacement = new DerivationTree.Node(s, b);
        DerivationTree replaced = TreeOps.replace(original, target, replacement);

        ConcatenationUnparser unparser = new ConcatenationUnparser();
        assertEquals("a", unparser.unparse(original.root, new HashMap<>()), "original unchanged");
        assertEquals("b", unparser.unparse(replaced.root, new HashMap<>()), "replacement applied");
    }

    @Test
    void deepCopyProducesIndependentTree() {
        NonTerminal s = new NonTerminal("S");
        Rule a = new Rule(s, List.of(new T("a")));
        DerivationTree.Node root = new DerivationTree.Node(s, a);
        DerivationTree.Node copy = root.deepCopy();
        copy.children.add(new DerivationTree.Node(s, a));

        assertTrue(root.children.isEmpty(), "original should not be mutated via copy");
    }

    @Test
    void preorderTraversesRootThenChildren() {
        NonTerminal s = new NonTerminal("S");
        Rule a = new Rule(s, List.of(new T("a"), new T("b")));
        DerivationTree.Node root = new DerivationTree.Node(s, a);
        DerivationTree.Node left = new DerivationTree.Node(s, new Rule(s, List.of(new T("x"))));
        DerivationTree.Node right = new DerivationTree.Node(s, new Rule(s, List.of(new T("y"))));
        root.children.add(left);
        root.children.add(right);
        List<DerivationTree.Node> traversal = root.preOrder();
        assertEquals(root, traversal.get(0));
        assertEquals(left, traversal.get(1));
        assertEquals(right, traversal.get(2));
    }
}
