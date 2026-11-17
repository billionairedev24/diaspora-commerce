import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Image build helper (java/Dockerfile): drops what a linux/amd64 or linux/arm64 JVM never loads from the third-party jars in a directory:
 * native libraries of other operating systems and CPU architectures (zstd-jni, snappy-java, lz4-java, grpc-netty-shaded, netty) and
 * GraalVM native-image metadata. Classes and every other resource stay as they are, so behaviour on the target platforms is identical.
 * Entries keep their name, order and timestamp, so the output is deterministic for a given input.
 * Usage: java java/tools/StripNatives.java DIR   (rewrites every *.jar in DIR that has such entries, in place; prints a summary)
 */
public final class StripNatives {

    private static final String LIBS = "(so|jnilib|dylib|dll)";
    /** Applied to every jar. */
    private static final List<Pattern> COMMON = List.of(
            // GraalVM native-image reachability metadata: unused on the JVM.
            Pattern.compile("META-INF/native-image/.*"),
            // netty / tcnative / quiche libraries bundled in META-INF/native: drop macOS, Windows and BSD (kqueue) libraries.
            Pattern.compile("META-INF/native/.*(osx|macos|windows|freebsd|kqueue).*"),
            Pattern.compile("META-INF/native/.*\\.(jnilib|dylib|dll)"));
    /** Applied to the jar whose file name starts with the key. Each pattern matches the native libraries to DROP. */
    private static final List<Rule> PER_JAR = List.of(
            // zstd-jni: <os>/<arch>/libzstd-jni-*.so
            new Rule("zstd-jni", Pattern.compile("(?!linux/(amd64|aarch64)/)(aix|darwin|freebsd|linux|win)/.*\\." + LIBS)),
            // snappy-java: org/xerial/snappy/native/<OS>/<arch>/libsnappyjava.*
            new Rule("snappy-java", Pattern.compile("org/xerial/snappy/native/(?!Linux/(x86_64|aarch64)/).*\\." + LIBS)),
            // lz4-java: net/jpountz/util/<os>/<arch>/liblz4-java.*
            new Rule("lz4-java", Pattern.compile("net/jpountz/util/(?!linux/(amd64|aarch64)/).*\\." + LIBS)));

    private record Rule(String jarPrefix, Pattern drop) {
    }

    private StripNatives() {
    }

    private static boolean drop(String jarName, String entry) {
        for (Pattern p : COMMON) {
            if (p.matcher(entry).matches()) {
                return true;
            }
        }
        for (Rule r : PER_JAR) {
            if (jarName.startsWith(r.jarPrefix()) && r.drop().matcher(entry).matches()) {
                return true;
            }
        }
        return false;
    }

    public static void main(String[] args) throws IOException {
        List<Path> jars = new ArrayList<>();
        try (Stream<Path> s = Files.list(Path.of(args[0]))) {
            s.filter(p -> p.getFileName().toString().endsWith(".jar")).sorted().forEach(jars::add);
        }
        long before = 0;
        long after = 0;
        int removed = 0;
        for (Path jar : jars) {
            before += Files.size(jar);
            String jarName = jar.getFileName().toString();
            int n = 0;
            try (ZipInputStream in = new ZipInputStream(Files.newInputStream(jar))) {
                for (ZipEntry e = in.getNextEntry(); e != null; e = in.getNextEntry()) {
                    n += drop(jarName, e.getName()) ? 1 : 0;
                }
            }
            if (n > 0) {
                Path tmp = jar.resolveSibling(jarName + ".tmp");
                try (ZipInputStream in = new ZipInputStream(Files.newInputStream(jar));
                     ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(tmp))) {
                    for (ZipEntry e = in.getNextEntry(); e != null; e = in.getNextEntry()) {
                        if (drop(jarName, e.getName())) {
                            continue;
                        }
                        ZipEntry copy = new ZipEntry(e.getName());
                        copy.setTime(e.getTime());
                        out.putNextEntry(copy);
                        in.transferTo(out);
                        out.closeEntry();
                    }
                }
                Files.move(tmp, jar, StandardCopyOption.REPLACE_EXISTING);
                removed += n;
                System.out.println("  " + jarName + ": " + n + " entries dropped");
            }
            after += Files.size(jar);
        }
        System.out.printf("StripNatives: %d entries dropped, %.1f MB -> %.1f MB%n", removed, before / 1e6, after / 1e6);
    }
}
