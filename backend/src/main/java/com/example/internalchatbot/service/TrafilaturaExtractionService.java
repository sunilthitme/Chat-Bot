package com.example.internalchatbot.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class TrafilaturaExtractionService {

    private static final Logger log = LoggerFactory.getLogger(TrafilaturaExtractionService.class);

    private final boolean enabled;
    private final String command;
    private final Duration timeout;
    private final int maxChars;
    private final TextNormalizer textNormalizer;
    private final AtomicBoolean available = new AtomicBoolean(true);

    public TrafilaturaExtractionService(
            @Value("${url.trafilatura.enabled:false}") boolean enabled,
            @Value("${url.trafilatura.command:trafilatura}") String command,
            @Value("${url.trafilatura.timeout:20s}") Duration timeout,
            @Value("${url.max-extracted-chars:180000}") int maxChars,
            TextNormalizer textNormalizer
    ) {
        this.enabled = enabled;
        this.command = command;
        this.timeout = timeout == null ? Duration.ofSeconds(20) : timeout;
        this.maxChars = Math.max(10_000, maxChars);
        this.textNormalizer = textNormalizer;
    }

    public Optional<String> extract(URI uri) {
        if (!enabled || !available.get()) {
            return Optional.empty();
        }

        Process process = null;
        try {
            process = new ProcessBuilder(command, "-u", uri.toString())
                    .redirectErrorStream(true)
                    .start();
            Process runningProcess = process;
            CompletableFuture<String> outputFuture = CompletableFuture.supplyAsync(() -> readOutput(runningProcess));
            boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                log.debug("Trafilatura timed out url={}", uri);
                return Optional.empty();
            }
            if (process.exitValue() != 0) {
                log.debug("Trafilatura exited with code={} url={}", process.exitValue(), uri);
                return Optional.empty();
            }
            String output = outputFuture.get(2, TimeUnit.SECONDS);
            String text = textNormalizer.normalize(output);
            if (text.length() > maxChars) {
                text = text.substring(0, maxChars);
            }
            return text.isBlank() ? Optional.empty() : Optional.of(text);
        } catch (IOException ex) {
            available.set(false);
            log.info("Trafilatura CLI is not available. Jsoup/Readability4J extraction remains active.");
            return Optional.empty();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (ExecutionException | java.util.concurrent.TimeoutException ex) {
            log.debug("Unable to read Trafilatura output url={} reason={}", uri, ex.getMessage());
            return Optional.empty();
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    private String readOutput(Process process) {
        try {
            return new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            return "";
        }
    }
}
