package net.mrcappy.quickbanui;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class PunishmentManager {

    private final QuickBanUI plugin;
    private final Storage storage;

    // Store all punishment history by player UUID
    private final Map<UUID, List<Punishment>> history = new ConcurrentHashMap<>();

    // Quick lookup for active punishments
    private final Map<UUID, Punishment> activeBans = new ConcurrentHashMap<>();
    private final Map<UUID, Punishment> activeMutes = new ConcurrentHashMap<>();

    public PunishmentManager(QuickBanUI plugin, Storage storage) {
        this.plugin = plugin;
        this.storage = storage;
        loadPunishments();
    }

    private void loadPunishments() {
        // Load all punishments from storage
        for (var entry : storage.getAllPunishments().entrySet()) {
            UUID uuid = entry.getKey();
            history.put(uuid, new ArrayList<>(entry.getValue()));

            // Cache active punishments for quick lookup
            for (Punishment p : entry.getValue()) {
                if (p.active && !p.isExpired()) {
                    switch (p.type) {
                        case BAN -> activeBans.put(uuid, p);
                        case MUTE -> activeMutes.put(uuid, p);
                    }
                }
            }
        }
    }

    public void punish(Player staff, OfflinePlayer target, PunishmentType type, String reason, String duration, boolean silent) {
        // Check cooldown first
        if (!plugin.checkCooldown(staff)) {
            return;
        }

        // Make sure reason isn't too long
        if (!plugin.checkReasonLength(reason)) {
            staff.sendMessage(plugin.getPrefix() + "§cReason is too long! Maximum: " +
                              plugin.getConfig().getInt("advanced.max-reason-length", 100) + " characters");
            return;
        }

        long expiry = parseDuration(duration);

        Punishment punishment = new Punishment(
                target.getUniqueId(),
                target.getName(),
                type,
                reason,
                staff.getUniqueId(),
                staff.getName(),
                System.currentTimeMillis(),
                expiry,
                true
        );

        // Add to history
        history.computeIfAbsent(target.getUniqueId(), k -> new ArrayList<>()).add(punishment);
        storage.addPunishment(punishment);

        // Apply the punishment
        applyPunishment(punishment, target, staff, duration, silent);

        // Send success message to staff
        String successMsg = plugin.color(plugin.getLang().getString("messages.punishment-success"))
                .replace("%type%", type.getPast())
                .replace("%player%", target.getName());
        staff.sendMessage(successMsg);

        // Broadcast to other staff if enabled
        if (!silent && plugin.getConfig().getBoolean("settings.broadcast-punishments")) {
            String broadcastMsg = plugin.color(plugin.getLang().getString("messages.staff-notification"))
                    .replace("%staff%", staff.getName())
                    .replace("%type%", type.getPast())
                    .replace("%player%", target.getName())
                    .replace("%reason%", reason);

            plugin.broadcast(broadcastMsg, "quickban.notify");
        }

        // Console logging
        if (plugin.getConfig().getBoolean("settings.log-to-console")) {
            plugin.getLogger().info(String.format("[PUNISHMENT] %s %s %s for: %s (%s)",
                    staff.getName(), type.getPast(), target.getName(), reason,
                    duration.equals("none") ? "N/A" : duration));
        }

        // External logging
        handleExternalLogging(punishment, staff.getName(), target.getName());
    }

    private void applyPunishment(Punishment punishment, OfflinePlayer target, Player staff, String duration, boolean silent) {
        switch (punishment.type) {
            case BAN -> handleBan(punishment, target, staff);
            case MUTE -> handleMute(punishment, target);
            case KICK -> handleKick(target, punishment.reason, staff.getName());
            case WARN -> handleWarn(target, punishment.reason, staff.getName());
        }
    }

    private void handleBan(Punishment punishment, OfflinePlayer target, Player staff) {
        activeBans.put(target.getUniqueId(), punishment);

        if (target.isOnline()) {
            // Build kick message
            String msg = buildBanMessage(punishment);

            // Schedule kick on main thread
            Bukkit.getScheduler().runTask(plugin, () ->
                    target.getPlayer().kickPlayer(msg));
        }
    }

    private void handleMute(Punishment punishment, OfflinePlayer target) {
        activeMutes.put(target.getUniqueId(), punishment);

        if (target.isOnline()) {
            Player p = target.getPlayer();
            p.sendMessage(plugin.color(plugin.getLang().getString("punishment-screens.mute.chat-blocked")));
            p.sendMessage(plugin.color(plugin.getLang().getString("punishment-screens.mute.reason")).replace("%reason%", punishment.reason));

            if (punishment.isPermanent()) {
                p.sendMessage(plugin.color(plugin.getLang().getString("punishment-screens.mute.duration-permanent")));
            } else {
                p.sendMessage(plugin.color(plugin.getLang().getString("punishment-screens.mute.time-left"))
                        .replace("%time%", plugin.formatTime(punishment.getRemaining())));
            }
        }
    }

    private void handleKick(OfflinePlayer target, String reason, String staffName) {
        if (!target.isOnline()) {
            // Can't kick offline player
            return;
        }

        String msg = plugin.color(plugin.getLang().getString("punishment-screens.kick.title")) + "\n\n" +
                     plugin.color(plugin.getLang().getString("punishment-screens.kick.reason")).replace("%reason%", reason) + "\n" +
                     plugin.color(plugin.getLang().getString("punishment-screens.kick.staff")).replace("%staff%", staffName);

        Bukkit.getScheduler().runTask(plugin, () ->
                target.getPlayer().kickPlayer(msg));
    }

    private void handleWarn(OfflinePlayer target, String reason, String staffName) {
        if (!target.isOnline()) {
            return;
        }

        Player p = target.getPlayer();
        String border = plugin.color(plugin.getLang().getString("punishment-screens.warn.border"));

        // Send warning message
        p.sendMessage("");
        p.sendMessage(border);
        p.sendMessage(plugin.color(plugin.getLang().getString("punishment-screens.warn.title")));
        p.sendMessage("");
        p.sendMessage(plugin.color(plugin.getLang().getString("punishment-screens.warn.staff")).replace("%staff%", staffName));
        p.sendMessage(plugin.color(plugin.getLang().getString("punishment-screens.warn.reason")).replace("%reason%", reason));
        p.sendMessage("");
        p.sendMessage(border);
    }

    private String buildBanMessage(Punishment punishment) {
        StringBuilder msg = new StringBuilder();
        msg.append(plugin.color(plugin.getLang().getString("punishment-screens.ban.title"))).append("\n\n");
        msg.append(plugin.color(plugin.getLang().getString("punishment-screens.ban.reason"))
                .replace("%reason%", punishment.reason)).append("\n");
        msg.append(plugin.color(plugin.getLang().getString("punishment-screens.ban.staff"))
                .replace("%staff%", punishment.staffName)).append("\n");

        if (punishment.isPermanent()) {
            msg.append(plugin.color(plugin.getLang().getString("punishment-screens.ban.duration-permanent")));
        } else {
            msg.append(plugin.color(plugin.getLang().getString("punishment-screens.ban.duration-temp"))
                    .replace("%duration%", plugin.formatTime(punishment.getRemaining())));
        }

        return msg.toString();
    }

    private void handleExternalLogging(Punishment punishment, String staffName, String targetName) {
        // Discord webhook
        if (plugin.getDiscordWebhook().isEnabled()) {
            plugin.getDiscordWebhook().sendPunishment(punishment, staffName, targetName);
        }

        // File logging
        if (plugin.getFileLogger().isEnabled()) {
            plugin.getFileLogger().logPunishment(punishment, staffName, targetName);
        }
    }

    public boolean removeBan(UUID uuid) {
        Punishment ban = activeBans.remove(uuid);
        if (ban != null) {
            ban.active = false;
            storage.save();
            return true;
        }
        return false;
    }

    public boolean removeMute(UUID uuid) {
        Punishment mute = activeMutes.remove(uuid);
        if (mute != null) {
            mute.active = false;
            storage.save();
            return true;
        }
        return false;
    }

    public void checkExpired() {
        long now = System.currentTimeMillis();

        // Check bans
        activeBans.entrySet().removeIf(entry -> {
            Punishment p = entry.getValue();
            if (!p.isPermanent() && p.expiry <= now) {
                p.active = false;
                storage.save();

                // Log expiration
                if (plugin.getFileLogger().isEnabled()) {
                    plugin.getFileLogger().logAction("BAN_EXPIRED",
                            "Player: " + p.playerName + " | Original reason: " + p.reason);
                }

                return true;
            }
            return false;
        });

        // Check mutes
        activeMutes.entrySet().removeIf(entry -> {
            Punishment p = entry.getValue();
            if (!p.isPermanent() && p.expiry <= now) {
                p.active = false;

                // Notify player if online
                Player player = Bukkit.getPlayer(entry.getKey());
                if (player != null) {
                    player.sendMessage(plugin.color(plugin.getLang().getString("punishment-screens.mute.expired")));
                }

                storage.save();

                // Log expiration
                if (plugin.getFileLogger().isEnabled()) {
                    plugin.getFileLogger().logAction("MUTE_EXPIRED",
                            "Player: " + p.playerName + " | Original reason: " + p.reason);
                }

                return true;
            }
            return false;
        });
    }

    private long parseDuration(String duration) {
        if (duration.equalsIgnoreCase("permanent") || duration.equalsIgnoreCase("perm") || duration.equals("none")) {
            return -1;
        }

        try {
            // Extract number and unit
            String number = duration.substring(0, duration.length() - 1);
            char unit = duration.charAt(duration.length() - 1);
            long value = Long.parseLong(number);

            // Convert to milliseconds
            long millis = value * switch (unit) {
                case 's' -> 1000L;                           // seconds
                case 'm' -> 60 * 1000L;                      // minutes
                case 'h' -> 60 * 60 * 1000L;                 // hours
                case 'd' -> 24 * 60 * 60 * 1000L;            // days
                case 'w' -> 7 * 24 * 60 * 60 * 1000L;        // weeks
                case 'M' -> 30 * 24 * 60 * 60 * 1000L;       // months (30 days)
                case 'y' -> 365 * 24 * 60 * 60 * 1000L;      // years
                default -> 1000L; // Default to seconds if unknown
            };

            return System.currentTimeMillis() + millis;
        } catch (Exception e) {
            // Invalid format, return permanent
            return -1;
        }
    }

    // Getters for history and stats
    public List<Punishment> getHistory(UUID uuid) {
        return new ArrayList<>(history.getOrDefault(uuid, new ArrayList<>()));
    }

    public Map<UUID, List<Punishment>> getAllHistory() {
        return new HashMap<>(history);
    }

    public Punishment getActiveBan(UUID uuid) { return activeBans.get(uuid); }
    public Punishment getActiveMute(UUID uuid) { return activeMutes.get(uuid); }
    public boolean isBanned(UUID uuid) { return activeBans.containsKey(uuid); }
    public boolean isMuted(UUID uuid) { return activeMutes.containsKey(uuid); }

    // Statistics methods
    public int getTotalPunishments() {
        return history.values().stream().mapToInt(List::size).sum();
    }

    public Map<PunishmentType, Integer> getTypeStats() {
        Map<PunishmentType, Integer> stats = new HashMap<>();
        for (List<Punishment> list : history.values()) {
            for (Punishment p : list) {
                stats.merge(p.type, 1, Integer::sum);
            }
        }
        return stats;
    }

    public Map<String, Integer> getStaffStats() {
        Map<String, Integer> stats = new HashMap<>();
        for (List<Punishment> list : history.values()) {
            for (Punishment p : list) {
                stats.merge(p.staffName, 1, Integer::sum);
            }
        }
        return stats;
    }

    public Map<String, Integer> getPlayerStats() {
        Map<String, Integer> stats = new HashMap<>();
        for (List<Punishment> list : history.values()) {
            for (Punishment p : list) {
                stats.merge(p.playerName, 1, Integer::sum);
            }
        }
        return stats;
    }

    // TODO: Add method to get punishments by date range for better analytics

    public enum PunishmentType {
        BAN("Ban", "banned"),
        MUTE("Mute", "muted"),
        KICK("Kick", "kicked"),
        WARN("Warn", "warned");

        private final String display;
        private final String past;

        PunishmentType(String display, String past) {
            this.display = display;
            this.past = past;
        }

        public String getDisplay() { return display; }
        public String getPast() { return past; }
    }

    public static class Punishment {
        public final UUID playerUuid;
        public final String playerName;
        public final PunishmentType type;
        public final String reason;
        public final UUID staffUuid;
        public final String staffName;
        public final long timestamp;
        public final long expiry;
        public boolean active;

        public Punishment(UUID playerUuid, String playerName, PunishmentType type, String reason,
                          UUID staffUuid, String staffName, long timestamp, long expiry, boolean active) {
            this.playerUuid = playerUuid;
            this.playerName = playerName;
            this.type = type;
            this.reason = reason;
            this.staffUuid = staffUuid;
            this.staffName = staffName;
            this.timestamp = timestamp;
            this.expiry = expiry;
            this.active = active;
        }

        public boolean isPermanent() {
            return expiry == -1;
        }

        public boolean isExpired() {
            return !isPermanent() && System.currentTimeMillis() > expiry;
        }

        public long getRemaining() {
            if (isPermanent()) return -1;
            return Math.max(0, expiry - System.currentTimeMillis());
        }
    }
}