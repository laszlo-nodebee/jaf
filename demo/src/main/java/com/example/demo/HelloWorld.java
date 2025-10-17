package com.example.demo;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

public class HelloWorld {
    public static void main(String[] args) {
        System.out.println("Hello World from the demo app!");
        runIdCommand();
    }

    private static void runIdCommand() {
        Process process = null;
        try {
            process = Runtime.getRuntime().exec("id");
            streamOutput(process);
            int exitCode = process.waitFor();
            System.out.println("`id` exit code: " + exitCode);
        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.println("Failed to run `id`: " + e.getMessage());
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
    }

    private static void streamOutput(Process process) throws IOException {
        try (BufferedReader stdout =
                        new BufferedReader(new InputStreamReader(process.getInputStream()));
                BufferedReader stderr =
                        new BufferedReader(new InputStreamReader(process.getErrorStream()))) {
            String line;
            while ((line = stdout.readLine()) != null) {
                System.out.println(line);
            }
            while ((line = stderr.readLine()) != null) {
                System.err.println(line);
            }
        }
    }
}
