# StaffDetecte (Fabric 1.21.11, solo client)

Mod **lato client**: non invia nulla al server, legge solo ciò che il tuo client già riceve.

## Comandi

| Comando | Cosa fa |
|---|---|
| `/staffdetecte` | Apre la GUI con la lista degli staffer da riconoscere |
| `/staffdetecte on` | Attiva il rilevamento (e ristampa il riepilogo) |
| `/staffdetecte off` | Lo disattiva |

### GUI
- Scrivi un nick e premi **spazio** (o virgola) → viene aggiunto.
- Puoi **incollare** una lista di nick separati da spazi, virgole o righe: li aggiunge tutti.
- **Aggiungi** aggiunge quello che hai scritto, **Rimuovi** toglie un nick, **Rilevamento ON/OFF** è lo stesso di `on`/`off`.
- Nick validi: lettere, numeri e `_`, max 16 caratteri. Il confronto ignora maiuscole/minuscole.

## Cosa rileva

1. **Vanish (scoreboard vs tab)** – ogni 0,5 s confronta i nick in lista con la tab:
   - in tab → *online*;
   - non in tab ma **scritto nella scoreboard** (team, punteggi o testo della sidebar) → **VANISH**;
   - non in tab ma con l'entità visibile vicino a te → **VANISH**;
   - da nessuna parte → offline.
   Ti avvisa (chat + action bar + suono) quando uno staffer entra in vanish, ne esce, entra o esce dal server. Al join e con `on` stampa un riepilogo (con "(spectator)" accanto a chi è in tab in quella modalità).
2. **Teleport da te** – se uno staffer compare di colpo vicino a te (≤ 24 blocchi) o passa da lontano (≥ 32) a ≤ 10 blocchi in meno di ~0,3 s, ti avvisa indicando se è **in spectator o no** e la distanza. C'è un cooldown di 5 s per staffer e una tolleranza dopo il tuo ingresso nel mondo o un tuo teleport, per evitare falsi allarmi.

## Config (`config/staffdetecte.json`)

```json
{
  "enabled": true,
  "useTeams": true,
  "useScoreHolders": true,
  "useSidebarText": true,
  "soundAlerts": true,
  "messageSeconds": 10,
  "staff": ["Nick1", "Nick2"]
}
```
`messageSeconds`: dopo quanti secondi i messaggi `[StaffDetecte]` spariscono dalla chat (`0` = restano). Si cambia anche dal bottone **Msg** nella GUI (fissi / 5s / 10s / 20s / 30s).
Se vedi falsi "vanish" perché la scoreboard conserva punteggi di giocatori offline, metti `useScoreHolders` a `false`.

## Limiti
- Il rilevamento vanish funziona solo se il server **mette il nome dello staffer nella scoreboard** (molto comune nei team dei ranghi/prefissi della tab). Se il vanish lo nasconde ovunque, il client non può saperlo.
- Il teleport si vede solo se l'entità dello staffer viene inviata al tuo client (spectator vanilla sì; vanish che nasconde l'entità no).
- Controlla che il regolamento del server consenta le mod client.

## Compilare
Servono JDK 21 e Gradle (o il wrapper del template ufficiale).

```
gradle build
```
Il jar è in `build/libs/` (usa quello senza `-sources`) e va in `.minecraft/mods/` insieme a **Fabric API**.

Il wrapper Gradle (`gradlew`) non è incluso: copialo dal template ufficiale su https://fabricmc.net/develop/template/ (scegli 1.21.11). Se Gradle si lamenta della versione di Loom, allinea `fabric-loom` in `build.gradle` e la versione del wrapper a quelle del template.
