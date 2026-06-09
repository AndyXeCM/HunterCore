package org.huntercore.plugins.tools;

import io.papermc.paper.datacomponent.item.ResolvableProfile;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.Nullable;

final class HunterActorManager {
    private static final String FAKE_PLAYERS = "fake-players";
    private static final String NPCS = "npcs";

    private final JavaPlugin plugin;
    private final HunterToolsPreferences preferences;
    private final NamespacedKey moduleKey;
    private final NamespacedKey idKey;
    private final Map<String, ManagedActor> liveActors = new HashMap<>();
    private Executor executor;

    HunterActorManager(final JavaPlugin plugin, final HunterToolsPreferences preferences, final Executor executor) {
        this.plugin = plugin;
        this.preferences = preferences;
        this.executor = executor;
        this.moduleKey = new NamespacedKey(plugin, "actor_module");
        this.idKey = new NamespacedKey(plugin, "actor_id");
    }

    void setExecutor(final Executor executor) {
        this.executor = executor;
    }

    void reload() {
        this.removeLiveActors(false);
        this.cleanupTaggedActors();
        final Runnable load = () -> {
            final List<HunterToolsPreferences.ActorDefinition> fakePlayers = this.preferences.actorIds(FAKE_PLAYERS).stream()
                .map(id -> this.preferences.actorDefinition(FAKE_PLAYERS, id))
                .filter(definition -> definition != null)
                .toList();
            final List<HunterToolsPreferences.ActorDefinition> npcs = this.preferences.actorIds(NPCS).stream()
                .map(id -> this.preferences.actorDefinition(NPCS, id))
                .filter(definition -> definition != null)
                .toList();
            this.plugin.getServer().getScheduler().runTask(this.plugin, () -> {
                if (this.preferences.moduleEnabled(FAKE_PLAYERS)) {
                    fakePlayers.forEach(definition -> this.spawnDefinition(FAKE_PLAYERS, definition));
                }
                if (this.preferences.moduleEnabled(NPCS)) {
                    npcs.forEach(definition -> this.spawnDefinition(NPCS, definition));
                }
            });
        };
        if (this.preferences.booleanValue("optimizations.enabled", true)
            && this.preferences.booleanValue("optimizations.hunter-tools.actor-async-load", true)) {
            CompletableFuture.runAsync(load, this.executor);
        } else {
            load.run();
        }
    }

    void shutdown() {
        this.removeLiveActors(true);
    }

    boolean fakePlayerCommand(final CommandSender sender, final String[] args) {
        return this.actorCommand(sender, FAKE_PLAYERS, "fakeplayer", args);
    }

    boolean npcCommand(final CommandSender sender, final String[] args) {
        return this.actorCommand(sender, NPCS, "npc", args);
    }

