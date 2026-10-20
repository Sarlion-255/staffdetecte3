package it.staffdetecte;

import it.staffdetecte.mixin.ChatHudAccessor;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.hud.MessageIndicator;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.network.message.MessageSignatureData;
import net.minecraft.scoreboard.ScoreHolder;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.scoreboard.ScoreboardDisplaySlot;
import net.minecraft.scoreboard.ScoreboardEntry;
import net.minecraft.scoreboard.ScoreboardObjective;
import net.minecraft.scoreboard.Team;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.world.GameMode;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Logica della mod:
 *  1. confronto scoreboard / tab per scovare gli staffer in vanish;
 *  2. avviso quando uno staffer (anche in spectator) compare o si teletrasporta vicino a te.
 */
public final class StaffMonitor {
    private enum Presence { OFFLINE, VISIBLE, VANISH }

    /** Ogni quanti tick (20 = 1s) ricontrollare tab e scoreboard. */
    private static final int VANISH_CHECK_INTERVAL = 10;
    /** Un teleport e' "arrivato da lontano": distanza precedente >= FAR ... */
    private static final double FAR = 32.0;
    /** ... e ora a distanza <= NEAR, nel giro di HISTORY tick. */
    private static final double NEAR = 10.0;
    /** Staffer che compare di colpo (entita' nuova) entro questa distanza. */
    private static final double NEW_NEAR = 24.0;
    private static final int HISTORY = 6;
    /** Tick minimi tra due avvisi per lo stesso staffer. */
    private static final int ALERT_COOLDOWN = 100;
    /** Tick di tolleranza dopo l'ingresso in un mondo o un tuo teleport. */
    private static final int JOIN_GRACE = 60;
    private static final int SELF_TP_GRACE = 20;
    private static final Pattern WORD = Pattern.compile("[A-Za-z0-9_]+");

    private static long tickCount;
    private static ClientWorld lastWorld;
    private static long graceUntil;
    private static long nextVanishCheck;

    private static boolean myPosKnown;
    private static double myX, myY, myZ;

    /** null = nessuna fotografia iniziale ancora fatta (stampa il riepilogo). */
    private static Map<String, Presence> presence;
    private static final Map<String, ArrayDeque<Double>> distHistory = new HashMap<>();
    private static final Map<String, Long> lastAlert = new HashMap<>();

    /** Messaggi in chat da rimuovere allo scadere del tempo. */
    private record PendingRemoval(MessageSignatureData signature, long expiryTick) {}
    private static final List<PendingRemoval> pendingRemovals = new ArrayList<>();
    private static final Random RANDOM = new Random();

    private StaffMonitor() {}

    public static MutableText prefix() {
        return Text.literal("[").formatted(Formatting.DARK_GRAY)
                .append(Text.literal("StaffDetecte").formatted(Formatting.GOLD))
                .append(Text.literal("] ").formatted(Formatting.DARK_GRAY));
    }

    /** Azzera lo stato: al prossimo controllo viene stampato di nuovo il riepilogo. */
    public static void reset() {
        presence = null;
        distHistory.clear();
        lastAlert.clear();
        nextVanishCheck = 0;
    }

    public static void tick(MinecraftClient client) {
        ClientWorld world = client.world;
        ClientPlayerEntity me = client.player;
        if (world == null || me == null || client.getNetworkHandler() == null) {
            lastWorld = null;
            myPosKnown = false;
            pendingRemovals.clear();
            reset();
            return;
        }
        tickCount++;
        expireMessages(client);

        if (world != lastWorld) {
            lastWorld = world;
            myPosKnown = false;
            reset();
            graceUntil = tickCount + JOIN_GRACE;
            nextVanishCheck = tickCount + JOIN_GRACE;
        }

        // Se sono io a teletrasportarmi (o a respawnare) tutti appaiono "vicini": niente falsi positivi.
        if (myPosKnown) {
            double dx = me.getX() - myX;
            double dy = me.getY() - myY;
            double dz = me.getZ() - myZ;
            if (dx * dx + dy * dy + dz * dz > 36.0) {
                graceUntil = Math.max(graceUntil, tickCount + SELF_TP_GRACE);
                distHistory.clear();
            }
        }
        myX = me.getX();
        myY = me.getY();
        myZ = me.getZ();
        myPosKnown = true;

        StaffConfig cfg = StaffConfig.get();
        if (!cfg.enabled || cfg.staff.isEmpty()) {
            return;
        }

        checkTeleports(client, cfg);

        if (tickCount >= nextVanishCheck) {
            nextVanishCheck = tickCount + VANISH_CHECK_INTERVAL;
            checkVanish(client, cfg);
        }
    }

