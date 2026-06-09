package org.huntercore.api;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Extension point for adding subcommands under /huntercore.
 */
public interface HunterCommandExtension {

    @NotNull String name();

    default @NotNull Collection<String> aliases() {
        return Collections.emptyList();
    }

    default @Nullable String permission() {
        return null;
    }

    default @NotNull String description() {
        return "";
    }

    boolean execute(@NotNull CommandSender sender, @NotNull String label, @NotNull String[] args);

    default @NotNull List<String> tabComplete(@NotNull CommandSender sender, @NotNull String alias, @NotNull String[] args) {
        return Collections.emptyList();
    }
}
