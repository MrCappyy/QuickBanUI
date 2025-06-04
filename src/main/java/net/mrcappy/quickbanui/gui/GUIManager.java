package net.mrcappy.quickbanui.gui;

import net.mrcappy.quickbanui.QuickBanUI;
import net.mrcappy.quickbanui.QuickBanUI.*;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

public class GUIManager implements Listener {

    private final QuickBanUI plugin;
    private final Map<UUID, PunishmentSession> sessions = new HashMap<>();
    private final Map<UUID, ReasonEditSession> reasonEditSessions = new HashMap<>();

    public GUIManager(QuickBanUI plugin) {
        this.plugin = plugin;
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    // ========== MAIN MENU ==========
    public void openMainMenu(Player staff, OfflinePlayer target) {
        Inventory gui = Bukkit.createInventory(null, 45, "§8Punish » §c" + target.getName());

        // Player head (slot 4)
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) head.getItemMeta();
        meta.setOwningPlayer(target);
        meta.setDisplayName("§c" + target.getName());

        List<String> lore = new ArrayList<>();
        lore.add("§7UUID: §f" + target.getUniqueId().toString().substring(0, 8) + "...");
        lore.add("§7Status: " + (target.isOnline() ? "§aOnline" : "§cOffline"));
        if (plugin.isBanned(target.getUniqueId())) lore.add("§7Currently: §cBanned");
        if (plugin.isMuted(target.getUniqueId())) lore.add("§7Currently: §6Muted");
        lore.add("");
        lore.add("§e► Click for history");

        meta.setLore(lore);
        head.setItemMeta(meta);
        gui.setItem(4, head);

        // Punishment buttons
        gui.setItem(20, createItem(Material.BARRIER, "§c§lBAN",
                "§7Remove " + target.getName() + " from server", "", "§e► Click to ban"));

        gui.setItem(22, createItem(Material.PAPER, "§6§lMUTE",
                "§7Prevent " + target.getName() + " from chatting", "", "§e► Click to mute"));

        gui.setItem(24, createItem(Material.LEATHER_BOOTS, "§e§lKICK",
                "§7Remove " + target.getName() + " temporarily", "", "§e► Click to kick"));

        gui.setItem(30, createItem(Material.BOOK, "§a§lWARN",
                "§7Send warning to " + target.getName(), "", "§e► Click to warn"));

        gui.setItem(32, createItem(Material.WRITABLE_BOOK, "§b§lHISTORY",
                "§7View punishment history", "", "§e► Click to view"));

        fillEmpty(gui);
        staff.openInventory(gui);
    }

