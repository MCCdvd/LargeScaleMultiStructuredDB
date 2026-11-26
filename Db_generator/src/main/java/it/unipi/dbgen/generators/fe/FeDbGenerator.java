package it.unipi.dbgen.generators.fe;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.Sorts;
import org.bson.Document;

import javax.swing.JOptionPane;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

public class FeDbGenerator {

    private static final String MONGO_URI = "mongodb://127.0.0.1:27017";
    private static final String DB_NAME = "FE_DB";
    private static final String COLLECTION_NAME = "cupper01_samples";

    // Parametri macchina Cupper01 e produzione
    private static final String MACHINE_ID = "Cupper01";
    private static final int STROKES_PER_MINUTE = 150;
    private static final int CUPS_PER_STROKE = 15;
    private static final int DEFAULT_PRODUCTION_HOURS = 8;
    private static final int SAMPLE_INTERVAL_MINUTES = 1;
    private static final int CUPPER_OOS_INTERVAL_MINUTES = 7 * 60; // ogni 7 ore (420 min)

    // Parametri qualità topwallcupsx (mm)
    private static final double CUP_LSL = 0.190;
    private static final double CUP_USL = 0.205;
    private static final double CUP_MIDPOINT = (CUP_LSL + CUP_USL) / 2.0; // 0.1975 mm
    private static final double CUP_SIGMA = 0.00125;
    private static final double CUP_MAX_MEAN_OFFSET = 0.0025;

    // Parametri Wet_Can_0x (x = 1..9), BM (3 macchine) e delay di 30 minuti
    private static final int WET_CAN_COUNT = 9;
    private static final int BM_COUNT = 3;
    private static final int BM_DELAY_MINUTES = 30;
    private static final int WET_CAN_OOS_WINDOW_MINUTES = 2 * 60; // ogni 2 ore (120 min)
    private static final int WET_CAN_OOS_PER_WINDOW = 10;         // 10 fuori specifica ogni 2 ore

    // Specifica Wet_Can: 0.130 <= Top_Wall_Wet_Can_X <= 0.150 mm (6-sigma sul centro 0.140 mm)
    private static final double WET_CAN_LSL = 0.130;
    private static final double WET_CAN_USL = 0.150;
    private static final double WET_CAN_MIDPOINT = (WET_CAN_LSL + WET_CAN_USL) / 2.0; // 0.140 mm
    private static final double WET_CAN_TOTAL_SIGMA = (WET_CAN_USL - WET_CAN_LSL) / 6.0; // ~0.003333 mm (6-sigma)
    private static final double WET_CAN_WITHIN_SIGMA = 0.0008;
    private static final double WET_CAN_MAX_MEAN_OFFSET = 0.0022;

    private static final DateTimeFormatter INPUT_FORMATTER = DateTimeFormatter.ofPattern("mm-HH-dd-MM-yy");
    private static final ZoneId ZONE_ID = ZoneId.systemDefault();

    private record OosInjection(int wetCanIndex, int bmIndex, boolean aboveUpper, double delta) {}

    private enum Mode {
        REPLACE_DB("Sostituisci database"),
        CREATE_RANGE("Crea nuovi dati dal mm-hh-dd-mm-aa al mm-hh-dd-mm-aa"),
        APPEND_UNTIL("Integra dati fino al mm-hh-dd-mm-aa");

        private final String label;

