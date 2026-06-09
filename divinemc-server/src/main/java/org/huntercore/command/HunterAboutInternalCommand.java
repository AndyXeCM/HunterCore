package org.huntercore.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;

public final class HunterAboutInternalCommand {
    public static final String DESCRIPTION = "Shows HunterCore server information";

    private HunterAboutInternalCommand() {
    }

    public static LiteralCommandNode<CommandSourceStack> create() {
        return Commands.literal("about")
            .requires(source -> source.getSender().hasPermission("bukkit.command.version"))
            .executes(context -> {
                context.getSource().getSender().sendMessage(HunterMessages.about());
                return Command.SINGLE_SUCCESS;
            })
            .build();
    }
}
