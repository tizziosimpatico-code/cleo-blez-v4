# Cleo BLE Bridge — Fase 1 (senza fotocamera/OCR)

Questo è un progetto Android Studio funzionante che trasforma il tablet in un
sensore Bluetooth LE "Cycling Speed and Cadence" (CSC), lo stesso standard
usato dai veri sensori di velocità da bici. MyWhoosh, in modalità
"Speed Sensor", lo vedrà come un sensore qualunque.

In questa fase la velocità si inserisce **a mano** nel campo di testo:
serve a verificare che tutta la catena BLE → PC → MyWhoosh funzioni, prima
di aggiungere fotocamera e OCR (fase 2).

## Come compilarlo

1. Installa [Android Studio](https://developer.android.com/studio).
2. Apri la cartella `CleoBLE` come progetto esistente ("Open").
3. Lascia che Gradle scarichi le dipendenze (richiede internet, la prima volta).
4. Collega il tablet via USB con il debug USB attivo, oppure esporta l'APK
   con `Build → Build Bundle(s)/APK(s) → Build APK(s)` e installalo manualmente.

Non serve alcun account Google Play: è un normale APK da installare (potresti
dover abilitare "Origini sconosciute" nelle impostazioni di sicurezza del tablet).

## Verifica preliminare fondamentale

Appena apri l'app, controlla lo stato mostrato: se dice che il dispositivo
potrebbe non supportare il ruolo di periferica BLE, il Tab A 2018 10.5"
potrebbe non andare bene e servirebbe un altro dispositivo (o un ESP32).
La maggior parte dei tablet Android con Bluetooth 4.0+ e Android 8+ lo supporta,
ma la certezza si ha solo provando: questo progetto stesso è il test.

## Come si usa

1. Attiva il Bluetooth sul tablet.
2. Apri l'app, inserisci una velocità (es. 20.0), premi START.
3. Concedi i permessi Bluetooth se richiesti.
4. Lo stato passa a "🟡 in pubblicità" — il tablet ora si chiama
   "CLEO Speed Sensor" ed è visibile via Bluetooth.
5. Su PC, apri MyWhoosh → impostazioni sensori → modalità Speed Sensor →
   cerca dispositivi. Dovrebbe comparire "CLEO Speed Sensor".
6. Collegalo. Lo stato nell'app deve diventare "🟢 CONNESSO".
7. Cambia il numero nel campo velocità: MyWhoosh dovrebbe aggiornare la
   velocità ricevuta entro un secondo.

## Un dettaglio importante: la circonferenza ruota

Il valore `WHEEL_CIRCUMFERENCE_MM` in `CscPeripheral.kt` (default 2105mm,
tipico di una ruota 700x23c) deve corrispondere alla circonferenza ruota
impostata dentro MyWhoosh. Se MyWhoosh permette di configurare la misura
ruota per il sensore, usa lo stesso valore, altrimenti la velocità ricostruita
da MyWhoosh non corrisponderà esattamente a quella che stai trasmettendo.

## Prossimo passo (fase 2): fotocamera + OCR

Una volta confermato che questa fase 1 funziona, il passo successivo è:
- aggiungere una CameraX preview,
- permettere di selezionare un rettangolo del display della Cleo,
- passare quel ritaglio a ML Kit Text Recognition,
- interpretare il numero riconosciuto come velocità e sostituire
  l'inserimento manuale con quel valore, aggiornato più volte al secondo.

Fammi sapere quando la fase 1 funziona (o cosa succede di diverso) e
scriviamo insieme la fase 2.
