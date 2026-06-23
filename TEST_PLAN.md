# Piano di Test TrekMesh

Test funzionali e di consumo batteria per 2–3 dispositivi Android.

---

## Strumenti di monitoraggio consigliati

### Sul dispositivo
- **Android Studio Profiler** — CPU, memoria, rete e batteria in tempo reale
- **AccuBattery** (app) — misura mAh consumati per sessione, utile senza PC

### Da PC via adb
```bash
# Reset contatori prima del test
adb shell dumpsys batterystats --reset
adb shell dumpsys batterystats --enable full-wake-history

# Dump dopo il test
adb shell dumpsys batterystats > bugreport.txt

# Visualizza con Battery Historian
python historian.py -p com.example.wnmaproject bugreport.txt
```

In Battery Historian cerca:
- Wakelock di `TrekMeshService`
- Radio state transitions (BT / Wi-Fi)
- Alarm wakeup

### Hardware esterno
- **USB power meter** (es. UM25C) — misura mA reali dal caricatore con schermo spento, il metodo più preciso per il drain in background

---

## Batteria A — Connessione base

| # | Test | Dispositivi | Procedura | Risultato atteso | Cosa misurare |
|---|------|-------------|-----------|-----------------|---------------|
| A1 | Discovery iniziale | 2 | Avvia app su entrambi, attendi | Peer count = 1 su entrambi entro 60s | Tempo di discovery |
| A2 | Connessione a 3 nodi | 3 | Avvia app su tutti e 3 | Ogni nodo vede ≥ 2 peer | Topologia P2P_CLUSTER |
| A3 | Recovery dopo disconnessione | 2 | Disattiva BT su un dispositivo per 30s, riattiva | Si riconnette automaticamente | Tempo di recovery |
| A4 | Errore 13 (connessione simultanea) | 2 | Avvia i due dispositivi esattamente insieme | Connessione stabilita dopo retry automatico | Log: numero di retry |

---

## Batteria B — Routing e TTL

| # | Test | Dispositivi | Procedura | Risultato atteso |
|---|------|-------------|-----------|-----------------|
| B1 | Messaggio diretto | 2 | A invia INFO a B | B riceve, TTL decrementato di 1 |
| B2 | Routing multi-hop | 3 | A e C distanti, B nel mezzo | C riceve il messaggio di A passando per B |
| B3 | Deduplicazione | 3 | A invia, B e C entrambi connessi ad A | Il messaggio non viene processato due volte da nessuno |
| B4 | TTL esaurito | 2 | Abbassa TTL iniziale a 1 nel codice, invia | Il ricevente non inoltra ulteriormente |
| B5 | Scadenza BROADCAST | 2 | Invia BROADCAST, abbassa soglia a 2 min per test, attendi | Sparisce dall'UI di entrambi dopo la soglia |
| B6 | Scadenza INFO P1-P2 | 2 | Invia INFO priorità 1, abbassa soglia a 2 min, attendi | Sparisce dopo la soglia |
| B7 | SOS non scade prima di 24h | 2 | Invia SOS, verifica dopo 6h | SOS ancora presente |

> **Tip per i test di scadenza:** modifica temporaneamente le costanti in `MessageDao.deleteExpired()` (es. 2 minuti invece di 6 ore) e ricompila.

---

## Batteria C — SOS e sicurezza

| # | Test | Dispositivi | Procedura | Risultato atteso |
|---|------|-------------|-----------|-----------------|
| C1 | SOS diretto | 2 | A (hiker) invia SOS, B (rifugio) riceve | B riceve notifica, card rossa ad alta priorità |
| C2 | SOS multi-hop | 3 | A invia SOS, C (rifugio) raggiungibile solo tramite B | C riceve SOS con TTL decrementato |
| C3 | SOS relay Protezione Civile | 2 | A invia SOS, B è rifugio con Wi-Fi attivo | B manda HTTP relay, arriva notifica di conferma |
| C4 | SOS preso in carico | 2 | B (rifugio) preme "Prendi in carico" | Card diventa arancione su entrambi i dispositivi |
| C5 | SOS risolto | 2 | B preme "Segna come risolto" | Card diventa verde su entrambi |
| C6 | Stato SOS persistito al riavvio | 2 | B prende in carico SOS, riavvia B | B riapre e vede ancora lo stato ACKNOWLEDGED |
| C7 | Safety timer | 1 | Attiva timer 30 min, non premere "Sto bene" | SOS automatico alla scadenza |
| C8 | BLE beacon passivo | 2 | Disattiva Nearby su A, mantieni solo BLE | B rileva il beacon di A nei log |

