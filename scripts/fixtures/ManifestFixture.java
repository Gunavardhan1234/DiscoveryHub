import com.smarsh.discoveryhub.export.application.ManifestBuilder;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class ManifestFixture {
    private ManifestFixture() {
    }

    public static void main(String[] args) throws IOException {
        Path output = Path.of(args[0]);
        Path root = Files.createTempDirectory("discoveryhub-export-fixture");
        Files.createDirectories(root.resolve("docs"));
        Files.writeString(root.resolve("docs/a.txt"), "alpha\n", StandardCharsets.UTF_8);
        Files.writeString(root.resolve("docs/b.txt"), "beta\n", StandardCharsets.UTF_8);

        var manifest = new ManifestBuilder().build("fixture-job", "fixture-case", List.of(
                new ManifestBuilder.Entry("docs/b.txt", "beta\n".getBytes(StandardCharsets.UTF_8)),
                new ManifestBuilder.Entry("docs/a.txt", "alpha\n".getBytes(StandardCharsets.UTF_8))));
        String json = """
                {"package":{"id":"%s","caseId":"%s","generatedAt":"%s","itemCount":%d,"packageChecksum":"%s"},"items":[{"path":"docs/a.txt","size":%d,"sha256":"%s"},{"path":"docs/b.txt","size":%d,"sha256":"%s"}]}
                """.formatted(
                manifest.packageInfo().id(), manifest.packageInfo().caseId(), manifest.packageInfo().generatedAt(),
                manifest.packageInfo().itemCount(), manifest.packageInfo().packageChecksum(),
                manifest.items().get(0).size(), manifest.items().get(0).sha256(),
                manifest.items().get(1).size(), manifest.items().get(1).sha256());
        Files.writeString(root.resolve("manifest.json"), json, StandardCharsets.UTF_8);

        Files.createDirectories(output.getParent());
        try (OutputStream stream = Files.newOutputStream(output);
             ZipOutputStream zip = new ZipOutputStream(stream)) {
            add(zip, root, root.resolve("manifest.json"));
            add(zip, root, root.resolve("docs/a.txt"));
            add(zip, root, root.resolve("docs/b.txt"));
        }
    }

    private static void add(ZipOutputStream zip, Path root, Path file) throws IOException {
        zip.putNextEntry(new ZipEntry(root.relativize(file).toString()));
        Files.copy(file, zip);
        zip.closeEntry();
    }
}
