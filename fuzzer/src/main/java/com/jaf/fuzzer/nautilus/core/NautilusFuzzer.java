package com.jaf.fuzzer.nautilus.core;

import com.jaf.fuzzer.nautilus.exec.ExecutionResult;
import com.jaf.fuzzer.nautilus.exec.InstrumentedExecutor;
import com.jaf.fuzzer.nautilus.gen.TreeGenerators;
import com.jaf.fuzzer.nautilus.gen.TreeGenerators.TreeGenerator;
import com.jaf.fuzzer.nautilus.grammar.Grammar;
import com.jaf.fuzzer.nautilus.min.Minimizer;
import com.jaf.fuzzer.nautilus.mut.Mutators;
import com.jaf.fuzzer.nautilus.tree.DerivationTree;
import com.jaf.fuzzer.nautilus.tree.DerivationTree.ConcatenationUnparser;
import com.jaf.fuzzer.nautilus.util.TreeOps;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;

/**
 * Core Nautilus fuzzer implementation. Closely follows the queue/scheduler defined in the plan:
 * INIT → DET → DET_AFL → RANDOM.
 */
public final class NautilusFuzzer {

    public enum Stage {
        INIT,
        DET,
        DET_AFL,
        RANDOM
    }

    public record QueueItem(DerivationTree tree, Stage stage, Set<Integer> newEdges) {}

    public static final class Config {
        public int initialSeeds = 1000;
        public int maxTreeSize = 64;
        public Duration randomStageBudgetPerItem = Duration.ofSeconds(3);
        public int aflTrialsPerItem = 256;
        public boolean enableUniformGeneration = true;
        public int maxCorpus = 10_000;
        public Random random = new Random();
    }

    private final Grammar grammar;
    private final Grammar.NonTerminal start;
    private final InstrumentedExecutor executor;
    private final DerivationTree.Unparser unparser = new ConcatenationUnparser();
    private final TreeGenerator generator;
    private final Config config;

    private final Deque<QueueItem> queue = new ArrayDeque<>();
    private final List<DerivationTree> corpus = new ArrayList<>();
    private final Set<Integer> globalEdges = new HashSet<>();
    private final Set<Integer> seenHashes = new HashSet<>();

    public NautilusFuzzer(
            Grammar grammar, Grammar.NonTerminal start, InstrumentedExecutor executor, Config config) {
        this.grammar = Objects.requireNonNull(grammar, "grammar");
        this.start = Objects.requireNonNull(start, "start");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.config = Objects.requireNonNull(config, "config");
        if (config.enableUniformGeneration) {
            Grammar.UniformIndex index = grammar.precomputeUniform(config.maxTreeSize);
            this.generator = new TreeGenerators.UniformGenerator(grammar, index, config.random);
        } else {
            this.generator = new TreeGenerators.NaiveGenerator(grammar, config.random);
        }
    }

    public void fuzz(Duration budget) {
        seed();
        Instant deadline = Instant.now().plus(budget);
        while (Instant.now().isBefore(deadline)) {
            QueueItem item = queue.pollFirst();
            if (item == null) {
                if (corpus.isEmpty()) {
                    break;
                }
                mutateCorpusUntil(deadline);
                break;
            }
            switch (item.stage) {
                case INIT -> processInit(item);
                case DET -> processDeterministic(item);
                case DET_AFL -> processDetAfl(item);
                case RANDOM -> processRandom(item, deadline);
            }
        }
    }

    public List<DerivationTree> corpus() {
        return List.copyOf(corpus);
    }

    public Set<Integer> coverage() {
        return Set.copyOf(globalEdges);
    }

    private void seed() {
        for (int i = 0; i < config.initialSeeds; i++) {
            DerivationTree tree = generator.generate(start, config.maxTreeSize);
            maybeEnqueue(tree, Stage.INIT);
        }
    }

    private void processInit(QueueItem item) {
        Minimizer minimizer = new Minimizer(grammar, unparser, generator);
        DerivationTree minimized = minimizer.run(item.tree, item.newEdges, executor);
        enqueue(new QueueItem(minimized, Stage.DET, item.newEdges));
    }

    private void processDeterministic(QueueItem item) {
        Mutators.RulesMutation rules = new Mutators.RulesMutation(grammar, item.tree);
        Random random = config.random;
        DerivationTree current = item.tree;
        DerivationTree next;
        while ((next = rules.mutate(current, random)) != null) {
            executeAndHandle(next);
            current = next;
        }
        enqueue(new QueueItem(current, Stage.DET_AFL, item.newEdges));
    }

