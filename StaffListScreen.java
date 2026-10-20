package it.staffdetecte;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.List;

/**
 * GUI aperta da /staffdetecte: elenco degli staffer da riconoscere.
 * - scrivi un nick e premi SPAZIO (o virgola) per aggiungerlo;
 * - incolla una lista di nick separati da spazi/virgole/righe: li aggiunge tutti;
 * - "Aggiungi" aggiunge quello che hai scritto; "Rimuovi" toglie un nick.
 */
public class StaffListScreen extends Screen {
    private static final int ROW_H = 22;
    private static final int LIST_TOP = 78;
    private static final int COL_W = 300;

    private TextFieldWidget input;
    private String inputText = "";
    private boolean updating;
    private boolean rebuild;
    private int page;
    private int rows = 5;
    private Text status = Text.empty();

    public StaffListScreen() {
        super(Text.literal("StaffDetecte - lista staffer"));
    }

    @Override
    protected void init() {
        StaffConfig cfg = StaffConfig.get();
        int cx = this.width / 2;
        int left = cx - COL_W / 2;

        rows = Math.max(3, Math.min(10, (this.height - LIST_TOP - 64) / ROW_H));
        int pages = pageCount();
        page = Math.max(0, Math.min(page, pages - 1));

        // Campo di inserimento
        input = new TextFieldWidget(this.textRenderer, left, 40, COL_W - 90, 20, Text.literal("Nick"));
        input.setMaxLength(4000);
        input.setPlaceholder(Text.literal("Nick + spazio, oppure incolla una lista"));
        updating = true;
        input.setText(inputText);
        updating = false;
        input.setChangedListener(this::onInputChanged);
        this.addDrawableChild(input);
        this.setInitialFocus(input);

        this.addDrawableChild(ButtonWidget.builder(Text.literal("Aggiungi"), b -> commitInput(true))
                .dimensions(left + COL_W - 84, 40, 84, 20).build());

        // Righe della lista (bottone "Rimuovi" per ogni nick)
        List<String> staff = cfg.staff;
        int start = page * rows;
        for (int i = 0; i < rows && start + i < staff.size(); i++) {
            final String nick = staff.get(start + i);
            int y = LIST_TOP + i * ROW_H;
            this.addDrawableChild(ButtonWidget.builder(Text.literal("Rimuovi"), b -> {
                cfg.removeStaff(nick);
                StaffMonitor.reset();
                rebuild = true;
            }).dimensions(left + COL_W - 64, y, 64, 20).build());
        }

        // Paginazione
        int navY = LIST_TOP + rows * ROW_H + 4;
        ButtonWidget prev = ButtonWidget.builder(Text.literal("<"), b -> {
            page--;
            rebuild = true;
        }).dimensions(cx - 60, navY, 20, 20).build();
        ButtonWidget next = ButtonWidget.builder(Text.literal(">"), b -> {
            page++;
            rebuild = true;
        }).dimensions(cx + 40, navY, 20, 20).build();
        prev.active = page > 0;
        next.active = page < pages - 1;
        this.addDrawableChild(prev);
        this.addDrawableChild(next);

        // Riga in basso: on/off e chiudi
        int by = this.height - 28;
        this.addDrawableChild(ButtonWidget.builder(
                Text.literal("Attivo: " + (cfg.enabled ? "ON" : "OFF")), b -> {
                    cfg.enabled = !cfg.enabled;
                    cfg.save();
                    StaffMonitor.reset();
                    rebuild = true;
                }).dimensions(left, by, 96, 20).build());
        this.addDrawableChild(ButtonWidget.builder(
                Text.literal(cfg.messageSeconds <= 0 ? "Msg: fissi" : "Msg: " + cfg.messageSeconds + "s"), b -> {
                    cfg.messageSeconds = nextMessageSeconds(cfg.messageSeconds);
                    cfg.save();
                    rebuild = true;
                }).dimensions(left + 102, by, 96, 20).build());
        this.addDrawableChild(ButtonWidget.builder(Text.literal("Fatto"), b -> this.close())
                .dimensions(left + 204, by, 96, 20).build());
    }

    /** Valori scelti dal bottone: 0 = i messaggi restano in chat, altrimenti secondi prima che spariscano. */
    private static final int[] MESSAGE_OPTIONS = {0, 5, 10, 20, 30};

