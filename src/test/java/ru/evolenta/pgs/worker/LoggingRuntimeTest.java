package ru.evolenta.pgs.worker;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import org.apache.logging.log4j.LogManager;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Verifies the actual standalone classpath and console configuration in a fresh JVM. */
public final class LoggingRuntimeTest {
    @TempDir Path directory;

    @Test void poiUsesSdkLoggingBridgeAndConsoleOnly() throws Exception {
        String executable = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win") ? "java.exe" : "java";
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", executable).toString(),
            "-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8", "-cp", classpath, Probe.class.getName())
            .directory(directory.toFile()).redirectErrorStream(true).start();
        if (!process.waitFor(20, TimeUnit.SECONDS)) { process.destroyForcibly(); fail("Logging probe timed out"); }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, process.exitValue(), output);
        assertTrue(output.contains("provider=org.apache.logging.slf4j.SLF4JLoggerContextFactory"), output);
        assertTrue(output.contains("INFO") && output.contains("rbu-logging-smoke"), output);
        assertTrue(output.contains("workbook=1"), output);
        assertFalse(output.contains("ERROR") || output.contains("logging provider"), output);
        // Corporate Spring logging may write platform files; only standalone promises console-only output.
        if (Boolean.getBoolean("rbu.standaloneLogging")) {
            assertFalse(output.contains("WARN"), output);
            try (var paths = Files.walk(directory)) { assertEquals(0, paths.filter(Files::isRegularFile).count(), "Console logging must not create files"); }
        }
    }

    public static final class Probe {
        public static void main(String[] args) throws Exception {
            try (var workbook = new XSSFWorkbook()) {
                workbook.createSheet("check").createRow(0).createCell(0).setCellValue("logging-check");
                LogManager.getLogger("rbu.logging").info("rbu-logging-smoke");
                System.out.println("provider=" + LogManager.getFactory().getClass().getName());
                System.out.println("workbook=" + workbook.getNumberOfSheets());
            }
        }
    }
}
