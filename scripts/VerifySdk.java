import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Pattern;

/** Compare an unpacked BOOT-INF/lib directory with the committed SDK lock. */
public class VerifySdk {
    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Usage: java scripts/VerifySdk.java docs/sdk-library-lock.json SDK_LIB_DIRECTORY");
        var match = Pattern.compile("\"([^\"]+\\.jar)\"\\s*:\\s*\"([a-f0-9]{64})\"").matcher(Files.readString(Path.of(args[0])));
        Map<String,String> expected = new LinkedHashMap<>();
        while (match.find()) {
            if (expected.put(match.group(1), match.group(2)) != null) throw new IllegalArgumentException("Duplicate SDK entry");
        }
        if (expected.isEmpty()) throw new IllegalArgumentException("SDK lock contains no libraries");
        Path folder = Path.of(args[1]).toRealPath();
        var actual = new TreeSet<String>();
        try (var paths = Files.list(folder)) {
            paths.filter(p -> p.getFileName().toString().endsWith(".jar")).forEach(p -> actual.add(p.getFileName().toString()));
        }
        int failures = 0;
        var digest = MessageDigest.getInstance("SHA-256");
        for (var entry : expected.entrySet()) {
            if (!actual.remove(entry.getKey())) {
                System.err.println("MISSING " + entry.getKey()); failures++; continue;
            }
            var hash = HexFormat.of().formatHex(digest.digest(Files.readAllBytes(folder.resolve(entry.getKey()))));
            if (!hash.equals(entry.getValue())) {System.err.println("CHANGED " + entry.getKey()); failures++;}
        }
        for (var extra : actual) {System.err.println("EXTRA " + extra); failures++;}
        if (failures != 0) throw new IllegalStateException("SDK mismatch: " + failures + " library entries");
        System.out.println("PASS SDK: " + expected.size() + " libraries match SHA-256");
    }
}