        Mode(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    public static void main(String[] args) {
        Mode selectedMode = askModeViaDialog();
        if (selectedMode == null) {
            System.out.println("Operazione annullata dall'utente.");
            return;
        }

        Random random = new Random(42L);
        double[] cupMeans = buildCenteredMeans(random, CUPS_PER_STROKE, CUP_MIDPOINT, CUP_MAX_MEAN_OFFSET, CUP_LSL, CUP_USL);
        double[] wetCanMeans = buildCenteredMeans(random, WET_CAN_COUNT, WET_CAN_MIDPOINT, WET_CAN_MAX_MEAN_OFFSET, WET_CAN_LSL, WET_CAN_USL);

        Instant startInclusive;
        Instant endInclusive;
        boolean dropExisting;
        int initialMinuteOffset = 0;

        switch (selectedMode) {
            case REPLACE_DB -> {
                dropExisting = true;
                startInclusive = Instant.now().truncatedTo(ChronoUnit.MINUTES);
                endInclusive = startInclusive.plus((long) DEFAULT_PRODUCTION_HOURS * 60 - 1, ChronoUnit.MINUTES);
            }
            case CREATE_RANGE -> {
                dropExisting = true;
                Instant[] range = askStartAndEndRange();
                if (range == null) {
                    System.out.println("Operazione annullata durante l'inserimento dell'intervallo.");
                    return;
                }
                startInclusive = range[0];
                endInclusive = range[1];
            }
            case APPEND_UNTIL -> {
                dropExisting = false;
                endInclusive = askSingleTimestamp("Inserisci la data/ora finale (mm-hh-dd-mm-aa):");
                if (endInclusive == null) {
                    System.out.println("Operazione annullata durante l'inserimento della data finale.");
                    return;
                }
                startInclusive = null;
            }
            default -> {
                return;
            }
        }

        try (MongoClient client = MongoClients.create(MONGO_URI)) {
            MongoDatabase db = client.getDatabase(DB_NAME);
            MongoCollection<Document> collection = db.getCollection(COLLECTION_NAME);

            if (dropExisting) {
                collection.drop();
                collection.createIndex(Indexes.ascending("timestamp"));
            } else {
                collection.createIndex(Indexes.ascending("timestamp"));
                Document lastDoc = collection.find().sort(Sorts.descending("timestamp")).first();
                if (lastDoc != null && lastDoc.getString("timestamp") != null) {
                    Instant lastTs = Instant.parse(lastDoc.getString("timestamp")).truncatedTo(ChronoUnit.MINUTES);
                    startInclusive = lastTs.plus(SAMPLE_INTERVAL_MINUTES, ChronoUnit.MINUTES);
                    Object lastMinuteObj = lastDoc.get("minuteIndex");
                    if (lastMinuteObj instanceof Number num) {
                        initialMinuteOffset = num.intValue() + SAMPLE_INTERVAL_MINUTES;
                    }
                } else {
                    startInclusive = Instant.now().truncatedTo(ChronoUnit.MINUTES);
                }
            }

            if (startInclusive.isAfter(endInclusive)) {
                JOptionPane.showMessageDialog(
                        null,
                        "Nessun nuovo campione da generare: l'istante di partenza (" +
                                formatForDisplay(startInclusive) + ") è successivo a quello finale (" +
                                formatForDisplay(endInclusive) + ").",
                        "Intervallo non valido",
                        JOptionPane.WARNING_MESSAGE
                );
                System.out.println("Nessun campione generato: start > end.");
                return;
            }

            long totalMinutesLong = Duration.between(startInclusive, endInclusive).toMinutes() + 1;
            int totalSteps = (int) totalMinutesLong;

            // Pianifica 10 Wet_Can_0x fuori specifica ogni 2 ore (120 min) su 3 BM in istanti casuali
            Map<Integer, List<OosInjection>> scheduledWetCanOosByArrivalMinute =
                    scheduleWetCanOutOfSpec(random, initialMinuteOffset, totalSteps);

            // Memorizza i valori Wet_Can_0x generati al momento della produzione della cup (minuto t)
            // per farli comparire all'ingresso in BM al minuto (t + BM_DELAY_MINUTES)
            Map<Integer, Map<String, Double>> delayedWetCanByArrivalMinute = new HashMap<>();

            List<Document> batch = new ArrayList<>(totalSteps);
            int cupOutOfSpecCount = 0;
            int wetCanOutOfSpecCount = 0;

            for (int step = 0; step < totalSteps; step++) {
                int minuteIndex = initialMinuteOffset + step;
                Instant sampleTime = startInclusive.plus((long) step * SAMPLE_INTERVAL_MINUTES, ChronoUnit.MINUTES);

                boolean injectCupOutOfSpec = (minuteIndex > 0) && (minuteIndex % CUPPER_OOS_INTERVAL_MINUTES == 0);
                int targetCupIndex = injectCupOutOfSpec ? (random.nextInt(CUPS_PER_STROKE) + 1) : -1;

                Map<String, Double> cupsMeasurements = new LinkedHashMap<>();

                for (int cupIdx = 1; cupIdx <= CUPS_PER_STROKE; cupIdx++) {
                    String fieldName = "topwallcups" + cupIdx;
                    double cupMean = cupMeans[cupIdx - 1];
                    double value;

                    if (injectCupOutOfSpec && cupIdx == targetCupIndex) {
                        boolean aboveUpper = random.nextBoolean();
                        double delta = 0.002 + random.nextDouble() * 0.006;
                        value = aboveUpper ? (CUP_USL + delta) : (CUP_LSL - delta);
                        cupOutOfSpecCount++;
                    } else {
                        value = sampleInSpecGaussian(random, cupMean, CUP_SIGMA, CUP_LSL, CUP_USL);
                    }

                    cupsMeasurements.put(fieldName, round4(value));
                }

                // Associazione casuale delle 9 Wet_Can_0x alle 15 CupX prodotte in questo minuto,
                // con ingresso in BM dopo BM_DELAY_MINUTES (30 minuti)
                int arrivalMinute = minuteIndex + BM_DELAY_MINUTES;
                List<OosInjection> injectionsForArrival = scheduledWetCanOosByArrivalMinute.getOrDefault(arrivalMinute, List.of());
                Map<String, OosInjection> injectionByWetCanField = new HashMap<>();
                for (OosInjection inj : injectionsForArrival) {
                    injectionByWetCanField.put("Top_Wall_Wet_Can_" + inj.wetCanIndex, inj);
                }

                List<Integer> cupPool = new ArrayList<>();
                for (int c = 1; c <= CUPS_PER_STROKE; c++) {
                    cupPool.add(c);
                }
                Collections.shuffle(cupPool, random);

                Map<String, Double> wetCanValuesAtArrival = new LinkedHashMap<>();
                for (int w = 1; w <= WET_CAN_COUNT; w++) {
                    String wetCanField = "Top_Wall_Wet_Can_" + w;
                    int associatedCup = cupPool.get((w - 1) % cupPool.size());
                    double cupVal = cupsMeasurements.get("topwallcups" + associatedCup);

                    double wetCanVal;
                    OosInjection inj = injectionByWetCanField.get(wetCanField);
                    if (inj != null) {
                        wetCanVal = inj.aboveUpper ? (WET_CAN_USL + inj.delta) : (WET_CAN_LSL - inj.delta);
                        wetCanOutOfSpecCount++;
                    } else {
                        // Componente 6-sigma centrata su WET_CAN_MIDPOINT + lieve modulazione dalla cup associata
                        double cupShift = (cupVal - CUP_MIDPOINT) * 0.15;
                        double baseMean = clamp(wetCanMeans[w - 1] + cupShift, WET_CAN_LSL + 0.002, WET_CAN_USL - 0.002);
                        wetCanVal = sampleInSpecGaussian(random, baseMean, WET_CAN_WITHIN_SIGMA, WET_CAN_LSL, WET_CAN_USL);
                    }

                    wetCanValuesAtArrival.put(wetCanField, round4(wetCanVal));
                }
                delayedWetCanByArrivalMinute.put(arrivalMinute, wetCanValuesAtArrival);

                // Costruisce il sotto-documento wetCanMeasurements per le coppette entrate in BM in questo minuto (con delay 30 min)
                Map<String, Double> activeWetCanMeasurements = delayedWetCanByArrivalMinute.remove(minuteIndex);
                Document wetCanDoc = new Document();
                for (int w = 1; w <= WET_CAN_COUNT; w++) {
                    String wetCanField = "Top_Wall_Wet_Can_" + w;
                    Double val = (activeWetCanMeasurements != null) ? activeWetCanMeasurements.get(wetCanField) : null;
                    wetCanDoc.append(wetCanField, val);
                }

                Document doc = new Document("machine", MACHINE_ID)
                        .append("minuteIndex", minuteIndex)
                        .append("timestamp", sampleTime.toString())
                        .append("strokesPerMinute", STROKES_PER_MINUTE)
                        .append("cupsPerStroke", CUPS_PER_STROKE)
                        .append("specLimitsMm", new Document("lsl", CUP_LSL).append("usl", CUP_USL).append("midpoint", CUP_MIDPOINT))
                        .append("cupMeansMm", toNamedValuesDocument(cupMeans, "topwallcups"))
                        .append("measurements", new Document(cupsMeasurements))
                        .append("wetCanMeasurements", wetCanDoc);

                batch.add(doc);
            }

            if (!batch.isEmpty()) {
                collection.insertMany(batch);
            }

            String summary = "Modalità: " + selectedMode.label + "\n" +
                    "Database / Collection: " + DB_NAME + "." + COLLECTION_NAME + "\n" +
                    "Intervallo generato: " + formatForDisplay(startInclusive) + " -> " + formatForDisplay(endInclusive) + "\n" +
                    "Campioni inseriti: " + batch.size() + "\n" +
                    "Fuori specifica Cupper01 iniettati: " + cupOutOfSpecCount + "\n" +
                    "Fuori specifica Wet_Can_01..09 (30 min delay, 3 BM) iniettati: " + wetCanOutOfSpecCount + "\n" +
                    "Media teorica Cupper01 (15 cups): " + round4(average(cupMeans)) + " mm\n" +
                    "Centro specifica Wet_Can (6-sigma): " + WET_CAN_MIDPOINT + " mm (sigma totale: " + round4(WET_CAN_TOTAL_SIGMA) + " mm)";

            System.out.println(summary);
            JOptionPane.showMessageDialog(null, summary, "Generazione completata", JOptionPane.INFORMATION_MESSAGE);
        }
    }

    private static Map<Integer, List<OosInjection>> scheduleWetCanOutOfSpec(
            Random random,
            int initialMinuteOffset,
            int totalSteps
    ) {
        Map<Integer, List<OosInjection>> schedule = new HashMap<>();
        if (totalSteps <= 0) {
            return schedule;
        }

        int endMinuteExclusive = initialMinuteOffset + totalSteps;
        int firstWindowIndex = Math.max(0, initialMinuteOffset / WET_CAN_OOS_WINDOW_MINUTES);
        int lastWindowIndex = Math.max(0, (endMinuteExclusive - 1) / WET_CAN_OOS_WINDOW_MINUTES);

        for (int wIdx = firstWindowIndex; wIdx <= lastWindowIndex; wIdx++) {
            int windowStart = wIdx * WET_CAN_OOS_WINDOW_MINUTES;
            int windowEnd = Math.min(endMinuteExclusive, windowStart + WET_CAN_OOS_WINDOW_MINUTES);
            if (windowEnd <= windowStart) {
                continue;
            }

            int span = windowEnd - windowStart;
            for (int k = 0; k < WET_CAN_OOS_PER_WINDOW; k++) {
                int randomMinute = windowStart + random.nextInt(span);
                int wetCanIndex = random.nextInt(WET_CAN_COUNT) + 1;
                int bmIndex = random.nextInt(BM_COUNT) + 1; // distribuite casualmente su 3 BM (1..3)
                boolean aboveUpper = random.nextBoolean();
                double delta = 0.002 + random.nextDouble() * 0.005;

                schedule.computeIfAbsent(randomMinute, ignored -> new ArrayList<>())
                        .add(new OosInjection(wetCanIndex, bmIndex, aboveUpper, delta));
            }
        }

        return schedule;
    }

    private static Mode askModeViaDialog() {
        Mode[] options = Mode.values();
        Object choice = JOptionPane.showInputDialog(
                null,
                "Seleziona l'operazione da eseguire su " + DB_NAME + "." + COLLECTION_NAME + ":",
                "Gestione Database FE_DB",
                JOptionPane.QUESTION_MESSAGE,
                null,
                options,
                options[0]
        );
        return (choice instanceof Mode mode) ? mode : null;
    }

    private static Instant[] askStartAndEndRange() {
        Instant start = askSingleTimestamp("Data/ora INIZIO (formato mm-hh-dd-mm-aa):");
        if (start == null) {
            return null;
        }
        Instant end = askSingleTimestamp("Data/ora FINE (formato mm-hh-dd-mm-aa):");
        if (end == null) {
            return null;
        }
        if (end.isBefore(start)) {
            JOptionPane.showMessageDialog(
                    null,
                    "La data/ora di fine non può essere precedente alla data/ora di inizio.",
                    "Intervallo non valido",
                    JOptionPane.ERROR_MESSAGE
            );
            return null;
        }
        return new Instant[]{start, end};
    }

    private static Instant askSingleTimestamp(String prompt) {
        String defaultValue = LocalDateTime.now(ZONE_ID).truncatedTo(ChronoUnit.MINUTES).format(INPUT_FORMATTER);
        while (true) {
            String input = JOptionPane.showInputDialog(
                    null,
                    prompt + "\nEsempio: 00-08-22-11-26 (min-ora-giorno-mese-anno)",
                    "Inserimento data e ora",
                    JOptionPane.PLAIN_MESSAGE
            );
            if (input == null) {
                return null;
            }
            String trimmed = input.trim();
            if (trimmed.isEmpty()) {
                trimmed = defaultValue;
            }
            try {
                LocalDateTime ldt = LocalDateTime.parse(trimmed, INPUT_FORMATTER).truncatedTo(ChronoUnit.MINUTES);
                return ldt.atZone(ZONE_ID).toInstant();
            } catch (DateTimeParseException ex) {
                JOptionPane.showMessageDialog(
                        null,
                        "Formato non valido ('" + trimmed + "').\nUsa esattamente mm-hh-dd-mm-aa (es. 00-08-22-11-26).",
                        "Errore formato",
                        JOptionPane.ERROR_MESSAGE
                );
            }
        }
    }

    private static String formatForDisplay(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZONE_ID).format(INPUT_FORMATTER);
    }

