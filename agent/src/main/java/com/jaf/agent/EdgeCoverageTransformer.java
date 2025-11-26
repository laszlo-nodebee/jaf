package com.jaf.agent;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.IllegalClassFormatException;
import java.security.ProtectionDomain;
import java.util.HashSet;
import java.util.Set;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.AdviceAdapter;

class EdgeCoverageTransformer implements ClassFileTransformer {
    private static final String COVERAGE_RUNTIME_INTERNAL = "com/jaf/agent/CoverageRuntime";

    @Override
    public byte[] transform(
            Module module,
            ClassLoader loader,
            String className,
            Class<?> classBeingRedefined,
            ProtectionDomain protectionDomain,
            byte[] classfileBuffer)
            throws IllegalClassFormatException {
        if (!shouldInstrument(className)) {
            return null;
        }
	System.out.println("transform(), instrumenting: " + className);

        try {
            ClassReader reader = new ClassReader(classfileBuffer);
            ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
            ClassVisitor visitor =
                    new ClassVisitor(Opcodes.ASM9, writer) {
                        @Override
                        public MethodVisitor visitMethod(
                                int access,
                                String name,
                                String descriptor,
                                String signature,
                                String[] exceptions) {
                            MethodVisitor baseVisitor =
                                    super.visitMethod(access, name, descriptor, signature, exceptions);
                            if (baseVisitor == null
                                    || (access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) {
                                return baseVisitor;
                            }
                            return new EdgeCoverageAdviceAdapter(
                                    baseVisitor, access, name, descriptor, className);
                        }
                    };

            reader.accept(visitor, ClassReader.EXPAND_FRAMES);
            return writer.toByteArray();
        } catch (Exception e) {
            throw new IllegalClassFormatException(
                    "Failed to add coverage instrumentation to " + className + ": " + e.getMessage());
        }
    }

    private boolean shouldInstrument(String className) {
        if (className == null) {
            return false;
        }
        if (className == "java/lang/ThreadLocal"
                || className.startsWith("com/jaf/agent")
                || className.startsWith("org/objectweb/asm")
                || className.startsWith("java.lang.ThreadLocal")) {
            return false;
        }
        return true;
    }

    private static final class EdgeCoverageAdviceAdapter extends AdviceAdapter {
        private final String className;
        private final String methodName;
        private final String methodDesc;
        private final Set<Label> seenLabels = new HashSet<>();
        private int blockIndex = 0;

        EdgeCoverageAdviceAdapter(
                MethodVisitor methodVisitor,
                int access,
                String name,
                String descriptor,
                String owner) {
            super(Opcodes.ASM9, methodVisitor, access, name, descriptor);
            this.className = owner;
            this.methodName = name;
            this.methodDesc = descriptor;
        }

        @Override
        protected void onMethodEnter() {
            injectEdgeInstrumentation();
        }

        @Override
        public void visitLabel(Label label) {
            super.visitLabel(label);
            if (seenLabels.add(label)) {
                injectEdgeInstrumentation();
            }
        }

        private void injectEdgeInstrumentation() {
            int edgeId = computeEdgeId(className, methodName, methodDesc, blockIndex++);
            visitLdcInsn(edgeId);
            visitMethodInsn(
                    Opcodes.INVOKESTATIC,
                    COVERAGE_RUNTIME_INTERNAL,
                    "enterEdge",
                    "(I)V",
                    false);
        }
    }

    private static int computeEdgeId(
            String internalClassName, String methodName, String methodDesc, int blockIndex) {
        int hash = 0x811C9DC5;
        hash = mix(hash, internalClassName);
        hash = mix(hash, methodName);
        hash = mix(hash, methodDesc);
        hash = mix(hash, blockIndex);
        return hash & (CoverageRuntime.MAP_SIZE - 1);
    }

    private static int mix(int seed, String value) {
        int h = seed;
        for (int i = 0; i < value.length(); i++) {
            h ^= value.charAt(i);
            h *= 0x01000193;
        }
        return h;
    }

    private static int mix(int seed, int value) {
        int h = seed;
        h ^= value;
        h *= 0x01000193;
        return h;
    }
}
