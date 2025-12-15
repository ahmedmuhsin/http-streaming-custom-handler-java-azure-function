package com.microsoft.azure;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class FileStreamingServerTest {

    private static Path storage;
    private static FileStreamingServer server;
    private static HttpClient client;

    @BeforeAll
    static void setUp() throws Exception {
        storage = Files.createTempDirectory("http-streaming-test");
        server = new FileStreamingServer(storage, 0);
        server.start();
        client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    @AfterAll
    static void tearDown() throws Exception {
        server.stop(Duration.ZERO);
        try (var walk = Files.walk(storage)) {
            walk.sorted(Comparator.reverseOrder())
                    .forEach(path -> {
                        if (!path.equals(storage)) {
                            try {
                                Files.deleteIfExists(path);
                            } catch (Exception ignored) {
                            }
                        }
                    });
        }
        Files.deleteIfExists(storage);
    }

    @Test
    void uploadAndDownloadLargeFile() throws Exception {
        byte[] payload = new byte[1_048_576]; // 1 MiB
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte) (i % 251);
        }

        Path tempFile = Files.createTempFile("payload", ".bin");
        Files.write(tempFile, payload);

        String filename = "large.bin";
        URI uploadUri = URI.create("http://localhost:%d/upload?filename=%s".formatted(
                server.getPort(), URLEncoder.encode(filename, StandardCharsets.UTF_8)));

        HttpRequest uploadRequest = HttpRequest.newBuilder(uploadUri)
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/octet-stream")
                .POST(HttpRequest.BodyPublishers.ofFile(tempFile))
                .build();

        HttpResponse<String> uploadResponse = client.send(uploadRequest, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, uploadResponse.statusCode(), uploadResponse.body());

        URI downloadUri = URI.create("http://localhost:%d/download?filename=%s".formatted(
                server.getPort(), URLEncoder.encode(filename, StandardCharsets.UTF_8)));

        Path downloaded = Files.createTempFile("downloaded", ".bin");
        HttpRequest downloadRequest = HttpRequest.newBuilder(downloadUri)
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();

        HttpResponse<Path> downloadResponse = client.send(downloadRequest, HttpResponse.BodyHandlers.ofFile(downloaded));
        assertEquals(200, downloadResponse.statusCode());

        assertArrayEquals(payload, Files.readAllBytes(downloaded));

        Files.deleteIfExists(tempFile);
        Files.deleteIfExists(downloaded);
    }

    @Test
    void generateStreamingData() throws Exception {
        int sizeMB = 1;
        long expectedBytes = sizeMB * 1024L * 1024L;

        URI generateUri = URI.create("http://localhost:%d/generate?sizeMB=%d".formatted(
                server.getPort(), sizeMB));

        HttpRequest request = HttpRequest.newBuilder(generateUri)
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();

        Path downloaded = Files.createTempFile("generated", ".bin");
        HttpResponse<Path> response = client.send(request, HttpResponse.BodyHandlers.ofFile(downloaded));

        assertEquals(200, response.statusCode());
        assertEquals(expectedBytes, Files.size(downloaded));
        assertEquals("application/octet-stream", response.headers().firstValue("Content-Type").orElse(""));

        Files.deleteIfExists(downloaded);
    }

    @Test
    void downloadNonExistentFileReturns404() throws Exception {
        URI downloadUri = URI.create("http://localhost:%d/download?filename=%s".formatted(
                server.getPort(), URLEncoder.encode("nonexistent.bin", StandardCharsets.UTF_8)));

        HttpRequest request = HttpRequest.newBuilder(downloadUri)
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(404, response.statusCode());
    }

    @Test
    void uploadWithoutFilenameReturns400() throws Exception {
        URI uploadUri = URI.create("http://localhost:%d/upload".formatted(server.getPort()));

        HttpRequest request = HttpRequest.newBuilder(uploadUri)
                .timeout(Duration.ofSeconds(5))
                .POST(HttpRequest.BodyPublishers.ofString("test"))
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(400, response.statusCode());
    }

    @Test
    void wrongMethodReturns405() throws Exception {
        URI uploadUri = URI.create("http://localhost:%d/upload?filename=test.bin".formatted(server.getPort()));

        HttpRequest request = HttpRequest.newBuilder(uploadUri)
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(405, response.statusCode());
    }
}
