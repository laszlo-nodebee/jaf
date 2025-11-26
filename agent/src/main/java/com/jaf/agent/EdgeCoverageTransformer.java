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
    private final Set<String> allowedClasses;

    EdgeCoverageTransformer() {
        this(null);
    }

    EdgeCoverageTransformer(Set<String> allowedClasses) {
        this.allowedClasses = allowedClasses == null ? null : new HashSet<>(allowedClasses);
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
        if ("java/lang/ThreadLocal".equals(className)
                || className.startsWith("com/jaf/agent")
                || className.startsWith("org/objectweb/asm")
                || className.startsWith("java.lang.ThreadLocal")) {
            return false;
        }
        if (allowedClasses != null && !allowedClasses.contains(className)) {
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
        private Label pendingLabel;
        private int pendingEdgeId;
        private boolean pendingInjected;
        private boolean injecting;

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
                pendingLabel = label;
                pendingEdgeId = nextEdgeId();
                pendingInjected = false;
            }
        }

        private void injectEdgeInstrumentation() {
            emitEdge(nextEdgeId());
        }

        private void emitEdge(int edgeId) {
            if (injecting) {
                return;
            }
            injecting = true;
            visitLdcInsn(edgeId);
            visitMethodInsn(
                    Opcodes.INVOKESTATIC,
                    COVERAGE_RUNTIME_INTERNAL,
                    "enterEdge",
                    "(I)V",
                    false);
            injecting = false;
        }

        private int nextEdgeId() {
            return computeEdgeId(className, methodName, methodDesc, blockIndex++);
        }

        private void injectPendingIfAny() {
            if (injecting) {
                return;
            }
            if (pendingLabel != null && !pendingInjected) {
                emitEdge(pendingEdgeId);
                pendingInjected = true;
                pendingLabel = null;
            }
        }

        @Override
        public void visitFrame(
                int type, int numLocal, Object[] local, int numStack, Object[] stack) {
            super.visitFrame(type, numLocal, local, numStack, stack);
            injectPendingIfAny();
        }

        @Override
        public void visitInsn(int opcode) {
            injectPendingIfAny();
            super.visitInsn(opcode);
        }

        @Override
        public void visitVarInsn(int opcode, int var) {
            injectPendingIfAny();
            super.visitVarInsn(opcode, var);
        }

        @Override
        public void visitTypeInsn(int opcode, String type) {
            injectPendingIfAny();
            super.visitTypeInsn(opcode, type);
        }

        @Override
        public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
            injectPendingIfAny();
            super.visitFieldInsn(opcode, owner, name, descriptor);
        }

        @Override
        public void visitMethodInsn(
                int opcode, String owner, String name, String descriptor, boolean isInterface) {
            injectPendingIfAny();
            super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
        }

        @Override
        public void visitInvokeDynamicInsn(
                String name, String descriptor, org.objectweb.asm.Handle bootstrapMethodHandle,
                Object... bootstrapMethodArguments) {
            injectPendingIfAny();
            super.visitInvokeDynamicInsn(name, descriptor, bootstrapMethodHandle,
                    bootstrapMethodArguments);
        }

        @Override
        public void visitIntInsn(int opcode, int operand) {
            injectPendingIfAny();
            super.visitIntInsn(opcode, operand);
        }

        @Override
        public void visitIincInsn(int var, int increment) {
            injectPendingIfAny();
            super.visitIincInsn(var, increment);
        }

        @Override
        public void visitMultiANewArrayInsn(String descriptor, int numDimensions) {
            injectPendingIfAny();
            super.visitMultiANewArrayInsn(descriptor, numDimensions);
        }

        @Override
        public void visitLdcInsn(Object value) {
            injectPendingIfAny();
            super.visitLdcInsn(value);
        }

        @Override
        public void visitJumpInsn(int opcode, Label label) {
            injectPendingIfAny();
            super.visitJumpInsn(opcode, label);
        }

        @Override
        public void visitTableSwitchInsn(
                int min, int max, Label defaultLabel, Label... labels) {
            injectPendingIfAny();
            super.visitTableSwitchInsn(min, max, defaultLabel, labels);
        }

        @Override
        public void visitLookupSwitchInsn(
                Label defaultLabel, int[] keys, Label[] labels) {
            injectPendingIfAny();
            super.visitLookupSwitchInsn(defaultLabel, keys, labels);
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
