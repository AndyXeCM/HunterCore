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
import org.bukkit.entity.Pose;
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
        if (args.length == 2 && List.of("remove", "tp", "tphere", "look", "pose", "click", "info").contains(HunterToolsPreferences.normalize(args[0]))) {
            return matching(args[1], this.knownActorIds(module));
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("look")) {
            return matching(args[2], List.of("north", "south", "east", "west", "up", "down"));
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("pose")) {
            return matching(args[2], poseNames());
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
                actor == null ? (definition == null ? "" : definition.pose()) : poseName(actor.entity().getPose()),
                definition == null ? "" : definition.clickCommand(),
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
            sender.sendMessage("Usage: /" + label + " <spawn|remove|list|tp|tphere|look|pose|click|info|clear>");
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
            case "tphere" -> this.teleportHere(sender, module, label, args);
            case "look" -> this.look(sender, module, label, args);
            case "pose" -> this.pose(sender, module, label, args);
            case "click" -> this.clickCommand(sender, module, label, args);
            case "info" -> this.info(sender, module, label, args);
            case "clear" -> this.clear(sender, module);
            default -> {
                sender.sendMessage("Usage: /" + label + " <spawn|remove|list|tp|tphere|look|pose|click|info|clear>");
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
        final String pose = current == null ? actorPose(actor) : current.pose();
        final String clickCommand = current == null ? "" : current.clickCommand();
        this.preferences.setActorDefinition(module, HunterToolsPreferences.ActorDefinition.of(module, displayName, kind, location, pose, clickCommand));
        this.save();
        sender.sendMessage("Teleported HunterCore " + module + " actor " + id + " to " + locationLine(location) + ".");
        return true;
    }

    private boolean teleportHere(final CommandSender sender, final String module, final String label, final String[] args) {
        if (!(sender instanceof final Player player)) {
            sender.sendMessage("Usage: /" + label + " tphere <name> must be run by a player.");
            return true;
        }
        if (args.length != 2) {
            sender.sendMessage("Usage: /" + label + " tphere <name>");
            return true;
        }
        final String id = HunterToolsPreferences.actorId(args[1]);
        final Location location = player.getLocation();
        final ManagedActor actor = this.liveActors.get(key(module, id));
        final HunterToolsPreferences.ActorDefinition current = this.preferences.actorDefinition(module, id);
        if (actor == null && current == null) {
            sender.sendMessage("HunterCore actor not found: " + id);
            return true;
        }
        if (actor != null) {
            actor.entity().teleportAsync(location);
        }
        final String displayName = current == null ? id : current.displayName();
        final String kind = current == null ? actor.kind() : current.kind();
        final String pose = current == null ? actorPose(actor) : current.pose();
        final String clickCommand = current == null ? "" : current.clickCommand();
        this.preferences.setActorDefinition(module, HunterToolsPreferences.ActorDefinition.of(module, displayName, kind, location, pose, clickCommand));
        this.save();
        sender.sendMessage("Moved HunterCore " + module + " actor " + id + " to you.");
        return true;
    }

    private boolean look(final CommandSender sender, final String module, final String label, final String[] args) {
        if (args.length < 2 || args.length > 4) {
            sender.sendMessage("Usage: /" + label + " look <name> [yaw pitch|north|south|east|west|up|down]");
            return true;
        }
        final String id = HunterToolsPreferences.actorId(args[1]);
        final ManagedActor actor = this.liveActors.get(key(module, id));
        final HunterToolsPreferences.ActorDefinition current = this.preferences.actorDefinition(module, id);
        if (actor == null && current == null) {
            sender.sendMessage("HunterCore actor not found: " + id);
            return true;
        }
        final float[] rotation = this.rotation(sender, label, args, 2);
        if (rotation == null) {
            return true;
        }
        final Location location = actorLocation(actor, current);
        if (location == null) {
            sender.sendMessage("HunterCore actor " + id + " has no loaded location.");
            return true;
        }
        location.setYaw(rotation[0]);
        location.setPitch(clampPitch(rotation[1]));
        if (actor != null) {
            actor.entity().teleportAsync(location);
        }
        final String displayName = current == null ? id : current.displayName();
        final String kind = current == null ? actor.kind() : current.kind();
        final String pose = current == null ? actorPose(actor) : current.pose();
        final String clickCommand = current == null ? "" : current.clickCommand();
        this.preferences.setActorDefinition(module, HunterToolsPreferences.ActorDefinition.of(module, displayName, kind, location, pose, clickCommand));
        this.save();
        sender.sendMessage("Rotated HunterCore " + module + " actor " + id + " to yaw " + format(rotation[0]) + ", pitch " + format(clampPitch(rotation[1])) + ".");
        return true;
    }

    private boolean pose(final CommandSender sender, final String module, final String label, final String[] args) {
        if (args.length != 3) {
            sender.sendMessage("Usage: /" + label + " pose <name> <standing|sneaking|swimming|fall-flying|sleeping>");
            return true;
        }
        final String id = HunterToolsPreferences.actorId(args[1]);
        final Pose pose = parsePose(args[2]);
        if (pose == null || !Mannequin.validPoses().contains(pose)) {
            sender.sendMessage("Pose must be one of: " + String.join(", ", poseNames()));
            return true;
        }
        final ManagedActor actor = this.liveActors.get(key(module, id));
        final HunterToolsPreferences.ActorDefinition current = this.preferences.actorDefinition(module, id);
        if (actor == null && current == null) {
            sender.sendMessage("HunterCore actor not found: " + id);
            return true;
        }
        if (!isMannequinActor(module, actor, current)) {
            sender.sendMessage("Only mannequin actors support fixed poses.");
            return true;
        }
        if (actor != null) {
            final Mannequin mannequin = (Mannequin) actor.entity();
            mannequin.setPose(pose, true);
        }
        final Location location = actorLocation(actor, current);
        if (location == null) {
            sender.sendMessage("HunterCore actor " + id + " has no loaded location.");
            return true;
        }
        final String displayName = current == null ? id : current.displayName();
        final String kind = current == null ? actor.kind() : current.kind();
        final String clickCommand = current == null ? "" : current.clickCommand();
        this.preferences.setActorDefinition(module, HunterToolsPreferences.ActorDefinition.of(module, displayName, kind, location, poseName(pose), clickCommand));
        this.save();
        sender.sendMessage("Set HunterCore " + module + " actor " + id + " pose to " + poseName(pose) + ".");
        return true;
    }

    private boolean clickCommand(final CommandSender sender, final String module, final String label, final String[] args) {
        if (args.length < 2) {
            sender.sendMessage("Usage: /" + label + " click <name> [command|clear]");
            return true;
        }
        final String id = HunterToolsPreferences.actorId(args[1]);
        final ManagedActor actor = this.liveActors.get(key(module, id));
        final HunterToolsPreferences.ActorDefinition current = this.preferences.actorDefinition(module, id);
        if (actor == null && current == null) {
            sender.sendMessage("HunterCore actor not found: " + id);
            return true;
        }
        if (args.length == 2) {
            final String command = current == null ? "" : current.clickCommand();
            sender.sendMessage("HunterCore actor " + id + " click command: " + (command == null || command.isBlank() ? "not configured" : command));
            return true;
        }
        final String command = String.join(" ", java.util.Arrays.copyOfRange(args, 2, args.length)).trim();
        final String savedCommand = List.of("clear", "none", "off", "-").contains(HunterToolsPreferences.normalize(command)) ? "" : sanitizeCommand(command);
        if (!this.setClickCommand(module, id, savedCommand)) {
            sender.sendMessage("HunterCore actor not found: " + id);
            return true;
        }
        sender.sendMessage("HunterCore " + module + " actor " + id + " click command " + (savedCommand.isBlank() ? "cleared." : "set."));
        return true;
    }

    private boolean info(final CommandSender sender, final String module, final String label, final String[] args) {
        if (args.length > 2) {
            sender.sendMessage("Usage: /" + label + " info [name]");
            return true;
        }
        if (args.length == 1) {
            sender.sendMessage(ChatColor.GOLD + "HunterCore " + module + " actors");
            sender.sendMessage("- Lightweight mannequin/villager entities for display, admin UI and placement tests.");
            sender.sendMessage("- They do not join as real ServerPlayer instances, occupy player slots, load chunks or run Carpet-style use/attack loops.");
            sender.sendMessage("- Commands: spawn, remove, list, tp, tphere, look, pose, click, info, clear.");
            sender.sendMessage("- Click command placeholders: %player%, %player_uuid%, %actor%, %actor_uuid%, %module%, %world%, %x%, %y%, %z%.");
            return true;
        }
        final String id = HunterToolsPreferences.actorId(args[1]);
        final ManagedActor actor = this.liveActors.get(key(module, id));
        final HunterToolsPreferences.ActorDefinition definition = this.preferences.actorDefinition(module, id);
        if (actor == null && definition == null) {
            sender.sendMessage("HunterCore actor not found: " + id);
            return true;
        }
        final Location location = actorLocation(actor, definition);
        final String kind = actor == null ? definition.kind() : actor.kind();
        final String pose = actor == null ? definition.pose() : actorPose(actor);
        final String clickCommand = definition == null ? "" : definition.clickCommand();
        sender.sendMessage(ChatColor.GOLD + "HunterCore actor " + id);
        sender.sendMessage("- module: " + module);
        sender.sendMessage("- kind: " + kind);
        sender.sendMessage("- state: " + (actor == null ? "configured" : "live"));
        sender.sendMessage("- pose: " + pose);
        sender.sendMessage("- click command: " + (clickCommand == null || clickCommand.isBlank() ? "not configured" : clickCommand));
        sender.sendMessage("- location: " + (location == null ? "not loaded" : locationLine(location)));
        sender.sendMessage("- uuid: " + (actor == null ? definition.uuid() : actor.entityUuid()));
        return true;
    }

    boolean setClickCommand(final String module, final String id, final String command) {
        final String actorId = HunterToolsPreferences.actorId(id);
        final ManagedActor actor = this.liveActors.get(key(module, actorId));
        final HunterToolsPreferences.ActorDefinition current = this.preferences.actorDefinition(module, actorId);
        if (actor == null && current == null) {
            return false;
        }
        final Location location = actorLocation(actor, current);
        if (location == null) {
            return false;
        }
        final String displayName = current == null ? actorId : current.displayName();
        final String kind = current == null ? actor.kind() : current.kind();
        final String pose = current == null ? actorPose(actor) : current.pose();
        this.preferences.setActorDefinition(module, HunterToolsPreferences.ActorDefinition.of(module, displayName, kind, location, pose, sanitizeCommand(command)));
        this.save();
        return true;
    }

    boolean handleInteract(final Player player, final Entity clicked) {
        final String module = clicked.getPersistentDataContainer().get(this.moduleKey, PersistentDataType.STRING);
        final String id = clicked.getPersistentDataContainer().get(this.idKey, PersistentDataType.STRING);
        if (module == null || id == null || !this.preferences.moduleEnabled(module)) {
            return false;
        }
        final HunterToolsPreferences.ActorDefinition definition = this.preferences.actorDefinition(module, id);
        final String command = definition == null ? "" : definition.clickCommand();
        if (command == null || command.isBlank()) {
            return false;
        }
        final String rendered = renderClickCommand(command, player, clicked, module, id);
        if (rendered.isBlank()) {
            return false;
        }
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), rendered);
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
        this.applyStoredPose(entity, definition.pose());
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

    private @Nullable float[] rotation(final CommandSender sender, final String label, final String[] args, final int index) {
        if (args.length == index) {
            if (sender instanceof final Player player) {
                final Location location = player.getLocation();
                return new float[] {location.getYaw(), location.getPitch()};
            }
            sender.sendMessage("Usage: /" + label + " look <name> <yaw pitch|north|south|east|west|up|down>");
            return null;
        }
        if (args.length == index + 1) {
            return switch (HunterToolsPreferences.normalize(args[index])) {
                case "south" -> new float[] {0.0F, 0.0F};
                case "west" -> new float[] {90.0F, 0.0F};
                case "north" -> new float[] {180.0F, 0.0F};
                case "east" -> new float[] {-90.0F, 0.0F};
                case "up" -> new float[] {0.0F, -90.0F};
                case "down" -> new float[] {0.0F, 90.0F};
                default -> {
                    sender.sendMessage("Direction must be one of north, south, east, west, up, down.");
                    yield null;
                }
            };
        }
        try {
            return new float[] {Float.parseFloat(args[index]), Float.parseFloat(args[index + 1])};
        } catch (final NumberFormatException ex) {
            sender.sendMessage("Yaw and pitch must be numbers.");
            return null;
        }
    }

    private @Nullable Location actorLocation(final @Nullable ManagedActor actor, final @Nullable HunterToolsPreferences.ActorDefinition definition) {
        if (actor != null) {
            final Entity entity = Bukkit.getEntity(actor.entityUuid());
            if (entity != null && entity.isValid()) {
                return entity.getLocation();
            }
        }
        return definition == null ? null : definition.location();
    }

    private void applyStoredPose(final Entity entity, final String rawPose) {
        if (!(entity instanceof final Mannequin mannequin)) {
            return;
        }
        final Pose pose = parsePose(rawPose);
        if (pose != null && Mannequin.validPoses().contains(pose)) {
            mannequin.setPose(pose, true);
        }
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

    private static boolean isMannequinActor(
        final String module,
        final @Nullable ManagedActor actor,
        final @Nullable HunterToolsPreferences.ActorDefinition definition
    ) {
        if (module.equals(FAKE_PLAYERS)) {
            return true;
        }
        if (actor != null) {
            return actor.entity() instanceof Mannequin;
        }
        return definition != null && HunterToolsPreferences.normalize(definition.kind()).equals("mannequin");
    }

    private static @Nullable Pose parsePose(final String value) {
        return switch (HunterToolsPreferences.normalize(value)) {
            case "standing", "stand" -> Pose.STANDING;
            case "sneaking", "crouching", "sneak", "crouch" -> Pose.SNEAKING;
            case "swimming", "swim" -> Pose.SWIMMING;
            case "fall-flying", "fallflying", "elytra" -> Pose.FALL_FLYING;
            case "sleeping", "sleep" -> Pose.SLEEPING;
            default -> null;
        };
    }

    private static List<String> poseNames() {
        return List.of("standing", "sneaking", "swimming", "fall-flying", "sleeping");
    }

    private static String poseName(final Pose pose) {
        return switch (pose) {
            case STANDING -> "standing";
            case SNEAKING -> "sneaking";
            case SWIMMING -> "swimming";
            case FALL_FLYING -> "fall-flying";
            case SLEEPING -> "sleeping";
            default -> pose.name().toLowerCase(Locale.ROOT).replace('_', '-');
        };
    }

    private static String actorPose(final @Nullable ManagedActor actor) {
        return actor == null ? "standing" : poseName(actor.entity().getPose());
    }

    private static String sanitizeCommand(final String command) {
        if (command == null) {
            return "";
        }
        String sanitized = command.replace('\n', ' ').replace('\r', ' ').trim();
        if (sanitized.startsWith("/")) {
            sanitized = sanitized.substring(1).trim();
        }
        return sanitized.length() > 512 ? sanitized.substring(0, 512).trim() : sanitized;
    }

    private static String renderClickCommand(
        final String command,
        final Player player,
        final Entity actor,
        final String module,
        final String id
    ) {
        final Location location = actor.getLocation();
        return sanitizeCommand(command)
            .replace("%player%", player.getName())
            .replace("%player_uuid%", player.getUniqueId().toString())
            .replace("%actor%", id)
            .replace("%actor_name%", id)
            .replace("%actor_uuid%", actor.getUniqueId().toString())
            .replace("%module%", module)
            .replace("%world%", location.getWorld() == null ? "" : location.getWorld().getName())
            .replace("%x%", String.format(Locale.ROOT, "%.2f", location.getX()))
            .replace("%y%", String.format(Locale.ROOT, "%.2f", location.getY()))
            .replace("%z%", String.format(Locale.ROOT, "%.2f", location.getZ()));
    }

    private static float clampPitch(final float pitch) {
        return Math.max(-90.0F, Math.min(90.0F, pitch));
    }

    private static String format(final float value) {
        return String.format(Locale.ROOT, "%.1f", value);
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
        String pose,
        String clickCommand,
        boolean live,
        String entityUuid
    ) {
    }
}
