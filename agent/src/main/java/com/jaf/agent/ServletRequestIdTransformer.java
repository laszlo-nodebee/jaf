package com.jaf.agent;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.IllegalClassFormatException;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

class ServletRequestIdTransformer implements ClassFileTransformer {
    private final Map<String, List<MethodTarget>> targetsByClass;
    private final Set<String> targetClassNames;

    ServletRequestIdTransformer() {
        Map<String, List<MethodTarget>> targets = new HashMap<>();
        addTarget(
                targets,
                "javax/servlet/FilterChain",
                "doFilter",
                "(Ljavax/servlet/ServletRequest;Ljavax/servlet/ServletResponse;)V");
        addTarget(
                targets,
                "jakarta/servlet/FilterChain",
                "doFilter",
                "(Ljakarta/servlet/ServletRequest;Ljakarta/servlet/ServletResponse;)V");
        addTarget(
                targets,
                "javax/servlet/http/HttpServlet",
                "service",
                "(Ljavax/servlet/http/HttpServletRequest;Ljavax/servlet/http/HttpServletResponse;)V");
        addTarget(
                targets,
                "jakarta/servlet/http/HttpServlet",
                "service",
                "(Ljakarta/servlet/http/HttpServletRequest;Ljakarta/servlet/http/HttpServletResponse;)V");
        this.targetsByClass = Collections.unmodifiableMap(targets);
        this.targetClassNames = targetsByClass.keySet();
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
        List<MethodTarget> methodTargets = targetsByClass.get(className);
        if (methodTargets == null || methodTargets.isEmpty()) {
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
                        MethodTarget target = findTarget(methodTargets, name, descriptor);
                        if (target == null || (access & Opcodes.ACC_ABSTRACT) != 0) {
                            return mv;
                        }

                        return new MethodVisitor(Opcodes.ASM9, mv) {
                            @Override
                            public void visitCode() {
                                super.visitCode();
                                int requestIndex = ((access & Opcodes.ACC_STATIC) != 0) ? 0 : 1;
                                mv.visitVarInsn(Opcodes.ALOAD, requestIndex);
                                mv.visitMethodInsn(
                                        Opcodes.INVOKESTATIC,
                                        "com/jaf/agent/FuzzingRequestContext",
                                        "updateFromServletRequest",
                                        "(Ljava/lang/Object;)V",
                                        false);
                            }
                        };
                    }
                };

        reader.accept(visitor, ClassReader.EXPAND_FRAMES);
        return writer.toByteArray();
    }

    private static void addTarget(
            Map<String, List<MethodTarget>> targets,
            String className,
            String methodName,
            String descriptor) {
        targets
                .computeIfAbsent(className, ignored -> new ArrayList<>())
                .add(new MethodTarget(methodName, descriptor));
    }

    private static MethodTarget findTarget(
            List<MethodTarget> targets, String methodName, String descriptor) {
        for (MethodTarget target : targets) {
            if (target.matches(methodName, descriptor)) {
                return target;
            }
        }
        return null;
    }

    private static final class MethodTarget {
        private final String methodName;
        private final String descriptor;

        private MethodTarget(String methodName, String descriptor) {
            this.methodName = methodName;
            this.descriptor = descriptor;
        }

        private boolean matches(String otherName, String otherDescriptor) {
            return methodName.equals(otherName) && descriptor.equals(otherDescriptor);
        }
    }
}