    // ========== PUNISHMENT MENU ==========
    public void openPunishmentMenu(Player staff, OfflinePlayer target, PunishmentType type) {
        PunishmentSession session = sessions.computeIfAbsent(staff.getUniqueId(),
                k -> new PunishmentSession());

        session.target = target;
        session.type = type;

        // Get customizable title from lang config
        String titleFormat = plugin.getLangConfig().getString("gui." + type.name().toLowerCase() + "-menu-title",
                "&8%type% » &c%player%");
        String title = titleFormat.replace("%type%", type.getDisplayName())
                .replace("%player%", target.getName()).replace("&", "§");

        Inventory gui = Bukkit.createInventory(null, 54, title);

        // Custom reason (slot 4)
        String customReasonName = plugin.getLangConfig().getString("gui.punishment.custom-reason.name", "&6&lCUSTOM REASON").replace("&", "§");
        String currentText = plugin.getLangConfig().getString("gui.punishment.custom-reason.current", "&7Current: &f%reason%")
                .replace("%reason%", session.reason.isEmpty() ? "None" : session.reason).replace("&", "§");
        String clickText = plugin.getLangConfig().getString("gui.punishment.custom-reason.click", "&e► Click to set").replace("&", "§");

        gui.setItem(4, createItem(Material.WRITABLE_BOOK, customReasonName,
                currentText, "", clickText));

        // Silent toggle (slot 8) - check if player has permission
        if (staff.hasPermission("quickban.silent")) {
            String silentName = plugin.getLangConfig().getString("gui.punishment.silent-mode.name-" + (session.silent ? "on" : "off"),
                    session.silent ? "&7&lSILENT &a&lON" : "&a&lSILENT &c&lOFF").replace("&", "§");
            String silentStatus = plugin.getLangConfig().getString("gui.punishment.silent-mode.status-" + (session.silent ? "on" : "off"),
                    "&7Broadcast: " + (session.silent ? "&cNo" : "&aYes")).replace("&", "§");

            gui.setItem(8, createItem(session.silent ? Material.GRAY_DYE : Material.LIME_DYE,
                    silentName, silentStatus, "", "§e► Click to toggle"));
        }

        // Quick reasons with custom materials from config
        String[] reasons = getReasons(type);
        List<String> materialNames = plugin.getConfig().getStringList("reason-materials." + type.name().toLowerCase());
        Material[] defaultMats = {Material.DIAMOND_SWORD, Material.POISONOUS_POTATO,
                Material.TNT, Material.PAPER, Material.EMERALD, Material.BOOK};

        int[] slots = {19, 20, 21, 28, 29, 30};

        for (int i = 0; i < Math.min(reasons.length, 6); i++) {
            Material mat = defaultMats[i % defaultMats.length];

            // Try to use custom material if specified
            if (i < materialNames.size()) {
                try {
                    mat = Material.valueOf(materialNames.get(i).toUpperCase());
                } catch (IllegalArgumentException ignored) {}
            }

            boolean selected = reasons[i].equals(session.reason);
            gui.setItem(slots[i], createItem(mat,
                    (selected ? "§a" : "§e") + reasons[i],
                    selected ? "§a✔ Selected" : "§7Click to select"));
        }

        // Duration (slot 13) - only for ban/mute
        if (type == PunishmentType.BAN || type == PunishmentType.MUTE) {
            // Load default duration from config
            if (session.duration.equals("7d")) { // If still default
                String configDefault = plugin.getConfig().getString("durations.defaults." + type.name().toLowerCase(), "7d");
                session.duration = configDefault;
            }

            Material mat = getDurationMaterial(session.duration);
            String color = getDurationColor(session.duration);

            gui.setItem(13, createItem(mat, color + "§l" + formatDuration(session.duration),
                    "§7Duration setting", "",
                    "§aLeft-click: §7Increase",
                    "§cRight-click: §7Decrease"));
        }

        // Confirm (slot 49)
        boolean ready = !session.reason.isEmpty();
        gui.setItem(49, createItem(ready ? Material.EMERALD_BLOCK : Material.REDSTONE_BLOCK,
                ready ? "§a§lCONFIRM" : "§c§lNOT READY",
                "§7Target: §f" + target.getName(),
                "§7Reason: §f" + (session.reason.isEmpty() ? "§cNone!" : session.reason),
                "§7Duration: §f" + formatDuration(session.duration),
                "", ready ? "§a► Click to execute!" : "§c► Set a reason!"));

        // Navigation
        gui.setItem(45, createItem(Material.ARROW, "§cBack"));
        gui.setItem(53, createItem(Material.BARRIER, "§cCancel"));

        fillEmpty(gui);
        staff.openInventory(gui);
    }

