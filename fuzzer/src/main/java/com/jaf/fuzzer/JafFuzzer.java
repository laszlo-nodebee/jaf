package com.jaf.fuzzer;

import com.jaf.fuzzer.instrumentation.GrpcInstrumentedExecutor;
import com.jaf.fuzzer.nautilus.core.DeterminismChecker;
import com.jaf.fuzzer.nautilus.core.NautilusFuzzer;
import com.jaf.fuzzer.nautilus.gen.TreeGenerators;
import com.jaf.fuzzer.nautilus.grammar.Grammar;
import com.jaf.fuzzer.nautilus.grammar.Grammar.NT;
import com.jaf.fuzzer.nautilus.grammar.Grammar.NonTerminal;
import com.jaf.fuzzer.nautilus.grammar.Grammar.Rule;
import com.jaf.fuzzer.nautilus.min.Minimizer;
import com.jaf.fuzzer.nautilus.mut.Mutators;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Random;

/** Entry point that wires the Nautilus-inspired fuzzer to the HTTP SUT through the agent. */
public final class JafFuzzer {

    private static final String DEFAULT_SOCKET = "/tmp/jaf-coverage.sock";
    private static final URI DEFAULT_TARGET = URI.create("http://127.0.0.1:8080/api/check-body");
    private static final int DEFAULT_DURATION_SECONDS = 30;
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration COVERAGE_TIMEOUT = Duration.ofSeconds(5);

    private JafFuzzer() {}

    public static void main(String[] args) throws Exception {
        CliConfig cli = parseArgs(args);
        Grammar grammar = buildDefaultGrammar();

        NautilusFuzzer.Config config = new NautilusFuzzer.Config();
        config.initialSeeds = 200;
        config.maxTreeSize = 64;
        config.aflTrialsPerItem = 128;
        config.randomStageBudgetPerItem = Duration.ofSeconds(1);
        config.random = new Random();
        config.enableUniformGeneration = cli.enableUniformGeneration();
        TreeGenerators.setDebug(cli.debugGeneration());
        Mutators.setExpansionDebug(cli.debugExpansion());
        Minimizer.setDebug(cli.debugMinimizer());
        DeterminismChecker.setDebug(cli.debugDeterminism());

        Duration budget =
                cli.durationSeconds() <= 0
                        ? Duration.ofHours(1)
                        : Duration.ofSeconds(cli.durationSeconds());
        System.out.println("[JAF] Target=" + cli.targetUri() + ", budget=" + budget);

        try (GrpcInstrumentedExecutor executor =
                GrpcInstrumentedExecutor.forUnixDomainSocket(
                        cli.socketPath(), cli.targetUri(), REQUEST_TIMEOUT, COVERAGE_TIMEOUT)) {
            waitForTarget(cli.targetUri());
            NautilusFuzzer fuzzer =
                    new NautilusFuzzer(grammar, grammar.start(), executor, config);
            Runtime.getRuntime().addShutdownHook(new Thread(executor::close));
            fuzzer.fuzz(budget);
            System.out.println(
                    "[JAF] Finished fuzzing. corpus="
                            + fuzzer.corpus().size()
                            + ", edges="
                            + fuzzer.coverage().size());
        }
    }

