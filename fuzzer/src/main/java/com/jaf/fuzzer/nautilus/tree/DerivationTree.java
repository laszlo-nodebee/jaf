package com.jaf.fuzzer.nautilus.tree;

import com.jaf.fuzzer.nautilus.grammar.Grammar;
import com.jaf.fuzzer.nautilus.grammar.Grammar.NT;
import com.jaf.fuzzer.nautilus.grammar.Grammar.NonTerminal;
import com.jaf.fuzzer.nautilus.grammar.Grammar.Rule;
import com.jaf.fuzzer.nautilus.grammar.Grammar.SemanticAction;
import com.jaf.fuzzer.nautilus.grammar.Grammar.Symbol;
import com.jaf.fuzzer.nautilus.grammar.Grammar.T;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;

/**
 * Representation of a derivation tree together with an unparser that converts trees back to string
 * form. This mirrors the structure from the Nautilus implementation plan.
 */
public final class DerivationTree {

    public static final class Node {
        public final NonTerminal nt;
        public Rule rule;
        public final List<Node> children = new ArrayList<>();
        public final List<Symbol> rhs;

        public Node(NonTerminal nt, Rule rule) {
            this.nt = nt;
            this.rule = rule;
            this.rhs = rule.rhs;
        }

        public Node deepCopy() {
            Node copy = new Node(nt, rule);
            for (Node child : children) {
                copy.children.add(child.deepCopy());
            }
            return copy;
        }

        public List<Node> preOrder() {
            List<Node> result = new ArrayList<>();
            Deque<Node> stack = new ArrayDeque<>();
            stack.push(this);
            while (!stack.isEmpty()) {
                Node current = stack.pop();
                result.add(current);
                ListIterator<Node> iterator = current.children.listIterator(current.children.size());
                while (iterator.hasPrevious()) {
                    stack.push(iterator.previous());
                }
            }
            return result;
        }

        public List<Node> postOrder() {
            List<Node> result = new ArrayList<>();
            Deque<Node> stack = new ArrayDeque<>();
            Deque<Node> reverse = new ArrayDeque<>();
            stack.push(this);
            while (!stack.isEmpty()) {
                Node current = stack.pop();
                reverse.push(current);
                for (Node child : current.children) {
                    stack.push(child);
                }
            }
            while (!reverse.isEmpty()) {
                result.add(reverse.pop());
            }
            return result;
        }
    }

    public final Node root;

    public DerivationTree(Node root) {
        this.root = root;
    }

    public interface Unparser {
        String unparse(Node node, Map<String, Object> ctx);
    }

    /**
     * Default unparser that concatenates terminal literals. Semantic actions are executed eagerly
     * against the provided context map.
     */
    public static final class ConcatenationUnparser implements Unparser {

        @Override
        public String unparse(Node node, Map<String, Object> ctx) {
            StringBuilder sb = new StringBuilder();
            render(node, ctx, sb);
            return sb.toString();
        }

        private void render(Node node, Map<String, Object> ctx, StringBuilder sb) {
            int childIndex = 0;
            for (Symbol symbol : node.rhs) {
                if (symbol instanceof T terminal) {
                    sb.append(terminal.literal);
                } else if (symbol instanceof NT) {
                    render(node.children.get(childIndex++), ctx, sb);
                } else if (symbol instanceof SemanticAction action) {
                    sb.append(action.render(ctx));
                }
            }
        }
    }
}
