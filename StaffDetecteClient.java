package it.staffdetecte;

import com.mojang.brigadier.CommandDispatcher;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class StaffDetecteClient implements ClientModInitializer {
    public static final String MOD_ID = "staffdetecte";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    /** La GUI viene aperta al tick successivo, altrimenti la chiusura della chat la annulla. */
    private static boolean openGuiPending = false;

    @Override
    public void onInitializeClient() {
        StaffConfig.load();

        ClientCommandRegistrationCallback.EVENT.register(
                (dispatcher, registryAccess) -> registerCommands(dispatcher));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (openGuiPending && client.currentScreen == null) {
                openGuiPending = false;
                client.setScreen(new StaffListScreen());
            }
            StaffMonitor.tick(client);
        });
    }

    private static void registerCommands(CommandDispatcher<FabricClientCommandSource> dispatcher) {
        dispatcher.register(ClientCommandManager.literal("staffdetecte")
                .executes(ctx -> {
                    openGuiPending = true;
                    return 1;
                })
                .then(ClientCommandManager.literal("on")
                        .executes(ctx -> setEnabled(ctx.getSource(), true)))
                .then(ClientCommandManager.literal("off")
                        .executes(ctx -> setEnabled(ctx.getSource(), false))));
    }

    private static int setEnabled(FabricClientCommandSource source, boolean value) {
        StaffConfig cfg = StaffConfig.get();
        cfg.enabled = value;
        cfg.save();
        StaffMonitor.reset();

        source.sendFeedback(StaffMonitor.prefix().append(
                Text.literal(value ? "Rilevamento ATTIVATO" : "Rilevamento DISATTIVATO")
                        .formatted(value ? Formatting.GREEN : Formatting.RED)));
        if (value && cfg.staff.isEmpty()) {
            source.sendFeedback(StaffMonitor.prefix().append(
                    Text.literal("La lista è vuota: usa /staffdetecte per aggiungere i nick.")
                            .formatted(Formatting.GRAY)));
        }
        return 1;
    }
}
