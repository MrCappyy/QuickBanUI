package net.mrcappy.quickbanui;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

public class Commands implements CommandExecutor {

    private final QuickBanUI plugin;

    public Commands(QuickBanUI plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        // Handle admin command separately since it has subcommands
        if (cmd.getName().equalsIgnoreCase("qb")) {
            return handleAdmin(sender, args);
        }

        if (cmd.getName().equalsIgnoreCase("unban")) {
            return handleUnban(sender, args);
        }

        if (cmd.getName().equalsIgnoreCase("unmute")) {
            return handleUnmute(sender, args);
        }

        if (cmd.getName().equalsIgnoreCase("history")) {
            return handleHistory(sender, args);
        }

        // Everything below this needs a player
        if (!(sender instanceof Player player)) {
            sender.sendMessage(plugin.color(plugin.getLang().getString("messages.console-only")));
            return true;
        }

        // Check perms
        if (!player.hasPermission("quickban." + cmd.getName().toLowerCase())) {
            player.sendMessage(plugin.color(plugin.getLang().getString("messages.no-permission")));
            return true;
        }

        if (args.length != 1) {
            player.sendMessage(plugin.color(plugin.getLang().getString("messages.usage." + cmd.getName().toLowerCase())));
            return true;
        }

        OfflinePlayer target = Bukkit.getOfflinePlayer(args[0]);

        // Make sure the player exists
        if (!target.hasPlayedBefore() && !target.isOnline()) {
            player.sendMessage(plugin.color(plugin.getLang().getString("messages.player-not-found"))
                    .replace("%player%", args[0]));
            return true;
        }

        // Can't punish yourself lol
        if (target.getUniqueId().equals(player.getUniqueId())) {
            player.sendMessage(plugin.color(plugin.getLang().getString("messages.cannot-punish-self")));
            return true;
        }

        // Check if player is exempt
        if (target.isOnline() && target.getPlayer().hasPermission("quickban.exempt")) {
            player.sendMessage(plugin.color(plugin.getLang().getString("messages.player-exempt")));
            return true;
        }

        // Figure out what type of punishment this is
        var type = switch (cmd.getName().toLowerCase()) {
            case "ban" -> PunishmentManager.PunishmentType.BAN;
            case "mute" -> PunishmentManager.PunishmentType.MUTE;
            case "kick" -> PunishmentManager.PunishmentType.KICK;
            case "warn" -> PunishmentManager.PunishmentType.WARN;
            default -> null;
        };

        if (type != null) {
            plugin.getGUIManager().openPunishmentMenu(player, target, type);
        } else {
            // /punish command opens main menu
            plugin.getGUIManager().openMainMenu(player, target);
        }

        return true;
    }

    private boolean handleAdmin(CommandSender sender, String[] args) {
        if (!sender.hasPermission("quickban.admin")) {
            sender.sendMessage(plugin.color(plugin.getLang().getString("messages.no-permission")));
            return true;
        }

        // Show help if no args
        if (args.length == 0) {
            showAdminHelp(sender);
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "reload" -> {
                plugin.reload();
                sender.sendMessage(plugin.color(plugin.getLang().getString("messages.reload-success")));
            }
            case "analytics" -> {
                if (sender instanceof Player player) {
                    plugin.getGUIManager().openAnalytics(player);
                } else {
                    showConsoleAnalytics(sender);
                }
            }
            case "reasons" -> {
                if (sender instanceof Player player) {
                    plugin.getGUIManager().openReasonsEditor(player);
                } else {
                    sender.sendMessage(plugin.getPrefix() + "§cThis command can only be used in-game!");
                }
            }
            case "backup" -> handleBackup(sender, args);
            case "update" -> {
                if (sender instanceof Player player) {
                    plugin.getUpdateChecker().checkNow(player);
                } else {
                    // Console update check
                    if (plugin.getUpdateChecker().isUpdateAvailable()) {
                        sender.sendMessage("§aUpdate available! Latest version: " + plugin.getUpdateChecker().getLatestVersion());
                    } else {
                        sender.sendMessage("§aYou are running the latest version!");
                    }
                }
            }
            default -> sender.sendMessage(plugin.getPrefix() + "§cUnknown subcommand!");
        }

        return true;
    }

    private void showAdminHelp(CommandSender sender) {
        sender.sendMessage(plugin.getPrefix() + "§7QuickBanUI v" + plugin.getDescription().getVersion() + " by MrCappy");
        sender.sendMessage("§e/qb reload §7- Reload configuration");
        sender.sendMessage("§e/qb analytics §7- View punishment analytics");
        sender.sendMessage("§e/qb reasons §7- Edit punishment reasons");
        sender.sendMessage("§e/qb backup §7- Manage backups");
        sender.sendMessage("§e/qb update §7- Check for updates");
    }

