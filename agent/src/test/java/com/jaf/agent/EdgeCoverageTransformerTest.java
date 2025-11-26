package com.jaf.agent;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.Opcodes;

class EdgeCoverageTransformerTest {

    @BeforeEach
    void resetCoverage() {
        CoverageRuntime.reset();
    }

    @Test
    void instrumentationUpdatesCoverageMap() throws Exception {
        byte[] originalBytes = SampleClassFactory.createSampleClass();

        EdgeCoverageTransformer transformer =
                new EdgeCoverageTransformer(Set.of("sample/Sample"));
        byte[] instrumented =
                transformer.transform(
                        null, null, "sample/Sample", null, null, originalBytes);

        Class<?> sampleClass = new SampleClassLoader().define("sample.Sample", instrumented);
        Object instance = sampleClass.getDeclaredConstructor().newInstance();
        Method method = sampleClass.getDeclaredMethod("branch", int.class);

        CoverageRuntime.startTracing();
        method.invoke(instance, 5);
        method.invoke(instance, -3);
        byte[] trace = CoverageRuntime.stopTracing();
        CoverageMaps.mergeIntoGlobal(trace);

        assertTrue(
                CoverageRuntime.nonZeroCount() >= 2,
                "Expected at least two coverage edges to be recorded");
    }

    private static final class SampleClassLoader extends ClassLoader {
        Class<?> define(String name, byte[] bytecode) {
            return defineClass(name, bytecode, 0, bytecode.length);
        }
    }

    private static final class SampleClassFactory {
        private SampleClassFactory() {}

        static byte[] createSampleClass() {
            ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
            cw.visit(
                    Opcodes.V17,
                    Opcodes.ACC_PUBLIC,
                    "sample/Sample",
                    null,
                    "java/lang/Object",
                    null);

            generateConstructor(cw);
            generateBranchMethod(cw);

            cw.visitEnd();
            return cw.toByteArray();
        }

        private static void generateConstructor(ClassWriter cw) {
            org.objectweb.asm.MethodVisitor mv =
                    cw.visitMethod(
                            Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
            mv.visitCode();
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitMethodInsn(
                    Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
            mv.visitInsn(Opcodes.RETURN);
            mv.visitMaxs(1, 1);
            mv.visitEnd();
        }

        private static void generateBranchMethod(ClassWriter cw) {
            org.objectweb.asm.MethodVisitor mv =
                    cw.visitMethod(
                            Opcodes.ACC_PUBLIC, "branch", "(I)I", null, null);
            mv.visitCode();

            Label positive = new Label();
            Label negative = new Label();
            Label exit = new Label();

            mv.visitVarInsn(Opcodes.ILOAD, 1);
            mv.visitJumpInsn(Opcodes.IFLT, negative);

            // Positive path.
            mv.visitLabel(positive);
            mv.visitVarInsn(Opcodes.ILOAD, 1);
            mv.visitJumpInsn(Opcodes.GOTO, exit);

            // Negative path.
            mv.visitLabel(negative);
            mv.visitVarInsn(Opcodes.ILOAD, 1);
            mv.visitInsn(Opcodes.INEG);

            mv.visitLabel(exit);
            mv.visitInsn(Opcodes.IRETURN);
            mv.visitMaxs(2, 2);
            mv.visitEnd();
        }
    }
}
