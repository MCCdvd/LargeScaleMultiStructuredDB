package it.unipi.dbgen.generators.pf;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.Sorts;
import org.bson.Document;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

public class PfDbGenerator {

    private static final String MONGO_URI = "mongodb://127.0.0.1:27017";

    private static final String BE_DB_NAME = "BE_DB";
    private static final String BE_COLLECTION_NAME = "be_spray_samples";

    private static final String PF_DB_NAME = "PF_DB";
    private static final String PF_COLLECTION_NAME = "pallets";

    private static final String DEFAULT_ORDER_NUMBER = "ORD-2026-001";
    private static final String DEFAULT_INITIAL_STATUS = "PENDING";

    private static final int CANS_PER_PALLET = 968;
    private static final long PALLET_PREP_MINUTES = 3L; // ~3 minuti per preparare un pallet

    private static final double SPRAY_LSL = 3.0;
    private static final double SPRAY_USL = 4.0;
    private static final double SPRAY_MID = 3.5;
    private static final double SPRAY_6SIGMA_HALF = 0.50;

    public record CanItem(
            int canSeqInPallet,
            String sourceBeDocId,
            int beMinuteIndex,
            String beTimestamp,
            String sprayChannel,
            double sprayThicknessMgSm,
            String wetCan,
            double wetCanThicknessMm,
            boolean outOfSpec,
            boolean outside6SigmaCenter
    ) {
    }

    public record PalletDocumentModel(
            String numeroOrdine,
            int palletProgressivo,
            Instant prepTimestamp,
            String stato,
            String chiavePallet,
            int cansCount,
            Instant firstBeTimestamp,
            Instant lastBeTimestamp,
            List<CanItem> cans
    ) {
    }

    public static String buildPalletKey(String numeroOrdine, int palletProgressivo, Instant prepTimestamp, String stato) {
        return numeroOrdine + ":" + palletProgressivo + ":" + prepTimestamp.toString() + ":" + stato;
    }

    public List<PalletDocumentModel> generatePfDbFromBeFifo(
            MongoClient mongoClient,
            String numeroOrdine,
            int startPalletProgressivo,
            boolean dropExistingCollection
    ) {
        MongoDatabase beDb = mongoClient.getDatabase(BE_DB_NAME);
        MongoCollection<Document> beCollection = beDb.getCollection(BE_COLLECTION_NAME);

        MongoDatabase pfDb = mongoClient.getDatabase(PF_DB_NAME);
        MongoCollection<Document> pfCollection = pfDb.getCollection(PF_COLLECTION_NAME);

        if (dropExistingCollection) {
            pfCollection.drop();
        }
        pfCollection.createIndex(
                Indexes.ascending("chiavePallet"),
                new IndexOptions().unique(true)
        );

        // 1) Lettura in ordine FIFO rigoroso dai dati già presenti in BE_DB
        List<CanItem> fifoCans = new ArrayList<>();
        int runningSeq = 0;

        for (Document beDoc : beCollection.find().sort(Sorts.ascending("timestamp", "minuteIndex"))) {
            String beDocId = beDoc.getObjectId("_id") != null ? beDoc.getObjectId("_id").toHexString() : "";
            int minuteIndex = beDoc.getInteger("minuteIndex", 0);
            String beTs = beDoc.getString("timestamp");
            Document sprayDoc = beDoc.get("Spray_thik_distr", Document.class);
            Document wetCansDoc = beDoc.get("WET_Cans", Document.class);

            if (sprayDoc == null) {
                continue;
            }

            for (int c = 1; c <= 5; c++) {
                String channel = "Can_Spray_" + c;
                Object rawVal = sprayDoc.get(channel);
                if (!(rawVal instanceof Number numVal)) {
                    continue;
                }

                double thickness = numVal.doubleValue();
                boolean outOfSpec = thickness < SPRAY_LSL || thickness > SPRAY_USL;
                boolean outside6Sigma = Math.abs(thickness - SPRAY_MID) > SPRAY_6SIGMA_HALF;

                String wetCanCode = "N/A";
                double wetCanThicknessMm = 0.0;
                if (wetCansDoc != null && wetCansDoc.get(channel) instanceof Document wDoc) {
                    if (wDoc.getString("wetCan") != null) {
                        wetCanCode = wDoc.getString("wetCan");
                    }
                    if (wDoc.get("thicknessMm") instanceof Number wNum) {
                        wetCanThicknessMm = wNum.doubleValue();
                    }
                }

                runningSeq++;
                fifoCans.add(new CanItem(
                        runningSeq,
                        beDocId,
                        minuteIndex,
                        beTs,
                        channel,
                        thickness,
                        wetCanCode,
                        wetCanThicknessMm,
                        outOfSpec,
                        outside6Sigma
                ));
            }
        }

        // 2) Suddivisione in pallet fino a 968 cans con tempo di preparazione di 3 minuti per pallet
        List<PalletDocumentModel> createdModels = new ArrayList<>();
        List<Document> mongoDocsToInsert = new ArrayList<>();

        int palletProgressivo = startPalletProgressivo;
        Instant pipelineCursor = null;

        for (int offset = 0; offset < fifoCans.size(); offset += CANS_PER_PALLET) {
            int end = Math.min(offset + CANS_PER_PALLET, fifoCans.size());
            List<CanItem> palletCans = new ArrayList<>(end - offset);

            for (int i = offset; i < end; i++) {
                CanItem src = fifoCans.get(i);
                palletCans.add(new CanItem(
                        (i - offset) + 1,
                        src.sourceBeDocId(),
                        src.beMinuteIndex(),
                        src.beTimestamp(),
                        src.sprayChannel(),
                        src.sprayThicknessMgSm(),
                        src.wetCan(),
                        src.wetCanThicknessMm(),
                        src.outOfSpec(),
                        src.outside6SigmaCenter()
                ));
            }

            Instant firstBeTs = parseInstantOrFallback(palletCans.get(0).beTimestamp(), Instant.now());
            Instant lastBeTs = parseInstantOrFallback(palletCans.get(palletCans.size() - 1).beTimestamp(), firstBeTs);

            // Ogni pallet richiede ~3 minuti di preparazione a partire dall'inizio del flusso/pallet precedente
            Instant baseStart = (pipelineCursor == null || firstBeTs.isAfter(pipelineCursor))
                    ? firstBeTs
                    : pipelineCursor;
            Instant prepTimestamp = baseStart.plus(PALLET_PREP_MINUTES, ChronoUnit.MINUTES);
            pipelineCursor = prepTimestamp;

            String chiavePallet = buildPalletKey(numeroOrdine, palletProgressivo, prepTimestamp, DEFAULT_INITIAL_STATUS);

            PalletDocumentModel model = new PalletDocumentModel(
                    numeroOrdine,
                    palletProgressivo,
                    prepTimestamp,
                    DEFAULT_INITIAL_STATUS,
                    chiavePallet,
                    palletCans.size(),
                    firstBeTs,
                    lastBeTs,
                    palletCans
            );
            createdModels.add(model);
            mongoDocsToInsert.add(toMongoDocument(model));
            palletProgressivo++;
        }

        if (!mongoDocsToInsert.isEmpty()) {
            pfCollection.insertMany(mongoDocsToInsert);
        }

        return createdModels;
    }