---

## Batteria D — Ciclo di vita messaggi

| # | Test | Dispositivi | Procedura | Risultato atteso |
|---|------|-------------|-----------|-----------------|
| D1 | Elimina INFO propagato | 2 | A invia INFO, poi lo elimina dal dettaglio | Sparisce anche da B; log mostra `DELETE_MSG` |
| D2 | Resolve voting — soglia raggiunta | 3 | A invia INFO, B e C premono "Segnala risolto" | Dopo il 2° voto il messaggio sparisce da tutti e 3 |
| D3 | Resolve voting — 1 solo voto | 2 | Solo B vota risolto | Il messaggio rimane (serve il 2° voto) |
| D4 | Resolve voting — doppio voto stesso utente | 2 | B preme "Segnala risolto" due volte (riaprendo il dettaglio) | Il pulsante diventa "✓ Già segnalato" alla seconda apertura, conteggio non aumenta |
| D5 | Resolve voting — solo INFO non BROADCAST | 2 | A invia BROADCAST, B apre il dettaglio | Il pulsante "Segnala risolto" non è visibile |
| D6 | Elimina con peer offline — no ritrasmissione | 2 | A elimina INFO mentre B è offline, B si riconnette | B non ritrasmette il messaggio eliminato alla riconnessione (TTL azzerato nel DB) |
| D7 | SOS stato persistito al riavvio | 2 | B (rifugio) prende in carico SOS di A, riavvia B | B riapre e vede ancora ACKNOWLEDGED |

---

## Batteria E — BLE passivo SOS

Verifica che il monitoraggio passivo funzioni indipendentemente dallo stato della mesh.

| # | Test | Dispositivi | Procedura | Risultato atteso |
|---|------|-------------|-----------|-----------------|
| E1 | Rilevamento SOS con mesh attiva | 2 | A invia SOS, B ha mesh attiva | B riceve notifica normale via Nearby |
| E2 | Rilevamento passivo con mesh disattivata | 2 | A invia SOS, B ha mesh disattivata ma BT attivo | B mostra nel log "🚨 SOS passivo captato" anche senza connessione mesh |
| E3 | BLE passivo con servizio mesh OFF | 2 | Disattiva mesh su B dalle impostazioni, A invia SOS | B rileva comunque il beacon BLE di A |
| E4 | Notifica SOS passivo | 2 | B non ha mesh, A è in SOS | B riceve notifica "SOS passivo captato" |
| E5 | No doppio log se già connessi | 2 | A e B connessi via mesh, A in SOS | Il messaggio "SOS passivo" NON appare nel log (già connessi) |

---

## Batteria F — Consumo batteria

Esegui con **schermo spento**, nessun'altra app in foreground, durata minima **1 ora** per risultati significativi.

| # | Scenario | Setup | Metrica da raccogliere |
|---|----------|-------|----------------------|
| F1 | Baseline — app chiusa | Telefono idle, TrekMesh non installata | % batteria/h, mAh/h |
| F2 | Mesh attiva, nessun peer | 1 dispositivo solo, mesh ON | mAh/h — overhead del servizio in ascolto |
| F3 | Mesh attiva, 1 peer connesso | 2 dispositivi connessi, nessun messaggio | mAh/h — overhead connessione Nearby |
| F4 | Mesh attiva, traffico medio | 2 dispositivi, 1 messaggio ogni 5 min | mAh/h — impatto del traffico messaggi |
| F5 | Mesh disattivata (solo BLE passivo) | Mesh OFF dalle impostazioni | Deve essere leggermente > F1 ma molto < F3 |
| F6 | Trasferimento immagine | Invia foto grande via mesh | Picco di consumo durante upgrade a Wi-Fi Direct |
| F7 | 3 peer connessi | 3 dispositivi connessi, nessun messaggio | Confronta con F3 — verifica se scala linearmente |

