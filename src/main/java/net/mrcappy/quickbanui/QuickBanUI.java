package net.mrcappy.quickbanui;

import com.google.gson.Gson;
import net.mrcappy.quickbanui.gui.GUIManager;
import net.mrcappy.quickbanui.integrations.DiscordWebhook;
import net.mrcappy.quickbanui.utils.BackupManager;
import net.mrcappy.quickbanui.utils.FileLogger;
import net.mrcappy.quickbanui.utils.UpdateChecker;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerLoginEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class QuickBanUI extends JavaPlugin implements Listener {

    private static QuickBanUI instance;

    // Core managers
    private PunishmentManager punishmentManager;
    private GUIManager guiManager;
    private Storage storage;
    private Commands commands;

    // Feature managers
    private DiscordWebhook discordWebhook;
    private UpdateChecker updateChecker;
    private BackupManager backupManager;
    private FileLogger fileLogger;

    // Prevent spam clicking
    private final Map<UUID, Long> punishmentCooldowns = new ConcurrentHashMap<>();

    private FileConfiguration langConfig;
    private String prefix;
    private Gson gson;

    @Override
    public void onEnable() {
        instance = this;
        gson = new Gson();

        // Load configs
        saveDefaultConfig();
        saveResource("lang.yml", false);
        loadLang();

        // Initialize core systems
        storage = new Storage(this);
        punishmentManager = new PunishmentManager(this, storage);
        guiManager = new GUIManager(this);
        commands = new Commands(this);

        // Initialize features
        discordWebhook = new DiscordWebhook(this);
        updateChecker = new UpdateChecker(this);
        backupManager = new BackupManager(this);
        fileLogger = new FileLogger(this);

        // Register events
        getServer().getPluginManager().registerEvents(this, this);
        getServer().getPluginManager().registerEvents(guiManager, this);
        getServer().getPluginManager().registerEvents(updateChecker, this);

        registerCommands();
        scheduleTasks();

        // Startup messages
        getLogger().info("QuickBanUI v2.0 enabled!");
        getLogger().info("Discord: " + (discordWebhook.isEnabled() ? "Enabled" : "Disabled"));
        getLogger().info("Update Checker: " + (updateChecker.isUpdateAvailable() ? "Update Available!" : "Up to date"));
        getLogger().info("Backups: " + (backupManager.isEnabled() ? "Enabled" : "Disabled"));
        getLogger().info("File Logging: " + (fileLogger.isEnabled() ? "Enabled" : "Disabled"));
    }

    @Override
    public void onDisable() {
        // Save everything
        if (storage != null) {
            storage.save();
        }

        // Shutdown file logger properly
        if (fileLogger != null) {
            fileLogger.shutdown();
        }

        // Create shutdown backup if enabled
        if (backupManager != null && backupManager.isEnabled()) {
            backupManager.createBackup();
        }
    }

    private void registerCommands() {
        // Main commands
        getCommand("punish").setExecutor(commands);
        getCommand("ban").setExecutor(commands);
        getCommand("mute").setExecutor(commands);
        getCommand("kick").setExecutor(commands);
        getCommand("warn").setExecutor(commands);

        // Utility commands
        getCommand("unban").setExecutor(commands);
        getCommand("unmute").setExecutor(commands);
        getCommand("history").setExecutor(commands);
        getCommand("qb").setExecutor(commands);
    }

    private void scheduleTasks() {
        // Auto-save task
        int autosaveInterval = getConfig().getInt("advanced.auto-save-interval", 5);
        if (autosaveInterval > 0) {
            Bukkit.getScheduler().runTaskTimer(this, () -> storage.save(),
                    autosaveInterval * 60 * 20L, autosaveInterval * 60 * 20L);
        }

        // Check for expired punishments
        int expiryCheck = getConfig().getInt("advanced.expiry-check-interval", 20);
        Bukkit.getScheduler().runTaskTimer(this, () -> punishmentManager.checkExpired(),
                20L, expiryCheck * 20L);
    }

    private void loadLang() {
        File langFile = new File(getDataFolder(), "lang.yml");
        langConfig = YamlConfiguration.loadConfiguration(langFile);
        prefix = color(langConfig.getString("messages.prefix", "&c&lQuickBan &8» &7"));
    }

    @EventHandler
    public void onLogin(PlayerLoginEvent e) {
        UUID uuid = e.getPlayer().getUniqueId();
        var ban = punishmentManager.getActiveBan(uuid);

        if (ban != null) {
            // Build the ban message
            String msg = buildBanScreen(ban);
            e.disallow(PlayerLoginEvent.Result.KICK_BANNED, msg);
        }
    }

    private String buildBanScreen(PunishmentManager.Punishment ban) {
        StringBuilder msg = new StringBuilder();

        msg.append(color(langConfig.getString("punishment-screens.ban.title"))).append("\n\n");
        msg.append(color(langConfig.getString("punishment-screens.ban.reason"))
                .replace("%reason%", ban.reason)).append("\n");
        msg.append(color(langConfig.getString("punishment-screens.ban.staff"))
                .replace("%staff%", ban.staffName)).append("\n");

        if (ban.isPermanent()) {
            msg.append(color(langConfig.getString("punishment-screens.ban.duration-permanent")));
        } else {
            msg.append(color(langConfig.getString("punishment-screens.ban.time-left"))
                    .replace("%time%", formatTime(ban.getRemaining())));
        }

        // Add appeal link if configured
        String appeal = getConfig().getString("settings.appeal-link", "");
        if (!appeal.isEmpty()) {
            msg.append("\n").append(color(langConfig.getString("punishment-screens.ban.appeal"))
                    .replace("%appeal%", appeal));
        }

        return msg.toString();
    }

    @EventHandler
    public void onChat(AsyncPlayerChatEvent e) {
        UUID uuid = e.getPlayer().getUniqueId();
        var mute = punishmentManager.getActiveMute(uuid);

        if (mute != null) {
            e.setCancelled(true);

            // Tell them they're muted
            e.getPlayer().sendMessage(color(langConfig.getString("punishment-screens.mute.chat-blocked")));
            e.getPlayer().sendMessage(color(langConfig.getString("punishment-screens.mute.reason"))
                    .replace("%reason%", mute.reason));

            if (mute.isPermanent()) {
                e.getPlayer().sendMessage(color(langConfig.getString("punishment-screens.mute.duration-permanent")));
            } else {
                e.getPlayer().sendMessage(color(langConfig.getString("punishment-screens.mute.time-left"))
                        .replace("%time%", formatTime(mute.getRemaining())));
            }
        }
    }

    @EventHandler
    public void onCommand(PlayerCommandPreprocessEvent e) {
        // Check if player is muted
        if (!punishmentManager.isMuted(e.getPlayer().getUniqueId())) {
            return;
        }

        String cmd = e.getMessage().toLowerCase();
        List<String> blockedCommands = getConfig().getStringList("settings.mute-blocked-commands");

        // Check if command is blocked
        for (String blocked : blockedCommands) {
            if (cmd.startsWith("/" + blocked)) {
                e.setCancelled(true);
                e.getPlayer().sendMessage(color(langConfig.getString("messages.cannot-use-commands-muted")));
                break;
            }
        }
    }

    public boolean checkCooldown(Player player) {
        int cooldown = getConfig().getInt("advanced.punishment-cooldown", 3);
        if (cooldown <= 0) return true;

        UUID uuid = player.getUniqueId();
        long lastPunishment = punishmentCooldowns.getOrDefault(uuid, 0L);
        long currentTime = System.currentTimeMillis();

        if (currentTime - lastPunishment < cooldown * 1000L) {
            long remaining = ((cooldown * 1000L) - (currentTime - lastPunishment)) / 1000L + 1;
            player.sendMessage(getPrefix() + "§cPlease wait " + remaining + " seconds before punishing again!");
            return false;
        }

        punishmentCooldowns.put(uuid, currentTime);
        return true;
    }

    public boolean checkReasonLength(String reason) {
        int maxLength = getConfig().getInt("advanced.max-reason-length", 100);
        return reason.length() <= maxLength;
    }

    public void reload() {
        reloadConfig();
        loadLang();
        storage.reload();

        // Reinitialize features with new config
        discordWebhook = new DiscordWebhook(this);
        updateChecker = new UpdateChecker(this);

        getLogger().info("Configuration reloaded!");
    }

    public void broadcast(String message, String permission) {
        // Send to all online players with permission
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.hasPermission(permission)) {
                p.sendMessage(message);
            }
        }
    }

    public String formatTime(long millis) {
        if (millis <= 0) return "Expired";

        long seconds = millis / 1000;
        long minutes = seconds / 60;
        long hours = minutes / 60;
        long days = hours / 24;

        // Format nicely
        if (days > 0) return days + "d " + (hours % 24) + "h";
        if (hours > 0) return hours + "h " + (minutes % 60) + "m";
        if (minutes > 0) return minutes + "m";
        return seconds + "s";
    }

    public String color(String s) {
        if (s == null) return "";
        // Convert & to § for color codes
        return s.replace("&", "§");
    }

    // Getters
    public String getPrefix() { return prefix; }
    public FileConfiguration getLang() { return langConfig; }
    public PunishmentManager getPunishmentManager() { return punishmentManager; }
    public GUIManager getGUIManager() { return guiManager; }
    public DiscordWebhook getDiscordWebhook() { return discordWebhook; }
    public UpdateChecker getUpdateChecker() { return updateChecker; }
    public BackupManager getBackupManager() { return backupManager; }
    public FileLogger getFileLogger() { return fileLogger; }
    public Gson getGson() { return gson; }
    public static QuickBanUI getInstance() { return instance; }
}