    // ========== HISTORY MENU ==========
    public void openHistoryMenu(Player staff, OfflinePlayer target) {
        Inventory gui = Bukkit.createInventory(null, 54, "§8History » §c" + target.getName());

        List<PunishmentRecord> history = plugin.getHistory(target.getUniqueId());
        history.sort((a, b) -> Long.compare(b.timestamp, a.timestamp));

        int slot = 0;
        for (PunishmentRecord record : history) {
            if (slot >= 45) break;

            Material mat = switch (record.type) {
                case BAN -> Material.BARRIER;
                case MUTE -> Material.PAPER;
                case KICK -> Material.LEATHER_BOOTS;
                case WARN -> Material.BOOK;
            };

            String timeAgo = getTimeAgo(record.timestamp);
            LocalDateTime date = LocalDateTime.ofInstant(
                    Instant.ofEpochMilli(record.timestamp),
                    ZoneId.systemDefault()
            );

            gui.setItem(slot++, createItem(mat,
                    "§c" + record.type.getDisplayName() + " §7- " + timeAgo,
                    "§7Reason: §f" + record.reason,
                    "§7Duration: §f" + (record.duration.equals("none") ? "N/A" : record.duration),
                    "§7Staff: §e" + record.staffName,
                    "§7Date: §f" + date.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")),
                    "", record.active ? "§a✓ Active" : "§c✗ Expired"));
        }

        if (history.isEmpty()) {
            gui.setItem(22, createItem(Material.BOOK, "§7Clean record!"));
        }

        gui.setItem(49, createItem(Material.ARROW, "§cBack"));

        fillEmpty(gui);
        staff.openInventory(gui);
    }

    // ========== REASONS EDITOR ==========
    public void openReasonsEditor(Player staff) {
        Inventory gui = Bukkit.createInventory(null, 36, "§8Reason Editor");

        // Punishment type selectors
        gui.setItem(10, createItem(Material.BARRIER, "§c§lBAN REASONS",
                "§7Edit ban reasons", "", "§e► Click to edit"));

        gui.setItem(12, createItem(Material.PAPER, "§6§lMUTE REASONS",
                "§7Edit mute reasons", "", "§e► Click to edit"));

        gui.setItem(14, createItem(Material.LEATHER_BOOTS, "§e§lKICK REASONS",
                "§7Edit kick reasons", "", "§e► Click to edit"));

        gui.setItem(16, createItem(Material.BOOK, "§a§lWARN REASONS",
                "§7Edit warn reasons", "", "§e► Click to edit"));

        // Info
        gui.setItem(22, createItem(Material.REDSTONE_TORCH, "§e§lINFORMATION",
                "§7Click a punishment type to edit",
                "§7its quick-select reasons.",
                "",
                "§7Changes are saved automatically."));

        // Back button
        gui.setItem(31, createItem(Material.ARROW, "§cBack"));

        fillEmpty(gui);
        staff.openInventory(gui);
    }

    public void openReasonsList(Player staff, PunishmentType type) {
        String title = "§8Edit " + type.getDisplayName() + " Reasons";
        Inventory gui = Bukkit.createInventory(null, 54, title);

        // Get current reasons
        List<String> reasons = plugin.getConfig().getStringList("punishment-reasons." + type.name().toLowerCase());

        // Display current reasons
        int slot = 0;
        for (String reason : reasons) {
            if (slot < 45) {
                gui.setItem(slot, createItem(Material.PAPER, "§e" + reason,
                        "§7Current reason #" + (slot + 1),
                        "",
                        "§aLeft-click: §7Edit",
                        "§cRight-click: §7Remove"));
                slot++;
            }
        }

        // Add new reason button
        gui.setItem(49, createItem(Material.EMERALD, "§a§lADD REASON",
                "§7Add a new reason", "", "§e► Click to add"));

        // Back button
        gui.setItem(53, createItem(Material.ARROW, "§cBack"));

        fillEmpty(gui);
        staff.openInventory(gui);
    }