    static void waitForTarget(URI target) throws InterruptedException {
        URI healthUri = target;
        try {
            healthUri =
                    new URI(
                            target.getScheme(),
                            target.getUserInfo(),
                            target.getHost(),
                            target.getPort(),
                            "/",
                            null,
                            null);
        } catch (Exception e) {
            System.err.println("[JAF] Failed to derive health URI, using target directly: " + e);
        }

        HttpClient client =
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build();
        int attempts = 60;
        System.out.println("[JAF] Waiting for target availability at " + healthUri);
        for (int i = 0; i < attempts; i++) {
            HttpRequest request =
                    HttpRequest.newBuilder(healthUri)
                            .timeout(Duration.ofSeconds(1))
                            .method("HEAD", HttpRequest.BodyPublishers.noBody())
                            .build();
            try {
                HttpResponse<Void> response =
                        client.send(request, HttpResponse.BodyHandlers.discarding());
                int status = response.statusCode();
                if (status >= 200 && status < 500) {
                    System.out.println("[JAF] Target reachable at " + healthUri);
                    return;
                }
                System.out.println(
                        "[JAF] Target responded with status "
                                + status
                                + ", waiting before retrying...");
            } catch (IOException e) {
                System.out.println("[JAF] Target not ready (" + e.getMessage() + "), retrying...");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw e;
            }
            Thread.sleep(1000);
        }
        throw new RuntimeException("SUT did not become available at " + healthUri);
    }

    static CliConfig parseArgs(String[] args) throws URISyntaxException {
        int duration = DEFAULT_DURATION_SECONDS;
        String socketPath = DEFAULT_SOCKET;
        URI target = DEFAULT_TARGET;
        boolean debugGeneration = false;
        boolean debugExpansion = false;
        boolean enableUniformGeneration = true;
        boolean debugMinimizer = false;
        boolean debugDeterminism = false;
        if (args != null) {
            for (String arg : args) {
                if (arg == null) {
                    continue;
                }
                if (arg.equals("--debug-gen")) {
                    debugGeneration = true;
                    continue;
                }
                if (arg.equals("--debug-expansion")) {
                    debugExpansion = true;
                    continue;
                }
                if (arg.equals("--debug-minimizer")) {
                    debugMinimizer = true;
                    continue;
                }
                if (arg.equals("--debug-determinism")) {
                    debugDeterminism = true;
                    continue;
                }
                if (arg.equals("--no-uniform")) {
                    enableUniformGeneration = false;
                    continue;
                }
                if (arg.startsWith("--duration=")) {
                    String value = arg.substring("--duration=".length());
                    try {
                        duration = Integer.parseInt(value);
                    } catch (NumberFormatException e) {
                        System.err.println("Invalid duration value: " + value);
                    }
                } else if (arg.startsWith("--socket=")) {
                    socketPath = arg.substring("--socket=".length());
                } else if (arg.startsWith("--sut=")) {
                    target = new URI(arg.substring("--sut=".length()));
                }
            }
        }
        return new CliConfig(
                duration,
                socketPath,
                target,
                debugGeneration,
                debugExpansion,
                debugMinimizer,
                debugDeterminism,
                enableUniformGeneration);
    }