    private static double[] buildCenteredMeans(Random random, int n, double targetMean, double maxOffset, double lsl, double usl) {
        double[] means = new double[n];
        double sum = 0.0;

        for (int i = 0; i < n - 1; i++) {
            double offset = (random.nextDouble() * 2.0 - 1.0) * maxOffset;
            double candidate = clamp(targetMean + offset, lsl + 0.002, usl - 0.002);
            means[i] = round4(candidate);
            sum += means[i];
        }

        double lastMean = (targetMean * n) - sum;
        if (lastMean < lsl || lastMean > usl) {
            double halfSpan = Math.min(maxOffset, Math.min(targetMean - lsl, usl - targetMean) * 0.6);
            sum = 0.0;
            for (int i = 0; i < n - 1; i++) {
                double offset = (random.nextDouble() * 2.0 - 1.0) * halfSpan;
                means[i] = round4(targetMean + offset);
                sum += means[i];
            }
            lastMean = (targetMean * n) - sum;
        }

        means[n - 1] = round4(lastMean);
        return means;
    }

    private static Document toNamedValuesDocument(double[] values, String prefix) {
        Document doc = new Document();
        for (int i = 0; i < values.length; i++) {
            doc.append(prefix + (i + 1), values[i]);
        }
        return doc;
    }

    private static double sampleInSpecGaussian(Random random, double mean, double sigma, double lsl, double usl) {
        double val;
        do {
            val = mean + random.nextGaussian() * sigma;
        } while (val < lsl || val > usl);
        return val;
    }

    private static double clamp(double val, double min, double max) {
        return Math.max(min, Math.min(max, val));
    }

    private static double average(double[] values) {
        double s = 0.0;
        for (double v : values) {
            s += v;
        }
        return s / values.length;
    }

    private static double round4(double val) {
        return Math.round(val * 10000.0) / 10000.0;
    }
}