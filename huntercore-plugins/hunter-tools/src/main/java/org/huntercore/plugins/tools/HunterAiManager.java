package org.huntercore.plugins.tools;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.entity.Pose;
import org.bukkit.util.Vector;

final class HunterAiManager {
    private static final String AI = "ai";
    private static final String NPCS = "npcs";
    private static final List<String> DEFAULT_NPC_COMMANDS = List.of("say", "tell", "msg", "title", "effect", "playsound");

    private final HunterToolsPlugin plugin;
    private final HunterToolsPreferences preferences;
    private final HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15L))
        .build();
    private final java.util.Map<String, Long> chatCooldowns = new ConcurrentHashMap<>();
    private final java.util.Map<String, Long> npcCooldowns = new ConcurrentHashMap<>();
    private Executor executor;

    HunterAiManager(final HunterToolsPlugin plugin, final HunterToolsPreferences preferences, final Executor executor) {
        this.plugin = plugin;
        this.preferences = preferences;
        this.executor = executor;
    }

    void setExecutor(final Executor executor) {
        this.executor = executor;
    }

    boolean handleChat(final Player player, final String rawMessage) {
        if (!this.preferences.moduleEnabled(AI) || !this.preferences.booleanValue("modules.ai.chat.enabled", true)) {
            return false;
        }
        final String prefix = this.preferences.stringValue("modules.ai.chat.trigger-prefix", "@ai").trim();
        final String message = rawMessage == null ? "" : rawMessage.trim();
        if (prefix.isBlank() || !message.startsWith(prefix)) {
            return false;
        }

        final String prompt = message.substring(prefix.length()).trim();
        if (prompt.isBlank()) {
            this.send(player, "&cUsage: " + prefix + " <message>");
            return true;
        }
        final long waitMillis = this.cooldownRemaining(this.chatCooldowns, player.getUniqueId().toString(), "modules.ai.chat.cooldown-seconds", 5);
        if (waitMillis > 0L) {
            this.send(player, "&eAI cooldown: " + Math.max(1L, (waitMillis + 999L) / 1000L) + "s.");
            return true;
        }

        final String playerName = player.getName();
        final String worldName = Bukkit.isPrimaryThread() ? player.getWorld().getName() : "unknown";
        final String system = this.preferences.stringValue("modules.ai.chat.system-prompt", defaultChatPrompt())
            + "\n\nContext: player=" + playerName + ", world=" + worldName + ".";
        this.send(player, "&7AI is thinking...");
        this.complete(system, prompt).whenComplete((response, error) -> {
            if (error != null) {
                this.plugin.getLogger().warning("HunterCore AI chat failed: " + error.getMessage());
                this.send(player, "&cAI request failed: " + cleanError(error));
                return;
            }
            this.deliverChatResponse(player.getUniqueId(), playerName, response);
        });
        return true;
    }

    boolean handleActorInteract(final Player player, final HunterActorManager.ActorInteraction actor) {
        if (!this.preferences.moduleEnabled(AI)
            || !this.preferences.booleanValue("modules.ai.npc.enabled", true)
            || actor == null
            || !NPCS.equals(actor.module())
            || !actor.aiEnabled()) {
            return false;
        }
        final long waitMillis = this.cooldownRemaining(
            this.npcCooldowns,
            player.getUniqueId() + ":" + actor.module() + ":" + actor.id(),
            "modules.ai.npc.cooldown-seconds",
            5
        );
        if (waitMillis > 0L) {
            this.send(player, "&eNPC AI cooldown: " + Math.max(1L, (waitMillis + 999L) / 1000L) + "s.");
            return true;
        }

        final Entity clicked = actor.entity();
        final Location location = clicked.getLocation();
        final String world = location.getWorld() == null ? "unknown" : location.getWorld().getName();
        final String persona = actor.aiPersona() == null || actor.aiPersona().isBlank()
            ? ""
            : "\nNPC persona: " + actor.aiPersona().trim();
        final String system = this.preferences.stringValue("modules.ai.npc.system-prompt", defaultNpcPrompt())
            + "\n\nNPC id=" + actor.id()
            + ", displayName=" + actor.displayName()
            + ", kind=" + actor.kind()
            + ", world=" + world
            + ", x=" + String.format(Locale.ROOT, "%.1f", location.getX())
            + ", y=" + String.format(Locale.ROOT, "%.1f", location.getY())
            + ", z=" + String.format(Locale.ROOT, "%.1f", location.getZ())
            + persona;
        final String userPrompt = "Player " + player.getName() + " clicked this NPC and wants to interact.";
        this.send(player, "&7" + actor.displayName() + " is thinking...");
        this.complete(system, userPrompt).whenComplete((response, error) -> {
            if (error != null) {
                this.plugin.getLogger().warning("HunterCore NPC AI failed: " + error.getMessage());
                this.send(player, "&cNPC AI request failed: " + cleanError(error));
                return;
            }
            Bukkit.getScheduler().runTask(this.plugin, () -> this.applyNpcResponse(player.getUniqueId(), actor, response));
        });
        return true;
    }

    CompletableFuture<String> completeTest(final String prompt) {
        final String system = "You are a concise test assistant for HunterCore's native AI integration.";
        return this.complete(system, prompt == null || prompt.isBlank() ? "Say HunterCore AI is ready." : prompt.trim());
    }

    CompletableFuture<String> completeFakePlayerPlan(final String system, final String prompt) {
        return this.complete(system, prompt);
    }

    boolean apiKeyConfigured() {
        return !this.aiConfig().apiKey().isBlank();
    }

    private CompletableFuture<String> complete(final String system, final String prompt) {
        final Executor activeExecutor = this.executor == null ? Runnable::run : this.executor;
        return CompletableFuture.supplyAsync(() -> {
            try {
                return this.requestCompletion(system, prompt);
            } catch (final IOException ex) {
                throw new IllegalStateException(ex.getMessage(), ex);
            } catch (final InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("AI request interrupted.", ex);
            }
        }, activeExecutor);
    }

    private String requestCompletion(final String system, final String prompt) throws IOException, InterruptedException {
        final AiConfig config = this.aiConfig();
        if (config.apiKey().isBlank()) {
            throw new IOException("API key is not configured.");
        }
        final URI uri = completionUri(config.baseUrl());
        final String body = "{"
            + "\"model\":\"" + escapeJson(config.model()) + "\","
            + "\"messages\":["
            + "{\"role\":\"system\",\"content\":\"" + escapeJson(system) + "\"},"
            + "{\"role\":\"user\",\"content\":\"" + escapeJson(prompt) + "\"}"
            + "],"
            + "\"temperature\":" + String.format(Locale.ROOT, "%.3f", config.temperature()) + ","
            + "\"max_tokens\":" + config.maxTokens()
            + "}";
        final HttpRequest request = HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(config.timeoutSeconds()))
            .header("Authorization", "Bearer " + config.apiKey())
            .header("Content-Type", "application/json")
            .header("User-Agent", "HunterCore-AI/1.0")
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
            .build();
        final HttpResponse<String> response = this.httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("HTTP " + response.statusCode() + ": " + truncate(response.body(), 240));
        }
        final String content = extractContent(response.body());
        if (content.isBlank()) {
            throw new IOException("AI response did not contain message content.");
        }
        return content.trim();
    }

    private AiConfig aiConfig() {
        final String configuredKey = this.preferences.stringValue("modules.ai.api-key", "").trim();
        final String envName = this.preferences.stringValue("modules.ai.api-key-env", "OPENAI_API_KEY").trim();
        final String envKey = envName.isBlank() ? "" : System.getenv(envName);
        final String key = configuredKey.isBlank() ? (envKey == null ? "" : envKey.trim()) : configuredKey;
        return new AiConfig(
            this.preferences.stringValue("modules.ai.base-url", "https://api.openai.com/v1").trim(),
            key,
            this.preferences.stringValue("modules.ai.model", "gpt-4o-mini").trim(),
            Math.max(0.0D, Math.min(2.0D, this.preferences.doubleValue("modules.ai.temperature", 0.7D))),
            Math.max(16, Math.min(4096, this.preferences.intValue("modules.ai.max-tokens", 300))),
            Math.max(1, Math.min(120, this.preferences.intValue("modules.ai.timeout-seconds", 30)))
        );
    }

    private long cooldownRemaining(final java.util.Map<String, Long> cooldowns, final String key, final String path, final int fallbackSeconds) {
        final long now = System.currentTimeMillis();
        final long expires = cooldowns.getOrDefault(key, 0L);
        if (expires > now) {
            return expires - now;
        }
        final int seconds = Math.max(0, this.preferences.intValue(path, fallbackSeconds));
        if (seconds > 0) {
            cooldowns.put(key, now + seconds * 1000L);
        }
        return 0L;
    }

    private void deliverChatResponse(final UUID playerId, final String playerName, final String response) {
        Bukkit.getScheduler().runTask(this.plugin, () -> {
            final Player player = Bukkit.getPlayer(playerId);
            if (player == null && !this.preferences.booleanValue("modules.ai.chat.broadcast", true)) {
                return;
            }
            final String format = this.preferences.stringValue("modules.ai.chat.response-format", "&bAI &8> &f%response%");
            final String rendered = color(format
                .replace("%player%", playerName)
                .replace("%response%", response.trim()));
            if (this.preferences.booleanValue("modules.ai.chat.broadcast", true)) {
                for (final Player online : Bukkit.getOnlinePlayers()) {
                    this.sendLines(online, rendered);
                }
            } else if (player != null) {
                this.sendLines(player, rendered);
            }
        });
    }

    private void applyNpcResponse(final UUID playerId, final HunterActorManager.ActorInteraction actor, final String response) {
        final Player player = Bukkit.getPlayer(playerId);
        final Entity entity = Bukkit.getEntity(actor.entityUuid());
        if (player == null || entity == null || !entity.isValid()) {
            return;
        }
        final boolean allowActions = this.preferences.booleanValue("modules.ai.npc.allow-actions", true);
        final ParsedNpcResponse parsed = parseNpcResponse(response);
        if (allowActions) {
            if (parsed.lookAtPlayer()) {
                faceEntity(entity, player);
            }
            if (!parsed.pose().isBlank() && entity instanceof final Mannequin mannequin) {
                final Pose pose = parsePose(parsed.pose());
                if (pose != null && Mannequin.validPoses().contains(pose)) {
                    mannequin.setPose(pose, true);
                }
            }
            for (final String command : parsed.commands()) {
                this.dispatchNpcCommand(command, player, actor, entity);
            }
        }
        final String text = parsed.text().isBlank() ? "..." : parsed.text();
        final String line = color(this.preferences.stringValue("modules.ai.npc.response-format", "&d%npc% &8> &f%response%")
            .replace("%npc%", actor.displayName())
            .replace("%actor%", actor.id())
            .replace("%player%", player.getName())
            .replace("%response%", text));
        for (final Player receiver : this.npcReceivers(player, entity)) {
            this.sendLines(receiver, line);
        }
    }

    private Collection<Player> npcReceivers(final Player player, final Entity entity) {
        final int radius = Math.max(0, Math.min(128, this.preferences.intValue("modules.ai.npc.response-radius-blocks", 16)));
        if (radius <= 0 || entity.getWorld() == null) {
            return List.of(player);
        }
        final List<Player> receivers = new ArrayList<>();
        final World world = entity.getWorld();
        final double radiusSquared = radius * radius;
        for (final Player online : world.getPlayers()) {
            if (online.getLocation().distanceSquared(entity.getLocation()) <= radiusSquared) {
                receivers.add(online);
            }
        }
        if (!receivers.contains(player)) {
            receivers.add(player);
        }
        return receivers;
    }

    private void dispatchNpcCommand(final String rawCommand, final Player player, final HunterActorManager.ActorInteraction actor, final Entity entity) {
        String command = rawCommand == null ? "" : rawCommand.trim().replace('\n', ' ').replace('\r', ' ');
        if (command.startsWith("/")) {
            command = command.substring(1).trim();
        }
        if (command.isBlank() || command.length() > 256) {
            return;
        }
        final String root = commandRoot(command);
        final Set<String> whitelist = commandWhitelist();
        if (!whitelist.contains("*") && !whitelist.contains(root)) {
            this.plugin.getLogger().fine("Blocked HunterCore NPC AI command outside whitelist: " + root);
            return;
        }
        final Location location = entity.getLocation();
        final String rendered = command
            .replace("%player%", player.getName())
            .replace("%player_uuid%", player.getUniqueId().toString())
            .replace("%npc%", actor.displayName())
            .replace("%actor%", actor.id())
            .replace("%actor_uuid%", actor.entityUuid().toString())
            .replace("%world%", location.getWorld() == null ? "" : location.getWorld().getName())
            .replace("%x%", String.format(Locale.ROOT, "%.2f", location.getX()))
            .replace("%y%", String.format(Locale.ROOT, "%.2f", location.getY()))
            .replace("%z%", String.format(Locale.ROOT, "%.2f", location.getZ()));
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), rendered);
    }

    private Set<String> commandWhitelist() {
        final Set<String> commands = new HashSet<>();
        for (final String raw : this.preferences.stringList("modules.ai.npc.command-whitelist", DEFAULT_NPC_COMMANDS)) {
            final String root = commandRoot(raw);
            if (!root.isBlank()) {
                commands.add(root);
            }
        }
        return commands;
    }

    private void send(final Player player, final String message) {
        Bukkit.getScheduler().runTask(this.plugin, () -> {
            if (player.isOnline()) {
                this.sendLines(player, color(message));
            }
        });
    }

    private void sendLines(final Player player, final String message) {
        for (final String line : message.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1)) {
            player.sendMessage(line);
        }
    }

    private static ParsedNpcResponse parseNpcResponse(final String response) {
        final List<String> textLines = new ArrayList<>();
        final List<String> commands = new ArrayList<>();
        boolean look = false;
        String pose = "";
        for (final String rawLine : response.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1)) {
            final String line = rawLine.trim();
            final String lower = line.toLowerCase(Locale.ROOT);
            if (lower.equals("[look]") || lower.equals("[face]") || lower.equals("[look-at-player]")) {
                look = true;
                continue;
            }
            if (lower.startsWith("[pose:") && lower.endsWith("]")) {
                pose = line.substring("[pose:".length(), line.length() - 1).trim();
                continue;
            }
            if (lower.startsWith("[command:") && lower.endsWith("]")) {
                if (commands.size() < 5) {
                    commands.add(line.substring("[command:".length(), line.length() - 1).trim());
                }
                continue;
            }
            textLines.add(rawLine);
        }
        return new ParsedNpcResponse(String.join("\n", textLines).trim(), look, pose, commands);
    }

    private static void faceEntity(final Entity entity, final Player player) {
        final Location from = entity.getLocation();
        final Vector diff = player.getEyeLocation().toVector().subtract(from.toVector());
        final double horizontal = Math.sqrt(diff.getX() * diff.getX() + diff.getZ() * diff.getZ());
        from.setYaw((float) Math.toDegrees(Math.atan2(-diff.getX(), diff.getZ())));
        from.setPitch((float) Math.toDegrees(-Math.atan2(diff.getY(), horizontal)));
        entity.teleportAsync(from);
    }

    private static Pose parsePose(final String value) {
        return switch (HunterToolsPreferences.normalize(value)) {
            case "standing", "stand" -> Pose.STANDING;
            case "sneaking", "crouching", "sneak", "crouch" -> Pose.SNEAKING;
            case "swimming", "swim" -> Pose.SWIMMING;
            case "fall-flying", "fallflying", "elytra" -> Pose.FALL_FLYING;
            case "sleeping", "sleep" -> Pose.SLEEPING;
            default -> null;
        };
    }

    private static URI completionUri(final String baseUrl) {
        final String configured = baseUrl == null || baseUrl.isBlank() ? "https://api.openai.com/v1" : baseUrl.trim();
        if (configured.endsWith("/chat/completions")) {
            return URI.create(configured);
        }
        return URI.create(configured.replaceAll("/+$", "") + "/chat/completions");
    }

    private static String extractContent(final String json) throws IOException {
        int index = 0;
        while (index < json.length()) {
            final int key = json.indexOf("\"content\"", index);
            if (key < 0) {
                break;
            }
            final int colon = json.indexOf(':', key + 9);
            if (colon < 0) {
                break;
            }
            int valueStart = colon + 1;
            while (valueStart < json.length() && Character.isWhitespace(json.charAt(valueStart))) {
                valueStart++;
            }
            if (valueStart >= json.length() || json.charAt(valueStart) != '"') {
                index = valueStart + 1;
                continue;
            }
            final int valueEnd = findStringEnd(json, valueStart + 1);
            if (valueEnd < 0) {
                throw new IOException("Malformed AI JSON response.");
            }
            return unescapeJson(json.substring(valueStart + 1, valueEnd));
        }
        return "";
    }

    private static int findStringEnd(final String json, final int start) {
        boolean escaped = false;
        for (int i = start; i < json.length(); i++) {
            final char c = json.charAt(i);
            if (escaped) {
                escaped = false;
            } else if (c == '\\') {
                escaped = true;
            } else if (c == '"') {
                return i;
            }
        }
        return -1;
    }

    private static String unescapeJson(final String value) {
        final StringBuilder builder = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            final char c = value.charAt(i);
            if (c != '\\' || i + 1 >= value.length()) {
                builder.append(c);
                continue;
            }
            final char next = value.charAt(++i);
            switch (next) {
                case '"' -> builder.append('"');
                case '\\' -> builder.append('\\');
                case '/' -> builder.append('/');
                case 'b' -> builder.append('\b');
                case 'f' -> builder.append('\f');
                case 'n' -> builder.append('\n');
                case 'r' -> builder.append('\r');
                case 't' -> builder.append('\t');
                case 'u' -> {
                    if (i + 4 < value.length()) {
                        try {
                            builder.append((char) Integer.parseInt(value.substring(i + 1, i + 5), 16));
                            i += 4;
                        } catch (final NumberFormatException ex) {
                            builder.append("\\u");
                        }
                    } else {
                        builder.append("\\u");
                    }
                }
                default -> builder.append(next);
            }
        }
        return builder.toString();
    }

    private static String escapeJson(final String value) {
        final StringBuilder builder = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++) {
            final char c = value.charAt(i);
            switch (c) {
                case '"' -> builder.append("\\\"");
                case '\\' -> builder.append("\\\\");
                case '\n' -> builder.append("\\n");
                case '\r' -> builder.append("\\r");
                case '\t' -> builder.append("\\t");
                default -> {
                    if (c < 0x20) {
                        builder.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    } else {
                        builder.append(c);
                    }
                }
            }
        }
        return builder.toString();
    }

    private static String commandRoot(final String command) {
        final String root = command.replaceFirst("^/+", "").trim().split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
        final int namespace = root.indexOf(':');
        return namespace >= 0 && namespace + 1 < root.length() ? root.substring(namespace + 1) : root;
    }

    private static String cleanError(final Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return truncate(current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage(), 160);
    }

    private static String truncate(final String value, final int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max) + "...";
    }

    private static String color(final String text) {
        return ChatColor.translateAlternateColorCodes('&', text);
    }

    private static String defaultChatPrompt() {
        return "You are the native AI assistant for a Minecraft server running HunterCore. "
            + "Answer in the same language as the player when possible. Keep responses useful, friendly, and concise. "
            + "Do not claim server permissions you do not have.";
    }

    private static String defaultNpcPrompt() {
        return "You control a HunterCore Minecraft NPC. Reply as the NPC in one or two short chat lines. "
            + "You may add action lines on their own line: [look], [pose:standing], [pose:sneaking], "
            + "or [command:say text]. Only use commands when they are helpful and safe.";
    }

    private record AiConfig(String baseUrl, String apiKey, String model, double temperature, int maxTokens, int timeoutSeconds) {
    }

    private record ParsedNpcResponse(String text, boolean lookAtPlayer, String pose, List<String> commands) {
    }
}
