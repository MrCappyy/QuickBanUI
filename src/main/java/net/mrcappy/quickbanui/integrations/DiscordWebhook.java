package net.mrcappy.quickbanui.integrations;

import com.google.gson.JsonObject;
import net.mrcappy.quickbanui.QuickBanUI;
import net.mrcappy.quickbanui.PunishmentManager;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

public class DiscordWebhook {

    private final QuickBanUI plugin;
    private final String webhookUrl;
    private final boolean enabled;
    private final ConfigurationSection config;

    public DiscordWebhook(QuickBanUI plugin) {
        this.plugin = plugin;
        this.config = plugin.getConfig().getConfigurationSection("discord");
        this.enabled = config != null && config.getBoolean("enabled", false);
        this.webhookUrl = config != null ? config.getString("webhook-url", "") : "";
    }

    public void sendPunishment(PunishmentManager.Punishment punishment, String staffName, String targetName) {
        if (!enabled || webhookUrl.isEmpty()) return;

        // Run async to not block main thread
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                JsonObject json = buildPunishmentEmbed(punishment, staffName, targetName);
                sendWebhook(json.toString());
            } catch (Exception e) {
                plugin.getLogger().warning("Failed to send Discord webhook: " + e.getMessage());
                if (plugin.getConfig().getBoolean("advanced.debug", false)) {
                    e.printStackTrace();
                }
            }
        });
    }

    private JsonObject buildPunishmentEmbed(PunishmentManager.Punishment punishment, String staffName, String targetName) {
        JsonObject json = new JsonObject();
        json.addProperty("username", config.getString("username", "QuickBanUI"));
        json.addProperty("avatar_url", config.getString("avatar-url", ""));

        JsonObject embed = new JsonObject();

        // Set color based on punishment type
        int color = switch (punishment.type) {
            case BAN -> 0xFF0000;    // Red
            case MUTE -> 0xFFA500;   // Orange
            case KICK -> 0xFFFF00;   // Yellow
            case WARN -> 0x00FF00;   // Green
        };
        embed.addProperty("color", color);

        // Title
        embed.addProperty("title", punishment.type.getDisplay() + " | " + targetName);

        // Description
        embed.addProperty("description", "**" + targetName + "** has been " + punishment.type.getPast() + "!");

        // Fields
        List<JsonObject> fields = new ArrayList<>();

        // Reason field
        JsonObject reasonField = new JsonObject();
        reasonField.addProperty("name", "Reason");
        reasonField.addProperty("value", punishment.reason);
        reasonField.addProperty("inline", true);
        fields.add(reasonField);

        // Staff field
        JsonObject staffField = new JsonObject();
        staffField.addProperty("name", "Staff Member");
        staffField.addProperty("value", staffName);
        staffField.addProperty("inline", true);
        fields.add(staffField);

        // Duration field for bans/mutes
        if (punishment.type == PunishmentManager.PunishmentType.BAN ||
                punishment.type == PunishmentManager.PunishmentType.MUTE) {
            JsonObject durationField = new JsonObject();
            durationField.addProperty("name", "Duration");
            durationField.addProperty("value", punishment.isPermanent() ? "Permanent" :
                    plugin.formatTime(punishment.expiry - punishment.timestamp));
            durationField.addProperty("inline", true);
            fields.add(durationField);
        }

        // Server field if enabled
        if (config.getBoolean("show-server", true)) {
            JsonObject serverField = new JsonObject();
            serverField.addProperty("name", "Server");
            serverField.addProperty("value", config.getString("server-name", "Server"));
            serverField.addProperty("inline", true);
            fields.add(serverField);
        }

        embed.add("fields", plugin.getGson().toJsonTree(fields));

        // Footer
        JsonObject footer = new JsonObject();
        footer.addProperty("text", "QuickBanUI v" + plugin.getDescription().getVersion());
        footer.addProperty("icon_url", config.getString("footer-icon", ""));
        embed.add("footer", footer);

        // Timestamp
        embed.addProperty("timestamp", DateTimeFormatter.ISO_INSTANT.format(Instant.now()));

        // Add embed to main json
        List<JsonObject> embeds = new ArrayList<>();
        embeds.add(embed);
        json.add("embeds", plugin.getGson().toJsonTree(embeds));

        return json;
    }

    public void sendUnpunishment(String type, String staffName, String targetName) {
        if (!enabled || webhookUrl.isEmpty()) return;

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                JsonObject json = new JsonObject();
                json.addProperty("username", config.getString("username", "QuickBanUI"));
                json.addProperty("avatar_url", config.getString("avatar-url", ""));

                JsonObject embed = new JsonObject();
                embed.addProperty("color", 0x00FF00); // Green for unpunishments
                embed.addProperty("title", type + " | " + targetName);
                embed.addProperty("description", "**" + targetName + "** has been " + type.toLowerCase() + "!");

                List<JsonObject> fields = new ArrayList<>();

                // Staff member who unpunished
                JsonObject staffField = new JsonObject();
                staffField.addProperty("name", "Staff Member");
                staffField.addProperty("value", staffName);
                staffField.addProperty("inline", true);
                fields.add(staffField);

                if (config.getBoolean("show-server", true)) {
                    JsonObject serverField = new JsonObject();
                    serverField.addProperty("name", "Server");
                    serverField.addProperty("value", config.getString("server-name", "Server"));
                    serverField.addProperty("inline", true);
                    fields.add(serverField);
                }

                embed.add("fields", plugin.getGson().toJsonTree(fields));

                // Footer
                JsonObject footer = new JsonObject();
                footer.addProperty("text", "QuickBanUI v" + plugin.getDescription().getVersion());
                footer.addProperty("icon_url", config.getString("footer-icon", ""));
                embed.add("footer", footer);

                embed.addProperty("timestamp", DateTimeFormatter.ISO_INSTANT.format(Instant.now()));

                List<JsonObject> embeds = new ArrayList<>();
                embeds.add(embed);
                json.add("embeds", plugin.getGson().toJsonTree(embeds));

                sendWebhook(json.toString());

            } catch (Exception e) {
                plugin.getLogger().warning("Failed to send Discord webhook: " + e.getMessage());
            }
        });
    }

    private void sendWebhook(String jsonPayload) throws Exception {
        URL url = new URL(webhookUrl);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setDoOutput(true);
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("User-Agent", "QuickBanUI/" + plugin.getDescription().getVersion());

        // Write the JSON
        try (OutputStream os = conn.getOutputStream()) {
            byte[] input = jsonPayload.getBytes(StandardCharsets.UTF_8);
            os.write(input, 0, input.length);
        }

        int responseCode = conn.getResponseCode();
        if (responseCode < 200 || responseCode >= 300) {
            throw new Exception("Discord webhook returned status code: " + responseCode);
        }

        conn.disconnect();
    }

    public boolean isEnabled() {
        return enabled;
    }
}