    // ------------------------------------------------------------------
    // Teleport / comparsa di uno staffer vicino a te
    // ------------------------------------------------------------------

    private static void checkTeleports(MinecraftClient client, StaffConfig cfg) {
        Map<String, String> staff = cfg.staffByLower();
        boolean grace = tickCount < graceUntil;
        Set<String> present = new HashSet<>();

        for (AbstractClientPlayerEntity p : client.world.getPlayers()) {
            if (p == client.player) {
                continue;
            }
            String key = p.getName().getString().toLowerCase(Locale.ROOT);
            if (!staff.containsKey(key)) {
                continue;
            }
            present.add(key);

            double dist = p.distanceTo(client.player);
            ArrayDeque<Double> hist = distHistory.get(key);
            boolean appeared = hist == null;
            if (appeared) {
                hist = new ArrayDeque<>();
                distHistory.put(key, hist);
            }

            double maxPast = 0;
            for (double d : hist) {
                maxPast = Math.max(maxPast, d);
            }
            boolean arrived = appeared
                    ? dist <= NEW_NEAR
                    : (maxPast >= FAR && dist <= NEAR);

            hist.addLast(dist);
            while (hist.size() > HISTORY) {
                hist.removeFirst();
            }

            if (arrived && !grace) {
                Long last = lastAlert.get(key);
                if (last == null || tickCount - last >= ALERT_COOLDOWN) {
                    lastAlert.put(key, tickCount);
                    alertTeleport(client, staff.get(key), p, dist);
                }
            }
        }

        // Chi non e' piu' nel mondo perde lo storico: quando ricompare conta come "nuovo".
        distHistory.keySet().removeIf(k -> !present.contains(k));
    }

    private static void alertTeleport(MinecraftClient client, String nick, PlayerEntity p, double dist) {
        boolean spectator = p.isSpectator();
        if (!spectator && client.getNetworkHandler() != null) {
            PlayerListEntry entry = client.getNetworkHandler().getPlayerListEntry(p.getUuid());
            spectator = entry != null && entry.getGameMode() == GameMode.SPECTATOR;
        }

        MutableText msg = prefix()
                .append(Text.literal("⚠ ").formatted(Formatting.RED))
                .append(Text.literal(nick).formatted(Formatting.YELLOW, Formatting.BOLD))
                .append(Text.literal(" si è teletrasportato da te ").formatted(Formatting.RED))
                .append(Text.literal(spectator ? "(in SPECTATOR" : "(NON in spectator")
                        .formatted(spectator ? Formatting.LIGHT_PURPLE : Formatting.AQUA))
                .append(Text.literal(String.format(Locale.ROOT, ", a %.1f blocchi)", dist))
                        .formatted(Formatting.GRAY));
        send(client, msg, true);
    }

    // ------------------------------------------------------------------
    // Confronto scoreboard / tab (vanish)
    // ------------------------------------------------------------------