    private static int nextMessageSeconds(int current) {
        for (int i = 0; i < MESSAGE_OPTIONS.length; i++) {
            if (MESSAGE_OPTIONS[i] == current) {
                return MESSAGE_OPTIONS[(i + 1) % MESSAGE_OPTIONS.length];
            }
        }
        return MESSAGE_OPTIONS[0];
    }

    private int pageCount() {
        int size = StaffConfig.get().staff.size();
        return Math.max(1, (size + rows - 1) / rows);
    }

    private static boolean isSeparator(char c) {
        return Character.isWhitespace(c) || c == ',' || c == ';';
    }

    private void onInputChanged(String text) {
        if (updating) {
            return;
        }
        int previousLength = inputText.length();
        inputText = text;

        boolean endsWithSeparator = !text.isEmpty() && isSeparator(text.charAt(text.length() - 1));
        boolean pasted = text.length() - previousLength > 1;
        if (endsWithSeparator || pasted) {
            commitInput(pasted);
        }
    }

    /**
     * Aggiunge i nick scritti nel campo.
     * @param flush se true aggiunge anche l'ultimo nick (senza separatore finale).
     */
    private void commitInput(boolean flush) {
        StaffConfig cfg = StaffConfig.get();
        String[] parts = input.getText().split("[\\s,;]+", -1);

        StringBuilder rest = new StringBuilder();
        int added = 0;
        int duplicates = 0;
        int invalid = 0;
        for (int i = 0; i < parts.length; i++) {
            String p = parts[i];
            if (p.isEmpty()) {
                continue;
            }
            boolean last = i == parts.length - 1;
            if (last && !flush) {
                rest.append(p); // nick ancora in digitazione
                continue;
            }
            if (!StaffConfig.isValidNick(p)) {
                invalid++;
            } else if (cfg.addStaff(p)) {
                added++;
            } else {
                duplicates++;
            }
        }

        updating = true;
        input.setText(rest.toString());
        updating = false;
        inputText = rest.toString();

        if (added + duplicates + invalid == 0) {
            if (flush) {
                status = Text.literal("Scrivi prima un nick.").formatted(Formatting.GRAY);
            }
            return;
        }

        StringBuilder msg = new StringBuilder();
        if (added > 0) {
            msg.append("Aggiunti: ").append(added).append("  ");
        }
        if (duplicates > 0) {
            msg.append("Già presenti: ").append(duplicates).append("  ");
        }
        if (invalid > 0) {
            msg.append("Non validi: ").append(invalid).append(" (solo lettere, numeri e _, max 16)");
        }
        status = Text.literal(msg.toString().trim()).formatted(invalid > 0 ? Formatting.RED : Formatting.GREEN);

        if (added > 0) {
            StaffMonitor.reset();
            page = Integer.MAX_VALUE; // vai all'ultima pagina
            rebuild = true;
        }
    }

    @Override
    public void tick() {
        if (rebuild) {
            rebuild = false;
            this.clearAndInit();
        }
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);

        List<String> staff = StaffConfig.get().staff;
        int cx = this.width / 2;
        int left = cx - COL_W / 2;

        context.drawCenteredTextWithShadow(this.textRenderer, this.title, cx, 8, 0xFFFFAA00);
        context.drawCenteredTextWithShadow(this.textRenderer,
                Text.literal("Staffer in lista: " + staff.size()), cx, 22, 0xFFAAAAAA);

        if (!status.getString().isEmpty()) {
            context.drawCenteredTextWithShadow(this.textRenderer, status, cx, 64, 0xFFFFFFFF);
        }

        if (staff.isEmpty()) {
            context.drawCenteredTextWithShadow(this.textRenderer,
                    Text.literal("Nessuno staffer in lista"), cx, LIST_TOP + 8, 0xFF888888);
        }

        int start = page * rows;
        for (int i = 0; i < rows && start + i < staff.size(); i++) {
            int y = LIST_TOP + i * ROW_H;
            String line = (start + i + 1) + ".  " + staff.get(start + i);
            context.drawTextWithShadow(this.textRenderer, line, left + 6, y + 6, 0xFFFFFFFF);
        }

        int navY = LIST_TOP + rows * ROW_H + 4;
        context.drawCenteredTextWithShadow(this.textRenderer,
                Text.literal("Pagina " + (page + 1) + "/" + pageCount()), cx, navY + 6, 0xFFAAAAAA);
    }
}