### Come raccogliere i dati
1. Annota % batteria e ora di inizio
2. Lascia girare per 1h con schermo spento
3. Annota % batteria e ora di fine
4. Calcola: `mAh consumati = capacità_batteria_mAh × (Δ% / 100)`

---

## Batteria G — Test prestazionali: range e ostacoli

Questi test misurano la portata reale del segnale in condizioni di campo. Usare **2 dispositivi** salvo dove indicato.

### G1 — Linea di vista (Line of Sight)

| Distanza | Procedura | Metrica |
|----------|-----------|---------|
| 10 m | Dispositivi all'aperto, nessun ostacolo | Tempo di discovery, latenza messaggio |
| 25 m | Idem | Idem |
| 50 m | Idem | Idem |
| 75 m | Idem | Idem |
| 100 m | Idem | Verifica se Nearby usa BT o richiede il deep scan Wi-Fi (visibile nei log) |
| 100 m+ | Attiva deep scan manuale | Distanza massima prima della disconnessione |

> Ripeti ogni distanza 3 volte e prendi la media. Annota se la connessione è BT o Wi-Fi Direct.

### G2 — Ostacoli interni (indoor)

| Scenario | Procedura | Risultato atteso |
|----------|-----------|-----------------|
| Stessa stanza | A e B nella stessa stanza | Discovery < 30s |
| 1 parete interna | A e B separati da 1 muro | Verifica degradazione latenza |
| 2 pareti interne | A e B separati da 2 muri | Verifica se ancora raggiungibili |
| Piano diverso | A al piano 0, B al piano 1 | Limite tipico per BT through solaio |
| Cantina/interrato | Un dispositivo in cantina | Caso limite — probabile perdita segnale |

### G3 — Ostacoli naturali (outdoor)

| Scenario | Procedura | Risultato atteso |
|----------|-----------|-----------------|
| Bosco fitto | 30 m di distanza con alberi tra i dispositivi | Verifica attenuazione BT vs linea di vista |
| Dislivello | A in basso, B su un rialzo di 5 m a 50 m distanza | Confronta con G1 a 50 m piano |
| Roccia interposta | Grande masso tra i due dispositivi | Caso limite — BT tende a diffrangersi poco |
| Avvallamento | A in un avvallamento, B sul bordo | Effetto "ombra radio" |
| Pioggia/umidità | Ripeti G1 a 50 m con pioggia | Verifica se l'umidità degrada il segnale |

### G4 — Multi-hop in condizioni reali

| # | Scenario | Dispositivi | Setup |
|---|----------|-------------|-------|
| G4a | Relay in edificio | 3 | A al piano 0, B al piano 1 (relay), C al piano 2 — A raggiunge C? |
| G4b | Relay outdoor con ostacolo | 3 | A e C separati da un edificio, B sul lato come relay |
| G4c | Range esteso tramite relay | 3 | A a 0 m, B a 80 m, C a 160 m — messaggio di A arriva a C? |
| G4d | Relay con peer offline | 3 | A invia, B (relay) va offline prima che C riceva | C riceve quando B torna online? (DTN behavior) |

### G5 — Simulazione attenuazione in laboratorio

Tecnica per testare condizioni di range senza spostarsi fisicamente:

| Tecnica | Attenuazione stimata | Equivalente reale |
|---------|---------------------|-------------------|
| 1 strato di carta stagnola | ~10 dB | ~1 parete / ~30 m extra |
| 2 strati di carta stagnola | ~20 dB | ~2 pareti / ~60 m extra |
| Scatola metallica parz. chiusa | ~30 dB | Ambiente molto schermato |
| Sacchetto Faraday | completa schermatura | Nodo completamente isolato |

