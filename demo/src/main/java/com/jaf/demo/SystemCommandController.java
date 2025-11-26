package com.jaf.demo;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.springframework.hateoas.EntityModel;
import org.springframework.hateoas.server.mvc.WebMvcLinkBuilder;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/system")
class SystemCommandController {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @PostMapping(value = "/id", consumes = MediaType.APPLICATION_JSON_VALUE)
    EntityModel<SystemCommandResponse> runIdCommand(@RequestBody(required = false) String body) {
        System.out.println("[Demo] Raw request body: " + (body == null ? "<null>" : body));

        DemoApplication.SystemCommandRequest request = null;
        if (body != null && !body.isBlank()) {
            try {
                request = OBJECT_MAPPER.readValue(body, DemoApplication.SystemCommandRequest.class);
                System.out.println("[Demo] Parsed command: " + request.command());
            } catch (JsonProcessingException e) {
                System.out.println("[Demo] Failed to parse body: " + e.getOriginalMessage());
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "Invalid request body", e);
            }
        }

        String command = DemoApplication.DEFAULT_COMMAND;
        if (request != null && request.command() != null && !request.command().isBlank()) {
            command = request.command();
        }
        if (!DemoApplication.DEFAULT_COMMAND.equals(command)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Unsupported command: " + command);
        }

        Process process = null;
        try {
            process = new ProcessBuilder(command).start();
            List<String> stdout = readAll(process.getInputStream());
            List<String> stderr = readAll(process.getErrorStream());
            int exitCode = process.waitFor();

            SystemCommandResponse response =
                    new SystemCommandResponse(command, stdout, stderr, exitCode);

            return EntityModel.of(
                    response,
                    WebMvcLinkBuilder.linkTo(
                                    WebMvcLinkBuilder.methodOn(SystemCommandController.class)
                                            .runIdCommand("{\"command\":\"id\"}"))
                            .withSelfRel());
        } catch (IOException e) {
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR, "Failed to execute id", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR, "Interrupted while waiting for id", e);
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
    }

    private static List<String> readAll(InputStream stream) throws IOException {
        try (BufferedReader reader =
                new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            List<String> lines = new ArrayList<>();
            String line;
            while ((line = reader.readLine()) != null) {
                lines.add(line);
            }
            return lines;
        }
    }

    record SystemCommandResponse(
            String command, List<String> stdout, List<String> stderr, int exitCode) {}
}
