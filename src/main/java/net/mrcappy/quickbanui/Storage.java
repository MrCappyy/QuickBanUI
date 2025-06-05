package net.mrcappy.quickbanui;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.sql.*;
import java.util.*;

public class Storage {

    private final QuickBanUI plugin;
    private final File dataFile;
    private FileConfiguration data;

    // MySQL stuff
    private Connection connection;
    private boolean useMySQL;

    public Storage(QuickBanUI plugin) {
        this.plugin = plugin;
        this.dataFile = new File(plugin.getDataFolder(), "punishments.yml");
        this.useMySQL = plugin.getConfig().getBoolean("mysql.enabled");

        if (useMySQL) {
            connectMySQL();
        } else {
            loadYaml();
        }
    }

    private void connectMySQL() {
        try {
            String host = plugin.getConfig().getString("mysql.host");
            String port = plugin.getConfig().getString("mysql.port");
            String database = plugin.getConfig().getString("mysql.database");
            String username = plugin.getConfig().getString("mysql.username");
            String password = plugin.getConfig().getString("mysql.password");

            // Build connection string
            connection = DriverManager.getConnection(
                    "jdbc:mysql://" + host + ":" + port + "/" + database +
                            "?autoReconnect=true&useSSL=false&useUnicode=true&characterEncoding=UTF-8",
                    username, password);

            createTables();
            plugin.getLogger().info("Connected to MySQL database");
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to connect to MySQL! Using YAML instead.");
            plugin.getLogger().severe("Error: " + e.getMessage());
            useMySQL = false;
            loadYaml(); // Fallback to YAML
        }
    }

