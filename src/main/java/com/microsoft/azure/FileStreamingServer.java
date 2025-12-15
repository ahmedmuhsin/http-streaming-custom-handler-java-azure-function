package com.microsoft.azure;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Logger;

public final class FileStreamingServer {

    private static final Logger LOG = Logger.getLogger(FileStreamingServer.class.getName());

    private final Path storageDirectory;
    private final HttpServer server;
    private final ExecutorService executor;

    public FileStreamingServer(Path storageDirectory, int port) {
        try {
            this.storageDirectory = storageDirectory.toAbsolutePath().normalize();
            Files.createDirectories(this.storageDirectory);

            this.server = HttpServer.create(new InetSocketAddress(port), 0);
            this.executor = Executors.newVirtualThreadPerTaskExecutor();
            this.server.setExecutor(executor);

            this.server.createContext("/upload", this::handleUpload);
            this.server.createContext("/download", this::handleDownload);
            this.server.createContext("/generate", this::handleGenerate);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to initialize HTTP server", e);
        }
    }

    public void start() {
        server.start();
        LOG.info(() -> "HTTP streaming server started on http://localhost:" + getPort());
        LOG.info(() -> "Storage directory: " + storageDirectory);
    }

    public void stop(Duration delay) {
        int seconds = (int) Math.max(0, delay.toSeconds());
        server.stop(seconds);
        executor.shutdownNow();
    }

    public int getPort() {
        return server.getAddress().getPort();
    }

    public static void main(String[] args) {
        // Azure Functions custom handler port takes priority, then fallback to HTTP_STREAMING_PORT, then 8080
        int port = Optional.ofNullable(System.getenv("FUNCTIONS_CUSTOMHANDLER_PORT"))
                .or(() -> Optional.ofNullable(System.getenv("HTTP_STREAMING_PORT")))
                .map(Integer::parseInt)
                .orElse(8080);

        // Use HTTP_STREAMING_STORAGE env var, or create a temp directory
        Path storage = Optional.ofNullable(System.getenv("HTTP_STREAMING_STORAGE"))
                .map(Path::of)
                .orElseGet(() -> {
                    try {
                        return Files.createTempDirectory("http-streaming-storage");
                    } catch (IOException e) {
                        throw new UncheckedIOException("Failed to create temp storage directory", e);
                    }
                });

        FileStreamingServer streamingServer = new FileStreamingServer(storage, port);
        streamingServer.start();
    }

    private void handleUpload(HttpExchange exchange) throws IOException {
        String clientInfo = exchange.getRemoteAddress().toString();
        LOG.info(() -> "[UPLOAD] Request received from " + clientInfo);

        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            LOG.warning(() -> "[UPLOAD] Rejected: wrong method " + exchange.getRequestMethod());
            sendTextResponse(exchange, 405, "Only POST allowed");
            return;
        }

        Optional<String> filenameOpt = queryParam(exchange.getRequestURI(), "filename");
        if (filenameOpt.isEmpty() || filenameOpt.get().isBlank()) {
            LOG.warning("[UPLOAD] Rejected: missing filename parameter");
            sendTextResponse(exchange, 400, "Missing filename query parameter");
            return;
        }

        String filename = Path.of(filenameOpt.get()).getFileName().toString();
        Path targetFile = storageDirectory.resolve(filename).normalize();
        if (!targetFile.startsWith(storageDirectory)) {
            LOG.warning(() -> "[UPLOAD] Rejected: invalid filename '" + filename + "'");
            sendTextResponse(exchange, 400, "Invalid filename");
            return;
        }

        LOG.info(() -> "[UPLOAD] Starting upload of '" + filename + "' to " + targetFile);
        Instant startTime = Instant.now();

        long bytesWritten;
        try (InputStream requestBody = exchange.getRequestBody()) {
            bytesWritten = Files.copy(requestBody, targetFile, StandardCopyOption.REPLACE_EXISTING);
        }

