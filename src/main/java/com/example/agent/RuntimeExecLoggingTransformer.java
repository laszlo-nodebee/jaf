package com.example.agent;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.IllegalClassFormatException;
import java.security.ProtectionDomain;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

class RuntimeExecLoggingTransformer implements ClassFileTransformer {
    @Override
    public byte[] transform(
            Module module,
            ClassLoader loader,
            String className,
            Class<?> classBeingRedefined,
            ProtectionDomain protectionDomain,
            byte[] classfileBuffer)
            throws IllegalClassFormatException {
        if (!"java/lang/Runtime".equals(className)) {
            return null;
        }

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
                        MethodVisitor mv =
                                super.visitMethod(access, name, descriptor, signature, exceptions);
                        if (!"exec".equals(name)
                                || !"([Ljava/lang/String;[Ljava/lang/String;Ljava/io/File;)Ljava/lang/Process;"
                                        .equals(descriptor)) {
                            return mv;
                        }
                        return new MethodVisitor(Opcodes.ASM9, mv) {
                            @Override
                            public void visitCode() {
                                super.visitCode();
                                injectLogging(this);
                            }
                        };
                    }
                };

        reader.accept(visitor, ClassReader.EXPAND_FRAMES);
        return writer.toByteArray();
    }

    private static void injectLogging(MethodVisitor mv) {
        mv.visitFieldInsn(
                Opcodes.GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        mv.visitTypeInsn(Opcodes.NEW, "java/lang/StringBuilder");
        mv.visitInsn(Opcodes.DUP);
        mv.visitLdcInsn("Runtime.exec called with args:");
        mv.visitMethodInsn(
                Opcodes.INVOKESPECIAL,
                "java/lang/StringBuilder",
                "<init>",
                "(Ljava/lang/String;)V",
                false);
        mv.visitLdcInsn(" command=");
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                "java/lang/StringBuilder",
                "append",
                "(Ljava/lang/String;)Ljava/lang/StringBuilder;",
                false);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(
                Opcodes.INVOKESTATIC,
                "java/util/Arrays",
                "toString",
                "([Ljava/lang/Object;)Ljava/lang/String;",
                false);
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                "java/lang/StringBuilder",
                "append",
                "(Ljava/lang/String;)Ljava/lang/StringBuilder;",
                false);
        mv.visitLdcInsn(" env=");
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                "java/lang/StringBuilder",
                "append",
                "(Ljava/lang/String;)Ljava/lang/StringBuilder;",
                false);
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitMethodInsn(
                Opcodes.INVOKESTATIC,
                "java/util/Arrays",
                "toString",
                "([Ljava/lang/Object;)Ljava/lang/String;",
                false);
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                "java/lang/StringBuilder",
                "append",
                "(Ljava/lang/String;)Ljava/lang/StringBuilder;",
                false);
        mv.visitLdcInsn(" dir=");
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                "java/lang/StringBuilder",
                "append",
                "(Ljava/lang/String;)Ljava/lang/StringBuilder;",
                false);
        mv.visitVarInsn(Opcodes.ALOAD, 3);
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                "java/lang/StringBuilder",
                "append",
                "(Ljava/lang/Object;)Ljava/lang/StringBuilder;",
                false);
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                "java/lang/StringBuilder",
                "toString",
                "()Ljava/lang/String;",
                false);
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false);
    }
}