    private void createTables() throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            // Create punishments table if it doesn't exist
            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS punishments (
                    id INT AUTO_INCREMENT PRIMARY KEY,
                    player_uuid VARCHAR(36) NOT NULL,
                    player_name VARCHAR(16) NOT NULL,
                    type VARCHAR(10) NOT NULL,
                    reason TEXT NOT NULL,
                    staff_uuid VARCHAR(36) NOT NULL,
                    staff_name VARCHAR(16) NOT NULL,
                    timestamp BIGINT NOT NULL,
                    expiry BIGINT NOT NULL,
                    active BOOLEAN DEFAULT TRUE,
                    server VARCHAR(50),
                    INDEX idx_player (player_uuid),
                    INDEX idx_active (active)
                ) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci
            """);
        }
    }

    private void loadYaml() {
        if (!dataFile.exists()) {
            try {
                dataFile.getParentFile().mkdirs();
                dataFile.createNewFile();
            } catch (IOException e) {
                plugin.getLogger().severe("Failed to create punishments.yml!");
                e.printStackTrace();
            }
        }
        data = YamlConfiguration.loadConfiguration(dataFile);
    }

    public void reload() {
        if (useMySQL) {
            try {
                // Close existing connection
                if (connection != null && !connection.isClosed()) {
                    connection.close();
                }
                connectMySQL();
            } catch (SQLException e) {
                e.printStackTrace();
            }
        } else {
            loadYaml();
        }
    }

    public Map<UUID, List<PunishmentManager.Punishment>> getAllPunishments() {
        Map<UUID, List<PunishmentManager.Punishment>> all = new HashMap<>();

        if (useMySQL && connection != null) {
            loadFromMySQL(all);
        } else {
            loadFromYaml(all);
        }

        return all;
    }

    private void loadFromMySQL(Map<UUID, List<PunishmentManager.Punishment>> all) {
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT * FROM punishments ORDER BY timestamp DESC")) {

            while (rs.next()) {
                UUID uuid = UUID.fromString(rs.getString("player_uuid"));

                PunishmentManager.Punishment p = new PunishmentManager.Punishment(
                        uuid,
                        rs.getString("player_name"),
                        PunishmentManager.PunishmentType.valueOf(rs.getString("type")),
                        rs.getString("reason"),
                        UUID.fromString(rs.getString("staff_uuid")),
                        rs.getString("staff_name"),
                        rs.getLong("timestamp"),
                        rs.getLong("expiry"),
                        rs.getBoolean("active")
                );

                all.computeIfAbsent(uuid, k -> new ArrayList<>()).add(p);
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to load punishments from MySQL!");
            e.printStackTrace();
        }
    }

    private void loadFromYaml(Map<UUID, List<PunishmentManager.Punishment>> all) {
        if (data == null || !data.contains("history")) {
            return; // No data to load
        }

        // Load each player's history
        for (String uuidStr : data.getConfigurationSection("history").getKeys(false)) {
            try {
                UUID uuid = UUID.fromString(uuidStr);
                List<PunishmentManager.Punishment> list = new ArrayList<>();

                // Load each punishment
                for (String key : data.getConfigurationSection("history." + uuidStr).getKeys(false)) {
                    String path = "history." + uuidStr + "." + key;

                    list.add(new PunishmentManager.Punishment(
                            uuid,
                            data.getString(path + ".playerName", "Unknown"),
                            PunishmentManager.PunishmentType.valueOf(data.getString(path + ".type", "BAN")),
                            data.getString(path + ".reason", "No reason"),
                            UUID.fromString(data.getString(path + ".staffUuid")),
                            data.getString(path + ".staffName", "Console"),
                            data.getLong(path + ".timestamp"),
                            data.getLong(path + ".expiry"),
                            data.getBoolean(path + ".active", true)
                    ));
                }

                all.put(uuid, list);
            } catch (Exception e) {
                plugin.getLogger().warning("Failed to load punishment for UUID: " + uuidStr);
                // Skip this entry and continue
            }
        }
    }

    public void addPunishment(PunishmentManager.Punishment p) {
        if (useMySQL && connection != null) {
            addToMySQL(p);
        } else {
            // For YAML, we just trigger a save since the data is already in memory
            save();
        }
    }

    private void addToMySQL(PunishmentManager.Punishment p) {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO punishments (player_uuid, player_name, type, reason, staff_uuid, staff_name, timestamp, expiry, server) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")) {

            ps.setString(1, p.playerUuid.toString());
            ps.setString(2, p.playerName);
            ps.setString(3, p.type.name());
            ps.setString(4, p.reason);
            ps.setString(5, p.staffUuid.toString());
            ps.setString(6, p.staffName);
            ps.setLong(7, p.timestamp);
            ps.setLong(8, p.expiry);
            ps.setString(9, plugin.getConfig().getString("mysql.server-name", "Server-1"));
            ps.executeUpdate();

        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to add punishment to MySQL!");
            e.printStackTrace();
        }
    }

    public void save() {
        if (useMySQL) return; // MySQL saves immediately

        if (data == null) {
            plugin.getLogger().severe("Cannot save - data is null!");
            return;
        }

        // Clear old data
        data.set("history", null);

        // Get all current history
        var allHistory = plugin.getPunishmentManager().getAllHistory();

        // Save each player's history
        for (var entry : allHistory.entrySet()) {
            String basePath = "history." + entry.getKey().toString();
            int i = 0;

            for (var p : entry.getValue()) {
                String path = basePath + "." + i++;
                data.set(path + ".playerName", p.playerName);
                data.set(path + ".type", p.type.name());
                data.set(path + ".reason", p.reason);
                data.set(path + ".staffUuid", p.staffUuid.toString());
                data.set(path + ".staffName", p.staffName);
                data.set(path + ".timestamp", p.timestamp);
                data.set(path + ".expiry", p.expiry);
                data.set(path + ".active", p.active);
            }
        }

        try {
            data.save(dataFile);
        } catch (IOException e) {
            plugin.getLogger().severe("Failed to save punishments.yml!");
            e.printStackTrace();
        }
    }

    // TODO: Add method to update punishment status in MySQL without reloading everything
}