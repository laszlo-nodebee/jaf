package com.jaf.agent;

import java.io.PrintWriter;
import java.lang.instrument.ClassFileTransformer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.ProtectionDomain;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.util.TraceClassVisitor;

final class DebugDumpingTransformer implements ClassFileTransformer {
    private final Path outputDir;
    private final String targetClass; // internal name, null means all

    DebugDumpingTransformer(Path outputDir, String targetClass) {
        this.outputDir = outputDir;
        this.targetClass = targetClass;
    }

    @Override
    public byte[] transform(
            Module module,
            ClassLoader loader,
            String className,
            Class<?> classBeingRedefined,
            ProtectionDomain protectionDomain,
            byte[] classfileBuffer) {
        if (!shouldDump(className) || classfileBuffer == null) {
            return null;
        }
	System.out.println("dumping " + className);
        try {
            ClassReader reader = new ClassReader(classfileBuffer);
            ClassWriter writer = new ClassWriter(reader, 0);
            Path asmOut = outputDir.resolve(className + ".asm");
            Files.createDirectories(asmOut.getParent());
            try (PrintWriter pw = new PrintWriter(Files.newBufferedWriter(asmOut))) {
                TraceClassVisitor tcv = new TraceClassVisitor(writer, pw);
                reader.accept(tcv, 0);
            }
            byte[] rewritten = writer.toByteArray();
            Path classOut = outputDir.resolve(className + ".class");
            Files.createDirectories(classOut.getParent());
            Files.write(classOut, rewritten);
            return rewritten;
        } catch (Exception e) {
            System.err.println("Failed to dump ASM for " + className + ": " + e);
            return null;
        }
    }

    private boolean shouldDump(String className) {
        if (className == null) {
            return false;
        }
        if (targetClass == null) {
            return true;
        }
        return className.equals(targetClass);
    }
}
