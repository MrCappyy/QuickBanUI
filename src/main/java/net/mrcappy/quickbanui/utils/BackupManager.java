package net.mrcappy.quickbanui.utils;

import net.mrcappy.quickbanui.QuickBanUI;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.*;
import java.nio.file.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class BackupManager {

    private final QuickBanUI plugin;
    private final File backupFolder;
    private final boolean enabled;
    private final int maxBackups;
    private final boolean backupOnStartup;
    private final SimpleDateFormat dateFormat;

    // Hardcoded backup settings
    private static final boolean BACKUP_ENABLED = true;
    private static final int MAX_BACKUPS = 10;
    private static final boolean BACKUP_ON_STARTUP = true;
    private static final long BACKUP_INTERVAL_HOURS = 24;

    public BackupManager(QuickBanUI plugin) {
        this.plugin = plugin;
        this.backupFolder = new File(plugin.getDataFolder(), "backups");
        this.enabled = BACKUP_ENABLED;
        this.maxBackups = MAX_BACKUPS;
        this.backupOnStartup = BACKUP_ON_STARTUP;
        this.dateFormat = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss");

        if (enabled) {
            if (!backupFolder.exists()) {
                backupFolder.mkdirs();
            }

            if (backupOnStartup) {
                // Delay startup backup to ensure everything is loaded
                Bukkit.getScheduler().runTaskLater(plugin, this::createBackup, 100L);
            }

            // Schedule automatic backups
            long interval = BACKUP_INTERVAL_HOURS * 60 * 60 * 20; // hours to ticks
            if (interval > 0) {
                Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::createBackup, interval, interval);
            }
        }
    }

    public void createBackup() {
        if (!enabled) return;

        try {
            String timestamp = dateFormat.format(new Date());
            File backupFile = new File(backupFolder, "backup_" + timestamp + ".zip");

            plugin.getLogger().info("Creating backup: " + backupFile.getName());

            try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(backupFile))) {
                // Backup punishments.yml
                File punishmentsFile = new File(plugin.getDataFolder(), "punishments.yml");
                if (punishmentsFile.exists()) {
                    addFileToZip(zos, punishmentsFile, "punishments.yml");
                }

                // Backup config.yml
                File configFile = new File(plugin.getDataFolder(), "config.yml");
                if (configFile.exists()) {
                    addFileToZip(zos, configFile, "config.yml");
                }

                // Backup lang.yml
                File langFile = new File(plugin.getDataFolder(), "lang.yml");
                if (langFile.exists()) {
                    addFileToZip(zos, langFile, "lang.yml");
                }

                // Add backup info
                String info = "QuickBanUI Backup\n" +
                        "Version: " + plugin.getDescription().getVersion() + "\n" +
                        "Date: " + new Date() + "\n" +
                        "Server: " + Bukkit.getVersion() + "\n" +
                        "Total Punishments: " + plugin.getPunishmentManager().getTotalPunishments();

                ZipEntry infoEntry = new ZipEntry("backup_info.txt");
                zos.putNextEntry(infoEntry);
                zos.write(info.getBytes());
                zos.closeEntry();
            }

            plugin.getLogger().info("Backup created successfully: " + backupFile.getName());

            // Clean old backups
            cleanOldBackups();

        } catch (Exception e) {
            plugin.getLogger().severe("Failed to create backup: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void addFileToZip(ZipOutputStream zos, File file, String entryName) throws IOException {
        ZipEntry entry = new ZipEntry(entryName);
        zos.putNextEntry(entry);

        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] buffer = new byte[1024];
            int length;
            while ((length = fis.read(buffer)) > 0) {
                zos.write(buffer, 0, length);
            }
        }

        zos.closeEntry();
    }

    private void cleanOldBackups() {
        try {
            List<File> backups = Arrays.asList(backupFolder.listFiles((dir, name) -> name.endsWith(".zip")));

            if (backups.size() > maxBackups) {
                // Sort by modification time (oldest first)
                backups.sort(Comparator.comparingLong(File::lastModified));

                int toDelete = backups.size() - maxBackups;
                for (int i = 0; i < toDelete; i++) {
                    if (backups.get(i).delete()) {
                        plugin.getLogger().info("Deleted old backup: " + backups.get(i).getName());
                    }
                }
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to clean old backups: " + e.getMessage());
        }
    }

    public boolean restoreBackup(String backupName) {
        File backupFile = new File(backupFolder, backupName);
        if (!backupFile.exists()) {
            return false;
        }

        try {
            // Create a temporary folder for extraction
            File tempFolder = new File(backupFolder, "temp_" + System.currentTimeMillis());
            tempFolder.mkdirs();

            // Extract backup
            unzip(backupFile, tempFolder);

            // Restore files
            File punishmentsFile = new File(tempFolder, "punishments.yml");
            if (punishmentsFile.exists()) {
                Files.copy(punishmentsFile.toPath(),
                        new File(plugin.getDataFolder(), "punishments.yml").toPath(),
                        StandardCopyOption.REPLACE_EXISTING);
            }

            File configFile = new File(tempFolder, "config.yml");
            if (configFile.exists()) {
                Files.copy(configFile.toPath(),
                        new File(plugin.getDataFolder(), "config.yml").toPath(),
                        StandardCopyOption.REPLACE_EXISTING);
            }

            File langFile = new File(tempFolder, "lang.yml");
            if (langFile.exists()) {
                Files.copy(langFile.toPath(),
                        new File(plugin.getDataFolder(), "lang.yml").toPath(),
                        StandardCopyOption.REPLACE_EXISTING);
            }

            // Clean up temp folder
            deleteDirectory(tempFolder);

            plugin.getLogger().info("Backup restored successfully: " + backupName);
            return true;

        } catch (Exception e) {
            plugin.getLogger().severe("Failed to restore backup: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    private void unzip(File zipFile, File destDir) throws IOException {
        byte[] buffer = new byte[1024];
        try (java.util.zip.ZipInputStream zis = new java.util.zip.ZipInputStream(new FileInputStream(zipFile))) {
            ZipEntry zipEntry = zis.getNextEntry();
            while (zipEntry != null) {
                File newFile = new File(destDir, zipEntry.getName());
                if (zipEntry.isDirectory()) {
                    newFile.mkdirs();
                } else {
                    new File(newFile.getParent()).mkdirs();
                    try (FileOutputStream fos = new FileOutputStream(newFile)) {
                        int len;
                        while ((len = zis.read(buffer)) > 0) {
                            fos.write(buffer, 0, len);
                        }
                    }
                }
                zipEntry = zis.getNextEntry();
            }
        }
    }

    private void deleteDirectory(File dir) {
        try (Stream<Path> paths = Files.walk(dir.toPath())) {
            paths.sorted(Comparator.reverseOrder())
                    .map(Path::toFile)
                    .forEach(File::delete);
        } catch (IOException e) {
            plugin.getLogger().warning("Failed to delete directory: " + e.getMessage());
        }
    }

    public List<String> getAvailableBackups() {
        List<String> backups = new ArrayList<>();
        if (backupFolder.exists()) {
            File[] files = backupFolder.listFiles((dir, name) -> name.endsWith(".zip"));
            if (files != null) {
                Arrays.stream(files)
                        .sorted((a, b) -> Long.compare(b.lastModified(), a.lastModified()))
                        .forEach(f -> backups.add(f.getName()));
            }
        }
        return backups;
    }

    public File getBackupFolder() {
        return backupFolder;
    }

    public boolean isEnabled() {
        return enabled;
    }
}