> Misura la latenza e il tasso di consegna con e senza attenuazione per ogni livello.

### G6 — Latenza e affidabilità

| # | Test | Procedura | Metrica |
|---|------|-----------|---------|
| G6a | Latenza media 1 hop | A invia 10 messaggi a intervalli di 30s, B connesso | Media e deviazione standard del tempo di ricezione |
| G6b | Latenza media 2 hop | Stesso con relay C nel mezzo | Confronta con G6a |
| G6c | Tasso di consegna | A invia 20 messaggi, conta quanti arrivano a B | % messaggi ricevuti |
| G6d | Consegna sotto carico | 3 dispositivi si mandano messaggi contemporaneamente | Verifica collisioni e perdite |

---

## Batteria H — Confronti

| Confronto | Come eseguirlo |
|-----------|---------------|
| **TrekMesh vs Meshtastic** | Stesso test F3 e G1 su ciascuna app, confronta mAh/h e range |
| **TrekMesh vs Briar** | Stesso test F3, confronta mAh/h |
| **Con/senza deep scan Wi-Fi** | Disabilita il deep scan nel codice, ripeti F3 e G1 — impatto su range e batteria |
| **Con/senza immagini** | Test F4 con e senza foto allegate, confronta consumo |
| **1 peer vs 2 peer vs 3 peer** | F3 ripetuto con 1, 2, 3 dispositivi — verifica scaling |
| **BT only vs BT + Wi-Fi Direct** | Blocca il deep scan, confronta range (G1) e consumo (F3) |
| **Mesh attiva vs passiva** | F3 vs F5 — overhead Nearby rispetto al solo BLE scan |

---

## Note operative

- Per simulare nodi fisicamente lontani senza spostarsi, avvolgi un dispositivo in **carta stagnola** (vedi G5)
- Ripeti ogni test almeno **2 volte** — la prima connessione Nearby è spesso più lenta delle successive
- Tieni un **log manuale** con timestamp di ogni evento (connessione, messaggio, disconnessione) da confrontare poi con Battery Historian
- Per i test multi-hop (B2, G4), verifica con i log di Android Studio che il messaggio transiti effettivamente per il nodo intermedio e non arrivi direttamente
- Per i test di range (G1–G3) usa un'app come **Network Analyzer** per vedere i livelli RSSI BT e Wi-Fi in tempo reale
- Per i test prestazionali outdoor, porta un powerbank — i test di range richiedono che i dispositivi rimangano accesi a lungo
- Il test D6 (elimina con peer offline) funziona ora grazie al TTL=0 pre-cancellazione; i `DELETE_MSG` non vengono comunque accodati per reconnect tardivi — limitazione nota

---

## Template foglio risultati

```
Data: ___________
Dispositivi: ___________ / ___________ / ___________
Versione app: ___________

Test | Risultato (OK / FAIL / PARZIALE) | Note
-----|----------------------------------|-----
A1   |                                  |
A2   |                                  |
...

Consumo batteria:
  F1 baseline:         ____% /h
  F2 mesh sola:        ____% /h   (delta vs F1: ____)
  F3 1 peer:           ____% /h   (delta vs F2: ____)
  F4 traffico:         ____% /h   (delta vs F3: ____)
  F5 solo BLE passivo: ____% /h   (delta vs F1: ____)

Range (G1 — linea di vista):
  10 m:   discovery ____s, latenza ____ms
  25 m:   discovery ____s, latenza ____ms
  50 m:   discovery ____s, latenza ____ms
  75 m:   discovery ____s, latenza ____ms
  100 m:  discovery ____s, latenza ____ms  (BT / Wi-Fi Direct: ____)
  Max:    ____ m

Range con ostacoli (G2/G3):
  1 parete:       raggiunto sì/no, latenza ____ms
  2 pareti:       raggiunto sì/no, latenza ____ms
  Piano diverso:  raggiunto sì/no
  Bosco 30 m:     raggiunto sì/no, latenza ____ms
```
