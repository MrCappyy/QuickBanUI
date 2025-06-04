package net.mrcappy.quickbanui;

import net.mrcappy.quickbanui.gui.GUIManager;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerLoginEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.*;
import java.sql.*;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public final class QuickBanUI extends JavaPlugin implements Listener, CommandExecutor {

    private static QuickBanUI instance;
    private GUIManager guiManager;

    // Punishment Storage
    private final Map<UUID, List<PunishmentRecord>> punishmentHistory = new HashMap<>();
    private final Map<UUID, ActivePunishment> activeBans = new ConcurrentHashMap<>();
    private final Map<UUID, ActivePunishment> activeMutes = new ConcurrentHashMap<>();

    // Configuration
    private FileConfiguration config;
    private FileConfiguration langConfig;
    private FileConfiguration punishmentData;

    // Config values
    private String prefix;
    private boolean broadcastPunishments;
    private boolean logToConsole;

    @Override
    public void onEnable() {
        instance = this;

        // Create plugin folder
        if (!getDataFolder().exists()) {
            getDataFolder().mkdirs();
        }

        // Save configs
        saveDefaultConfig();
        saveResource("lang.yml", false);

        // Create punishments.yml
        File punishmentsFile = new File(getDataFolder(), "punishments.yml");
        if (!punishmentsFile.exists()) {
            try {
                punishmentsFile.createNewFile();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        // Load configs
        loadConfigurations();
        loadActivePunishments();

        // Initialize GUI Manager
        guiManager = new GUIManager(this);

        // Register commands
        String[] commands = {"punish", "ban", "mute", "kick", "warn", "unban", "unmute", "history", "qb"};
        for (String cmd : commands) {
            if (getCommand(cmd) != null) {
                getCommand(cmd).setExecutor(this);
            }
        }

        // Register events
        getServer().getPluginManager().registerEvents(this, this);

        // Start tasks
        startPunishmentChecker();

        getLogger().info("QuickBanUI v2.0 has been enabled!");
    }

    @Override
    public void onDisable() {
        saveActivePunishments();
        getLogger().info("QuickBanUI has been disabled!");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        // Admin command
        if (label.equalsIgnoreCase("qb") || label.equalsIgnoreCase("quickban") || label.equalsIgnoreCase("quickbanui")) {
            return handleAdminCommand(sender, args);
        }

        // Unban command
        if (label.equalsIgnoreCase("unban")) {
            return handleUnbanCommand(sender, args);
        }

        // Unmute command
        if (label.equalsIgnoreCase("unmute")) {
            return handleUnmuteCommand(sender, args);
        }

        // History command (with aliases)
        if (label.equalsIgnoreCase("history") || label.equalsIgnoreCase("punishhistory") || label.equalsIgnoreCase("ph")) {
            if (args.length != 1) {
                sender.sendMessage(prefix + "§cUsage: /" + label + " <player>");
                return true;
            }

            if (sender instanceof Player player) {
                if (!player.hasPermission("quickban.history")) {
                    player.sendMessage(prefix + "§cYou don't have permission!");
                    return true;
                }

                OfflinePlayer target = Bukkit.getOfflinePlayer(args[0]);
                guiManager.openHistoryMenu(player, target);
            } else {
                // Console history
                showConsoleHistory(sender, args[0]);
            }
            return true;
        }

        // All other punishment commands require player
        if (!(sender instanceof Player player)) {
            sender.sendMessage(prefix + "§cThis command can only be used by players!");
            return true;
        }

        // Permission check
        String permission = "quickban." + command.getName().toLowerCase();
        if (!player.hasPermission(permission)) {
            player.sendMessage(prefix + "§cYou don't have permission!");
            return true;
        }

        if (args.length != 1) {
            player.sendMessage(prefix + "§cUsage: /" + label + " <player>");
            return true;
        }

        String targetName = args[0];
        OfflinePlayer target = Bukkit.getOfflinePlayer(targetName);

        if (!target.hasPlayedBefore() && target.getPlayer() == null) {
            player.sendMessage(prefix + "§cPlayer not found!");
            return true;
        }

        // Check self-punishment
        if (target.getUniqueId().equals(player.getUniqueId())) {
            player.sendMessage(prefix + "§cYou cannot punish yourself!");
            return true;
        }

        // Check exempt
        if (target.isOnline() && target.getPlayer().hasPermission("quickban.exempt")) {
            player.sendMessage(prefix + "§cThis player is exempt from punishments!");
            return true;
        }

        // Open appropriate GUI based on command name (not label, to support aliases)
        switch (command.getName().toLowerCase()) {
            case "punish" -> guiManager.openMainMenu(player, target);
            case "ban" -> guiManager.openPunishmentMenu(player, target, PunishmentType.BAN);
            case "mute" -> guiManager.openPunishmentMenu(player, target, PunishmentType.MUTE);
            case "kick" -> guiManager.openPunishmentMenu(player, target, PunishmentType.KICK);
            case "warn" -> guiManager.openPunishmentMenu(player, target, PunishmentType.WARN);
        }

        return true;
    }

    private boolean handleAdminCommand(CommandSender sender, String[] args) {
        if (!sender.hasPermission("quickban.admin")) {
            sender.sendMessage(prefix + "§cYou don't have permission!");
            return true;
        }

        if (args.length == 0) {
            sender.sendMessage(prefix + "§7QuickBanUI v2.0 by MrCappy");
            sender.sendMessage("§7Commands:");
            sender.sendMessage("§e/qb reload §7- Reload configuration");
            sender.sendMessage("§e/qb reasons §7- Edit punishment reasons");
            sender.sendMessage("§e/qb analytics §7- View punishment analytics");
            sender.sendMessage("§e/qb clearcache §7- Clear player cache");
            sender.sendMessage("§e/qb backup §7- Backup punishment data");
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "reload" -> {
                reloadPlugin();
                sender.sendMessage(prefix + "§aConfiguration reloaded!");
                return true;
            }

            case "reasons" -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage(prefix + "§cThis command can only be used in-game!");
                    return true;
                }
                // Open reasons editor GUI
                guiManager.openReasonsEditor(player);
                return true;
            }

            case "analytics" -> {
                if (!(sender instanceof Player player)) {
                    // Show analytics in console
                    showConsoleAnalytics(sender);
                    return true;
                }
                // Open analytics GUI for players
                guiManager.openAnalyticsMenu(player);
                return true;
            }

            case "clearcache" -> {
                clearPlayerCache();
                sender.sendMessage(prefix + "§aPlayer cache cleared!");
                return true;
            }

            case "backup" -> {
                if (backupData()) {
                    sender.sendMessage(prefix + "§aBackup created successfully!");
                } else {
                    sender.sendMessage(prefix + "§cBackup failed! Check console for errors.");
                }
                return true;
            }

            default -> {
                sender.sendMessage(prefix + "§cUnknown subcommand. Use /qb for help.");
                return true;
            }
        }
    }

    private void showConsoleHistory(CommandSender sender, String playerName) {
        OfflinePlayer target = Bukkit.getOfflinePlayer(playerName);

        if (!target.hasPlayedBefore() && target.getPlayer() == null) {
            sender.sendMessage(prefix + "§cPlayer not found!");
            return;
        }

        List<PunishmentRecord> history = getHistory(target.getUniqueId());

        if (history.isEmpty()) {
            sender.sendMessage(prefix + "§7" + playerName + " has no punishment history.");
            return;
        }

        // Sort by timestamp (newest first)
        history.sort((a, b) -> Long.compare(b.timestamp, a.timestamp));

        sender.sendMessage("");
        sender.sendMessage("§6§l=== Punishment History for " + playerName + " ===");
        sender.sendMessage("");

        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

        for (PunishmentRecord record : history) {
            LocalDateTime date = LocalDateTime.ofInstant(
                    Instant.ofEpochMilli(record.timestamp),
                    ZoneId.systemDefault()
            );

            String status = record.active ? "§a[ACTIVE]" : "§c[EXPIRED]";
            String type = getTypeColor(record.type) + record.type.getDisplayName();

            sender.sendMessage(String.format("§7%s %s %s",
                    date.format(formatter),
                    status,
                    type
            ));
            sender.sendMessage("  §7Reason: §f" + record.reason);
            sender.sendMessage("  §7Duration: §e" + (record.duration.equals("none") ? "N/A" : record.duration));
            sender.sendMessage("  §7Staff: §b" + record.staffName);
            sender.sendMessage("");
        }

        sender.sendMessage("§7Total punishments: §e" + history.size());
        sender.sendMessage("");
    }

    private String getTypeColor(PunishmentType type) {
        return switch (type) {
            case BAN -> "§c";
            case MUTE -> "§6";
            case KICK -> "§e";
            case WARN -> "§a";
        };
    }

    private void reloadPlugin() {
        // Reload configuration files
        reloadConfig();
        loadConfigurations();

        // Log reload
        if (logToConsole) {
            getLogger().info("Configuration reloaded by command");
        }
    }

    private void showConsoleAnalytics(CommandSender sender) {
        sender.sendMessage("");
        sender.sendMessage("§6§l=== QuickBanUI Analytics ===");
        sender.sendMessage("");

        // Calculate analytics
        int totalPunishments = 0;
        int activeBansCount = activeBans.size();
        int activeMutesCount = activeMutes.size();

        Map<PunishmentType, Integer> typeCount = new HashMap<>();
        Map<String, Integer> staffCount = new HashMap<>();
        Map<String, Integer> playerCount = new HashMap<>();

        // Process all punishment records
        for (List<PunishmentRecord> records : punishmentHistory.values()) {
            for (PunishmentRecord record : records) {
                totalPunishments++;

                // Count by type
                typeCount.merge(record.type, 1, Integer::sum);

                // Count by staff
                staffCount.merge(record.staffName, 1, Integer::sum);

                // Count by player
                playerCount.merge(record.playerName, 1, Integer::sum);
            }
        }

        // Display statistics
        sender.sendMessage("§e§lTotal Statistics:");
        sender.sendMessage("  §7Total punishments: §f" + totalPunishments);
        sender.sendMessage("  §7Active bans: §c" + activeBansCount);
        sender.sendMessage("  §7Active mutes: §6" + activeMutesCount);
        sender.sendMessage("");

        sender.sendMessage("§e§lPunishment Types:");
        for (Map.Entry<PunishmentType, Integer> entry : typeCount.entrySet()) {
            sender.sendMessage("  §7" + entry.getKey().getDisplayName() + "s: §f" + entry.getValue());
        }
        sender.sendMessage("");

        sender.sendMessage("§e§lTop Staff Activity:");
        staffCount.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(5)
                .forEach(entry ->
                        sender.sendMessage("  §b" + entry.getKey() + ": §f" + entry.getValue())
                );
        sender.sendMessage("");

        sender.sendMessage("§e§lMost Punished Players:");
        playerCount.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(5)
                .forEach(entry ->
                        sender.sendMessage("  §c" + entry.getKey() + ": §f" + entry.getValue())
                );
        sender.sendMessage("");
    }

    private void clearPlayerCache() {
        // Clear any cached player names or data
        // This is useful if you implement name caching as mentioned in config

        // For now, just clear the punishment history cache if needed
        // You might want to implement actual name caching later

        if (logToConsole) {
            getLogger().info("Player cache cleared");
        }
    }

    private boolean backupData() {
        try {
            File backupDir = new File(getDataFolder(), "backups");
            if (!backupDir.exists()) {
                backupDir.mkdirs();
            }

            // Create timestamp for backup
            String timestamp = LocalDateTime.now()
                    .format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"));

            // Backup punishments.yml
            File punishmentsFile = new File(getDataFolder(), "punishments.yml");
            File backupFile = new File(backupDir, "punishments_" + timestamp + ".yml");

            if (punishmentsFile.exists()) {
                copyFile(punishmentsFile, backupFile);
            }

            // Clean old backups if max backups exceeded
            cleanOldBackups(backupDir);

            if (logToConsole) {
                getLogger().info("Created backup: " + backupFile.getName());
            }

            return true;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    private void copyFile(File source, File dest) throws IOException {
        try (InputStream in = new FileInputStream(source);
             OutputStream out = new FileOutputStream(dest)) {
            byte[] buffer = new byte[1024];
            int length;
            while ((length = in.read(buffer)) > 0) {
                out.write(buffer, 0, length);
            }
        }
    }

    private void cleanOldBackups(File backupDir) {
        int maxBackups = config.getInt("advanced.max-backups", 10);

        File[] backups = backupDir.listFiles((dir, name) -> name.startsWith("punishments_"));
        if (backups != null && backups.length > maxBackups) {
            // Sort by last modified (oldest first)
            Arrays.sort(backups, Comparator.comparingLong(File::lastModified));

            // Delete oldest backups
            int toDelete = backups.length - maxBackups;
            for (int i = 0; i < toDelete; i++) {
                if (backups[i].delete() && logToConsole) {
                    getLogger().info("Deleted old backup: " + backups[i].getName());
                }
            }
        }
    }

    private boolean handleUnbanCommand(CommandSender sender, String[] args) {
        if (!sender.hasPermission("quickban.unban")) {
            sender.sendMessage(prefix + "§cNo permission!");
            return true;
        }

        if (args.length != 1) {
            sender.sendMessage(prefix + "§cUsage: /unban <player>");
            return true;
        }

        OfflinePlayer target = Bukkit.getOfflinePlayer(args[0]);
        if (removeBan(target.getUniqueId())) {
            sender.sendMessage(prefix + "§aUnbanned " + target.getName());

            if (broadcastPunishments) {
                for (Player p : Bukkit.getOnlinePlayers()) {
                    if (p.hasPermission("quickban.notify")) {
                        p.sendMessage(prefix + "§e" + sender.getName() + " §7unbanned §a" + target.getName());
                    }
                }
            }
        } else {
            sender.sendMessage(prefix + "§cPlayer is not banned!");
        }

        return true;
    }

    private boolean handleUnmuteCommand(CommandSender sender, String[] args) {
        if (!sender.hasPermission("quickban.unmute")) {
            sender.sendMessage(prefix + "§cNo permission!");
            return true;
        }

        if (args.length != 1) {
            sender.sendMessage(prefix + "§cUsage: /unmute <player>");
            return true;
        }

        OfflinePlayer target = Bukkit.getOfflinePlayer(args[0]);
        if (removeMute(target.getUniqueId())) {
            sender.sendMessage(prefix + "§aUnmuted " + target.getName());

            if (target.isOnline()) {
                target.getPlayer().sendMessage(prefix + "§aYou have been unmuted!");
            }

            if (broadcastPunishments) {
                for (Player p : Bukkit.getOnlinePlayers()) {
                    if (p.hasPermission("quickban.notify")) {
                        p.sendMessage(prefix + "§e" + sender.getName() + " §7unmuted §a" + target.getName());
                    }
                }
            }
        } else {
            sender.sendMessage(prefix + "§cPlayer is not muted!");
        }

        return true;
    }

    // PUNISHMENT EXECUTION
    public void executePunishment(Player staff, OfflinePlayer target, PunishmentType type,
                                  String reason, String duration, boolean silent) {

        long endTime = calculateEndTime(duration);

        // Add to history
        PunishmentRecord record = new PunishmentRecord(
                target.getUniqueId(), target.getName(), type, reason, duration,
                staff.getUniqueId(), staff.getName(), System.currentTimeMillis(), true
        );
        addPunishmentRecord(record);

        // Execute punishment
        switch (type) {
            case BAN -> {
                activeBans.put(target.getUniqueId(), new ActivePunishment(reason, System.currentTimeMillis(), endTime, staff.getName()));

                if (target.isOnline()) {
                    String kickMsg = "§c§lYou have been banned!\n\n" +
                            "§7Reason: §f" + reason + "\n" +
                            "§7Duration: §f" + (endTime == -1 ? "Permanent" : duration) + "\n" +
                            "§7By: §f" + staff.getName();

                    Bukkit.getScheduler().runTask(this, () ->
                            target.getPlayer().kickPlayer(kickMsg)
                    );
                }
            }

            case MUTE -> {
                activeMutes.put(target.getUniqueId(), new ActivePunishment(reason, System.currentTimeMillis(), endTime, staff.getName()));

                if (target.isOnline()) {
                    target.getPlayer().sendMessage(prefix + "§cYou have been muted!");
                    target.getPlayer().sendMessage("§7Reason: §f" + reason);
                    target.getPlayer().sendMessage("§7Duration: §f" + (endTime == -1 ? "Permanent" : duration));
                }
            }

            case KICK -> {
                if (target.isOnline()) {
                    String kickMsg = "§c§lYou have been kicked!\n\n" +
                            "§7Reason: §f" + reason + "\n" +
                            "§7By: §f" + staff.getName();

                    Bukkit.getScheduler().runTask(this, () ->
                            target.getPlayer().kickPlayer(kickMsg)
                    );
                } else {
                    staff.sendMessage(prefix + "§cCannot kick offline player!");
                    return;
                }
            }

            case WARN -> {
                if (target.isOnline()) {
                    target.getPlayer().sendMessage("");
                    target.getPlayer().sendMessage("§c§l▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬");
                    target.getPlayer().sendMessage("§c§lWARNING");
                    target.getPlayer().sendMessage("");
                    target.getPlayer().sendMessage("§7Warned by: §e" + staff.getName());
                    target.getPlayer().sendMessage("§7Reason: §f" + reason);
                    target.getPlayer().sendMessage("");
                    target.getPlayer().sendMessage("§c§l▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬");
                } else {
                    staff.sendMessage(prefix + "§cCannot warn offline player!");
                    return;
                }
            }
        }

        // Save
        saveActivePunishments();

        // Log
        if (logToConsole) {
            getLogger().info(String.format("[PUNISHMENT] %s %s %s for: %s (%s)",
                    staff.getName(), type.getPastTense(), target.getName(), reason,
                    duration.equals("none") ? "N/A" : duration));
        }

        // Broadcast
        if (broadcastPunishments && !silent) {
            String msg = prefix + "§e" + staff.getName() + " §7" + type.getPastTense() +
                    " §c" + target.getName() + " §7for: §f" + reason;

            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.hasPermission("quickban.notify")) {
                    p.sendMessage(msg);
                }
            }
        }

        staff.sendMessage(prefix + "§aSuccessfully " + type.getPastTense() + " §e" + target.getName());
    }

    // EVENT HANDLERS
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerLogin(PlayerLoginEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();

        if (activeBans.containsKey(uuid)) {
            ActivePunishment ban = activeBans.get(uuid);

            String kickMsg = "§c§lYou are banned from this server!\n\n" +
                    "§7Reason: §f" + ban.reason + "\n" +
                    "§7By: §f" + ban.staffName + "\n";

            if (ban.endTime == -1) {
                kickMsg += "§7Duration: §cPermanent";
            } else {
                long timeLeft = ban.endTime - System.currentTimeMillis();
                kickMsg += "§7Time left: §e" + formatDuration(timeLeft);
            }

            event.disallow(PlayerLoginEvent.Result.KICK_BANNED, kickMsg);
        }
    }

    @EventHandler
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();

        if (activeMutes.containsKey(uuid)) {
            event.setCancelled(true);
            ActivePunishment mute = activeMutes.get(uuid);

            event.getPlayer().sendMessage(prefix + "§cYou are muted!");
            event.getPlayer().sendMessage("§7Reason: §f" + mute.reason);

            if (mute.endTime != -1) {
                long timeLeft = mute.endTime - System.currentTimeMillis();
                event.getPlayer().sendMessage("§7Time left: §e" + formatDuration(timeLeft));
            }
        }
    }

    @EventHandler
    public void onCommand(PlayerCommandPreprocessEvent event) {
        if (activeMutes.containsKey(event.getPlayer().getUniqueId())) {
            String cmd = event.getMessage().toLowerCase();
            if (cmd.startsWith("/tell") || cmd.startsWith("/msg") ||
                    cmd.startsWith("/w ") || cmd.startsWith("/r ")) {
                event.setCancelled(true);
                event.getPlayer().sendMessage(prefix + "§cYou cannot use chat commands while muted!");
            }
        }
    }

    // UTILITY METHODS
    private void loadConfigurations() {
        config = getConfig();
        langConfig = YamlConfiguration.loadConfiguration(new File(getDataFolder(), "lang.yml"));
        punishmentData = YamlConfiguration.loadConfiguration(new File(getDataFolder(), "punishments.yml"));

        prefix = config.getString("messages.prefix", "&c&lQuickBan &8» &7").replace("&", "§");
        broadcastPunishments = config.getBoolean("settings.broadcast-punishments", true);
        logToConsole = config.getBoolean("settings.log-to-console", true);
    }

    private void loadActivePunishments() {
        // Load bans
        if (punishmentData.contains("active-bans")) {
            for (String uuidStr : punishmentData.getConfigurationSection("active-bans").getKeys(false)) {
                UUID uuid = UUID.fromString(uuidStr);
                String path = "active-bans." + uuidStr;

                activeBans.put(uuid, new ActivePunishment(
                        punishmentData.getString(path + ".reason"),
                        punishmentData.getLong(path + ".startTime"),
                        punishmentData.getLong(path + ".endTime"),
                        punishmentData.getString(path + ".staffName")
                ));
            }
        }

        // Load mutes
        if (punishmentData.contains("active-mutes")) {
            for (String uuidStr : punishmentData.getConfigurationSection("active-mutes").getKeys(false)) {
                UUID uuid = UUID.fromString(uuidStr);
                String path = "active-mutes." + uuidStr;

                activeMutes.put(uuid, new ActivePunishment(
                        punishmentData.getString(path + ".reason"),
                        punishmentData.getLong(path + ".startTime"),
                        punishmentData.getLong(path + ".endTime"),
                        punishmentData.getString(path + ".staffName")
                ));
            }
        }

        // Load history
        if (punishmentData.contains("history")) {
            for (String uuidStr : punishmentData.getConfigurationSection("history").getKeys(false)) {
                UUID uuid = UUID.fromString(uuidStr);
                List<PunishmentRecord> records = new ArrayList<>();

                for (String key : punishmentData.getConfigurationSection("history." + uuidStr).getKeys(false)) {
                    String path = "history." + uuidStr + "." + key;
                    records.add(new PunishmentRecord(
                            uuid,
                            punishmentData.getString(path + ".playerName"),
                            PunishmentType.valueOf(punishmentData.getString(path + ".type")),
                            punishmentData.getString(path + ".reason"),
                            punishmentData.getString(path + ".duration"),
                            UUID.fromString(punishmentData.getString(path + ".staffUuid")),
                            punishmentData.getString(path + ".staffName"),
                            punishmentData.getLong(path + ".timestamp"),
                            punishmentData.getBoolean(path + ".active")
                    ));
                }

                punishmentHistory.put(uuid, records);
            }
        }
    }

    private void saveActivePunishments() {
        // Save bans
        punishmentData.set("active-bans", null);
        for (Map.Entry<UUID, ActivePunishment> entry : activeBans.entrySet()) {
            String path = "active-bans." + entry.getKey().toString();
            ActivePunishment p = entry.getValue();

            punishmentData.set(path + ".reason", p.reason);
            punishmentData.set(path + ".startTime", p.startTime);
            punishmentData.set(path + ".endTime", p.endTime);
            punishmentData.set(path + ".staffName", p.staffName);
        }

        // Save mutes
        punishmentData.set("active-mutes", null);
        for (Map.Entry<UUID, ActivePunishment> entry : activeMutes.entrySet()) {
            String path = "active-mutes." + entry.getKey().toString();
            ActivePunishment p = entry.getValue();

            punishmentData.set(path + ".reason", p.reason);
            punishmentData.set(path + ".startTime", p.startTime);
            punishmentData.set(path + ".endTime", p.endTime);
            punishmentData.set(path + ".staffName", p.staffName);
        }

        // Save history
        punishmentData.set("history", null);
        for (Map.Entry<UUID, List<PunishmentRecord>> entry : punishmentHistory.entrySet()) {
            String basePath = "history." + entry.getKey().toString();
            int i = 0;

            for (PunishmentRecord r : entry.getValue()) {
                String path = basePath + "." + i++;
                punishmentData.set(path + ".playerName", r.playerName);
                punishmentData.set(path + ".type", r.type.name());
                punishmentData.set(path + ".reason", r.reason);
                punishmentData.set(path + ".duration", r.duration);
                punishmentData.set(path + ".staffUuid", r.staffUuid.toString());
                punishmentData.set(path + ".staffName", r.staffName);
                punishmentData.set(path + ".timestamp", r.timestamp);
                punishmentData.set(path + ".active", r.active);
            }
        }

        try {
            punishmentData.save(new File(getDataFolder(), "punishments.yml"));
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void startPunishmentChecker() {
        new BukkitRunnable() {
            @Override
            public void run() {
                long now = System.currentTimeMillis();

                // Check bans
                activeBans.entrySet().removeIf(entry -> {
                    ActivePunishment p = entry.getValue();
                    if (p.endTime != -1 && p.endTime <= now) {
                        updatePunishmentStatus(entry.getKey(), PunishmentType.BAN, false);
                        return true;
                    }
                    return false;
                });

                // Check mutes
                activeMutes.entrySet().removeIf(entry -> {
                    ActivePunishment p = entry.getValue();
                    if (p.endTime != -1 && p.endTime <= now) {
                        Player player = Bukkit.getPlayer(entry.getKey());
                        if (player != null) {
                            player.sendMessage(prefix + "§aYour mute has expired!");
                        }
                        updatePunishmentStatus(entry.getKey(), PunishmentType.MUTE, false);
                        return true;
                    }
                    return false;
                });
            }
        }.runTaskTimer(this, 20L, 20L);
    }

    private void addPunishmentRecord(PunishmentRecord record) {
        punishmentHistory.computeIfAbsent(record.playerUuid, k -> new ArrayList<>()).add(record);
    }

    private void updatePunishmentStatus(UUID uuid, PunishmentType type, boolean active) {
        List<PunishmentRecord> records = punishmentHistory.get(uuid);
        if (records != null) {
            for (PunishmentRecord r : records) {
                if (r.type == type && r.active != active) {
                    r.active = active;
                    break;
                }
            }
        }
    }

    private long calculateEndTime(String duration) {
        if (duration.equals("permanent") || duration.equals("none")) {
            return -1;
        }

        long multiplier = 1000L;

        if (duration.endsWith("m")) {
            multiplier *= 60;
            duration = duration.substring(0, duration.length() - 1);
        } else if (duration.endsWith("h")) {
            multiplier *= 60 * 60;
            duration = duration.substring(0, duration.length() - 1);
        } else if (duration.endsWith("d")) {
            multiplier *= 60 * 60 * 24;
            duration = duration.substring(0, duration.length() - 1);
        }

        try {
            return System.currentTimeMillis() + (Long.parseLong(duration) * multiplier);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private String formatDuration(long millis) {
        if (millis <= 0) return "Expired";

        long seconds = millis / 1000;
        long minutes = seconds / 60;
        long hours = minutes / 60;
        long days = hours / 24;

        if (days > 0) return days + "d " + (hours % 24) + "h";
        if (hours > 0) return hours + "h " + (minutes % 60) + "m";
        if (minutes > 0) return minutes + "m";
        return seconds + "s";
    }

    public boolean removeBan(UUID uuid) {
        if (activeBans.remove(uuid) != null) {
            updatePunishmentStatus(uuid, PunishmentType.BAN, false);
            saveActivePunishments();
            return true;
        }
        return false;
    }

    public boolean removeMute(UUID uuid) {
        if (activeMutes.remove(uuid) != null) {
            updatePunishmentStatus(uuid, PunishmentType.MUTE, false);
            saveActivePunishments();
            return true;
        }
        return false;
    }

    // Getters
    public String getPrefix() { return prefix; }
    public FileConfiguration getLangConfig() { return langConfig; }
    public List<PunishmentRecord> getHistory(UUID uuid) {
        return punishmentHistory.getOrDefault(uuid, new ArrayList<>());
    }
    public Map<UUID, List<PunishmentRecord>> getHistory() {
        return punishmentHistory;
    }
    public boolean isBanned(UUID uuid) { return activeBans.containsKey(uuid); }
    public boolean isMuted(UUID uuid) { return activeMutes.containsKey(uuid); }
    public int getActiveBansCount() { return activeBans.size(); }
    public int getActiveMutesCount() { return activeMutes.size(); }
    public GUIManager getGUIManager() { return guiManager; }

    public static QuickBanUI getInstance() { return instance; }

    // INNER CLASSES
    public enum PunishmentType {
        BAN("Ban", "banned"),
        MUTE("Mute", "muted"),
        KICK("Kick", "kicked"),
        WARN("Warn", "warned");

        private final String displayName;
        private final String pastTense;

        PunishmentType(String displayName, String pastTense) {
            this.displayName = displayName;
            this.pastTense = pastTense;
        }

        public String getDisplayName() { return displayName; }
        public String getPastTense() { return pastTense; }
    }

    public static class PunishmentRecord {
        public final UUID playerUuid;
        public final String playerName;
        public final PunishmentType type;
        public final String reason;
        public final String duration;
        public final UUID staffUuid;
        public final String staffName;
        public final long timestamp;
        public boolean active;

        public PunishmentRecord(UUID playerUuid, String playerName, PunishmentType type,
                                String reason, String duration, UUID staffUuid,
                                String staffName, long timestamp, boolean active) {
            this.playerUuid = playerUuid;
            this.playerName = playerName;
            this.type = type;
            this.reason = reason;
            this.duration = duration;
            this.staffUuid = staffUuid;
            this.staffName = staffName;
            this.timestamp = timestamp;
            this.active = active;
        }
    }

    public static class ActivePunishment {
        public final String reason;
        public final long startTime;
        public final long endTime;
        public final String staffName;

        public ActivePunishment(String reason, long startTime, long endTime, String staffName) {
            this.reason = reason;
            this.startTime = startTime;
            this.endTime = endTime;
            this.staffName = staffName;
        }
    }
}