        Duration elapsed = Duration.between(startTime, Instant.now());
        double mbPerSec = bytesWritten / 1024.0 / 1024.0 / Math.max(elapsed.toMillis() / 1000.0, 0.001);
        LOG.info(() -> "[UPLOAD] Completed '" + filename + "': " + formatBytes(bytesWritten) +
                " in " + elapsed.toMillis() + "ms (" + String.format("%.2f", mbPerSec) + " MB/s)");

        sendTextResponse(exchange, 200, "Uploaded %s (%d bytes)".formatted(filename, bytesWritten));
    }

    private void handleDownload(HttpExchange exchange) throws IOException {
        String clientInfo = exchange.getRemoteAddress().toString();
        LOG.info(() -> "[DOWNLOAD] Request received from " + clientInfo);

        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            LOG.warning(() -> "[DOWNLOAD] Rejected: wrong method " + exchange.getRequestMethod());
            sendTextResponse(exchange, 405, "Only GET allowed");
            return;
        }

        Optional<String> filenameOpt = queryParam(exchange.getRequestURI(), "filename");
        if (filenameOpt.isEmpty() || filenameOpt.get().isBlank()) {
            LOG.warning("[DOWNLOAD] Rejected: missing filename parameter");
            sendTextResponse(exchange, 400, "Missing filename query parameter");
            return;
        }

        Path requestedFile = storageDirectory.resolve(filenameOpt.get()).normalize();
        if (!requestedFile.startsWith(storageDirectory) || !Files.exists(requestedFile) || !Files.isRegularFile(requestedFile)) {
            LOG.warning(() -> "[DOWNLOAD] Rejected: file not found '" + filenameOpt.get() + "'");
            sendTextResponse(exchange, 404, "File not found");
            return;
        }

        long fileSize = Files.size(requestedFile);
        LOG.info(() -> "[DOWNLOAD] Starting download of '" + requestedFile.getFileName() + "' (" + formatBytes(fileSize) + ")");
        Instant startTime = Instant.now();

        Headers headers = exchange.getResponseHeaders();
        headers.add("Content-Type", probeContentType(requestedFile));
        headers.add("Content-Length", Long.toString(fileSize));
        headers.add("Content-Disposition", "attachment; filename=\"%s\"".formatted(requestedFile.getFileName()));

        exchange.sendResponseHeaders(200, fileSize);
        try (OutputStream responseBody = exchange.getResponseBody();
             InputStream fileStream = Files.newInputStream(requestedFile)) {
            fileStream.transferTo(responseBody);
        }

        Duration elapsed = Duration.between(startTime, Instant.now());
        double mbPerSec = fileSize / 1024.0 / 1024.0 / Math.max(elapsed.toMillis() / 1000.0, 0.001);
        LOG.info(() -> "[DOWNLOAD] Completed '" + requestedFile.getFileName() + "': " + formatBytes(fileSize) +
                " in " + elapsed.toMillis() + "ms (" + String.format("%.2f", mbPerSec) + " MB/s)");
    }

    private void handleGenerate(HttpExchange exchange) throws IOException {
        String clientInfo = exchange.getRemoteAddress().toString();
        LOG.info(() -> "[GENERATE] Request received from " + clientInfo);

        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            LOG.warning(() -> "[GENERATE] Rejected: wrong method " + exchange.getRequestMethod());
            sendTextResponse(exchange, 405, "Only GET allowed");
            return;
        }

        Optional<String> sizeOpt = queryParam(exchange.getRequestURI(), "sizeMB");
        if (sizeOpt.isEmpty()) {
            LOG.warning("[GENERATE] Rejected: missing sizeMB parameter");
            sendTextResponse(exchange, 400, "Missing sizeMB query parameter");
            return;
        }

        int sizeMB;
        try {
            sizeMB = Integer.parseInt(sizeOpt.get());
            if (sizeMB <= 0 || sizeMB > 10_000) {
                LOG.warning(() -> "[GENERATE] Rejected: sizeMB out of range: " + sizeOpt.get());
                sendTextResponse(exchange, 400, "sizeMB must be between 1 and 10000");
                return;
            }
        } catch (NumberFormatException e) {
            LOG.warning(() -> "[GENERATE] Rejected: invalid sizeMB: " + sizeOpt.get());
            sendTextResponse(exchange, 400, "sizeMB must be a valid integer");
            return;
        }

        long totalBytes = (long) sizeMB * 1024 * 1024;
        LOG.info(() -> "[GENERATE] Starting generation of " + sizeMB + " MB (" + formatBytes(totalBytes) + ")");
        Instant startTime = Instant.now();

        Headers headers = exchange.getResponseHeaders();
        headers.add("Content-Type", "application/octet-stream");
        headers.add("Content-Length", Long.toString(totalBytes));
        headers.add("Content-Disposition", "attachment; filename=\"generated-%dMB.bin\"".formatted(sizeMB));

        exchange.sendResponseHeaders(200, totalBytes);

        // Stream random data in chunks - never holds more than buffer size in memory
        byte[] buffer = new byte[64 * 1024]; // 64 KB chunks
        Random random = new Random();
        long bytesRemaining = totalBytes;
        long lastLogBytes = totalBytes;
        final int logIntervalMB = 100; // Log progress every 100 MB

        try (OutputStream responseBody = exchange.getResponseBody()) {
            while (bytesRemaining > 0) {
                int chunkSize = (int) Math.min(buffer.length, bytesRemaining);
                random.nextBytes(buffer);
                responseBody.write(buffer, 0, chunkSize);
                bytesRemaining -= chunkSize;

                // Log progress periodically
                long bytesSent = totalBytes - bytesRemaining;
                if ((lastLogBytes - bytesRemaining) >= (long) logIntervalMB * 1024 * 1024) {
                    int percentComplete = (int) ((bytesSent * 100) / totalBytes);
                    LOG.info(() -> "[GENERATE] Progress: " + formatBytes(bytesSent) + " / " + formatBytes(totalBytes) +
                            " (" + percentComplete + "%)");
                    lastLogBytes = bytesRemaining;
                }
            }
        }

        Duration elapsed = Duration.between(startTime, Instant.now());
        double mbPerSec = totalBytes / 1024.0 / 1024.0 / Math.max(elapsed.toMillis() / 1000.0, 0.001);
        LOG.info(() -> "[GENERATE] Completed: " + formatBytes(totalBytes) +
                " in " + elapsed.toMillis() + "ms (" + String.format("%.2f", mbPerSec) + " MB/s)");
    }

    private static Optional<String> queryParam(URI uri, String key) {
        String query = uri.getRawQuery();
        if (query == null || query.isEmpty()) {
            return Optional.empty();
        }

        for (String pair : query.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            String[] parts = pair.split("=", 2);
            if (parts.length == 2 && parts[0].equals(key)) {
                return Optional.of(decode(parts[1]));
            }
        }
        return Optional.empty();
    }

    private static String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private static void sendTextResponse(HttpExchange exchange, int status, String message) throws IOException {
        byte[] payload = message.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "text/plain; charset=utf-8");
        exchange.sendResponseHeaders(status, payload.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(payload);
        }
    }

    private static String probeContentType(Path file) {
        try {
            return Optional.ofNullable(Files.probeContentType(file)).orElse("application/octet-stream");
        } catch (IOException e) {
            return "application/octet-stream";
        }
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        } else if (bytes < 1024 * 1024) {
            return String.format("%.2f KB", bytes / 1024.0);
        } else if (bytes < 1024 * 1024 * 1024) {
            return String.format("%.2f MB", bytes / (1024.0 * 1024));
        } else {
            return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
        }
    }
}
