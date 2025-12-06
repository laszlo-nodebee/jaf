package com.jaf.agent;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.IllegalClassFormatException;
import java.security.ProtectionDomain;
import java.util.Collections;
import java.util.Set;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.AdviceAdapter;

class HintsTransformer implements ClassFileTransformer {
    private static final String TARGET_CLASS = "java/lang/String";
    private static final String HINTS_INTERNAL = "com/jaf/agent/Hints";
    private static final String ON_EQUALS_NAME = "onEquals";
    private static final String ON_EQUALS_DESC = "(Ljava/lang/String;Ljava/lang/Object;)V";
    private static final String EQUALS_NAME = "equals";
    private static final String EQUALS_DESC = "(Ljava/lang/Object;)Z";

    Set<String> targetClasses() {
        return Collections.singleton(TARGET_CLASS);
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
            ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_FRAMES);
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
                                    || (access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0
                                    || !EQUALS_NAME.equals(name)
                                    || !EQUALS_DESC.equals(descriptor)) {
                                return baseVisitor;
                            }
                            return new HintsAdviceAdapter(baseVisitor, access, name, descriptor);
                        }
                    };
            reader.accept(visitor, ClassReader.EXPAND_FRAMES);
            return writer.toByteArray();
        } catch (Exception e) {
            throw new IllegalClassFormatException(
                    "Failed to add hints instrumentation to java/lang/String: " + e.getMessage());
        }
    }

    private boolean shouldInstrument(String className) {
        return TARGET_CLASS.equals(className);
    }

    private static final class HintsAdviceAdapter extends AdviceAdapter {
        HintsAdviceAdapter(MethodVisitor methodVisitor, int access, String name, String descriptor) {
            super(Opcodes.ASM9, methodVisitor, access, name, descriptor);
        }

        @Override
        protected void onMethodEnter() {
            loadThis();
            loadArg(0);
            visitMethodInsn(
                    Opcodes.INVOKESTATIC,
                    HINTS_INTERNAL,
                    ON_EQUALS_NAME,
                    ON_EQUALS_DESC,
                    false);
        }
    }
}
