package com.example.agent;

import java.lang.instrument.Instrumentation;
import org.objectweb.asm.Opcodes;

public class HelloAgent {
    public static void premain(String agentArgs, Instrumentation inst) {
        logHello(agentArgs);
        installTransformer(inst);
    }

    public static void premain(String agentArgs) {
        logHello(agentArgs);
    }

    private static void logHello(String agentArgs) {
        System.out.println(
                "Hello from Java Agent! args=" + agentArgs + ", ASM API=" + Opcodes.ASM9);
    }

    private static void installTransformer(Instrumentation inst) {
        RuntimeExecLoggingTransformer transformer = new RuntimeExecLoggingTransformer();
        try {
            inst.addTransformer(transformer, true);
            if (inst.isRetransformClassesSupported()) {
                inst.retransformClasses(Runtime.class);
            } else {
                System.out.println("Instrumentation does not support retransformation.");
            }
        } catch (Exception e) {
            System.err.println("Failed to install Runtime exec logging transformer: " + e);
        }
    }
}
