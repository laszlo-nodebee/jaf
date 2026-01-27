package com.jaf.fuzzer.nautilus.util;

import com.jaf.fuzzer.nautilus.tree.DerivationTree;

/**
 * Tree utilities used for structural replacements during minimization and mutation.
 */
public final class TreeOps {

    private TreeOps() {}

    public static DerivationTree replace(
            DerivationTree tree, DerivationTree.Node oldSubtree, DerivationTree.Node newSubtree) {
        DerivationTree.Node copy = replaceRec(tree.root, oldSubtree, newSubtree);
        return new DerivationTree(copy);
    }

    private static DerivationTree.Node replaceRec(
            DerivationTree.Node current, DerivationTree.Node oldSubtree, DerivationTree.Node newSubtree) {
        if (current == oldSubtree) {
            return newSubtree.deepCopy();
        }
        DerivationTree.Node node = new DerivationTree.Node(current.nt, current.rule, current.rhs);
        for (DerivationTree.Node child : current.children) {
            node.children.add(replaceRec(child, oldSubtree, newSubtree));
        }
        return node;
    }
}
