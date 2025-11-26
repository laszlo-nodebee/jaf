package com.jaf.agent;

import java.io.IOException;
import java.io.InputStream;
import java.lang.instrument.Instrumentation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.nio.file.StandardOpenOption;
import org.objectweb.asm.Opcodes;

public class JafAgent {
    public static void premain(String agentArgs, Instrumentation inst) {
        logStartup(agentArgs);
        appendAgentJarToBootstrap(inst);
        startCoverageServer();
        waitForFuzzerConnection();
        installTransformer(inst);
    }

    public static void premain(String agentArgs) {
        logStartup(agentArgs);
        startCoverageServer();
        waitForFuzzerConnection();
    }

    private static void logStartup(String agentArgs) {
        System.out.println(
                "JAF agent initialized. args=" + agentArgs + ", ASM API=" + Opcodes.ASM9);
    }

    private static CoverageServer coverageServer;
    private static volatile boolean bootstrapHelpersInstalled = false;
    private static volatile JarFile bootstrapHelperJar;
    private static Path bootstrapHelperJarPath;

    private static void installTransformer(Instrumentation inst) {
        appendAgentJarToBootstrap(inst);
        String[] targets = {
            "java/lang/Runtime#exec([Ljava/lang/String;[Ljava/lang/String;Ljava/io/File;)Ljava/lang/Process;|command,env,dir",
            "java/io/ObjectInputStream#readObject()Ljava/lang/Object;",
            "java/io/ObjectInputStream#readUnshared()Ljava/lang/Object;",
            "java/io/ObjectInputFilter$Config#setSerialFilter(Ljava/io/ObjectInputFilter;)V|filter",
            "java/rmi/Naming#lookup(Ljava/lang/String;)Ljava/rmi/Remote;|name",
            "java/rmi/registry/Registry#lookup(Ljava/lang/String;)Ljava/rmi/Remote;|name",
            "java/rmi/registry/LocateRegistry#getRegistry()Ljava/rmi/registry/Registry;",
            "java/rmi/registry/LocateRegistry#getRegistry(I)Ljava/rmi/registry/Registry;|port",
            "java/rmi/registry/LocateRegistry#getRegistry(Ljava/lang/String;)Ljava/rmi/registry/Registry;|host",
            "java/rmi/registry/LocateRegistry#getRegistry(Ljava/lang/String;I)Ljava/rmi/registry/Registry;|host,port",
            "java/rmi/registry/LocateRegistry#getRegistry(Ljava/lang/String;ILjava/rmi/server/RMIClientSocketFactory;)Ljava/rmi/registry/Registry;|host,port,factory",
            "javax/naming/InitialContext#lookup(Ljava/lang/String;)Ljava/lang/Object;|name",
            "javax/naming/Context#lookup(Ljava/lang/String;)Ljava/lang/Object;|name",
            "javax/naming/Context#lookup(Ljavax/naming/Name;)Ljava/lang/Object;|name",
            "org/apache/logging/log4j/core/lookup/JndiLookup#lookup(Ljava/lang/String;)Ljava/lang/String;|key",
            "javax/jms/ObjectMessage#getObject()Ljava/lang/Object;",
            "javax/jms/Message#getBody(Ljava/lang/Class;)Ljava/lang/Object;|type",
            "javax/management/remote/JMXConnectorFactory#connect(Ljavax/management/remote/JMXServiceURL;)Ljavax/management/remote/JMXConnector;|url",
            "javax/management/remote/JMXConnectorFactory#connect(Ljavax/management/remote/JMXServiceURL;Ljava/util/Map;)Ljavax/management/remote/JMXConnector;|url,env",
            "javax/management/remote/JMXConnectorServerFactory#newJMXConnectorServer(Ljavax/management/remote/JMXServiceURL;Ljava/util/Map;Ljavax/management/MBeanServer;)Ljavax/management/remote/JMXConnectorServer;|url,env,server",
            "com/sun/tools/attach/VirtualMachine#attach(Ljava/lang/String;)Lcom/sun/tools/attach/VirtualMachine;|id",
            "com/sun/tools/attach/VirtualMachine#attach(Lcom/sun/tools/attach/VirtualMachineDescriptor;)Lcom/sun/tools/attach/VirtualMachine;|descriptor",
            "com/sun/tools/attach/VirtualMachine#loadAgent(Ljava/lang/String;)V|agentPath",
            "com/sun/tools/attach/VirtualMachine#loadAgent(Ljava/lang/String;Ljava/lang/String;)V|agentPath,options",
            "com/sun/tools/attach/VirtualMachine#loadAgentLibrary(Ljava/lang/String;)V|library",
            "com/sun/tools/attach/VirtualMachine#loadAgentLibrary(Ljava/lang/String;Ljava/lang/String;)V|library,options",
            "javax/script/ScriptEngine#eval(Ljava/lang/String;)Ljava/lang/Object;|script",
            "javax/script/ScriptEngine#eval(Ljava/lang/String;Ljavax/script/ScriptContext;)Ljava/lang/Object;|script,context",
            "javax/script/ScriptEngineManager#getEngineByName(Ljava/lang/String;)Ljavax/script/ScriptEngine;|name",
            "org/springframework/expression/spel/standard/SpelExpressionParser#parseExpression(Ljava/lang/String;)Lorg/springframework/expression/Expression;|expression",
            "org/springframework/expression/Expression#getValue()Ljava/lang/Object;",
            "org/springframework/expression/Expression#getValue(Ljava/lang/Object;)Ljava/lang/Object;|context",
            "ognl/Ognl#getValue(Ljava/lang/String;Ljava/lang/Object;)Ljava/lang/Object;|expression,root",
            "ognl/Ognl#getValue(Ljava/lang/String;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;|expression,root,context",
            "com/fasterxml/jackson/databind/ObjectMapper#enableDefaultTyping()Lcom/fasterxml/jackson/databind/ObjectMapper;",
            "com/fasterxml/jackson/databind/ObjectMapper#enableDefaultTyping(Lcom/fasterxml/jackson/databind/ObjectMapper$DefaultTyping;)Lcom/fasterxml/jackson/databind/ObjectMapper;|typing",
            "com/fasterxml/jackson/databind/ObjectMapper#enableDefaultTyping(Lcom/fasterxml/jackson/databind/ObjectMapper$DefaultTyping;Lcom/fasterxml/jackson/annotation/JsonTypeInfo$As;)Lcom/fasterxml/jackson/databind/ObjectMapper;|typing,as",
            "com/fasterxml/jackson/databind/ObjectMapper#activateDefaultTyping(Lcom/fasterxml/jackson/databind/jsontype/PolymorphicTypeValidator;)Lcom/fasterxml/jackson/databind/ObjectMapper;|validator",
            "com/fasterxml/jackson/databind/ObjectMapper#activateDefaultTyping(Lcom/fasterxml/jackson/databind/jsontype/PolymorphicTypeValidator;Lcom/fasterxml/jackson/annotation/JsonTypeInfo$As;)Lcom/fasterxml/jackson/databind/ObjectMapper;|validator,as",
            "com/fasterxml/jackson/databind/ObjectMapper#activateDefaultTyping(Lcom/fasterxml/jackson/databind/jsontype/PolymorphicTypeValidator;Lcom/fasterxml/jackson/annotation/JsonTypeInfo$As;Ljava/lang/Class;)Lcom/fasterxml/jackson/databind/ObjectMapper;|validator,as,subtype",
            "com/fasterxml/jackson/databind/ObjectMapper#readValue(Ljava/lang/String;Ljava/lang/Class;)Ljava/lang/Object;|json,type",
            "com/fasterxml/jackson/databind/ObjectMapper#readValue(Ljava/io/InputStream;Ljava/lang/Class;)Ljava/lang/Object;|input,type",
            "com/fasterxml/jackson/databind/ObjectMapper#readValue(Ljava/io/File;Ljava/lang/Class;)Ljava/lang/Object;|file,type",
            "org/yaml/snakeyaml/Yaml#load(Ljava/lang/String;)Ljava/lang/Object;|yaml",
            "org/yaml/snakeyaml/Yaml#load(Ljava/io/InputStream;)Ljava/lang/Object;|yaml",
            "org/yaml/snakeyaml/Yaml#loadAll(Ljava/lang/String;)Ljava/lang/Iterable;|yaml",
            "org/yaml/snakeyaml/Yaml#loadAll(Ljava/io/InputStream;)Ljava/lang/Iterable;|yaml",
            "javax/xml/parsers/DocumentBuilder#parse(Ljava/io/InputStream;)Lorg/w3c/dom/Document;|input",
            "javax/xml/parsers/DocumentBuilder#parse(Ljava/lang/String;)Lorg/w3c/dom/Document;|uri",
            "javax/xml/parsers/DocumentBuilder#parse(Ljava/io/File;)Lorg/w3c/dom/Document;|file",
            "org/xml/sax/SAXParser#parse(Ljava/io/InputStream;Lorg/xml/sax/helpers/DefaultHandler;)V|input,handler",
            "javax/xml/transform/TransformerFactory#newTransformer(Ljavax/xml/transform/Source;)Ljavax/xml/transform/Transformer;|source",
            "javax/xml/transform/Transformer#transform(Ljavax/xml/transform/Source;Ljavax/xml/transform/Result;)V|source,result",
            "java/lang/ProcessBuilder#start()Ljava/lang/Process;",
            "java/util/zip/ZipInputStream#getNextEntry()Ljava/util/zip/ZipEntry;",
            "java/util/jar/JarInputStream#getNextJarEntry()Ljava/util/jar/JarEntry;",
            "java/net/URLClassLoader#<init>([Ljava/net/URL;)V|urls",
            "java/net/URLClassLoader#addURL(Ljava/net/URL;)V|url",
            //"java/lang/invoke/MethodHandles$Lookup#defineClass([B)Ljava/lang/Class;",
        };
        MethodLoggingTransformer loggingTransformer = new MethodLoggingTransformer(targets);
        ServletRequestIdTransformer requestIdTransformer = new ServletRequestIdTransformer();
        EdgeCoverageTransformer coverageTransformer = new EdgeCoverageTransformer();
        try {
            inst.addTransformer(coverageTransformer, true);
            inst.addTransformer(requestIdTransformer, true);
            inst.addTransformer(loggingTransformer, true);
            if (inst.isRetransformClassesSupported()) {
                Set<String> targetNames = new HashSet<>(loggingTransformer.targetClasses());
                targetNames.addAll(requestIdTransformer.targetClasses());
                List<Class<?>> toRetransform = new ArrayList<>();
                for (Class<?> loaded : inst.getAllLoadedClasses()) {
                    String internalName = loaded.getName().replace('.', '/');
                    if (targetNames.contains(internalName) && inst.isModifiableClass(loaded)) {
                        toRetransform.add(loaded);
                    }
                }
                if (!toRetransform.isEmpty()) {
                    inst.retransformClasses(toRetransform.toArray(new Class<?>[0]));
                }
            } else {
                System.out.println("Instrumentation does not support retransformation.");
            }
        } catch (Exception e) {
            System.err.println("Failed to install Runtime exec logging transformer: " + e);
        }
    }

