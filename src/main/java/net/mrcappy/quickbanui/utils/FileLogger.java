package net.mrcappy.quickbanui.utils;

import net.mrcappy.quickbanui.QuickBanUI;
import net.mrcappy.quickbanui.PunishmentManager;

import java.io.*;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.*;

public class FileLogger {

    private final QuickBanUI plugin;
    private final File logsFolder;
    private final boolean enabled;
    private final boolean dailyLogs;
    private final SimpleDateFormat dateFormat;
    private final SimpleDateFormat fileFormat;
    private final ExecutorService executor;
    private PrintWriter currentWriter;
    private String currentFileName;

    // Hardcoded file logging settings
    private static final boolean FILE_LOGGING_ENABLED = true;
    private static final boolean DAILY_LOGS = true;
    private static final int MAX_LOG_DAYS = 30;

    public FileLogger(QuickBanUI plugin) {
        this.plugin = plugin;
        this.logsFolder = new File(plugin.getDataFolder(), "logs");
        this.enabled = FILE_LOGGING_ENABLED;
        this.dailyLogs = DAILY_LOGS;
        this.dateFormat = new SimpleDateFormat(plugin.getConfig().getString("settings.date-format", "yyyy-MM-dd HH:mm:ss"));
        this.fileFormat = new SimpleDateFormat("yyyy-MM-dd");
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r);
            t.setDaemon(true);
            t.setName("QuickBanUI-FileLogger");
            return t;
        });

        if (enabled) {
            if (!logsFolder.exists()) {
                logsFolder.mkdirs();
            }
            initWriter();

            // Clean old logs
            if (MAX_LOG_DAYS > 0) {
                cleanOldLogs(MAX_LOG_DAYS);
            }
        }
    }

    private void initWriter() {
        try {
            String fileName = dailyLogs ? "quickban_" + fileFormat.format(new Date()) + ".log" : "quickban.log";

            if (!fileName.equals(currentFileName)) {
                if (currentWriter != null) {
                    currentWriter.close();
                }

                File logFile = new File(logsFolder, fileName);
                currentWriter = new PrintWriter(new BufferedWriter(new FileWriter(logFile, true)));
                currentFileName = fileName;
            }
        } catch (IOException e) {
            plugin.getLogger().severe("Failed to initialize file logger: " + e.getMessage());
        }
    }

    public void logPunishment(PunishmentManager.Punishment punishment, String staffName, String targetName) {
        if (!enabled) return;

        executor.submit(() -> {
            try {
                if (dailyLogs) {
                    initWriter(); // Check if we need a new file
                }

                String timestamp = dateFormat.format(new Date());
                String duration = punishment.isPermanent() ? "PERMANENT" :
                        plugin.formatTime(punishment.expiry - punishment.timestamp);

                String logEntry = String.format("[%s] [PUNISHMENT] Type: %s | Player: %s | Staff: %s | Reason: %s | Duration: %s",
                        timestamp,
                        punishment.type.name(),
                        targetName,
                        staffName,
                        punishment.reason,
                        duration
                );

                if (currentWriter != null) {
                    currentWriter.println(logEntry);
                    currentWriter.flush();
                }

            } catch (Exception e) {
                plugin.getLogger().warning("Failed to log punishment: " + e.getMessage());
            }
        });
    }

    public void logUnpunishment(String type, String staffName, String targetName) {
        if (!enabled) return;

        executor.submit(() -> {
            try {
                if (dailyLogs) {
                    initWriter();
                }

                String timestamp = dateFormat.format(new Date());
                String logEntry = String.format("[%s] [%s] Player: %s | Staff: %s",
                        timestamp,
                        type.toUpperCase(),
                        targetName,
                        staffName
                );

                if (currentWriter != null) {
                    currentWriter.println(logEntry);
                    currentWriter.flush();
                }

            } catch (Exception e) {
                plugin.getLogger().warning("Failed to log unpunishment: " + e.getMessage());
            }
        });
    }

    public void logAction(String action, String details) {
        if (!enabled) return;

        executor.submit(() -> {
            try {
                if (dailyLogs) {
                    initWriter();
                }

                String timestamp = dateFormat.format(new Date());
                String logEntry = String.format("[%s] [%s] %s",
                        timestamp,
                        action.toUpperCase(),
                        details
                );

                if (currentWriter != null) {
                    currentWriter.println(logEntry);
                    currentWriter.flush();
                }

            } catch (Exception e) {
                plugin.getLogger().warning("Failed to log action: " + e.getMessage());
            }
        });
    }

    private void cleanOldLogs(int maxDays) {
        executor.submit(() -> {
            try {
                long cutoff = System.currentTimeMillis() - (maxDays * 24L * 60L * 60L * 1000L);
                File[] files = logsFolder.listFiles((dir, name) -> name.endsWith(".log"));

                if (files != null) {
                    int deleted = 0;
                    for (File file : files) {
                        if (file.lastModified() < cutoff) {
                            if (file.delete()) {
                                deleted++;
                            }
                        }
                    }

                    if (deleted > 0) {
                        plugin.getLogger().info("Cleaned " + deleted + " old log files");
                    }
                }
            } catch (Exception e) {
                plugin.getLogger().warning("Failed to clean old logs: " + e.getMessage());
            }
        });
    }

    public void shutdown() {
        executor.shutdown();
        if (currentWriter != null) {
            currentWriter.close();
        }
    }

    public File getLogsFolder() {
        return logsFolder;
    }

    public boolean isEnabled() {
        return enabled;
    }
}