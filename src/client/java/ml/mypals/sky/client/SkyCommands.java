package ml.mypals.sky.client;

import com.mojang.brigadier.CommandDispatcher;
import ml.mypals.sky.client.iris.IrisAccess;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.network.chat.Component;

/** {@code /skyreceiver [on|off]}: whether sunlight comes through the sky blocks in the receiver mode or the maps mode. */
final class SkyCommands {
    private SkyCommands() {
    }

    static void register(CommandDispatcher<FabricClientCommandSource> dispatcher) {
        dispatcher.register(ClientCommands.literal("skyreceiver")
                .executes(context -> feedback(context.getSource()))
                .then(ClientCommands.literal("on").executes(context -> set(context.getSource(), true)))
                .then(ClientCommands.literal("off").executes(context -> set(context.getSource(), false))));
    }

    private static int set(FabricClientCommandSource source, boolean on) {
        if (ThroughDepth.wanted() != on) {
            ThroughDepth.setWanted(on);
            // The shadow programs were patched for the mode in use when they compiled.
            if (SkyClient.packInUse()) {
                IrisAccess.reload();
            }
        }
        return feedback(source);
    }

    private static int feedback(FabricClientCommandSource source) {
        if (ThroughDepth.wanted() && !ThroughDepth.supported()) {
            source.sendFeedback(Component.translatable("commands.sky.skyreceiver.unsupported"));
        } else {
            source.sendFeedback(Component.translatable(ThroughDepth.wanted() ? "commands.sky.skyreceiver.on" : "commands.sky.skyreceiver.off"));
        }
        return 1;
    }
}