    private static void checkVanish(MinecraftClient client, StaffConfig cfg) {
        ClientPlayNetworkHandler handler = client.getNetworkHandler();
        ClientWorld world = client.world;
        if (handler == null || world == null) {
            return;
        }
        Map<String, String> staff = cfg.staffByLower();

        // Giocatori nella tab (nome reale del profilo + gamemode).
        Map<String, GameMode> tab = new HashMap<>();
        for (PlayerListEntry entry : handler.getPlayerList()) {
            tab.put(entry.getProfile().name().toLowerCase(Locale.ROOT), entry.getGameMode());
        }
        if (tab.isEmpty()) {
            return; // tab non ancora popolata: evita falsi positivi
        }

        // Giocatori presenti come entita' nel mondo.
        Set<String> inWorld = new HashSet<>();
        for (AbstractClientPlayerEntity p : world.getPlayers()) {
            inWorld.add(p.getName().getString().toLowerCase(Locale.ROOT));
        }

        // Staffer "scritti" nella scoreboard.
        Map<String, String> hits = scanScoreboard(world.getScoreboard(), cfg, staff.keySet());

        Map<String, Presence> now = new LinkedHashMap<>();
        Map<String, String> why = new HashMap<>();
        for (String key : staff.keySet()) {
            if (tab.containsKey(key)) {
                now.put(key, Presence.VISIBLE);
            } else if (hits.containsKey(key)) {
                now.put(key, Presence.VANISH);
                why.put(key, "scoreboard: " + hits.get(key));
            } else if (inWorld.contains(key)) {
                now.put(key, Presence.VANISH);
                why.put(key, "entità nel mondo ma non in tab");
            } else {
                now.put(key, Presence.OFFLINE);
            }
        }

        if (presence == null) {
            presence = now;
            printSummary(client, staff, now, why, tab);
            return;
        }

        for (Map.Entry<String, Presence> e : now.entrySet()) {
            Presence before = presence.getOrDefault(e.getKey(), Presence.OFFLINE);
            if (before != e.getValue()) {
                announceChange(client, staff.get(e.getKey()), before, e.getValue(), why.get(e.getKey()));
            }
        }
        presence = now;
    }

    /** Cerca i nick degli staffer in team, score holder e testo della sidebar. */
    private static Map<String, String> scanScoreboard(Scoreboard sb, StaffConfig cfg, Set<String> staffKeys) {
        Map<String, String> hits = new HashMap<>();

        if (cfg.useTeams) {
            for (Team team : sb.getTeams()) {
                for (String member : team.getPlayerList()) {
                    String k = member.toLowerCase(Locale.ROOT);
                    if (staffKeys.contains(k)) {
                        hits.putIfAbsent(k, "team");
                    }
                }
            }
        }

        if (cfg.useScoreHolders) {
            for (ScoreHolder holder : sb.getKnownScoreHolders()) {
                String k = holder.getNameForScoreboard().toLowerCase(Locale.ROOT);
                if (staffKeys.contains(k)) {
                    hits.putIfAbsent(k, "punteggi");
                }
            }
        }

        if (cfg.useSidebarText) {
            ScoreboardObjective objective = sb.getObjectiveForSlot(ScoreboardDisplaySlot.SIDEBAR);
            if (objective != null) {
                for (ScoreboardEntry entry : sb.getScoreboardEntries(objective)) {
                    Team team = sb.getScoreHolderTeam(entry.owner());
                    Text decorated = Team.decorateName(team, entry.name());
                    String line = Formatting.strip(entry.owner() + " " + decorated.getString());
                    if (line == null) {
                        continue;
                    }
                    Matcher m = WORD.matcher(line);
                    while (m.find()) {
                        String k = m.group().toLowerCase(Locale.ROOT);
                        if (staffKeys.contains(k)) {
                            hits.putIfAbsent(k, "sidebar");
                        }
                    }
                }
            }
        }
        return hits;
    }

    private static void printSummary(MinecraftClient client, Map<String, String> staff,
                                     Map<String, Presence> now, Map<String, String> why,
                                     Map<String, GameMode> tab) {
        List<String> visible = new ArrayList<>();
        List<String> vanish = new ArrayList<>();
        for (Map.Entry<String, Presence> e : now.entrySet()) {
            String nick = staff.get(e.getKey());
            if (e.getValue() == Presence.VISIBLE) {
                visible.add(tab.get(e.getKey()) == GameMode.SPECTATOR ? nick + " (spectator)" : nick);
            } else if (e.getValue() == Presence.VANISH) {
                vanish.add(nick + " [" + why.get(e.getKey()) + "]");
            }
        }

        if (visible.isEmpty() && vanish.isEmpty()) {
            send(client, prefix().append(Text.literal(
                    "Nessuno staffer rilevato (" + staff.size() + " nick in lista).")
                    .formatted(Formatting.GRAY)), false);
            return;
        }
        if (!visible.isEmpty()) {
            send(client, prefix().append(Text.literal("Staff online: ").formatted(Formatting.GREEN))
                    .append(Text.literal(String.join(", ", visible)).formatted(Formatting.WHITE)), false);
        }
        if (!vanish.isEmpty()) {
            send(client, prefix().append(Text.literal("⚠ Staff in VANISH: ").formatted(Formatting.RED, Formatting.BOLD))
                    .append(Text.literal(String.join(", ", vanish)).formatted(Formatting.YELLOW)), true);
        }
    }

