import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * OpenAssistant Research Report Bridge
 * 
 * Restoration of the missing USB report receiver.
 * This tool uses ADB to pull research reports from the device during debug runs.
 */
public class ResearchReportBridge {

    private static final String TAG = "[ReportBridge] ";
    private static final String DEVICE_REPORT_PATH = "/sdcard/Download/OpenAssistant/reports/";

    public static void main(String[] args) {
        List<String> argList = Arrays.asList(args);

        if (argList.contains("--self-test")) {
            System.out.println(TAG + "Self-test passed.");
            System.exit(0);
        }

        String adbPath = null;
        String projectPath = null;

        for (int i = 0; i < args.length; i++) {
            if ("--adb".equals(args[i]) && i + 1 < args.length) {
                adbPath = args[i + 1];
            } else if ("--project".equals(args[i]) && i + 1 < args.length) {
                projectPath = args[i + 1];
            }
        }

        if (adbPath == null || projectPath == null) {
            System.err.println(TAG + "Error: Missing --adb or --project arguments.");
            System.exit(1);
        }

        System.out.println(TAG + "Started with project: " + projectPath);
        System.out.println(TAG + "Using ADB: " + adbPath);

        startReceiver(adbPath, projectPath);
    }

    private static void startReceiver(String adbPath, String projectPath) {
        File reportDir = new File(projectPath, "build/reports/openassistant/device");
        if (!reportDir.exists()) {
            reportDir.mkdirs();
        }

        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
        executor.scheduleAtFixedRate(() -> {
            try {
                syncReports(adbPath, reportDir);
            } catch (Exception e) {
                System.err.println(TAG + "Sync error: " + e.getMessage());
            }
        }, 0, 5, TimeUnit.SECONDS);

        System.out.println(TAG + "Receiver is active. Syncing every 5 seconds to: " + reportDir.getAbsolutePath());
        
        // Keep alive
        try {
            Thread.currentThread().join();
        } catch (InterruptedException e) {
            System.out.println(TAG + "Shutting down.");
            executor.shutdown();
        }
    }

    private static void syncReports(String adbPath, File targetDir) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(adbPath, "shell", "ls", DEVICE_REPORT_PATH);
        Process p = pb.start();
        
        BufferedReader reader = new ProcessBuilder(adbPath, "shell", "ls", DEVICE_REPORT_PATH)
            .start()
            .inputReader();
        
        String line;
        while ((line = reader.readLine()) != null) {
            line = line.trim();
            if (line.isEmpty() || line.contains("No such file")) continue;
            
            File targetFile = new File(targetDir, line);
            if (!targetFile.exists()) {
                System.out.println(TAG + "Pulling new report: " + line);
                new ProcessBuilder(adbPath, "pull", DEVICE_REPORT_PATH + line, targetFile.getAbsolutePath())
                    .start()
                    .waitFor();
            }
        }
    }
}
