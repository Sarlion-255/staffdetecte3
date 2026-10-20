package it.staffdetecte;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Configurazione salvata in config/staffdetecte.json.
 */
public final class StaffConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Pattern VALID_NICK = Pattern.compile("[A-Za-z0-9_]{1,16}");

    private static StaffConfig instance = new StaffConfig();

    /** Rilevamento attivo (/staffdetecte on|off). */
    public boolean enabled = true;
    /** Cerca gli staffer nei team della scoreboard (molto usati per prefissi/ranghi). */
    public boolean useTeams = true;
    /** Cerca gli staffer tra i "score holder" della scoreboard. */
    public boolean useScoreHolders = true;
    /** Cerca gli staffer nel testo scritto nella sidebar. */
    public boolean useSidebarText = true;
    /** Suono quando scatta un avviso. */
    public boolean soundAlerts = true;
    /** Dopo quanti secondi i messaggi [StaffDetecte] spariscono dalla chat (0 = restano). */
    public int messageSeconds = 10;
    /** Nick degli staffer da riconoscere. */
    public List<String> staff = new ArrayList<>();

    private transient Map<String, String> byLowerCache;

    public static StaffConfig get() {
        return instance;
    }

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("staffdetecte.json");
    }

    public static void load() {
        Path file = file();
        if (Files.exists(file)) {
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                StaffConfig loaded = GSON.fromJson(reader, StaffConfig.class);
                if (loaded != null) {
                    if (loaded.staff == null) {
                        loaded.staff = new ArrayList<>();
                    }
                    instance = loaded;
                }
            } catch (Exception e) {
                StaffDetecteClient.LOGGER.error("Impossibile leggere {}", file, e);
            }
        }
        instance.save();
    }

    public void save() {
        try (Writer writer = Files.newBufferedWriter(file(), StandardCharsets.UTF_8)) {
            GSON.toJson(this, writer);
        } catch (Exception e) {
            StaffDetecteClient.LOGGER.error("Impossibile salvare la configurazione", e);
        }
    }

    public static boolean isValidNick(String nick) {
        return nick != null && VALID_NICK.matcher(nick).matches();
    }

    /** @return true se il nick e' stato aggiunto (valido e non gia' presente). */
    public boolean addStaff(String nick) {
        if (!isValidNick(nick) || staffByLower().containsKey(nick.toLowerCase(Locale.ROOT))) {
            return false;
        }
        staff.add(nick);
        byLowerCache = null;
        save();
        return true;
    }

    public void removeStaff(String nick) {
        staff.removeIf(s -> s.equalsIgnoreCase(nick));
        byLowerCache = null;
        save();
    }

    /** Mappa nick minuscolo -> nick come scritto dall'utente. */
    public Map<String, String> staffByLower() {
        if (byLowerCache == null) {
            Map<String, String> map = new LinkedHashMap<>();
            for (String s : staff) {
                map.put(s.toLowerCase(Locale.ROOT), s);
            }
            byLowerCache = map;
        }
        return byLowerCache;
    }
}
