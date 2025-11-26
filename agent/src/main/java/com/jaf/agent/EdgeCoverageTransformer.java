package com.jaf.agent;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.IllegalClassFormatException;
import java.security.ProtectionDomain;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

class EdgeCoverageTransformer implements ClassFileTransformer {
    private static final String COVERAGE_RUNTIME_INTERNAL = "com/jaf/agent/CoverageRuntime";

    private final Set<String> targetClasses;

    EdgeCoverageTransformer() {
        this(Collections.emptySet());
    }

    EdgeCoverageTransformer(Set<String> targetClasses) {
        this.targetClasses = targetClasses == null ? Collections.emptySet() : targetClasses;
    }

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
            ClassWriter writer =
                    new ClassWriter(reader, ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
            ClassVisitor visitor =
                    new ClassVisitor(Opcodes.ASM9, writer) {
                        @Override
                        public MethodVisitor visitMethod(
                                int access,
                                String name,
                                String descriptor,
                                String signature,
                                String[] exceptions) {
                            MethodVisitor mv =
                                    super.visitMethod(access, name, descriptor, signature, exceptions);
                            if ((access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) {
                                return mv;
                            }
                            return new EdgeCoverageMethodVisitor(
                                    mv, className, name, descriptor);
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
        if (className.startsWith("java/")
                || className.startsWith("sun/")
                || className.startsWith("jdk/")
                || className.startsWith("javax/")
                || className.startsWith("org/springframework/boot/loader")
                || className.startsWith("com/jaf/agent")) {
            return false;
        }
        if (!targetClasses.isEmpty()) {
            return targetClasses.contains(className);
        }
        return className.startsWith("com/jaf/demo/");
    }

    private static final class EdgeCoverageMethodVisitor extends MethodVisitor {
        private final String className;
        private final String methodName;
        private final String methodDesc;
        private final Set<Label> seenLabels = new HashSet<>();
        private int blockIndex = 0;

        EdgeCoverageMethodVisitor(
                MethodVisitor delegate, String className, String methodName, String methodDesc) {
            super(Opcodes.ASM9, delegate);
            this.className = className;
            this.methodName = methodName;
            this.methodDesc = methodDesc;
        }

        @Override
        public void visitCode() {
            super.visitCode();
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
            mv.visitLdcInsn(edgeId);
            mv.visitMethodInsn(
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
