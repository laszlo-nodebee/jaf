package com.jaf.agent;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.IllegalClassFormatException;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

class MethodLoggingTransformer implements ClassFileTransformer {
    private final Map<String, List<MethodConfig>> configsByClass;
    private final Set<String> targetClassNames;

    MethodLoggingTransformer(String[] configStrings) {
        Map<String, List<MethodConfig>> configs = new HashMap<>();
        if (configStrings != null) {
            for (String entry : configStrings) {
                MethodConfig config = MethodConfig.parse(entry);
                configs.computeIfAbsent(config.className, ignored -> new ArrayList<>()).add(config);
            }
        }
        configsByClass = configs;
        targetClassNames = Collections.unmodifiableSet(new HashSet<>(configs.keySet()));
    }

    Set<String> targetClasses() {
        return targetClassNames;
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
        List<MethodConfig> classConfigs = configsByClass.get(className);
        if (classConfigs == null || classConfigs.isEmpty()) {
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
                        MethodConfig target = findConfig(classConfigs, name, descriptor);
                        if (target == null) {
                            return mv;
                        }
			System.out.println(String.format("MethodLoggingTransformer, visiting method name: %s, descriptor: %s", name, descriptor));

                        return new MethodVisitor(Opcodes.ASM9, mv) {
                            @Override
                            public void visitCode() {
                                super.visitCode();
                                injectLogging(this, access, target);
                            }
                        };
                    }
                };

