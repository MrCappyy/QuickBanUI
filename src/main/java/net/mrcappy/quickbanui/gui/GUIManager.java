package net.mrcappy.quickbanui.gui;

import net.mrcappy.quickbanui.QuickBanUI;
import net.mrcappy.quickbanui.PunishmentManager;
import net.mrcappy.quickbanui.PunishmentManager.PunishmentType;
import net.mrcappy.quickbanui.PunishmentManager.Punishment;
import org.bukkit.Bukkit;
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

    // Track active sessions
    private final Map<UUID, Session> sessions = new HashMap<>();
    private final Map<UUID, ReasonEditSession> reasonEditSessions = new HashMap<>();

    public GUIManager(QuickBanUI plugin) {
        this.plugin = plugin;
    }

    public void openMainMenu(Player staff, OfflinePlayer target) {
        String title = plugin.color(plugin.getLang().getString("gui.main.title", "&8Punish » &c%player%"))
                .replace("%player%", target.getName());
        Inventory inv = Bukkit.createInventory(null, 45, title);

        // Player head with info
        if (plugin.getConfig().getBoolean("gui.show-player-heads", true)) {
            ItemStack head = createPlayerHead(target);
            inv.setItem(4, head);
        }

        // Punishment buttons
        inv.setItem(20, createButton("ban", target.getName(), Material.BARRIER));
        inv.setItem(22, createButton("mute", target.getName(), Material.PAPER));
        inv.setItem(24, createButton("kick", target.getName(), Material.LEATHER_BOOTS));
        inv.setItem(30, createButton("warn", target.getName(), Material.BOOK));
        inv.setItem(32, createButton("history", target.getName(), Material.WRITABLE_BOOK));

        fillEmpty(inv);
        staff.openInventory(inv);
    }

    private ItemStack createPlayerHead(OfflinePlayer target) {
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) head.getItemMeta();
        meta.setOwningPlayer(target);
        meta.setDisplayName(plugin.color(plugin.getLang().getString("gui.main.player-info.name"))
                .replace("%player%", target.getName()));

        List<String> lore = new ArrayList<>();

        // Show UUID (first 8 chars for readability)
        lore.add(plugin.color(plugin.getLang().getString("gui.main.player-info.uuid"))
                .replace("%uuid%", target.getUniqueId().toString().substring(0, 8)));

        // Online status
        lore.add(target.isOnline() ?
                plugin.color(plugin.getLang().getString("gui.main.player-info.status-online")) :
                plugin.color(plugin.getLang().getString("gui.main.player-info.status-offline")));

        // Current punishments
        if (plugin.getPunishmentManager().isBanned(target.getUniqueId())) {
            lore.add(plugin.color(plugin.getLang().getString("gui.main.player-info.currently-banned")));
        }
        if (plugin.getPunishmentManager().isMuted(target.getUniqueId())) {
            lore.add(plugin.color(plugin.getLang().getString("gui.main.player-info.currently-muted")));
        }

        lore.add("");
        lore.add(plugin.color(plugin.getLang().getString("gui.main.player-info.click-history")));

        meta.setLore(lore);
        head.setItemMeta(meta);
        return head;
    }

    public void openPunishmentMenu(Player staff, OfflinePlayer target, PunishmentType type) {
        Session session = sessions.computeIfAbsent(staff.getUniqueId(), k -> new Session());
        session.target = target;
        session.type = type;

        String title = plugin.color(plugin.getLang().getString("gui.punishment.title"))
                .replace("%type%", type.getDisplay())
                .replace("%player%", target.getName());

        Inventory inv = Bukkit.createInventory(null, 54, title);

        // Custom reason book
        inv.setItem(4, createCustomReasonItem(session));

        // Silent mode toggle (if they have permission)
        if (staff.hasPermission("quickban.silent")) {
            inv.setItem(8, createSilentModeItem(session));
        }

        // Quick reasons
        addQuickReasons(inv, type, session);

        // Duration selector for bans/mutes
        if (type == PunishmentType.BAN || type == PunishmentType.MUTE) {
            if (session.duration == null || session.duration.isEmpty()) {
                session.duration = plugin.getConfig().getString("durations.defaults." + type.name().toLowerCase(), "7d");
            }
            inv.setItem(13, createDurationItem(session));
        }

        // Confirm button
        inv.setItem(49, createConfirmButton(session, target));

        // Navigation
        inv.setItem(45, createSimpleButton("gui.punishment.back", Material.ARROW));
        inv.setItem(53, createSimpleButton("gui.punishment.cancel", Material.BARRIER));

        fillEmpty(inv);
        staff.openInventory(inv);
    }

    private ItemStack createCustomReasonItem(Session session) {
        ItemStack customReason = new ItemStack(Material.WRITABLE_BOOK);
        ItemMeta customMeta = customReason.getItemMeta();
        customMeta.setDisplayName(plugin.color(plugin.getLang().getString("gui.punishment.custom-reason.name")));

        List<String> customLore = new ArrayList<>();
        customLore.add(plugin.color(plugin.getLang().getString("gui.punishment.custom-reason.current"))
                .replace("%reason%", session.reason.isEmpty() ?
                        plugin.getLang().getString("gui.punishment.custom-reason.none") : session.reason));
        customLore.add("");
        customLore.add(plugin.color(plugin.getLang().getString("gui.punishment.custom-reason.click")));
        customMeta.setLore(customLore);
        customReason.setItemMeta(customMeta);
        return customReason;
    }

    private ItemStack createSilentModeItem(Session session) {
        Material silentMat = session.silent ? Material.GRAY_DYE : Material.LIME_DYE;
        ItemStack silent = new ItemStack(silentMat);
        ItemMeta silentMeta = silent.getItemMeta();

        silentMeta.setDisplayName(plugin.color(plugin.getLang().getString(
                "gui.punishment.silent-mode.name-" + (session.silent ? "on" : "off"))));

        List<String> silentLore = Arrays.asList(
                plugin.color(plugin.getLang().getString(
                        "gui.punishment.silent-mode.status-" + (session.silent ? "on" : "off"))),
                "",
                plugin.color(plugin.getLang().getString("gui.punishment.silent-mode.click"))
        );
        silentMeta.setLore(silentLore);
        silent.setItemMeta(silentMeta);
        return silent;
    }

    private void addQuickReasons(Inventory inv, PunishmentType type, Session session) {
        List<String> reasons = plugin.getConfig().getStringList("punishment-reasons." + type.name().toLowerCase());
        List<String> materials = plugin.getConfig().getStringList("reason-materials." + type.name().toLowerCase());
        int[] slots = {19, 20, 21, 28, 29, 30};

        for (int i = 0; i < Math.min(reasons.size(), slots.length); i++) {
            Material mat = Material.PAPER;

            // Try to get the material
            if (i < materials.size()) {
                try {
                    mat = Material.valueOf(materials.get(i));
                } catch (Exception ignored) {
                    // Fallback to paper if invalid material
                }
            }

            boolean selected = reasons.get(i).equals(session.reason);
            ItemStack item = new ItemStack(mat);
            ItemMeta meta = item.getItemMeta();
            meta.setDisplayName((selected ? "§a" : "§e") + reasons.get(i));
            meta.setLore(Arrays.asList(selected ? "§a✔ Selected" : "§7Click to select"));
            item.setItemMeta(meta);
            inv.setItem(slots[i], item);
        }
    }

    private ItemStack createDurationItem(Session session) {
        ItemStack duration = new ItemStack(Material.CLOCK);
        ItemMeta durMeta = duration.getItemMeta();
        durMeta.setDisplayName(plugin.color(plugin.getLang().getString("gui.punishment.duration.name"))
                .replace("%duration%", formatDuration(session.duration)));

        List<String> durLore = Arrays.asList(
                plugin.color(plugin.getLang().getString("gui.punishment.duration.current")),
                "",
                plugin.color(plugin.getLang().getString("gui.punishment.duration.left-click")),
                plugin.color(plugin.getLang().getString("gui.punishment.duration.right-click"))
        );
        durMeta.setLore(durLore);
        duration.setItemMeta(durMeta);
        return duration;
    }

    private ItemStack createConfirmButton(Session session, OfflinePlayer target) {
        boolean ready = !session.reason.isEmpty();
        Material confirmMat = ready ? Material.EMERALD_BLOCK : Material.REDSTONE_BLOCK;
        ItemStack confirm = new ItemStack(confirmMat);
        ItemMeta confirmMeta = confirm.getItemMeta();

        confirmMeta.setDisplayName(plugin.color(plugin.getLang().getString(
                "gui.punishment.confirm.name-" + (ready ? "ready" : "not-ready"))));

        List<String> confirmLore = new ArrayList<>();
        confirmLore.add(plugin.color(plugin.getLang().getString("gui.punishment.confirm.target"))
                .replace("%player%", target.getName()));
        confirmLore.add(plugin.color(plugin.getLang().getString("gui.punishment.confirm.reason"))
                .replace("%reason%", session.reason.isEmpty() ?
                        plugin.getLang().getString("gui.punishment.confirm.reason-not-set") : session.reason));

        if (session.type == PunishmentType.BAN || session.type == PunishmentType.MUTE) {
            confirmLore.add(plugin.color(plugin.getLang().getString("gui.punishment.confirm.duration"))
                    .replace("%duration%", formatDuration(session.duration)));
        }

        confirmLore.add("");
        confirmLore.add(plugin.color(plugin.getLang().getString(
                ready ? "gui.punishment.confirm.click-execute" : "gui.punishment.confirm.set-reason")));

        confirmMeta.setLore(confirmLore);
        confirm.setItemMeta(confirmMeta);
        return confirm;
    }

    public void openHistory(Player staff, OfflinePlayer target) {
        List<Punishment> history = plugin.getPunishmentManager().getHistory(target.getUniqueId());
        int page = 0; // TODO: Add pagination support
        int itemsPerPage = plugin.getConfig().getInt("gui.history-items-per-page", 45);
        int pages = (int) Math.ceil(history.size() / (double) itemsPerPage);

        String title = plugin.color(plugin.getLang().getString("gui.history.title"))
                .replace("%player%", target.getName())
                .replace("%page%", String.valueOf(page + 1))
                .replace("%pages%", String.valueOf(Math.max(1, pages)));

        Inventory inv = Bukkit.createInventory(null, 54, title);

        if (history.isEmpty()) {
            // No history
            ItemStack empty = new ItemStack(Material.BOOK);
            ItemMeta emptyMeta = empty.getItemMeta();
            emptyMeta.setDisplayName(plugin.color(plugin.getLang().getString("gui.history.no-history")));
            empty.setItemMeta(emptyMeta);
            inv.setItem(22, empty);
        } else {
            // Display history items
            displayHistoryItems(inv, history, page, itemsPerPage);
        }

        inv.setItem(49, createSimpleButton("gui.history.back", Material.ARROW));

        fillEmpty(inv);
        staff.openInventory(inv);
    }

    private void displayHistoryItems(Inventory inv, List<Punishment> history, int page, int itemsPerPage) {
        String dateFormat = plugin.getConfig().getString("settings.date-format", "yyyy-MM-dd HH:mm");
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern(dateFormat);

        int slot = 0;
        for (int i = page * itemsPerPage; i < Math.min((page + 1) * itemsPerPage, history.size()); i++) {
            if (slot >= 45) break; // Don't overflow into bottom row

            Punishment p = history.get(i);
            Material mat = switch (p.type) {
                case BAN -> Material.BARRIER;
                case MUTE -> Material.PAPER;
                case KICK -> Material.LEATHER_BOOTS;
                case WARN -> Material.BOOK;
            };

            ItemStack item = new ItemStack(mat);
            ItemMeta meta = item.getItemMeta();

            LocalDateTime date = LocalDateTime.ofInstant(
                    Instant.ofEpochMilli(p.timestamp),
                    ZoneId.systemDefault()
            );

            meta.setDisplayName(plugin.color(plugin.getLang().getString("gui.history.punishment-item.name"))
                    .replace("%type%", p.type.getDisplay())
                    .replace("%time_ago%", getTimeAgo(p.timestamp)));

            List<String> lore = buildHistoryItemLore(p, date, formatter);
            meta.setLore(lore);
            item.setItemMeta(meta);
            inv.setItem(slot++, item);
        }
    }

    private List<String> buildHistoryItemLore(Punishment p, LocalDateTime date, DateTimeFormatter formatter) {
        List<String> lore = new ArrayList<>();

        lore.add(plugin.color(plugin.getLang().getString("gui.history.punishment-item.reason"))
                .replace("%reason%", p.reason));

        if (p.type == PunishmentType.BAN || p.type == PunishmentType.MUTE) {
            String duration = p.isPermanent() ? "Permanent" :
                    plugin.formatTime(p.expiry - p.timestamp);
            lore.add(plugin.color(plugin.getLang().getString("gui.history.punishment-item.duration"))
                    .replace("%duration%", duration));
        }

        lore.add(plugin.color(plugin.getLang().getString("gui.history.punishment-item.staff"))
                .replace("%staff%", p.staffName));
        lore.add(plugin.color(plugin.getLang().getString("gui.history.punishment-item.date"))
                .replace("%date%", date.format(formatter)));
        lore.add("");
        lore.add(plugin.color(plugin.getLang().getString(
                "gui.history.punishment-item." + (p.active ? "active" : "expired"))));

        return lore;
    }

    public void openAnalytics(Player staff) {
        String title = plugin.color(plugin.getLang().getString("gui.analytics.title"));
        Inventory inv = Bukkit.createInventory(null, 54, title);

        var pm = plugin.getPunishmentManager();

        // Overview book
        inv.setItem(4, createAnalyticsOverview(pm));

        // Punishment type stats
        int slot = 19;
        for (var entry : pm.getTypeStats().entrySet()) {
            inv.setItem(slot++, createTypeStatItem(entry.getKey(), entry.getValue(), pm.getTotalPunishments()));
        }

        // Staff stats
        inv.setItem(30, createStaffStatsItem(pm));

        // Player stats
        inv.setItem(32, createPlayerStatsItem(pm));

        inv.setItem(49, createSimpleButton("gui.analytics.close", Material.BARRIER));

        fillEmpty(inv);
        staff.openInventory(inv);
    }

    private ItemStack createAnalyticsOverview(PunishmentManager pm) {
        ItemStack overview = new ItemStack(Material.BOOK);
        ItemMeta overviewMeta = overview.getItemMeta();
        overviewMeta.setDisplayName(plugin.color(plugin.getLang().getString("gui.analytics.overview.name")));

        List<String> overviewLore = Arrays.asList(
                plugin.color(plugin.getLang().getString("gui.analytics.overview.total"))
                        .replace("%total%", String.valueOf(pm.getTotalPunishments())),
                plugin.color(plugin.getLang().getString("gui.analytics.overview.active-bans"))
                        .replace("%bans%", String.valueOf(getActiveBans())),
                plugin.color(plugin.getLang().getString("gui.analytics.overview.active-mutes"))
                        .replace("%mutes%", String.valueOf(getActiveMutes()))
        );
        overviewMeta.setLore(overviewLore);
        overview.setItemMeta(overviewMeta);
        return overview;
    }

    private ItemStack createTypeStatItem(PunishmentType type, int count, int total) {
        Material mat = switch (type) {
            case BAN -> Material.BARRIER;
            case MUTE -> Material.PAPER;
            case KICK -> Material.LEATHER_BOOTS;
            case WARN -> Material.BOOK;
        };

        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();

        float percent = total > 0 ? (count * 100f) / total : 0;

        meta.setDisplayName(plugin.color(plugin.getLang().getString("gui.analytics.type-stats.name"))
                .replace("%type%", type.getDisplay()));

        List<String> lore = Arrays.asList(
                plugin.color(plugin.getLang().getString("gui.analytics.type-stats.total"))
                        .replace("%count%", String.valueOf(count)),
                plugin.color(plugin.getLang().getString("gui.analytics.type-stats.percentage"))
                        .replace("%percent%", String.format("%.1f", percent))
        );
        meta.setLore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack createStaffStatsItem(PunishmentManager pm) {
        ItemStack staffStats = new ItemStack(Material.PLAYER_HEAD);
        ItemMeta staffMeta = staffStats.getItemMeta();
        staffMeta.setDisplayName(plugin.color(plugin.getLang().getString("gui.analytics.staff-stats.name")));

        List<String> staffLore = new ArrayList<>();
        staffLore.add(plugin.color(plugin.getLang().getString("gui.analytics.staff-stats.header")));
        staffLore.add("");

        // Top 5 staff
        pm.getStaffStats().entrySet().stream()
                .sorted((a, b) -> b.getValue().compareTo(a.getValue()))
                .limit(5)
                .forEach(entry -> staffLore.add(
                        plugin.color(plugin.getLang().getString("gui.analytics.staff-stats.entry"))
                                .replace("%staff%", entry.getKey())
                                .replace("%count%", String.valueOf(entry.getValue()))
                ));

        staffMeta.setLore(staffLore);
        staffStats.setItemMeta(staffMeta);
        return staffStats;
    }

    private ItemStack createPlayerStatsItem(PunishmentManager pm) {
        ItemStack playerStats = new ItemStack(Material.SKELETON_SKULL);
        ItemMeta playerMeta = playerStats.getItemMeta();
        playerMeta.setDisplayName(plugin.color(plugin.getLang().getString("gui.analytics.player-stats.name")));

        List<String> playerLore = new ArrayList<>();
        playerLore.add(plugin.color(plugin.getLang().getString("gui.analytics.player-stats.header")));
        playerLore.add("");

        // Top 5 punished players
        pm.getPlayerStats().entrySet().stream()
                .sorted((a, b) -> b.getValue().compareTo(a.getValue()))
                .limit(5)
                .forEach(entry -> playerLore.add(
                        plugin.color(plugin.getLang().getString("gui.analytics.player-stats.entry"))
                                .replace("%player%", entry.getKey())
                                .replace("%count%", String.valueOf(entry.getValue()))
                ));

        playerMeta.setLore(playerLore);
        playerStats.setItemMeta(playerMeta);
        return playerStats;
    }

    public void openReasonsEditor(Player staff) {
        String title = plugin.color(plugin.getLang().getString("gui.reasons.title"));
        Inventory inv = Bukkit.createInventory(null, 36, title);

        // One button for each punishment type
        inv.setItem(10, createTypeSelector(PunishmentType.BAN, Material.BARRIER));
        inv.setItem(12, createTypeSelector(PunishmentType.MUTE, Material.PAPER));
        inv.setItem(14, createTypeSelector(PunishmentType.KICK, Material.LEATHER_BOOTS));
        inv.setItem(16, createTypeSelector(PunishmentType.WARN, Material.BOOK));
        inv.setItem(31, createSimpleButton("gui.reasons.back", Material.ARROW));

        fillEmpty(inv);
        staff.openInventory(inv);
    }

    public void openReasonsList(Player staff, PunishmentType type) {
        String title = plugin.color(plugin.getLang().getString("gui.reasons.list-title"))
                .replace("%type%", type.getDisplay());
        Inventory inv = Bukkit.createInventory(null, 54, title);

        List<String> reasons = plugin.getConfig().getStringList("punishment-reasons." + type.name().toLowerCase());

        // Display each reason
        int slot = 0;
        for (int i = 0; i < reasons.size() && slot < 45; i++) {
            ItemStack item = new ItemStack(Material.PAPER);
            ItemMeta meta = item.getItemMeta();

            meta.setDisplayName(plugin.color(plugin.getLang().getString("gui.reasons.reason-item.name"))
                    .replace("%reason%", reasons.get(i)));

            List<String> lore = Arrays.asList(
                    plugin.color(plugin.getLang().getString("gui.reasons.reason-item.number"))
                            .replace("%number%", String.valueOf(i + 1)),
                    "",
                    plugin.color(plugin.getLang().getString("gui.reasons.reason-item.left-click")),
                    plugin.color(plugin.getLang().getString("gui.reasons.reason-item.right-click"))
            );
            meta.setLore(lore);
            item.setItemMeta(meta);
            inv.setItem(slot++, item);
        }

        // Add new reason button
        ItemStack add = new ItemStack(Material.EMERALD);
        ItemMeta addMeta = add.getItemMeta();
        addMeta.setDisplayName(plugin.color(plugin.getLang().getString("gui.reasons.add-reason.name")));

        List<String> addLore = new ArrayList<>();
        for (String line : plugin.getLang().getStringList("gui.reasons.add-reason.lore")) {
            addLore.add(plugin.color(line));
        }
        addMeta.setLore(addLore);
        add.setItemMeta(addMeta);
        inv.setItem(49, add);

        inv.setItem(53, createSimpleButton("gui.reasons.back", Material.ARROW));

        fillEmpty(inv);
        staff.openInventory(inv);
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player player)) return;

        String title = e.getView().getTitle();
        if (!title.startsWith("§8")) return; // Our GUIs start with dark gray

        e.setCancelled(true);

        if (e.getCurrentItem() == null || e.getCurrentItem().getType() == Material.AIR) return;

        // Ignore filler items
        if (e.getCurrentItem().getType() == Material.valueOf(plugin.getConfig().getString("gui.filler-material", "GRAY_STAINED_GLASS_PANE"))) return;

        int slot = e.getSlot();

        // Main punishment menu
        if (title.contains("Punish »")) {
            handleMainMenuClick(player, title, slot);
            return;
        }

        // Punishment type menu
        Session session = sessions.get(player.getUniqueId());
        if (session != null && (title.contains("Ban ") || title.contains("Mute ") ||
                title.contains("Kick ") || title.contains("Warn "))) {
            handlePunishmentMenuClick(player, session, slot, e);
            return;
        }

        // History menu
        if (title.contains("History »")) {
            if (slot == 49) {
                String name = title.split("» §c")[1].split(" §7")[0];
                openMainMenu(player, Bukkit.getOfflinePlayer(name));
            }
            return;
        }

        // Analytics menu
        if (title.contains("Analytics")) {
            if (slot == 49) player.closeInventory();
            return;
        }

        // Reason editor
        if (title.contains("Reason Editor")) {
            handleReasonEditorClick(player, slot);
            return;
        }

        // Reason list
        if (title.contains("Edit") && title.contains("Reasons")) {
            handleReasonListClick(player, title, slot, e);
        }
    }

    private void handleMainMenuClick(Player player, String title, int slot) {
        String name = title.split("» §c")[1];
        OfflinePlayer target = Bukkit.getOfflinePlayer(name);

        switch (slot) {
            case 4, 32 -> openHistory(player, target);
            case 20 -> openPunishmentMenu(player, target, PunishmentType.BAN);
            case 22 -> openPunishmentMenu(player, target, PunishmentType.MUTE);
            case 24 -> openPunishmentMenu(player, target, PunishmentType.KICK);
            case 30 -> openPunishmentMenu(player, target, PunishmentType.WARN);
        }
    }

    private void handlePunishmentMenuClick(Player player, Session session, int slot, InventoryClickEvent e) {
        switch (slot) {
            case 4 -> {
                // Custom reason
                player.closeInventory();
                session.inputMode = true;
                player.sendMessage(plugin.color(plugin.getLang().getString("gui.reasons.input-reason")));
                player.sendMessage(plugin.getPrefix() + "§7Maximum length: §e" +
                        plugin.getConfig().getInt("advanced.max-reason-length", 100) + " §7characters");
            }
            case 8 -> {
                // Silent toggle
                if (player.hasPermission("quickban.silent")) {
                    session.silent = !session.silent;
                    openPunishmentMenu(player, session.target, session.type);
                }
            }
            case 13 -> {
                // Duration selector
                if (session.type == PunishmentType.BAN || session.type == PunishmentType.MUTE) {
                    cycleDuration(session, e.isLeftClick());
                    openPunishmentMenu(player, session.target, session.type);
                }
            }
            case 19, 20, 21, 28, 29, 30 -> {
                // Quick reason selection
                ItemMeta meta = e.getCurrentItem().getItemMeta();
                if (meta != null) {
                    String reason = meta.getDisplayName();
                    reason = reason.startsWith("§a") || reason.startsWith("§e") ? reason.substring(2) : reason;
                    session.reason = session.reason.equals(reason) ? "" : reason;
                    openPunishmentMenu(player, session.target, session.type);
                }
            }
            case 45 -> {
                // Back
                sessions.remove(player.getUniqueId());
                openMainMenu(player, session.target);
            }
            case 49 -> {
                // Confirm punishment
                if (!session.reason.isEmpty()) {
                    String dur = (session.type == PunishmentType.KICK || session.type == PunishmentType.WARN) ?
                            "none" : session.duration;
                    plugin.getPunishmentManager().punish(
                            player, session.target, session.type,
                            session.reason, dur, session.silent
                    );
                    sessions.remove(player.getUniqueId());
                    player.closeInventory();
                }
            }
            case 53 -> {
                // Cancel
                sessions.remove(player.getUniqueId());
                player.closeInventory();
                player.sendMessage(plugin.color(plugin.getLang().getString("gui.reasons.cancelled")));
            }
        }
    }

    private void cycleDuration(Session session, boolean forward) {
        List<String> durations = plugin.getConfig().getStringList("durations.options");
        int idx = durations.indexOf(session.duration);
        if (idx == -1) idx = 0;

        if (forward) {
            idx = (idx + 1) % durations.size();
        } else {
            idx = (idx - 1 + durations.size()) % durations.size();
        }

        session.duration = durations.get(idx);
    }

    private void handleReasonEditorClick(Player player, int slot) {
        switch (slot) {
            case 10 -> openReasonsList(player, PunishmentType.BAN);
            case 12 -> openReasonsList(player, PunishmentType.MUTE);
            case 14 -> openReasonsList(player, PunishmentType.KICK);
            case 16 -> openReasonsList(player, PunishmentType.WARN);
            case 31 -> player.closeInventory();
        }
    }

    private void handleReasonListClick(Player player, String title, int slot, InventoryClickEvent e) {
        // Find punishment type from title
        PunishmentType type = null;
        for (PunishmentType t : PunishmentType.values()) {
            if (title.contains(t.getDisplay())) {
                type = t;
                break;
            }
        }

        if (type == null) return;

        final PunishmentType finalType = type;

        if (slot == 53) {
            // Back to editor
            openReasonsEditor(player);
        } else if (slot == 49) {
            // Add new reason
            ReasonEditSession editSession = new ReasonEditSession();
            editSession.type = finalType;
            editSession.action = ReasonEditAction.ADD;
            reasonEditSessions.put(player.getUniqueId(), editSession);

            player.closeInventory();
            player.sendMessage(plugin.color(plugin.getLang().getString("gui.reasons.input-reason")));
            player.sendMessage(plugin.getPrefix() + "§7Maximum length: §e" +
                    plugin.getConfig().getInt("advanced.max-reason-length", 100) + " §7characters");
        } else if (slot < 45 && e.getCurrentItem().getType() == Material.PAPER) {
            // Edit or remove existing reason
            List<String> reasons = plugin.getConfig().getStringList("punishment-reasons." + finalType.name().toLowerCase());
            if (slot < reasons.size()) {
                if (e.isLeftClick()) {
                    // Edit
                    ReasonEditSession editSession = new ReasonEditSession();
                    editSession.type = finalType;
                    editSession.action = ReasonEditAction.EDIT;
                    editSession.index = slot;
                    editSession.oldReason = reasons.get(slot);
                    reasonEditSessions.put(player.getUniqueId(), editSession);

                    player.closeInventory();
                    player.sendMessage(plugin.color(plugin.getLang().getString("gui.reasons.input-edit")));
                    player.sendMessage(plugin.color(plugin.getLang().getString("gui.reasons.current-reason"))
                            .replace("%reason%", reasons.get(slot)));
                    player.sendMessage(plugin.getPrefix() + "§7Maximum length: §e" +
                            plugin.getConfig().getInt("advanced.max-reason-length", 100) + " §7characters");
                } else if (e.isRightClick()) {
                    // Remove
                    reasons.remove(slot);
                    plugin.getConfig().set("punishment-reasons." + finalType.name().toLowerCase(), reasons);
                    plugin.saveConfig();
                    player.sendMessage(plugin.color(plugin.getLang().getString("gui.reasons.reason-removed")));
                    openReasonsList(player, finalType);
                }
            }
        }
    }

    @EventHandler
    public void onChat(AsyncPlayerChatEvent e) {
        // Handle custom reason input
        Session session = sessions.get(e.getPlayer().getUniqueId());
        if (session != null && session.inputMode) {
            e.setCancelled(true);
            session.inputMode = false;

            if (!e.getMessage().equalsIgnoreCase("cancel")) {
                if (plugin.checkReasonLength(e.getMessage())) {
                    session.reason = e.getMessage();
                } else {
                    e.getPlayer().sendMessage(plugin.getPrefix() + "§cReason is too long! Maximum: " +
                            plugin.getConfig().getInt("advanced.max-reason-length", 100) + " characters");
                    e.getPlayer().sendMessage(plugin.getPrefix() + "§7Your reason was §e" + e.getMessage().length() + " §7characters.");
                }
            }

            // Reopen the menu
            Bukkit.getScheduler().runTask(plugin, () ->
                    openPunishmentMenu(e.getPlayer(), session.target, session.type));
            return;
        }

        // Handle reason editor input
        ReasonEditSession editSession = reasonEditSessions.get(e.getPlayer().getUniqueId());
        if (editSession != null) {
            e.setCancelled(true);
            reasonEditSessions.remove(e.getPlayer().getUniqueId());

            if (!e.getMessage().equalsIgnoreCase("cancel")) {
                // Check length
                if (!plugin.checkReasonLength(e.getMessage())) {
                    e.getPlayer().sendMessage(plugin.getPrefix() + "§cReason is too long! Maximum: " +
                            plugin.getConfig().getInt("advanced.max-reason-length", 100) + " characters");
                    e.getPlayer().sendMessage(plugin.getPrefix() + "§7Your reason was §e" + e.getMessage().length() + " §7characters.");
                    Bukkit.getScheduler().runTask(plugin, () ->
                            openReasonsList(e.getPlayer(), editSession.type));
                    return;
                }

                List<String> reasons = plugin.getConfig().getStringList("punishment-reasons." + editSession.type.name().toLowerCase());

                switch (editSession.action) {
                    case ADD -> {
                        reasons.add(e.getMessage());
                        e.getPlayer().sendMessage(plugin.color(plugin.getLang().getString("gui.reasons.reason-added")));
                    }
                    case EDIT -> {
                        if (editSession.index < reasons.size()) {
                            reasons.set(editSession.index, e.getMessage());
                            e.getPlayer().sendMessage(plugin.color(plugin.getLang().getString("gui.reasons.reason-updated")));
                        }
                    }
                }

                plugin.getConfig().set("punishment-reasons." + editSession.type.name().toLowerCase(), reasons);
                plugin.saveConfig();
            }

            Bukkit.getScheduler().runTask(plugin, () ->
                    openReasonsList(e.getPlayer(), editSession.type));
        }
    }

    // Helper methods
    private ItemStack createButton(String type, String playerName, Material material) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();

        meta.setDisplayName(plugin.color(plugin.getLang().getString("gui.main." + type + ".name")));

        List<String> lore = new ArrayList<>();
        for (String line : plugin.getLang().getStringList("gui.main." + type + ".lore")) {
            lore.add(plugin.color(line).replace("%player%", playerName));
        }
        meta.setLore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack createSimpleButton(String path, Material material) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();

        meta.setDisplayName(plugin.color(plugin.getLang().getString(path + ".name")));

        if (plugin.getLang().contains(path + ".lore")) {
            List<String> lore = new ArrayList<>();
            if (plugin.getLang().isList(path + ".lore")) {
                for (String line : plugin.getLang().getStringList(path + ".lore")) {
                    lore.add(plugin.color(line));
                }
            } else {
                lore.add(plugin.color(plugin.getLang().getString(path + ".lore")));
            }
            meta.setLore(lore);
        }

        item.setItemMeta(meta);
        return item;
    }

    private ItemStack createTypeSelector(PunishmentType type, Material material) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();

        meta.setDisplayName(plugin.color(plugin.getLang().getString("gui.reasons.type-selector." + type.name().toLowerCase())));

        List<String> lore = new ArrayList<>();
        for (String line : plugin.getLang().getStringList("gui.reasons.type-selector.lore")) {
            lore.add(plugin.color(line).replace("%type%", type.name().toLowerCase()));
        }
        meta.setLore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private void fillEmpty(Inventory inv) {
        if (!plugin.getConfig().getBoolean("gui.fill-empty-slots", true)) return;

        String fillerName = plugin.getConfig().getString("gui.filler-material", "GRAY_STAINED_GLASS_PANE");
        Material filler = Material.GRAY_STAINED_GLASS_PANE;
        try {
            filler = Material.valueOf(fillerName);
        } catch (Exception ignored) {}

        ItemStack fillerItem = new ItemStack(filler);
        ItemMeta meta = fillerItem.getItemMeta();
        meta.setDisplayName(" ");
        fillerItem.setItemMeta(meta);

        for (int i = 0; i < inv.getSize(); i++) {
            if (inv.getItem(i) == null) {
                inv.setItem(i, fillerItem);
            }
        }
    }

    private String formatDuration(String dur) {
        return switch (dur) {
            case "5m" -> "5 Minutes";
            case "10m" -> "10 Minutes";
            case "30m" -> "30 Minutes";
            case "1h" -> "1 Hour";
            case "2h" -> "2 Hours";
            case "6h" -> "6 Hours";
            case "12h" -> "12 Hours";
            case "1d" -> "1 Day";
            case "3d" -> "3 Days";
            case "7d" -> "7 Days";
            case "14d" -> "14 Days";
            case "30d" -> "30 Days";
            case "60d" -> "60 Days";
            case "90d" -> "90 Days";
            case "permanent" -> "Permanent";
            default -> dur; // Show raw value if not recognized
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

    // Stats helpers
    public int getActiveBans() {
        return (int) plugin.getPunishmentManager().getAllHistory().values().stream()
                .flatMap(List::stream)
                .filter(p -> p.type == PunishmentType.BAN && p.active && !p.isExpired())
                .count();
    }

    public int getActiveMutes() {
        return (int) plugin.getPunishmentManager().getAllHistory().values().stream()
                .flatMap(List::stream)
                .filter(p -> p.type == PunishmentType.MUTE && p.active && !p.isExpired())
                .count();
    }

    // Session classes
    private static class Session {
        OfflinePlayer target;
        PunishmentType type;
        String reason = "";
        String duration = "";
        boolean silent = false;
        boolean inputMode = false;
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