    private static void announceChange(MinecraftClient client, String nick,
                                       Presence before, Presence after, String why) {
        MutableText nickText = Text.literal(nick).formatted(Formatting.YELLOW, Formatting.BOLD);

        if (after == Presence.VANISH) {
            send(client, prefix().append(Text.literal("⚠ ").formatted(Formatting.RED))
                    .append(nickText)
                    .append(Text.literal(" è in VANISH ").formatted(Formatting.RED, Formatting.BOLD))
                    .append(Text.literal("[" + why + "]").formatted(Formatting.DARK_GRAY)), true);
        } else if (before == Presence.VANISH && after == Presence.VISIBLE) {
            send(client, prefix().append(nickText)
                    .append(Text.literal(" è uscito dal vanish").formatted(Formatting.GREEN)), false);
        } else if (before == Presence.VANISH) {
            send(client, prefix().append(nickText)
                    .append(Text.literal(" non è più rilevato (era in vanish)").formatted(Formatting.GRAY)), false);
        } else if (after == Presence.VISIBLE) {
            send(client, prefix().append(nickText)
                    .append(Text.literal(" è entrato (staff online)").formatted(Formatting.GREEN)), false);
        } else {
            send(client, prefix().append(nickText)
                    .append(Text.literal(" è uscito dal server").formatted(Formatting.GRAY)), false);
        }
    }

    // ------------------------------------------------------------------

    /** Scrive in chat; se messageSeconds > 0 lo marca per la rimozione automatica. */
    private static void addToChat(MinecraftClient client, ClientPlayerEntity player, Text text) {
        int seconds = StaffConfig.get().messageSeconds;
        if (seconds <= 0) {
            player.sendMessage(text, false);
            return;
        }
        byte[] data = new byte[MessageSignatureData.SIZE];
        RANDOM.nextBytes(data);
        MessageSignatureData signature = new MessageSignatureData(data);
        client.inGameHud.getChatHud().addMessage(text, signature, MessageIndicator.system());
        pendingRemovals.add(new PendingRemoval(signature, tickCount + seconds * 20L));
    }

    private static void expireMessages(MinecraftClient client) {
        if (pendingRemovals.isEmpty()) {
            return;
        }
        List<MessageSignatureData> expired = new ArrayList<>();
        pendingRemovals.removeIf(p -> {
            if (tickCount < p.expiryTick()) {
                return false;
            }
            expired.add(p.signature());
            return true;
        });
        if (expired.isEmpty()) {
            return;
        }

        // Toglie le righe dalla lista della chat (niente segnaposto "deleted by the server").
        ChatHudAccessor chat = (ChatHudAccessor) client.inGameHud.getChatHud();
        chat.staffdetecte$getMessages().removeIf(line -> {
            MessageSignatureData sig = line.signature();
            if (sig == null) {
                return false;
            }
            for (MessageSignatureData e : expired) {
                if (e == sig || e.equals(sig)) {
                    return true;
                }
            }
            return false;
        });
        chat.staffdetecte$refresh();
    }

    private static void send(MinecraftClient client, Text text, boolean alert) {
        ClientPlayerEntity player = client.player;
        if (player == null) {
            return;
        }
        addToChat(client, player, text);
        if (alert) {
            player.sendMessage(text, true);
            if (StaffConfig.get().soundAlerts) {
                client.getSoundManager().play(
                        PositionedSoundInstance.ui(SoundEvents.ENTITY_PLAYER_LEVELUP, 0.6f, 1.0f));
            }
        }
    }
}
