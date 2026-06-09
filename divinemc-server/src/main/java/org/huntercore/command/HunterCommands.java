package org.huntercore.command;

import net.minecraft.server.MinecraftServer;
import org.bukkit.command.Command;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.framework.qual.DefaultQualifier;
import org.huntercore.bootstrap.HunterCoreBootstrap;
import org.huntercore.bootstrap.HunterCoreRuntime;

@DefaultQualifier(NonNull.class)
public final class HunterCommands {
    private HunterCommands() {
    }

    public static void registerCommands(final MinecraftServer server) {
        HunterCoreBootstrap.init();
        final Command command = new HunterCoreCommand();
        server.server.getCommandMap().register(HunterCoreCommand.COMMAND_LABEL, HunterCoreRuntime.COMMAND_NAMESPACE, command);
    }
}