    // ========== ANALYTICS MENU ==========
    public void openAnalyticsMenu(Player staff) {
        Inventory gui = Bukkit.createInventory(null, 54, "§8Analytics");

        // Calculate statistics
        Map<PunishmentType, Integer> typeCount = new HashMap<>();
        Map<String, Integer> staffCount = new HashMap<>();
        Map<String, Integer> playerCount = new HashMap<>();
        int totalPunishments = 0;
        int todayPunishments = 0;
        int weekPunishments = 0;

        long now = System.currentTimeMillis();
        long todayStart = getStartOfDay(now);
        long weekStart = todayStart - (7 * 24 * 60 * 60 * 1000L);

        // Process all records
        for (List<PunishmentRecord> records : plugin.getHistory().values()) {
            for (PunishmentRecord record : records) {
                totalPunishments++;

                // Count by type
                typeCount.merge(record.type, 1, Integer::sum);

                // Count by staff
                staffCount.merge(record.staffName, 1, Integer::sum);

                // Count by player
                playerCount.merge(record.playerName, 1, Integer::sum);

                // Time-based counts
                if (record.timestamp >= todayStart) todayPunishments++;
                if (record.timestamp >= weekStart) weekPunishments++;
            }
        }

        // Overview (slot 4)
        gui.setItem(4, createItem(Material.BOOK, "§6§lOVERVIEW",
                "§7Total punishments: §e" + totalPunishments,
                "§7Active bans: §c" + plugin.getActiveBansCount(),
                "§7Active mutes: §6" + plugin.getActiveMutesCount(),
                "",
                "§7Today: §a" + todayPunishments,
                "§7This week: §b" + weekPunishments));

        // Type breakdown (slots 19-22)
        int typeSlot = 19;
        for (PunishmentType type : PunishmentType.values()) {
            Material mat = switch (type) {
                case BAN -> Material.BARRIER;
                case MUTE -> Material.PAPER;
                case KICK -> Material.LEATHER_BOOTS;
                case WARN -> Material.BOOK;
            };

            int count = typeCount.getOrDefault(type, 0);
            float percentage = totalPunishments > 0 ? (count * 100f) / totalPunishments : 0;

            gui.setItem(typeSlot++, createItem(mat,
                    "§e" + type.getDisplayName() + "s",
                    "§7Total: §f" + count,
                    "§7Percentage: §f" + String.format("%.1f%%", percentage)));
        }

        // Top staff (slot 30)
        List<String> topStaffLore = new ArrayList<>();
        topStaffLore.add("§7Most active staff:");
        topStaffLore.add("");

        staffCount.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(5)
                .forEach(entry ->
                        topStaffLore.add("§b" + entry.getKey() + ": §f" + entry.getValue())
                );

        gui.setItem(30, createItem(Material.PLAYER_HEAD, "§b§lTOP STAFF",
                topStaffLore.toArray(new String[0])));

        // Most punished (slot 32)
        List<String> topPlayersLore = new ArrayList<>();
        topPlayersLore.add("§7Most punished players:");
        topPlayersLore.add("");

        playerCount.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(5)
                .forEach(entry ->
                        topPlayersLore.add("§c" + entry.getKey() + ": §f" + entry.getValue())
                );

        gui.setItem(32, createItem(Material.SKELETON_SKULL, "§c§lTOP OFFENDERS",
                topPlayersLore.toArray(new String[0])));

        // Graph visualization (bottom row)
        drawGraph(gui, typeCount, totalPunishments);

        // Back button
        gui.setItem(49, createItem(Material.ARROW, "§cBack"));

        fillEmpty(gui);
        staff.openInventory(gui);
    }