    private static Document toMongoDocument(PalletDocumentModel model) {
        List<Document> canDocs = new ArrayList<>(model.cans().size());
        int oosCount = 0;
        int out6SigmaCount = 0;

        for (CanItem c : model.cans()) {
            if (c.outOfSpec()) oosCount++;
            if (c.outside6SigmaCenter()) out6SigmaCount++;

            canDocs.add(new Document("canSeqInPallet", c.canSeqInPallet())
                    .append("sourceBeDocId", c.sourceBeDocId())
                    .append("beMinuteIndex", c.beMinuteIndex())
                    .append("beTimestamp", c.beTimestamp())
                    .append("sprayChannel", c.sprayChannel())
                    .append("sprayThicknessMgSm", c.sprayThicknessMgSm())
                    .append("wetCan", c.wetCan())
                    .append("wetCanThicknessMm", c.wetCanThicknessMm())
                    .append("outOfSpec", c.outOfSpec())
                    .append("outside6SigmaCenter", c.outside6SigmaCenter()));
        }

        return new Document("numeroOrdine", model.numeroOrdine())
                .append("palletProgressivo", model.palletProgressivo())
                .append("prepDurationMinutes", PALLET_PREP_MINUTES)
                .append("prepTimestamp", model.prepTimestamp().toString())
                .append("stato", model.stato())
                .append("chiavePallet", model.chiavePallet())
                .append("cansCount", model.cansCount())
                .append("outOfSpecCans", oosCount)
                .append("outside6SigmaCans", out6SigmaCount)
                .append("firstBeTimestamp", model.firstBeTimestamp().toString())
                .append("lastBeTimestamp", model.lastBeTimestamp().toString())
                .append("orderPolicy", "FIFO")
                .append("cans", canDocs);
    }

    private static Instant parseInstantOrFallback(String rawTs, Instant fallback) {
        if (rawTs == null || rawTs.isBlank()) {
            return fallback;
        }
        try {
            return Instant.parse(rawTs);
        } catch (Exception ex) {
            return fallback;
        }
    }

    public static void main(String[] args) {
        String orderNumber = (args != null && args.length > 0 && !args[0].isBlank())
                ? args[0]
                : DEFAULT_ORDER_NUMBER;

        try (MongoClient mongoClient = MongoClients.create(MONGO_URI)) {
            PfDbGenerator generator = new PfDbGenerator();
            List<PalletDocumentModel> pallets = generator.generatePfDbFromBeFifo(
                    mongoClient,
                    orderNumber,
                    1,
                    true
            );

            System.out.println("PF_DB.pallets popolato in FIFO da BE_DB.");
            System.out.println("Ordine: " + orderNumber + " | Pallet creati: " + pallets.size());
            for (PalletDocumentModel p : pallets) {
                System.out.println(" - Chiave: " + p.chiavePallet()
                        + " | Cans: " + p.cansCount()
                        + " | Prep (+3m): " + p.prepTimestamp());
            }
        }
    }
}