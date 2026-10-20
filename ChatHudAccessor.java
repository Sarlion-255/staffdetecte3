package it.staffdetecte.mixin;

import net.minecraft.client.gui.hud.ChatHud;
import net.minecraft.client.gui.hud.ChatHudLine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.List;

/** Permette di togliere del tutto una riga dalla chat (senza il segnaposto "deleted by the server"). */
@Mixin(ChatHud.class)
public interface ChatHudAccessor {
    @Accessor("messages")
    List<ChatHudLine> staffdetecte$getMessages();

    @Invoker("refresh")
    void staffdetecte$refresh();
}