    // ========== CLICK HANDLER ==========
    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player player)) return;

        String title = e.getView().getTitle();
        if (!title.startsWith("§8")) return;

        e.setCancelled(true);

        if (e.getCurrentItem() == null || e.getCurrentItem().getType() == Material.AIR) return;

        int slot = e.getSlot();

        // Main menu
        if (title.contains("Punish »")) {
            String name = title.split("» §c")[1];
            OfflinePlayer target = Bukkit.getOfflinePlayer(name);

            switch (slot) {
                case 4 -> openHistoryMenu(player, target);
                case 20 -> openPunishmentMenu(player, target, PunishmentType.BAN);
                case 22 -> openPunishmentMenu(player, target, PunishmentType.MUTE);
                case 24 -> openPunishmentMenu(player, target, PunishmentType.KICK);
                case 30 -> openPunishmentMenu(player, target, PunishmentType.WARN);
                case 32 -> openHistoryMenu(player, target);
            }
            return;
        }

        // History menu
        if (title.contains("History »")) {
            if (slot == 49) {
                String name = title.split("» §c")[1];
                openMainMenu(player, Bukkit.getOfflinePlayer(name));
            }
            return;
        }

        // Reason Editor menu
        if (title.equals("§8Reason Editor")) {
            switch (slot) {
                case 10 -> openReasonsList(player, PunishmentType.BAN);
                case 12 -> openReasonsList(player, PunishmentType.MUTE);
                case 14 -> openReasonsList(player, PunishmentType.KICK);
                case 16 -> openReasonsList(player, PunishmentType.WARN);
                case 31 -> player.closeInventory();
            }
            return;
        }

        // Reasons List menu
        if (title.startsWith("§8Edit ") && title.contains(" Reasons")) {
            // Extract type from title
            PunishmentType type = null;
            for (PunishmentType pt : PunishmentType.values()) {
                if (title.contains(pt.getDisplayName())) {
                    type = pt;
                    break;
                }
            }

            if (type != null) {
                if (slot == 53) { // Back
                    openReasonsEditor(player);
                } else if (slot == 49) { // Add new
                    ReasonEditSession editSession = new ReasonEditSession();
                    editSession.type = type;
                    editSession.action = ReasonEditAction.ADD;
                    reasonEditSessions.put(player.getUniqueId(), editSession);

                    player.closeInventory();
                    player.sendMessage("§eType the new reason in chat (or 'cancel'):");
                } else if (slot < 45 && e.getCurrentItem() != null && e.getCurrentItem().getType() == Material.PAPER) {
                    // Edit or remove existing reason
                    List<String> reasons = plugin.getConfig().getStringList("punishment-reasons." + type.name().toLowerCase());
                    if (slot < reasons.size()) {
                        if (e.isLeftClick()) { // Edit
                            ReasonEditSession editSession = new ReasonEditSession();
                            editSession.type = type;
                            editSession.action = ReasonEditAction.EDIT;
                            editSession.index = slot;
                            editSession.oldReason = reasons.get(slot);
                            reasonEditSessions.put(player.getUniqueId(), editSession);

                            player.closeInventory();
                            player.sendMessage("§eType the new reason in chat (or 'cancel'):");
                            player.sendMessage("§7Current: §f" + reasons.get(slot));
                        } else if (e.isRightClick()) { // Remove
                            reasons.remove(slot);
                            plugin.getConfig().set("punishment-reasons." + type.name().toLowerCase(), reasons);
                            plugin.saveConfig();
                            openReasonsList(player, type);
                        }
                    }
                }
            }
            return;
        }

        // Analytics menu
        if (title.equals("§8Analytics")) {
            if (slot == 49) {
                player.closeInventory();
            }
            return;
        }

        // Punishment menu
        PunishmentSession session = sessions.get(player.getUniqueId());
        if (session == null) return;

        switch (slot) {
            case 4 -> { // Custom reason
                player.closeInventory();
                session.awaitingInput = true;
                player.sendMessage("§6Type the reason in chat (or 'cancel'):");
            }

            case 8 -> { // Silent toggle
                session.silent = !session.silent;
                openPunishmentMenu(player, session.target, session.type);
            }

            case 13 -> { // Duration
                if (session.type == PunishmentType.BAN || session.type == PunishmentType.MUTE) {
                    // Load durations from config
                    List<String> configDurations = plugin.getConfig().getStringList("durations.options");
                    String[] durations = configDurations.isEmpty() ?
                            new String[]{"30m", "1h", "3h", "1d", "3d", "7d", "30d", "permanent"} :
                            configDurations.toArray(new String[0]);

                    int idx = Arrays.asList(durations).indexOf(session.duration);
                    if (idx == -1) idx = 0; // Default to first if not found

                    if (e.isLeftClick()) {
                        idx = (idx + 1) % durations.length;
                    } else if (e.isRightClick()) {
                        idx = (idx - 1 + durations.length) % durations.length;
                    }

                    session.duration = durations[idx];
                    openPunishmentMenu(player, session.target, session.type);
                }
            }

            case 19, 20, 21, 28, 29, 30 -> { // Quick reasons
                String reason = ChatColor.stripColor(e.getCurrentItem().getItemMeta().getDisplayName());
                session.reason = session.reason.equals(reason) ? "" : reason;
                openPunishmentMenu(player, session.target, session.type);
            }

            case 45 -> { // Back
                sessions.remove(player.getUniqueId());
                openMainMenu(player, session.target);
            }

            case 49 -> { // Confirm
                if (!session.reason.isEmpty()) {
                    player.closeInventory();
                    sessions.remove(player.getUniqueId());

                    String dur = (session.type == PunishmentType.KICK ||
                            session.type == PunishmentType.WARN) ? "none" : session.duration;

                    plugin.executePunishment(player, session.target, session.type,
                            session.reason, dur, session.silent);
                }
            }

            case 53 -> { // Cancel
                sessions.remove(player.getUniqueId());
                player.closeInventory();
                player.sendMessage(plugin.getPrefix() + "§cCancelled.");
            }
        }
    }

    // ========== CHAT HANDLER ==========
    @EventHandler
    public void onChat(AsyncPlayerChatEvent e) {
        // Handle punishment session input
        PunishmentSession session = sessions.get(e.getPlayer().getUniqueId());
        if (session != null && session.awaitingInput) {
            e.setCancelled(true);
            session.awaitingInput = false;

            if (!e.getMessage().equalsIgnoreCase("cancel")) {
                session.reason = e.getMessage();
            }

            Bukkit.getScheduler().runTask(plugin, () ->
                    openPunishmentMenu(e.getPlayer(), session.target, session.type)
            );
            return;
        }

        // Handle reason edit session input
        ReasonEditSession editSession = reasonEditSessions.get(e.getPlayer().getUniqueId());
        if (editSession != null) {
            e.setCancelled(true);
            reasonEditSessions.remove(e.getPlayer().getUniqueId());

            if (!e.getMessage().equalsIgnoreCase("cancel")) {
                List<String> reasons = plugin.getConfig().getStringList("punishment-reasons." + editSession.type.name().toLowerCase());

                switch (editSession.action) {
                    case ADD -> reasons.add(e.getMessage());
                    case EDIT -> {
                        if (editSession.index < reasons.size()) {
                            reasons.set(editSession.index, e.getMessage());
                        }
                    }
                }

                plugin.getConfig().set("punishment-reasons." + editSession.type.name().toLowerCase(), reasons);
                plugin.saveConfig();

                e.getPlayer().sendMessage(plugin.getPrefix() + "§aReason updated successfully!");
            }

            Bukkit.getScheduler().runTask(plugin, () ->
                    openReasonsList(e.getPlayer(), editSession.type)
            );
        }
    }

    // ========== UTILITIES ==========
    private ItemStack createItem(Material mat, String name, String... lore) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(name);
        if (lore.length > 0) meta.setLore(Arrays.asList(lore));
        item.setItemMeta(meta);
        return item;
    }

    private void fillEmpty(Inventory inv) {
        ItemStack filler = createItem(Material.GRAY_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < inv.getSize(); i++) {
            if (inv.getItem(i) == null) inv.setItem(i, filler);
        }
    }

    private String[] getReasons(PunishmentType type) {
        // Try to load from config first
        List<String> configReasons = plugin.getConfig().getStringList("punishment-reasons." + type.name().toLowerCase());

        if (!configReasons.isEmpty()) {
            return configReasons.toArray(new String[0]);
        }

        // Default reasons if not in config
        return switch (type) {
            case BAN -> new String[]{"Hacking", "Griefing", "Ban Evasion", "Exploiting", "Toxicity", "Scamming"};
            case MUTE -> new String[]{"Spam", "Toxicity", "Advertising", "Caps", "Swearing", "Harassment"};
            case KICK -> new String[]{"AFK", "Inappropriate", "Server Full", "Restart", "Test", "Warning"};
            case WARN -> new String[]{"Minor Rule Break", "Language", "Behavior", "Build", "Chat", "Other"};
        };
    }

    private Material getDurationMaterial(String dur) {
        return switch (dur) {
            case "30m" -> Material.LIME_WOOL;
            case "1h" -> Material.YELLOW_WOOL;
            case "3h" -> Material.ORANGE_WOOL;
            case "1d", "3d" -> Material.RED_WOOL;
            case "7d" -> Material.PURPLE_WOOL;
            case "30d" -> Material.BLACK_WOOL;
            case "permanent" -> Material.BEDROCK;
            default -> Material.WHITE_WOOL;
        };
    }

    private String getDurationColor(String dur) {
        return switch (dur) {
            case "30m" -> "§a";
            case "1h" -> "§e";
            case "3h" -> "§6";
            case "1d", "3d" -> "§c";
            case "7d" -> "§5";
            case "30d" -> "§8";
            case "permanent" -> "§4";
            default -> "§f";
        };
    }

    private String formatDuration(String dur) {
        return switch (dur) {
            case "30m" -> "30 Minutes";
            case "1h" -> "1 Hour";
            case "3h" -> "3 Hours";
            case "1d" -> "1 Day";
            case "3d" -> "3 Days";
            case "7d" -> "7 Days";
            case "30d" -> "30 Days";
            case "permanent" -> "Permanent";
            default -> "Not Set";
        };
    }

    private String getTimeAgo(long timestamp) {
        long diff = System.currentTimeMillis() - timestamp;
        long seconds = diff / 1000;
        long minutes = seconds / 60;
        long hours = minutes / 60;
        long days = hours / 24;

        if (days > 0) return days + "d ago";
        if (hours > 0) return hours + "h ago";
        if (minutes > 0) return minutes + "m ago";
        return seconds + "s ago";
    }

    private long getStartOfDay(long timestamp) {
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(timestamp);
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        return cal.getTimeInMillis();
    }

    private void drawGraph(Inventory gui, Map<PunishmentType, Integer> typeCount, int total) {
        if (total == 0) return;

        int[] slots = {45, 46, 47, 48, 50, 51, 52, 53};
        int slotIndex = 0;

        for (PunishmentType type : PunishmentType.values()) {
            if (slotIndex >= slots.length) break;

            int count = typeCount.getOrDefault(type, 0);
            float percentage = (count * 100f) / total;

            Material mat = Material.WHITE_STAINED_GLASS_PANE;
            if (percentage > 40) mat = Material.RED_STAINED_GLASS_PANE;
            else if (percentage > 25) mat = Material.ORANGE_STAINED_GLASS_PANE;
            else if (percentage > 10) mat = Material.YELLOW_STAINED_GLASS_PANE;
            else if (percentage > 0) mat = Material.LIME_STAINED_GLASS_PANE;

            gui.setItem(slots[slotIndex++], createItem(mat,
                    "§f" + type.getDisplayName(),
                    "§7" + String.format("%.1f%%", percentage)));

            if (slotIndex < slots.length) {
                gui.setItem(slots[slotIndex++], createItem(Material.GRAY_STAINED_GLASS_PANE, " "));
            }
        }
    }

    // ========== SESSION CLASSES ==========
    private static class PunishmentSession {
        OfflinePlayer target;
        PunishmentType type;
        String reason = "";
        String duration = "7d";
        boolean silent = false;
        boolean awaitingInput = false;
    }

    private static class ReasonEditSession {
        PunishmentType type;
        ReasonEditAction action;
        int index;
        String oldReason;
    }

    private enum ReasonEditAction {
        ADD, EDIT
    }
}