    static Grammar buildDefaultGrammar() {
        NonTerminal START = new NonTerminal("START");
        NonTerminal OBJECT = new NonTerminal("OBJECT");
        NonTerminal MEMBERS = new NonTerminal("MEMBERS");
        NonTerminal PAIR = new NonTerminal("PAIR");
        NonTerminal KEY = new NonTerminal("KEY");
        NonTerminal VALUE = new NonTerminal("VALUE");
        NonTerminal STRING = new NonTerminal("STRING");
        NonTerminal STRING_BODY = new NonTerminal("STRING_BODY");
        NonTerminal CHAR = new NonTerminal("CHAR");
        NonTerminal LETTER = new NonTerminal("LETTER");
        NonTerminal DIGIT = new NonTerminal("DIGIT");
        NonTerminal NUMBER = new NonTerminal("NUMBER");
        NonTerminal DIGITS = new NonTerminal("DIGITS");

        Grammar grammar = new Grammar(START);

        grammar.add(new Rule(START, List.of(new NT(OBJECT))));
        grammar.add(new Rule(OBJECT, List.of(new Grammar.T("{"), new NT(MEMBERS), new Grammar.T("}"))));
        grammar.add(new Rule(OBJECT, List.of(new Grammar.T("{"), new Grammar.T("}"))));
        grammar.add(new Rule(MEMBERS, List.of(new NT(PAIR))));
        grammar.add(new Rule(MEMBERS, List.of(new NT(PAIR), new Grammar.T(","), new NT(MEMBERS))));
        grammar.add(new Rule(PAIR, List.of(new NT(KEY), new Grammar.T(":"), new NT(VALUE))));
        grammar.add(new Rule(KEY, List.of(new NT(STRING))));
        grammar.add(new Rule(VALUE, List.of(new NT(STRING))));
        grammar.add(new Rule(VALUE, List.of(new NT(NUMBER))));

        grammar.add(new Rule(STRING, List.of(new Grammar.T("\""), new NT(STRING_BODY), new Grammar.T("\""))));
        grammar.add(new Rule(STRING_BODY, List.of()));
        grammar.add(new Rule(STRING_BODY, List.of(new NT(CHAR), new NT(STRING_BODY))));
        grammar.add(new Rule(CHAR, List.of(new NT(LETTER))));
        grammar.add(new Rule(CHAR, List.of(new NT(DIGIT))));

        grammar.add(new Rule(LETTER, List.of(new Grammar.T("a"))));
        grammar.add(new Rule(LETTER, List.of(new Grammar.T("b"))));
        grammar.add(new Rule(LETTER, List.of(new Grammar.T("c"))));
        grammar.add(new Rule(LETTER, List.of(new Grammar.T("d"))));
        grammar.add(new Rule(LETTER, List.of(new Grammar.T("e"))));
        grammar.add(new Rule(LETTER, List.of(new Grammar.T("f"))));
        grammar.add(new Rule(LETTER, List.of(new Grammar.T("g"))));
        grammar.add(new Rule(LETTER, List.of(new Grammar.T("h"))));
        grammar.add(new Rule(LETTER, List.of(new Grammar.T("i"))));
        grammar.add(new Rule(LETTER, List.of(new Grammar.T("j"))));

        grammar.add(new Rule(DIGIT, List.of(new Grammar.T("0"))));
        grammar.add(new Rule(DIGIT, List.of(new Grammar.T("1"))));
        grammar.add(new Rule(DIGIT, List.of(new Grammar.T("2"))));
        grammar.add(new Rule(DIGIT, List.of(new Grammar.T("3"))));
        grammar.add(new Rule(DIGIT, List.of(new Grammar.T("4"))));
        grammar.add(new Rule(DIGIT, List.of(new Grammar.T("5"))));
        grammar.add(new Rule(DIGIT, List.of(new Grammar.T("6"))));
        grammar.add(new Rule(DIGIT, List.of(new Grammar.T("7"))));
        grammar.add(new Rule(DIGIT, List.of(new Grammar.T("8"))));
        grammar.add(new Rule(DIGIT, List.of(new Grammar.T("9"))));

        grammar.add(new Rule(NUMBER, List.of(new NT(DIGITS))));
        grammar.add(new Rule(DIGITS, List.of(new NT(DIGIT))));
        grammar.add(new Rule(DIGITS, List.of(new NT(DIGIT), new NT(DIGITS))));

        return grammar;
    }

//    static Grammar buildDefaultGrammar() {
//        NonTerminal START = new NonTerminal("START");
//        Grammar grammar = new Grammar(START);
//        grammar.add(new Rule(START, List.of(new Grammar.T("asdf"))));
//        grammar.add(new Rule(START, List.of(new Grammar.T("fdsa"))));
//        grammar.add(new Rule(START, List.of(new Grammar.T("demo-secret"))));
//        grammar.add(new Rule(START, List.of(new Grammar.T("foo"))));
//        grammar.add(new Rule(START, List.of(new Grammar.T("bar"))));
//        return grammar;
//    }


    static record CliConfig(
            int durationSeconds,
            String socketPath,
            URI targetUri,
            boolean debugGeneration,
            boolean debugExpansion,
            boolean debugMinimizer,
            boolean debugDeterminism,
            boolean enableUniformGeneration) {}
}
