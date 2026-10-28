import java.io.File;
import java.nio.file.*;
import java.util.*;

/** Compiles only the CMD client and its local tests; the worker is built by Maven. */
public class BuildDevCli {
    public static void main(String[] args) throws Exception {
        if (Runtime.version().feature() != 25) throw new IllegalArgumentException("Use JDK 25");
        Path repo = Path.of("").toAbsolutePath(), main = repo.resolve("local-runner/target/classes"), cli = repo.resolve("runtime/dev-cli/classes");
        if (!Files.exists(main.resolve("ru/evolenta/pgs/worker/Json.class"))) throw new IllegalStateException("Run scripts/build.cmd first");
        Files.createDirectories(cli); List<String> javac = new ArrayList<>(List.of("--release", "25", "-encoding", "UTF-8", "-cp", main.toString(), "-d", cli.toString()));
        try (var files = Files.walk(repo.resolve("tools/dev-cli/src"))) { javac.addAll(files.filter(p -> p.toString().endsWith(".java")).sorted().map(Path::toString).toList()); }
        int result = javax.tools.ToolProvider.getSystemJavaCompiler().run(null, System.out, System.err, javac.toArray(String[]::new));
        if (result != 0) throw new IllegalStateException("CLI compilation failed");
        String cp = main + File.pathSeparator + cli;
        result = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java.exe").toString(), "-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8", "-cp", cp, "ru.evolenta.pgs.dev.DevCliTest", repo.toString()).inheritIO().start().waitFor();
        if (result != 0) throw new IllegalStateException("CLI local tests failed");
        System.out.println("CMD client compiled and local tests passed. No DEV requests sent.");
    }
}
