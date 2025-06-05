package net.mrcappy.quickbanui.utils;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.mrcappy.quickbanui.QuickBanUI;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.CompletableFuture;

public class UpdateChecker implements Listener {

    private final QuickBanUI plugin;

    private static final int RESOURCE_ID = 125735;
    private static final boolean ENABLED = true;
    private static final boolean NOTIFY_OPS = true;
    private static final long CHECK_INTERVAL_HOURS = 12;

    private String latestVersion;
    private boolean updateAvailable = false;
    private String updateMessage = "";

    public UpdateChecker(QuickBanUI plugin) {
        this.plugin = plugin;

        if (ENABLED && RESOURCE_ID > 0) {
            checkForUpdates();

            // Schedule periodic checks
            long interval = CHECK_INTERVAL_HOURS * 60 * 60 * 20; // Convert hours to ticks
            Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::checkForUpdates, interval, interval);
        } else if (RESOURCE_ID == 0) {
            plugin.getLogger().warning("UpdateChecker: Resource ID not set! Please update the RESOURCE_ID in UpdateChecker.java");
        }
    }

    private void checkForUpdates() {
        if (RESOURCE_ID == 0) return;

        CompletableFuture.supplyAsync(() -> {
            try {
                // Check Spigot API
                URL url = new URL("https://api.spigotmc.org/legacy/update.php?resource=" + RESOURCE_ID);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);

                try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
                    String version = reader.readLine();
                    if (version != null && !version.isEmpty()) {
                        return version;
                    }
                }
            } catch (Exception e) {
                plugin.getLogger().warning("Failed to check for updates: " + e.getMessage());
            }
            return null;
        }).thenAccept(version -> {
            if (version != null) {
                handleVersionCheck(version);
            }
        });
    }

    private void handleVersionCheck(String version) {
        latestVersion = version;
        String currentVersion = plugin.getDescription().getVersion();

        if (isNewerVersion(currentVersion, version)) {
            updateAvailable = true;
            updateMessage = String.format(
                    "&a&lQuickBanUI Update Available!\n" +
                            "&7Current version: &c%s\n" +
                            "&7Latest version: &a%s\n" +
                            "&7Download: &bhttps://www.spigotmc.org/resources/%d/",
                    currentVersion, version, RESOURCE_ID
            );

            // Log to console
            plugin.getLogger().info("=================================");
            plugin.getLogger().info("QuickBanUI Update Available!");
            plugin.getLogger().info("Current version: " + currentVersion);
            plugin.getLogger().info("Latest version: " + version);
            plugin.getLogger().info("Download: https://www.spigotmc.org/resources/" + RESOURCE_ID + "/");
            plugin.getLogger().info("=================================");
        } else {
            updateAvailable = false;
            plugin.getLogger().info("QuickBanUI is up to date! (v" + currentVersion + ")");
        }
    }

    private boolean isNewerVersion(String current, String latest) {
        try {
            // Remove any non-numeric prefixes (like 'v')
            current = current.replaceAll("^[^0-9]*", "");
            latest = latest.replaceAll("^[^0-9]*", "");

            String[] currentParts = current.split("\\.");
            String[] latestParts = latest.split("\\.");

            int length = Math.max(currentParts.length, latestParts.length);

            for (int i = 0; i < length; i++) {
                int currentPart = i < currentParts.length ? Integer.parseInt(currentParts[i]) : 0;
                int latestPart = i < latestParts.length ? Integer.parseInt(latestParts[i]) : 0;

                if (latestPart > currentPart) {
                    return true;
                } else if (currentPart > latestPart) {
                    return false;
                }
            }

            return false;
        } catch (Exception e) {
            // If parsing fails, do string comparison
            return !current.equals(latest) && latest.compareTo(current) > 0;
        }
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        if (!ENABLED || !updateAvailable || !NOTIFY_OPS) return;

        Player player = event.getPlayer();

        // Notify admins and ops about updates
        if (player.hasPermission("quickban.admin") || player.isOp()) {
            // Delay message so it doesn't get lost in join spam
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                player.sendMessage(plugin.color(updateMessage));
            }, 40L); // 2 seconds
        }
    }

    public void checkNow(Player player) {
        if (!ENABLED) {
            player.sendMessage(plugin.getPrefix() + "§cUpdate checker is disabled!");
            return;
        }

        if (RESOURCE_ID == 0) {
            player.sendMessage(plugin.getPrefix() + "§cUpdate checker not configured! Resource ID is not set.");
            return;
        }

        player.sendMessage(plugin.getPrefix() + "§eChecking for updates...");

        CompletableFuture.supplyAsync(() -> {
            checkForUpdates();
            return updateAvailable;
        }).thenAccept(available -> {
            // Run on main thread
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (available) {
                    player.sendMessage(plugin.color(updateMessage));
                } else {
                    player.sendMessage(plugin.getPrefix() + "§aYou are running the latest version!");
                }
            });
        });
    }

    public boolean isUpdateAvailable() {
        return updateAvailable;
    }

    public String getLatestVersion() {
        return latestVersion;
    }
}