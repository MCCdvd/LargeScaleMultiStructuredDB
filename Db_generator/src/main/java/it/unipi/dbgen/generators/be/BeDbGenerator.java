package it.unipi.dbgen.generators.be;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.Sorts;
import org.bson.Document;

import javax.swing.JOptionPane;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

public class BeDbGenerator {

    private static final String MONGO_URI = "mongodb://127.0.0.1:27017";
    private static final String BE_DB_NAME = "BE_DB";
    private static final String BE_COLLECTION_NAME = "be_spray_samples";

    private static final String FE_DB_NAME = "FE_DB";
    private static final String FE_COLLECTION_NAME = "cupper01_samples";

    private static final String LINE_ID = "BE_Line01";
    private static final int CAN_SPRAY_COUNT = 5;
    private static final int BE_DELAY_MINUTES = 30;

    // Specifica nominale Spray_thik_distr: 3.0 <= Spray_thik_distr <= 4.0 mg/sm (centro 3.5)
    private static final double SPRAY_LSL = 3.0;
    private static final double SPRAY_USL = 4.0;
    private static final double SPRAY_MIDPOINT = (SPRAY_LSL + SPRAY_USL) / 2.0; // 3.5 mg/sm

    // Processo normale 6-sigma sul range [3.0, 4.0] mg/sm -> sigma = (4.0 - 3.0) / 6 = 0.1667 mg/sm
    private static final double NORMAL_SIGMA = (SPRAY_USL - SPRAY_LSL) / 6.0;
    private static final double MAX_CAN_MEAN_OFFSET = 0.18;

    // Condizione di errore: media spostata vicino al limite inferiore con dispersione 2-sigma
    private static final double FAULT_NEAR_LOWER_MEAN = 3.08;
    private static final double FAULT_2SIGMA_SPREAD = (SPRAY_USL - SPRAY_LSL) / 2.0; // 0.50 mg/sm (2-sigma sul range)
    private static final int FAULT_WINDOW_START_MINUTE = 180;
    private static final int FAULT_WINDOW_DURATION_MINUTES = 45;

    private static final DateTimeFormatter DISPLAY_FORMATTER = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss");
    private static final ZoneId ZONE_ID = ZoneId.systemDefault();

    public record WetCanFifoItem(String wetCanCode, String sourceKey, double thicknessMm, Instant feTimestamp) {
    }

    public interface WetCanSourceProvider {
        Deque<WetCanFifoItem> loadWetCansFifo(MongoClient client);
    }

    public static class MongoFeDbWetCanFifoProvider implements WetCanSourceProvider {
        @Override
        public Deque<WetCanFifoItem> loadWetCansFifo(MongoClient client) {
            Deque<WetCanFifoItem> fifoQueue = new ArrayDeque<>();
            MongoCollection<Document> feCollection = client
                    .getDatabase(FE_DB_NAME)
                    .getCollection(FE_COLLECTION_NAME);

            // Ordine cronologico crescente: FIFO (prima i dati arrivati prima in FE_DB)
            for (Document doc : feCollection.find().sort(Sorts.ascending("timestamp"))) {
                Instant feInstant = parseInstantSafe(doc.getString("timestamp"));
                Object wetObj = doc.get("wetCanMeasurements");
                if (wetObj instanceof Document wetDoc) {
                    for (Map.Entry<String, Object> entry : wetDoc.entrySet()) {
                        String key = entry.getKey();
                        if (isWetCanKey(key) && entry.getValue() instanceof Number num) {
                            fifoQueue.addLast(new WetCanFifoItem(
                                    normalizeWetCanCode(key),
                                    key,
                                    num.doubleValue(),
                                    feInstant
                            ));
                        }
                    }
                }
            }
            return fifoQueue;
        }

        private static boolean isWetCanKey(String key) {
            return key != null && key.toLowerCase(Locale.ROOT).contains("wet_can");
        }