    private void handleBackup(CommandSender sender, String[] args) {
        if (args.length == 1) {
            sender.sendMessage(plugin.getPrefix() + "§7Backup Commands:");
            sender.sendMessage("§e/qb backup create §7- Create a backup now");
            sender.sendMessage("§e/qb backup list §7- List available backups");
            sender.sendMessage("§e/qb backup restore <name> §7- Restore a backup");
            return;
        }

        switch (args[1].toLowerCase()) {
            case "create" -> {
                sender.sendMessage(plugin.getPrefix() + "§eCreating backup...");
                // Run async to avoid lag
                Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                    plugin.getBackupManager().createBackup();
                    sender.sendMessage(plugin.getPrefix() + "§aBackup created successfully!");
                });
            }
            case "list" -> {
                List<String> backups = plugin.getBackupManager().getAvailableBackups();
                if (backups.isEmpty()) {
                    sender.sendMessage(plugin.getPrefix() + "§cNo backups found!");
                } else {
                    sender.sendMessage(plugin.getPrefix() + "§7Available backups:");
                    for (String backup : backups) {
                        sender.sendMessage("§e- " + backup);
                    }
                }
            }
            case "restore" -> {
                if (args.length < 3) {
                    sender.sendMessage(plugin.getPrefix() + "§cUsage: /qb backup restore <name>");
                    return;
                }

                String backupName = args[2];
                sender.sendMessage(plugin.getPrefix() + "§eRestoring backup...");

                // TODO: Add confirmation for restore since it overwrites data
                Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                    if (plugin.getBackupManager().restoreBackup(backupName)) {
                        sender.sendMessage(plugin.getPrefix() + "§aBackup restored successfully! Please restart the server.");
                    } else {
                        sender.sendMessage(plugin.getPrefix() + "§cFailed to restore backup! Check console for details.");
                    }
                });
            }
            default -> sender.sendMessage(plugin.getPrefix() + "§cUnknown backup command!");
        }
    }

    private boolean handleUnban(CommandSender sender, String[] args) {
        if (!sender.hasPermission("quickban.unban")) {
            sender.sendMessage(plugin.color(plugin.getLang().getString("messages.no-permission")));
            return true;
        }

        if (args.length != 1) {
            sender.sendMessage(plugin.color(plugin.getLang().getString("messages.usage.unban")));
            return true;
        }

        OfflinePlayer target = Bukkit.getOfflinePlayer(args[0]);
        if (plugin.getPunishmentManager().removeBan(target.getUniqueId())) {
            sender.sendMessage(plugin.color(plugin.getLang().getString("messages.unban-success"))
                    .replace("%player%", target.getName()));

            // Notify staff if enabled
            if (plugin.getConfig().getBoolean("settings.broadcast-punishments")) {
                String msg = plugin.color(plugin.getLang().getString("messages.staff-unban-notification"))
                        .replace("%staff%", sender.getName())
                        .replace("%player%", target.getName());
                plugin.broadcast(msg, "quickban.notify");
            }

            // Log to Discord
            if (plugin.getDiscordWebhook().isEnabled()) {
                plugin.getDiscordWebhook().sendUnpunishment("UNBANNED", sender.getName(), target.getName());
            }

            // Log to file
            if (plugin.getFileLogger().isEnabled()) {
                plugin.getFileLogger().logUnpunishment("UNBAN", sender.getName(), target.getName());
            }
        } else {
            sender.sendMessage(plugin.color(plugin.getLang().getString("messages.player-not-banned")));
        }

        return true;
    }

    private boolean handleUnmute(CommandSender sender, String[] args) {
        if (!sender.hasPermission("quickban.unmute")) {
            sender.sendMessage(plugin.color(plugin.getLang().getString("messages.no-permission")));
            return true;
        }

        if (args.length != 1) {
            sender.sendMessage(plugin.color(plugin.getLang().getString("messages.usage.unmute")));
            return true;
        }

        OfflinePlayer target = Bukkit.getOfflinePlayer(args[0]);
        if (plugin.getPunishmentManager().removeMute(target.getUniqueId())) {
            sender.sendMessage(plugin.color(plugin.getLang().getString("messages.unmute-success"))
                    .replace("%player%", target.getName()));

            // Tell the player they're unmuted if online
            if (target.isOnline()) {
                target.getPlayer().sendMessage(plugin.color(plugin.getLang().getString("messages.target-unmuted")));
            }

            if (plugin.getConfig().getBoolean("settings.broadcast-punishments")) {
                String msg = plugin.color(plugin.getLang().getString("messages.staff-unmute-notification"))
                        .replace("%staff%", sender.getName())
                        .replace("%player%", target.getName());
                plugin.broadcast(msg, "quickban.notify");
            }

            // Discord webhook
            if (plugin.getDiscordWebhook().isEnabled()) {
                plugin.getDiscordWebhook().sendUnpunishment("UNMUTED", sender.getName(), target.getName());
            }

            // File logging
            if (plugin.getFileLogger().isEnabled()) {
                plugin.getFileLogger().logUnpunishment("UNMUTE", sender.getName(), target.getName());
            }
        } else {
            sender.sendMessage(plugin.color(plugin.getLang().getString("messages.player-not-muted")));
        }

        return true;
    }

    private boolean handleHistory(CommandSender sender, String[] args) {
        if (!sender.hasPermission("quickban.history")) {
            sender.sendMessage(plugin.color(plugin.getLang().getString("messages.no-permission")));
            return true;
        }

        if (args.length != 1) {
            sender.sendMessage(plugin.color(plugin.getLang().getString("messages.usage.history")));
            return true;
        }

        OfflinePlayer target = Bukkit.getOfflinePlayer(args[0]);

        if (!target.hasPlayedBefore() && !target.isOnline()) {
            sender.sendMessage(plugin.color(plugin.getLang().getString("messages.player-not-found"))
                    .replace("%player%", args[0]));
            return true;
        }

        // Players get GUI, console gets text
        if (sender instanceof Player player) {
            plugin.getGUIManager().openHistory(player, target);
        } else {
            showConsoleHistory(sender, target);
        }

        return true;
    }

    private void showConsoleHistory(CommandSender sender, OfflinePlayer target) {
        List<PunishmentManager.Punishment> history = plugin.getPunishmentManager().getHistory(target.getUniqueId());

        if (history.isEmpty()) {
            sender.sendMessage(plugin.color(plugin.getLang().getString("messages.no-history")));
            return;
        }

        sender.sendMessage("");
        sender.sendMessage(plugin.color(plugin.getLang().getString("messages.history-title"))
                .replace("%player%", target.getName()));
        sender.sendMessage("");

        String dateFormat = plugin.getConfig().getString("settings.date-format", "yyyy-MM-dd HH:mm");
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern(dateFormat);

        // Show each punishment
        for (var p : history) {
            LocalDateTime date = LocalDateTime.ofInstant(
                    Instant.ofEpochMilli(p.timestamp),
                    ZoneId.systemDefault()
            );

            String status = p.active ? "§a[ACTIVE]" : "§c[EXPIRED]";

            sender.sendMessage(String.format("§7%s %s §e%s",
                    date.format(formatter), status, p.type.getDisplay()));
            sender.sendMessage("  §7Reason: §f" + p.reason);
            sender.sendMessage("  §7Staff: §b" + p.staffName);

            // Show duration for bans/mutes
            if (p.type == PunishmentManager.PunishmentType.BAN ||
                p.type == PunishmentManager.PunishmentType.MUTE) {
                sender.sendMessage("  §7Duration: §e" +
                                   (p.isPermanent() ? "Permanent" : plugin.formatTime(p.expiry - p.timestamp)));
            }
            sender.sendMessage("");
        }

        sender.sendMessage("§7Total punishments: §e" + history.size());
    }

    private void showConsoleAnalytics(CommandSender sender) {
        var pm = plugin.getPunishmentManager();

        sender.sendMessage("");
        sender.sendMessage(plugin.color(plugin.getLang().getString("messages.analytics-title")));
        sender.sendMessage("");
        sender.sendMessage(plugin.color(plugin.getLang().getString("messages.analytics-total"))
                .replace("%amount%", String.valueOf(pm.getTotalPunishments())));
        sender.sendMessage("");

        // Punishment type breakdown
        sender.sendMessage("§ePunishment Types:");
        for (var entry : pm.getTypeStats().entrySet()) {
            sender.sendMessage("  §7" + entry.getKey().getDisplay() + "s: §f" + entry.getValue());
        }
        sender.sendMessage("");

        // Top staff members
        sender.sendMessage(plugin.color(plugin.getLang().getString("messages.analytics-staff-activity")));
        pm.getStaffStats().entrySet().stream()
                .sorted((a, b) -> b.getValue().compareTo(a.getValue()))
                .limit(5)
                .forEach(entry -> sender.sendMessage("  §b" + entry.getKey() + ": §f" + entry.getValue()));

        sender.sendMessage("");

        // Most punished players
        sender.sendMessage(plugin.color(plugin.getLang().getString("messages.analytics-top-players")));
        pm.getPlayerStats().entrySet().stream()
                .sorted((a, b) -> b.getValue().compareTo(a.getValue()))
                .limit(5)
                .forEach(entry -> sender.sendMessage("  §c" + entry.getKey() + ": §f" + entry.getValue()));
    }
}