    private void processDetAfl(QueueItem item) {
        Mutators.AflStyleMutation afl = new Mutators.AflStyleMutation();
        Random random = config.random;

        for (int i = 0; i < config.aflTrialsPerItem; i++) {
            String source = unparser.unparse(item.tree.root, new HashMap<>());
            byte[] mutated = afl.mutateBytes(source.getBytes(StandardCharsets.UTF_8), random);
            ExecutionResult result = run(mutated);
            if (consider(result)) {
                globalEdges.addAll(result.edges);
                List<DerivationTree.Node> leaves =
                        item.tree.root.preOrder().stream()
                                .filter(node -> node.children.isEmpty())
                                .toList();
                if (!leaves.isEmpty()) {
                    DerivationTree.Node leaf = leaves.get(random.nextInt(leaves.size()));
                    Mutators.addCustomTerminalRule(
                            grammar, leaf.nt, new String(mutated, StandardCharsets.UTF_8));
                }
            }
        }

        DerivationTree recursive = new Mutators.RandomRecursiveMutation().mutate(item.tree, random);
        if (recursive != null) {
            executeAndHandle(recursive);
        }
        enqueue(new QueueItem(item.tree, Stage.RANDOM, item.newEdges));
    }

    private void processRandom(QueueItem item, Instant deadline) {
        Instant stop = Instant.now().plus(config.randomStageBudgetPerItem);
        mutateWithRandomStage(item.tree, deadline, stop, false);
    }

    private void mutateCorpusUntil(Instant deadline) {
        mutateWithRandomStage(
                corpus.get(config.random.nextInt(corpus.size())), deadline, null, true);
    }

    private void mutateWithRandomStage(
            DerivationTree start, Instant deadline, Instant stop, boolean reseedOnFailure) {
        Random random = config.random;
        var subtreeReplacement = new Mutators.RandomSubtreeReplacement(grammar, generator);
        var splicing =
                new Mutators.SplicingMutation(
                        () -> corpus.isEmpty()
                                ? null
                                : corpus.get(random.nextInt(corpus.size())));

        DerivationTree current = start;
        while (Instant.now().isBefore(deadline) && (stop == null || Instant.now().isBefore(stop))) {
            DerivationTree mutated =
                    random.nextBoolean()
                            ? subtreeReplacement.mutate(current, random)
                            : splicing.mutate(current, random);
            if (mutated != null) {
                executeAndHandle(mutated);
                current = mutated;
            } else if (reseedOnFailure && !corpus.isEmpty()) {
                current = corpus.get(random.nextInt(corpus.size()));
            }
        }
    }

    private void executeAndHandle(DerivationTree tree) {
        String input = unparser.unparse(tree.root, new HashMap<>());
        ExecutionResult result = run(input.getBytes(StandardCharsets.UTF_8));
        if (consider(result)) {
            Set<Integer> newEdges = new HashSet<>(result.edges);
            newEdges.removeAll(globalEdges);
            globalEdges.addAll(result.edges);
            enqueue(new QueueItem(tree, Stage.INIT, newEdges));
        }
    }

    private ExecutionResult run(byte[] input) {
        try {
            return executor.run(input);
        } catch (Exception e) {
            return new ExecutionResult(
                    true, Collections.emptySet(), e.getMessage() == null ? new byte[0] : e.getMessage().getBytes());
        }
    }

    private boolean consider(ExecutionResult result) {
        if (result.crashed) {
            return true;
        }
        for (int edge : result.edges) {
            if (!globalEdges.contains(edge)) {
                return true;
            }
        }
        return false;
    }

    private void maybeEnqueue(DerivationTree tree, Stage stage) {
        String input = unparser.unparse(tree.root, new HashMap<>());
        int hash = input.hashCode();
        if (!seenHashes.add(hash)) {
            return;
        }
        ExecutionResult result = run(input.getBytes(StandardCharsets.UTF_8));
        if (result.crashed || result.edges.stream().anyMatch(edge -> !globalEdges.contains(edge))) {
            Set<Integer> newEdges = new HashSet<>(result.edges);
            newEdges.removeAll(globalEdges);
            globalEdges.addAll(result.edges);
            enqueue(new QueueItem(tree, stage, newEdges));
        }
    }

    private void enqueue(QueueItem item) {
        if (corpus.size() < config.maxCorpus) {
            corpus.add(item.tree);
        }
        queue.addLast(item);
    }
}
