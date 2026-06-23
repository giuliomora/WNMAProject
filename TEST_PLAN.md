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
| D4 | Elimina con peer offline | 2 | A elimina INFO mentre B è offline, B si riconnette | Verificare se B riceve il DELETE_MSG alla riconnessione (attualmente non garantito — noto) |

---

## Batteria E — Consumo batteria

Esegui con **schermo spento**, nessun'altra app in foreground, durata minima **1 ora** per risultati significativi.

| # | Scenario | Setup | Metrica da raccogliere |
|---|----------|-------|----------------------|
| E1 | Baseline — app chiusa | Telefono idle, TrekMesh non installata | % batteria/h, mAh/h |
| E2 | Mesh attiva, nessun peer | 1 dispositivo solo, mesh ON | mAh/h — overhead del servizio in ascolto |
| E3 | Mesh attiva, 1 peer connesso | 2 dispositivi connessi, nessun messaggio | mAh/h — overhead connessione Nearby |
| E4 | Mesh attiva, traffico medio | 2 dispositivi, 1 messaggio ogni 5 min | mAh/h — impatto del traffico messaggi |
| E5 | Mesh disattivata | Mesh OFF dalle impostazioni | Deve essere ≈ E1 |
| E6 | Trasferimento immagine | Invia foto grande via mesh | Picco di consumo durante upgrade a Wi-Fi Direct |
| E7 | 3 peer connessi | 3 dispositivi connessi, nessun messaggio | Confronta con E3 — verifica se scala linearmente |

### Come raccogliere i dati
1. Annota % batteria e ora di inizio
2. Lascia girare per 1h con schermo spento
3. Annota % batteria e ora di fine
4. Calcola: `mAh consumati = capacità_batteria_mAh × (Δ% / 100)`

---

## Batteria F — Confronti

| Confronto | Come eseguirlo |
|-----------|---------------|
| **TrekMesh vs Meshtastic** | Stesso test E3 su ciascuna app, confronta mAh/h |
| **TrekMesh vs Briar** | Stesso test E3, confronta mAh/h |
| **Con/senza deep scan Wi-Fi** | Disabilita temporaneamente il deep scan ogni 3 min nel codice, ripeti E3 |
| **Con/senza immagini** | Test E4 con e senza foto allegate, confronta consumo |
| **1 peer vs 2 peer vs 3 peer** | E3 ripetuto con 1, 2, 3 dispositivi — verifica scaling lineare o sub-lineare |
| **BT only vs BT + Wi-Fi Direct** | Blocca il deep scan, confronta discovery range e consumo |

---

## Note operative

- Per simulare nodi fisicamente lontani senza spostarsi, avvolgi un dispositivo in **carta stagnola** per attenuare il segnale BT/Wi-Fi
- Ripeti ogni test almeno **2 volte** — la prima connessione Nearby è spesso più lenta delle successive
- Tieni un **log manuale** con timestamp di ogni evento (connessione, messaggio, disconnessione) da confrontare poi con Battery Historian
- Per i test multi-hop (B2), verifica con i log di Android Studio che il messaggio transiti effettivamente per il nodo intermedio e non arrivi direttamente
- Il test D4 è attualmente un **caso limite noto**: i `DELETE_MSG` non vengono accodati per i peer offline — utile documentarlo come limitazione

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
  E1 baseline:      ____% /h
  E2 mesh sola:     ____% /h   (delta vs E1: ____)
  E3 1 peer:        ____% /h   (delta vs E2: ____)
  E4 traffico:      ____% /h   (delta vs E3: ____)
  E5 mesh off:      ____% /h   (delta vs E1: ____)
```