        reader.accept(visitor, ClassReader.EXPAND_FRAMES);
        return writer.toByteArray();
    }

    private static MethodConfig findConfig(
            List<MethodConfig> configs, String name, String descriptor) {
        for (MethodConfig config : configs) {
            if (config.methodName.equals(name) && config.descriptor.equals(descriptor)) {
                return config;
            }
        }
        return null;
    }

    private static void injectLogging(MethodVisitor mv, int access, MethodConfig config) {
        mv.visitFieldInsn(
                Opcodes.GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        mv.visitTypeInsn(Opcodes.NEW, "java/lang/StringBuilder");
        mv.visitInsn(Opcodes.DUP);
        mv.visitLdcInsn("Instrumented call " + config.displayName + " requestId=");
        mv.visitMethodInsn(
                Opcodes.INVOKESPECIAL,
                "java/lang/StringBuilder",
                "<init>",
                "(Ljava/lang/String;)V",
                false);
        mv.visitMethodInsn(
                Opcodes.INVOKESTATIC,
                "com/jaf/agent/FuzzingRequestContext",
                "currentRequestId",
                "()Ljava/lang/String;",
                false);
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                "java/lang/StringBuilder",
                "append",
                "(Ljava/lang/String;)Ljava/lang/StringBuilder;",
                false);
        mv.visitLdcInsn(" args:");
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                "java/lang/StringBuilder",
                "append",
                "(Ljava/lang/String;)Ljava/lang/StringBuilder;",
                false);

        Type[] argTypes = Type.getArgumentTypes(config.descriptor);
        int localIndex = (access & Opcodes.ACC_STATIC) != 0 ? 0 : 1;

        for (int i = 0; i < argTypes.length; i++) {
            Type argType = argTypes[i];
            mv.visitLdcInsn(" " + config.labelFor(i) + "=");
            mv.visitMethodInsn(
                    Opcodes.INVOKEVIRTUAL,
                    "java/lang/StringBuilder",
                    "append",
                    "(Ljava/lang/String;)Ljava/lang/StringBuilder;",
                    false);
            loadArgument(mv, argType, localIndex);
            appendArgument(mv, argType);
            localIndex += argType.getSize();
        }

        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                "java/lang/StringBuilder",
                "toString",
                "()Ljava/lang/String;",
                false);
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false);
    }

    private static void loadArgument(MethodVisitor mv, Type type, int index) {
        mv.visitVarInsn(type.getOpcode(Opcodes.ILOAD), index);
    }

    private static void appendArgument(MethodVisitor mv, Type type) {
        switch (type.getSort()) {
            case Type.BOOLEAN:
                mv.visitMethodInsn(
                        Opcodes.INVOKEVIRTUAL,
                        "java/lang/StringBuilder",
                        "append",
                        "(Z)Ljava/lang/StringBuilder;",
                        false);
                break;
            case Type.CHAR:
                mv.visitMethodInsn(
                        Opcodes.INVOKEVIRTUAL,
                        "java/lang/StringBuilder",
                        "append",
                        "(C)Ljava/lang/StringBuilder;",
                        false);
                break;
            case Type.BYTE:
            case Type.SHORT:
            case Type.INT:
                mv.visitMethodInsn(
                        Opcodes.INVOKEVIRTUAL,
                        "java/lang/StringBuilder",
                        "append",
                        "(I)Ljava/lang/StringBuilder;",
                        false);
                break;
            case Type.FLOAT:
                mv.visitMethodInsn(
                        Opcodes.INVOKEVIRTUAL,
                        "java/lang/StringBuilder",
                        "append",
                        "(F)Ljava/lang/StringBuilder;",
                        false);
                break;
            case Type.LONG:
                mv.visitMethodInsn(
                        Opcodes.INVOKEVIRTUAL,
                        "java/lang/StringBuilder",
                        "append",
                        "(J)Ljava/lang/StringBuilder;",
                        false);
                break;
            case Type.DOUBLE:
                mv.visitMethodInsn(
                        Opcodes.INVOKEVIRTUAL,
                        "java/lang/StringBuilder",
                        "append",
                        "(D)Ljava/lang/StringBuilder;",
                        false);
                break;
            case Type.ARRAY:
                appendArrayArgument(mv, type);
                break;
            case Type.OBJECT:
                mv.visitMethodInsn(
                        Opcodes.INVOKEVIRTUAL,
                        "java/lang/StringBuilder",
                        "append",
                        "(Ljava/lang/Object;)Ljava/lang/StringBuilder;",
                        false);
                break;
            default:
                mv.visitMethodInsn(
                        Opcodes.INVOKEVIRTUAL,
                        "java/lang/StringBuilder",
                        "append",
                        "(Ljava/lang/Object;)Ljava/lang/StringBuilder;",
                        false);
                break;
        }
    }

    private static void appendArrayArgument(MethodVisitor mv, Type type) {
        Type elementType = type.getElementType();
        String owner = "java/util/Arrays";
        String methodName = "toString";
        String descriptor;
        switch (elementType.getSort()) {
            case Type.BOOLEAN:
                descriptor = "([Z)Ljava/lang/String;";
                break;
            case Type.BYTE:
                descriptor = "([B)Ljava/lang/String;";
                break;
            case Type.CHAR:
                descriptor = "([C)Ljava/lang/String;";
                break;
            case Type.SHORT:
                descriptor = "([S)Ljava/lang/String;";
                break;
            case Type.INT:
                descriptor = "([I)Ljava/lang/String;";
                break;
            case Type.LONG:
                descriptor = "([J)Ljava/lang/String;";
                break;
            case Type.FLOAT:
                descriptor = "([F)Ljava/lang/String;";
                break;
            case Type.DOUBLE:
                descriptor = "([D)Ljava/lang/String;";
                break;
            default:
                descriptor = "([Ljava/lang/Object;)Ljava/lang/String;";
                break;
        }
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, owner, methodName, descriptor, false);
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                "java/lang/StringBuilder",
                "append",
                "(Ljava/lang/String;)Ljava/lang/StringBuilder;",
                false);
    }

    private static final class MethodConfig {
        final String className;
        final String methodName;
        final String descriptor;
        final String[] labels;
        final String displayName;

        private MethodConfig(String className, String methodName, String descriptor, String[] labels) {
            this.className = className;
            this.methodName = methodName;
            this.descriptor = descriptor;
            this.labels = labels;
            this.displayName = className.replace('/', '.') + "#" + methodName;
        }

        static MethodConfig parse(String raw) {
            if (raw == null || raw.isEmpty()) {
                throw new IllegalArgumentException("Empty method configuration");
            }
            String trimmed = raw.trim();
            String[] parts = trimmed.split("\\|", 2);
            String signature = parts[0];
            String labelPart = parts.length > 1 ? parts[1] : "";

            int hashIndex = signature.indexOf('#');
            int parenIndex = signature.indexOf('(');
            if (hashIndex <= 0 || parenIndex <= hashIndex) {
                throw new IllegalArgumentException(
                        "Invalid method signature format: " + signature);
            }

            String className = signature.substring(0, hashIndex);
            String methodName = signature.substring(hashIndex + 1, parenIndex);
            String descriptor = signature.substring(parenIndex);

            String[] labels =
                    labelPart.isEmpty()
                            ? new String[0]
                            : labelPart.split("\\s*,\\s*");

            return new MethodConfig(className, methodName, descriptor, labels);
        }

        String labelFor(int index) {
            if (index < labels.length) {
                String label = labels[index];
                if (!label.isEmpty()) {
                    return label;
                }
            }
            return "arg" + index;
        }
    }
}
