package org.gradle.wrapper;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Properties;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Tiny project-owned Gradle bootstrapper used only to make this source archive self-starting.
 * It is intentionally not a copy of Gradle's official wrapper implementation.
 */
public final class GradleWrapperMain {
    private GradleWrapperMain() {}

    public static void main(String[] args) throws Exception {
        Path projectDir = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
        Path propsFile = projectDir.resolve("gradle/wrapper/gradle-wrapper.properties");
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(propsFile)) {
            props.load(in);
        }

        String distributionUrl = require(props, "distributionUrl");
        String expectedSha = require(props, "distributionSha256Sum").toLowerCase();
        int timeoutMs = Integer.parseInt(props.getProperty("networkTimeout", "10000"));

        String fileName = Path.of(URI.create(distributionUrl).getPath()).getFileName().toString();
        String distributionName = fileName.endsWith(".zip")
                ? fileName.substring(0, fileName.length() - 4)
                : fileName;
        String gradleHomeName = distributionName.endsWith("-bin")
                ? distributionName.substring(0, distributionName.length() - 4)
                : distributionName;

        Path gradleUserHome = Paths.get(System.getenv().getOrDefault(
                "GRADLE_USER_HOME",
                Paths.get(System.getProperty("user.home"), ".gradle").toString()
        )).toAbsolutePath().normalize();
        String shortSha = expectedSha.substring(0, Math.min(16, expectedSha.length()));
        Path installRoot = gradleUserHome.resolve("wrapper/dists")
                .resolve(distributionName).resolve(shortSha);
        Path zipFile = installRoot.resolve(fileName);
        Path gradleHome = installRoot.resolve(gradleHomeName);

        if (!Files.isRegularFile(gradleHome.resolve("bin/gradle")) &&
                !Files.isRegularFile(gradleHome.resolve("bin/gradle.bat"))) {
            Files.createDirectories(installRoot);
            if (!Files.isRegularFile(zipFile) || !sha256(zipFile).equalsIgnoreCase(expectedSha)) {
                Files.deleteIfExists(zipFile);
                System.out.println("Downloading " + distributionUrl);
                HttpClient client = HttpClient.newBuilder()
                        .followRedirects(HttpClient.Redirect.NORMAL)
                        .connectTimeout(Duration.ofMillis(timeoutMs))
                        .build();
                HttpRequest request = HttpRequest.newBuilder(URI.create(distributionUrl))
                        .timeout(Duration.ofMillis(Math.max(timeoutMs, 120_000)))
                        .GET().build();
                HttpResponse<Path> response = client.send(
                        request, HttpResponse.BodyHandlers.ofFile(zipFile));
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    Files.deleteIfExists(zipFile);
                    throw new IOException("Gradle download failed: HTTP " + response.statusCode());
                }
                String actualSha = sha256(zipFile);
                if (!actualSha.equalsIgnoreCase(expectedSha)) {
                    Files.deleteIfExists(zipFile);
                    throw new SecurityException(
                            "Gradle SHA-256 mismatch. Expected " + expectedSha + ", got " + actualSha);
                }
            }

            deleteRecursively(gradleHome);
            unzip(zipFile, installRoot);
            if (!Files.isDirectory(gradleHome)) {
                gradleHome = locateGradleHome(installRoot, gradleHomeName);
            }
        }

        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        Path executable = gradleHome.resolve(windows ? "bin/gradle.bat" : "bin/gradle");
        if (!Files.isRegularFile(executable)) {
            throw new IOException("Gradle executable not found: " + executable);
        }

        String[] command = new String[args.length + 1];
        command[0] = executable.toString();
        System.arraycopy(args, 0, command, 1, args.length);
        Process process = new ProcessBuilder(command)
                .directory(projectDir.toFile())
                .inheritIO()
                .start();
        int exit = process.waitFor();
        System.exit(exit);
    }

    private static String require(Properties props, String key) {
        String value = props.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing wrapper property: " + key);
        }
        return value.trim();
    }

    private static Path locateGradleHome(Path installRoot, String preferredName) throws IOException {
        Path preferred = installRoot.resolve(preferredName);
        if (Files.isDirectory(preferred)) return preferred;
        try (var stream = Files.list(installRoot)) {
            return stream.filter(Files::isDirectory)
                    .filter(p -> p.getFileName().toString().startsWith("gradle-"))
                    .filter(p -> Files.exists(p.resolve("bin/gradle")) || Files.exists(p.resolve("bin/gradle.bat")))
                    .findFirst()
                    .orElseThrow(() -> new IOException("Unable to locate unpacked Gradle distribution"));
        }
    }

    private static void unzip(Path zipFile, Path destination) throws IOException {
        Path normalizedDestination = destination.toAbsolutePath().normalize();
        try (ZipInputStream zin = new ZipInputStream(Files.newInputStream(zipFile))) {
            ZipEntry entry;
            while ((entry = zin.getNextEntry()) != null) {
                Path target = normalizedDestination.resolve(entry.getName()).normalize();
                if (!target.startsWith(normalizedDestination)) {
                    throw new IOException("Unsafe ZIP entry: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(zin, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
                zin.closeEntry();
            }
        }
    }

    private static String sha256(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (var walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        } catch (RuntimeException e) {
            if (e.getCause() instanceof IOException io) throw io;
            throw e;
        }
    }
}