    List<String> completions(final String module, final String[] args) {
        if (args.length == 1) {
            return matching(args[0], HunterToolsPreferences.actorCommands());
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("remove") || args[0].equalsIgnoreCase("tp"))) {
            return matching(args[1], this.knownActorIds(module));
        }
        if (module.equals(NPCS) && args.length == 3 && args[0].equalsIgnoreCase("spawn")) {
            return matching(args[2], List.of("villager", "mannequin"));
        }
        return List.of();
    }

    int liveCount(final String module) {
        int count = 0;
        for (final ManagedActor actor : this.liveActors.values()) {
            if (actor.module().equals(module)) {
                count++;
            }
        }
        return count;
    }

    List<ActorView> views(final String module) {
        final List<ActorView> views = new ArrayList<>();
        for (final String id : this.knownActorIds(module)) {
            final HunterToolsPreferences.ActorDefinition definition = this.preferences.actorDefinition(module, id);
            final ManagedActor actor = this.liveActors.get(key(module, id));
            views.add(new ActorView(
                module,
                id,
                definition == null ? id : definition.displayName(),
                actor == null ? (definition == null ? "" : definition.kind()) : actor.kind(),
                definition == null ? "" : definition.world(),
                definition == null ? 0.0D : definition.x(),
                definition == null ? 0.0D : definition.y(),
                definition == null ? 0.0D : definition.z(),
                definition == null ? 0.0F : definition.yaw(),
                definition == null ? 0.0F : definition.pitch(),
                actor != null,
                actor == null ? "" : actor.entityUuid().toString()
            ));
        }
        return views;
    }

    private boolean actorCommand(final CommandSender sender, final String module, final String label, final String[] args) {
        if (!this.preferences.moduleEnabled(module)) {
            sender.sendMessage("HunterCore " + module + " module is disabled in preferences.yml.");
            return true;
        }
        if (args.length == 0) {
            sender.sendMessage("Usage: /" + label + " <spawn|remove|list|tp|clear>");
            return true;
        }
        final String sub = HunterToolsPreferences.normalize(args[0]);
        if (!this.preferences.commandEnabled(module, sub)) {
            sender.sendMessage("HunterCore " + module + " command " + sub + " is disabled in preferences.yml.");
            return true;
        }
        return switch (sub) {
            case "spawn" -> this.spawn(sender, module, label, args);
            case "remove" -> this.remove(sender, module, label, args);
            case "list" -> this.list(sender, module);
            case "tp" -> this.teleport(sender, module, label, args);
            case "clear" -> this.clear(sender, module);
            default -> {
                sender.sendMessage("Usage: /" + label + " <spawn|remove|list|tp|clear>");
                yield true;
            }
        };
    }

    private boolean spawn(final CommandSender sender, final String module, final String label, final String[] args) {
        if (args.length < 2) {
            sender.sendMessage("Usage: /" + label + " spawn <name>" + (module.equals(NPCS) ? " [villager|mannequin]" : "") + " [world x y z [yaw pitch]]");
            return true;
        }
        final int max = Math.max(1, this.preferences.intValue("modules." + module + ".max-active", 64));
        if (this.liveCount(module) >= max) {
            sender.sendMessage("HunterCore " + module + " limit reached: " + max);
            return true;
        }
        final String name = args[1];
        String kind = module.equals(NPCS) ? this.preferences.stringValue("modules.npcs.default-type", "villager") : "mannequin";
        int locationIndex = 2;
        if (module.equals(NPCS) && args.length >= 3 && isNpcKind(args[2])) {
            kind = args[2];
            locationIndex = 3;
        }
        final Location location = this.location(sender, args, locationIndex);
        if (location == null) {
            return true;
        }
        final HunterToolsPreferences.ActorDefinition definition = HunterToolsPreferences.ActorDefinition.of(module, name, kind, location);
        if (this.liveActors.containsKey(key(module, definition.id()))) {
            sender.sendMessage("HunterCore actor already exists: " + definition.id());
            return true;
        }
        if (this.spawnDefinition(module, definition) == null) {
            sender.sendMessage("Failed to spawn HunterCore actor " + definition.id() + ".");
            return true;
        }
        if (this.preferences.booleanValue("modules." + module + ".persist", true)) {
            this.preferences.setActorDefinition(module, definition);
            this.save();
        }
        sender.sendMessage("Spawned HunterCore " + module + " actor " + definition.id() + " at " + locationLine(location) + ".");
        return true;
    }

    private boolean remove(final CommandSender sender, final String module, final String label, final String[] args) {
        if (args.length != 2) {
            sender.sendMessage("Usage: /" + label + " remove <name>");
            return true;
        }
        final String id = HunterToolsPreferences.actorId(args[1]);
        final boolean removed = this.removeActor(module, id);
        this.preferences.removeActorDefinition(module, id);
        this.save();
        sender.sendMessage((removed ? "Removed" : "Forgot") + " HunterCore " + module + " actor " + id + ".");
        return true;
    }

    private boolean list(final CommandSender sender, final String module) {
        final List<String> ids = this.knownActorIds(module);
        sender.sendMessage(ChatColor.GOLD + "HunterCore " + module + ": " + ChatColor.WHITE + this.liveCount(module) + " live, " + ids.size() + " configured");
        for (final String id : ids) {
            final ManagedActor actor = this.liveActors.get(key(module, id));
            sender.sendMessage("- " + id + ": " + (actor == null ? ChatColor.RED + "not loaded" : ChatColor.GREEN + actor.kind() + " " + actor.entityUuid()));
        }
        return true;
    }

    private boolean teleport(final CommandSender sender, final String module, final String label, final String[] args) {
        if (args.length < 2) {
            sender.sendMessage("Usage: /" + label + " tp <name> [world x y z [yaw pitch]]");
            return true;
        }
        final String id = HunterToolsPreferences.actorId(args[1]);
        final Location location = this.location(sender, args, 2);
        if (location == null) {
            return true;
        }
        final ManagedActor actor = this.liveActors.get(key(module, id));
        final HunterToolsPreferences.ActorDefinition current = this.preferences.actorDefinition(module, id);
        if (actor == null && current == null) {
            sender.sendMessage("HunterCore actor not found: " + id);
            return true;
        }
        if (actor != null) {
            final Entity entity = Bukkit.getEntity(actor.entityUuid());
            if (entity != null && entity.isValid()) {
                entity.teleportAsync(location);
            }
        }
        final String displayName = current == null ? id : current.displayName();
        final String kind = current == null ? actor.kind() : current.kind();
        this.preferences.setActorDefinition(module, HunterToolsPreferences.ActorDefinition.of(module, displayName, kind, location));
        this.save();
        sender.sendMessage("Teleported HunterCore " + module + " actor " + id + " to " + locationLine(location) + ".");
        return true;
    }

    private boolean clear(final CommandSender sender, final String module) {
        int removed = 0;
        for (final String id : new ArrayList<>(this.knownActorIds(module))) {
            if (this.removeActor(module, id)) {
                removed++;
            }
        }
        this.preferences.clearActorDefinitions(module);
        this.save();
        sender.sendMessage("Cleared " + removed + " HunterCore " + module + " actor(s).");
        return true;
    }

    private @Nullable Entity spawnDefinition(final String module, final HunterToolsPreferences.ActorDefinition definition) {
        final Location location = definition.location();
        if (location == null || location.getWorld() == null) {
            this.plugin.getLogger().warning("Skipping HunterCore actor " + definition.id() + ": world is not loaded.");
            return null;
        }
        final Entity entity;
        final String kind = module.equals(FAKE_PLAYERS) ? "mannequin" : HunterToolsPreferences.normalize(definition.kind());
        if (kind.equals("villager")) {
            entity = this.spawnVillager(definition, location);
        } else {
            entity = this.spawnMannequin(module, definition, location);
        }
        this.tag(entity, module, definition.id());
        this.liveActors.put(key(module, definition.id()), new ManagedActor(module, definition.id(), kind, entity.getUniqueId(), entity));
        return entity;
    }

    private Mannequin spawnMannequin(final String module, final HunterToolsPreferences.ActorDefinition definition, final Location location) {
        return location.getWorld().spawn(location, Mannequin.class, CreatureSpawnEvent.SpawnReason.CUSTOM, false, mannequin -> {
            this.prepareLiving(mannequin, definition.displayName());
            mannequin.setImmovable(true);
            mannequin.setDescription(Component.text(module.equals(FAKE_PLAYERS) ? "HunterCore fake player" : "HunterCore NPC", NamedTextColor.GRAY));
            mannequin.setProfile(ResolvableProfile.resolvableProfile()
                .name(definition.displayName())
                .uuid(definition.uuid())
                .build());
        });
    }

    private Villager spawnVillager(final HunterToolsPreferences.ActorDefinition definition, final Location location) {
        return location.getWorld().spawn(location, Villager.class, CreatureSpawnEvent.SpawnReason.CUSTOM, false, villager -> {
            this.prepareLiving(villager, definition.displayName());
            villager.setVillagerLevel(1);
            villager.setVillagerType(Villager.Type.PLAINS);
            villager.setProfession(Villager.Profession.NONE);
            villager.setAI(this.preferences.booleanValue("modules.npcs.villager-ai", false));
        });
    }

    private void prepareLiving(final LivingEntity entity, final String displayName) {
        entity.customName(Component.text(displayName, NamedTextColor.AQUA));
        entity.setCustomNameVisible(true);
        entity.setInvulnerable(true);
        entity.setSilent(true);
        entity.setPersistent(false);
        entity.setRemoveWhenFarAway(false);
        entity.setCanPickupItems(false);
        entity.setCollidable(false);
    }

    private void tag(final Entity entity, final String module, final String id) {
        entity.getPersistentDataContainer().set(this.moduleKey, PersistentDataType.STRING, module);
        entity.getPersistentDataContainer().set(this.idKey, PersistentDataType.STRING, id);
        entity.addScoreboardTag("huntercore_actor");
    }

    private void cleanupTaggedActors() {
        for (final World world : Bukkit.getWorlds()) {
            for (final Entity entity : world.getEntities()) {
                if (entity.getPersistentDataContainer().has(this.moduleKey, PersistentDataType.STRING)
                    && entity.getPersistentDataContainer().has(this.idKey, PersistentDataType.STRING)) {
                    entity.remove();
                }
            }
        }
        this.liveActors.clear();
    }

    private void removeLiveActors(final boolean disable) {
        for (final ManagedActor actor : new ArrayList<>(this.liveActors.values())) {
            if (disable && !this.preferences.booleanValue("modules." + actor.module() + ".remove-on-disable", true)) {
                continue;
            }
            this.removeManagedActor(actor);
        }
        this.liveActors.clear();
    }

    private boolean removeActor(final String module, final String id) {
        final ManagedActor actor = this.liveActors.remove(key(module, id));
        if (actor != null) {
            this.removeManagedActor(actor);
            this.removeTaggedActor(module, id);
            return true;
        }
        return this.removeTaggedActor(module, id);
    }

    private void removeManagedActor(final ManagedActor actor) {
        actor.entity().remove();
        final Entity indexedEntity = Bukkit.getEntity(actor.entityUuid());
        if (indexedEntity != null && indexedEntity != actor.entity()) {
            indexedEntity.remove();
        }
    }

    private boolean removeTaggedActor(final String module, final String id) {
        boolean removed = false;
        for (final World world : Bukkit.getWorlds()) {
            for (final Entity entity : world.getEntities()) {
                final String entityModule = entity.getPersistentDataContainer().get(this.moduleKey, PersistentDataType.STRING);
                final String entityId = entity.getPersistentDataContainer().get(this.idKey, PersistentDataType.STRING);
                if (module.equals(entityModule) && id.equals(entityId)) {
                    entity.remove();
                    removed = true;
                }
            }
        }
        return removed;
    }

    private Location location(final CommandSender sender, final String[] args, final int index) {
        if (args.length >= index + 4) {
            final World world = Bukkit.getWorld(args[index]);
            if (world == null) {
                sender.sendMessage("World not found: " + args[index]);
                return null;
            }
            try {
                final double x = Double.parseDouble(args[index + 1]);
                final double y = Double.parseDouble(args[index + 2]);
                final double z = Double.parseDouble(args[index + 3]);
                final float yaw = args.length >= index + 5 ? Float.parseFloat(args[index + 4]) : 0.0F;
                final float pitch = args.length >= index + 6 ? Float.parseFloat(args[index + 5]) : 0.0F;
                return new Location(world, x, y, z, yaw, pitch);
            } catch (final NumberFormatException ex) {
                sender.sendMessage("Coordinates must be numbers.");
                return null;
            }
        }
        if (sender instanceof final Player player) {
            return player.getLocation();
        }
        final World world = Bukkit.getWorlds().isEmpty() ? null : Bukkit.getWorlds().getFirst();
        if (world == null) {
            sender.sendMessage("No loaded worlds are available.");
            return null;
        }
        return world.getSpawnLocation().add(0.5D, 0.0D, 0.5D);
    }

    private void save() {
        if (this.preferences.booleanValue("optimizations.hunter-tools.actor-batch-save", true)) {
            this.preferences.save(this.executor);
        } else {
            this.preferences.saveNow();
        }
    }

    private List<String> knownActorIds(final String module) {
        final List<String> ids = new ArrayList<>(this.preferences.actorIds(module));
        for (final ManagedActor actor : this.liveActors.values()) {
            if (actor.module().equals(module) && !ids.contains(actor.id())) {
                ids.add(actor.id());
            }
        }
        ids.sort(String::compareToIgnoreCase);
        return ids;
    }

    private static boolean isNpcKind(final String value) {
        final String kind = HunterToolsPreferences.normalize(value);
        return kind.equals("villager") || kind.equals("mannequin");
    }

    private static String key(final String module, final String id) {
        return module + ":" + HunterToolsPreferences.actorId(id);
    }

    private static String locationLine(final Location location) {
        return location.getWorld().getName()
            + " "
            + String.format(Locale.ROOT, "%.1f %.1f %.1f", location.getX(), location.getY(), location.getZ());
    }

    private static List<String> matching(final String prefix, final List<String> values) {
        final String lower = prefix.toLowerCase(Locale.ROOT);
        final List<String> matches = new ArrayList<>();
        for (final String value : values) {
            if (value.toLowerCase(Locale.ROOT).startsWith(lower)) {
                matches.add(value);
            }
        }
        return matches;
    }

    private record ManagedActor(String module, String id, String kind, UUID entityUuid, Entity entity) {
    }

    record ActorView(
        String module,
        String id,
        String displayName,
        String kind,
        String world,
        double x,
        double y,
        double z,
        float yaw,
        float pitch,
        boolean live,
        String entityUuid
    ) {
    }
}