    private static synchronized void startCoverageServer() {
        if (coverageServer != null) {
            return;
        }
        try {
            Path socketPath = Path.of("/tmp/jaf-coverage.sock");
            CoverageServer server = new CoverageServer(socketPath);
            server.start();
            coverageServer = server;
            Runtime.getRuntime()
                    .addShutdownHook(new Thread(() -> {
                        if (coverageServer != null) {
                            coverageServer.stop();
                        }
                    }));
        } catch (Exception e) {
            System.err.println("Failed to start coverage server: " + e);
        }
    }

    private static void waitForFuzzerConnection() {
        CoverageServer server = coverageServer;
        if (server == null || server.hasClientConnected()) {
            return;
        }
        System.out.println("JAF agent waiting for fuzzer connection on " + server.getSocketPath());
        try {
            server.awaitFirstClient();
            System.out.println("JAF fuzzer connected.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.println("Interrupted while waiting for fuzzer connection.");
        }
    }

    private static void appendAgentJarToBootstrap(Instrumentation inst) {
        if (bootstrapHelpersInstalled || inst == null) {
            return;
        }
        synchronized (JafAgent.class) {
            if (bootstrapHelpersInstalled) {
                return;
            }
            try {
                String[] helperClasses = {
                    "com/jaf/agent/FuzzingRequestContext.class",
                    "com/jaf/agent/FuzzingRequestContext$RequestState.class",
                    "com/jaf/agent/FuzzingRequestContext$RequestFinishedListener.class",
                    "com/jaf/agent/CoverageRuntime.class",
                    "com/jaf/agent/CoverageRuntime$TraceState.class",
                    "com/jaf/agent/CoverageMaps.class"
                };
                Path tempJar =
                        Files.createTempFile("jaf-agent-bootstrap-", ".jar").toAbsolutePath();
                try (JarOutputStream jos =
                        new JarOutputStream(
                                Files.newOutputStream(
                                        tempJar,
                                        StandardOpenOption.CREATE,
                                        StandardOpenOption.TRUNCATE_EXISTING,
                                        StandardOpenOption.WRITE))) {
                    for (String helperClass : helperClasses) {
                        byte[] classBytes = readClassBytes(helperClass);
                        if (classBytes == null) {
                            System.err.println("Failed to load bootstrap helper: " + helperClass);
                            continue;
                        }
                        JarEntry entry = new JarEntry(helperClass);
                        jos.putNextEntry(entry);
                        jos.write(classBytes);
                        jos.closeEntry();
                    }
                }
                JarFile jarFile = new JarFile(tempJar.toFile());
                inst.appendToBootstrapClassLoaderSearch(jarFile);
                bootstrapHelperJar = jarFile;
                bootstrapHelperJarPath = tempJar;
                bootstrapHelpersInstalled = true;
                Runtime.getRuntime()
                        .addShutdownHook(
                                new Thread(
                                        () -> {
                                            try {
                                                if (bootstrapHelperJar != null) {
                                                    bootstrapHelperJar.close();
                                                }
                                            } catch (IOException ignored) {
                                            }
                                            try {
                                                if (bootstrapHelperJarPath != null) {
                                                    Files.deleteIfExists(bootstrapHelperJarPath);
                                                }
                                            } catch (IOException ignored) {
                                            }
                                        }));
                try {
                    Class.forName("com.jaf.agent.FuzzingRequestContext", false, null);
                } catch (ClassNotFoundException e) {
                    System.err.println(
                            "Failed to preload FuzzingRequestContext in bootstrap loader: " + e);
                }
            } catch (Exception e) {
                System.err.println("Failed to append agent classes to bootstrap search: " + e);
            }
        }
    }

    private static byte[] readClassBytes(String resourceName) throws IOException {
        ClassLoader loader = JafAgent.class.getClassLoader();
        if (loader == null) {
            return null;
        }
        try (InputStream in = loader.getResourceAsStream(resourceName)) {
            if (in == null) {
                return null;
            }
            return in.readAllBytes();
        }
    }
}