        private static String normalizeWetCanCode(String key) {
            int lastUnderscore = key.lastIndexOf('_');
            if (lastUnderscore >= 0 && lastUnderscore < key.length() - 1) {
                String tail = key.substring(lastUnderscore + 1);
                try {
                    int idx = Integer.parseInt(tail);
                    return String.format("Wet_Can_%02d", idx);
                } catch (NumberFormatException ignored) {
                    return key;
                }
            }
            return key;
        }

        private static Instant parseInstantSafe(String rawTs) {
            if (rawTs != null && !rawTs.isBlank()) {
                try {
                    return Instant.parse(rawTs);
                } catch (Exception ignored) {
                    // Fallback sull'istante corrente se il formato non è ISO-8601
                }
            }
            return Instant.now().truncatedTo(ChronoUnit.MINUTES);
        }
    }

    public static void main(String[] args) {
        Random random = new Random(104L);
        double[] canSprayMeans = buildCenteredMeans(
                random,
                CAN_SPRAY_COUNT,
                SPRAY_MIDPOINT,
                MAX_CAN_MEAN_OFFSET,
                SPRAY_LSL,
                SPRAY_USL
        );

        try (MongoClient client = MongoClients.create(MONGO_URI)) {
            WetCanSourceProvider provider = new MongoFeDbWetCanFifoProvider();
            Deque<WetCanFifoItem> fifoWetCans = provider.loadWetCansFifo(client);

            if (fifoWetCans.isEmpty()) {
                String msg = "Nessun dato WET_Can trovato in " + FE_DB_NAME + "." + FE_COLLECTION_NAME + ". Generazione BE_DB annullata.";
                System.out.println(msg);
                JOptionPane.showMessageDialog(null, msg, "FE_DB senza WET_Can", JOptionPane.WARNING_MESSAGE);
                return;
            }

            MongoDatabase beDb = client.getDatabase(BE_DB_NAME);
            MongoCollection<Document> beCollection = beDb.getCollection(BE_COLLECTION_NAME);
            beCollection.drop();
            beCollection.createIndex(Indexes.ascending("timestamp"));

            List<Document> batch = new ArrayList<>();
            int stepIndex = 0;
            int outOfSpecCount = 0;
            Instant firstBeTimestamp = null;
            Instant lastBeTimestamp = null;

            // Consuma i WET_Can presenti in FE_DB in ordine rigorosamente FIFO
            while (!fifoWetCans.isEmpty()) {
                List<WetCanFifoItem> groupForStep = new ArrayList<>(CAN_SPRAY_COUNT);
                for (int c = 0; c < CAN_SPRAY_COUNT && !fifoWetCans.isEmpty(); c++) {
                    groupForStep.add(fifoWetCans.pollFirst());
                }

                WetCanFifoItem anchorItem = groupForStep.get(0);
                Instant beSampleTime = anchorItem.feTimestamp().plus(BE_DELAY_MINUTES, ChronoUnit.MINUTES);
                if (firstBeTimestamp == null) {
                    firstBeTimestamp = beSampleTime;
                }
                lastBeTimestamp = beSampleTime;

                boolean faultActive = isFaultWindow(stepIndex);
                int faultTargetCan = faultActive ? (random.nextInt(groupForStep.size()) + 1) : -1;

                Map<String, Double> sprayMeasurements = new LinkedHashMap<>();
                Document wetCansInputDoc = new Document();

                for (int idx = 1; idx <= groupForStep.size(); idx++) {
                    String sprayField = "Can_Spray_" + idx;
                    WetCanFifoItem wetCan = groupForStep.get(idx - 1);

                    wetCansInputDoc.append(sprayField, new Document("wetCan", wetCan.wetCanCode())
                            .append("sourceField", wetCan.sourceKey())
                            .append("thicknessMm", wetCan.thicknessMm())
                            .append("feTimestamp", wetCan.feTimestamp().toString())
                            .append("beTimestamp", beSampleTime.toString()));

                    double value;
                    if (faultActive && idx == faultTargetCan) {
                        value = FAULT_NEAR_LOWER_MEAN + random.nextGaussian() * FAULT_2SIGMA_SPREAD;
                        if (value < SPRAY_LSL || value > SPRAY_USL) {
                            outOfSpecCount++;
                        }
                    } else if (faultActive) {
                        double shiftedMean = Math.min(canSprayMeans[idx - 1], FAULT_NEAR_LOWER_MEAN + 0.15);
                        value = sampleInSpecGaussian(random, shiftedMean, NORMAL_SIGMA);
                    } else {
                        double wetCanAdjustment = (wetCan.thicknessMm() - 0.140) * 0.5;
                        double effectiveMean = clamp(canSprayMeans[idx - 1] + wetCanAdjustment, SPRAY_LSL + 0.05, SPRAY_USL - 0.05);
                        value = sampleInSpecGaussian(random, effectiveMean, NORMAL_SIGMA);
                    }

                    sprayMeasurements.put(sprayField, round4(value));
                }

                Document doc = new Document("line", LINE_ID)
                        .append("minuteIndex", stepIndex)
                        .append("timestamp", beSampleTime.toString())
                        .append("beDelayMinutes", BE_DELAY_MINUTES)
                        .append("orderPolicy", "FIFO")
                        .append("semifinishedType", "WET_Cans")
                        .append("sprayThikDistrSpecMgSm", new Document("min", SPRAY_LSL).append("max", SPRAY_USL).append("center", SPRAY_MIDPOINT))
                        .append("canSprayMeansMgSm", toNamedValuesDocument(canSprayMeans, "Can_Spray_"))
                        .append("WET_Cans", wetCansInputDoc)
                        .append("Spray_thik_distr", new Document(sprayMeasurements));

                batch.add(doc);
                stepIndex++;
            }

            if (!batch.isEmpty()) {
                beCollection.insertMany(batch);
            }

            String summary = "Database / Collection: " + BE_DB_NAME + "." + BE_COLLECTION_NAME + "\n" +
                    "Sorgente: solo WET_Can da " + FE_DB_NAME + "." + FE_COLLECTION_NAME + " (FIFO)\n" +
                    "Primo timestamp BE (+30 min da FE): " + (firstBeTimestamp != null ? formatTs(firstBeTimestamp) : "N/A") + "\n" +
                    "Ultimo timestamp BE (+30 min da FE): " + (lastBeTimestamp != null ? formatTs(lastBeTimestamp) : "N/A") + "\n" +
                    "Documenti BE generati: " + batch.size() + "\n" +
                    "Valori fuori specifica rilevati (< 3.0 o > 4.0): " + outOfSpecCount;

            System.out.println(summary);
            JOptionPane.showMessageDialog(null, summary, "Generazione BE_DB completata", JOptionPane.INFORMATION_MESSAGE);
        }
    }

    private static boolean isFaultWindow(int minuteIndex) {
        return minuteIndex >= FAULT_WINDOW_START_MINUTE
                && minuteIndex < (FAULT_WINDOW_START_MINUTE + FAULT_WINDOW_DURATION_MINUTES);
    }

    private static String formatTs(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZONE_ID).format(DISPLAY_FORMATTER);
    }

    private static double[] buildCenteredMeans(Random random, int n, double targetMean, double maxOffset, double lsl, double usl) {
        double[] means = new double[n];
        double sum = 0.0;

        for (int i = 0; i < n - 1; i++) {
            double offset = (random.nextDouble() * 2.0 - 1.0) * maxOffset;
            double candidate = clamp(targetMean + offset, lsl + 0.05, usl - 0.05);
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

    private static double sampleInSpecGaussian(Random random, double mean, double sigma) {
        double val;
        do {
            val = mean + random.nextGaussian() * sigma;
        } while (val < SPRAY_LSL || val > SPRAY_USL);
        return val;
    }

    private static double clamp(double val, double min, double max) {
        return Math.max(min, Math.min(max, val));
    }

    private static double round4(double val) {
        return Math.round(val * 10000.0) / 10000.0;
    }
}