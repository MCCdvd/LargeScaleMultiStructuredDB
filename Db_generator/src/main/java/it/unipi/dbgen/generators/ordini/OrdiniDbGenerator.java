package it.unipi.dbgen.sql;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

public class OrdiniDbGenerator {

    // Connessione al server SQL (senza DB nel URL per poter creare il database Ordini se manca)
    public static final String SERVER_JDBC_URL =
            "jdbc:mysql://127.0.0.1:3306/?createDatabaseIfNotExist=true&useSSL=false&serverTimezone=UTC&allowPublicKeyRetrieval=true";
    public static final String ORDINI_JDBC_URL =
            "jdbc:mysql://127.0.0.1:3306/Ordini?createDatabaseIfNotExist=true&useSSL=false&serverTimezone=UTC&allowPublicKeyRetrieval=true";
    public static final String SQL_USER = "root";
    public static final String SQL_PASSWORD = "password";

    public static final int CANS_PER_PALLET = 968;

    public record PalletSeed(
            int palletProgressivo,
            String chiavePallet,
            int cansCount,
            Instant timestampPreparazione,
            String statoPallet
    ) {
    }

    public void createDatabaseAndTables() throws SQLException {
        String createDbSql = "CREATE DATABASE IF NOT EXISTS Ordini";

        String createOrdiniTableSql = """
                CREATE TABLE IF NOT EXISTS ordini (
                    numero_ordine VARCHAR(64) PRIMARY KEY,
                    descrizione VARCHAR(255) NOT NULL,
                    quantita INT NOT NULL,
                    pallet_previsti INT NOT NULL DEFAULT 0,
                    pallet_prodotti_ok INT NOT NULL DEFAULT 0,
                    data_inserimento_ordine DATETIME NOT NULL,
                    data_chiusura_ordine DATETIME NULL,
                    stato VARCHAR(32) NOT NULL DEFAULT 'APERTO',
                    riferimento_certificato VARCHAR(255) NULL
                )
                """;

        String createPalletOrdineTableSql = """
                CREATE TABLE IF NOT EXISTS pallet_ordine (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    numero_ordine VARCHAR(64) NOT NULL,
                    pallet_progressivo INT NOT NULL,
                    chiave_pallet VARCHAR(255) NOT NULL UNIQUE,
                    cans_count INT NOT NULL,
                    timestamp_preparazione DATETIME NOT NULL,
                    stato_pallet VARCHAR(32) NOT NULL DEFAULT 'PENDING',
                    CONSTRAINT fk_pallet_ordine_num
                        FOREIGN KEY (numero_ordine) REFERENCES ordini(numero_ordine)
                        ON DELETE CASCADE
                )
                """;

        try (Connection serverConn = DriverManager.getConnection(SERVER_JDBC_URL, SQL_USER, SQL_PASSWORD);
             Statement st = serverConn.createStatement()) {
            st.executeUpdate(createDbSql);
        }

        try (Connection ordiniConn = DriverManager.getConnection(ORDINI_JDBC_URL, SQL_USER, SQL_PASSWORD);
             Statement st = ordiniConn.createStatement()) {
            st.executeUpdate(createOrdiniTableSql);
            st.executeUpdate(createPalletOrdineTableSql);
        }
    }

    public void upsertOrdine(
            String numeroOrdine,
            String descrizione,
            int quantita,
            Instant dataInserimentoOrdine,
            Instant dataChiusuraOrdine,
            String stato,
            String riferimentoCertificato
    ) throws SQLException {
        int palletPrevisti = (int) Math.ceil((double) quantita / CANS_PER_PALLET);

        String upsertSql = """
                INSERT INTO ordini (
                    numero_ordine,
                    descrizione,
                    quantita,
                    pallet_previsti,
                    pallet_prodotti_ok,
                    data_inserimento_ordine,
                    data_chiusura_ordine,
                    stato,
                    riferimento_certificato
                ) VALUES (?, ?, ?, ?, 0, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE
                    descrizione = VALUES(descrizione),
                    quantita = VALUES(quantita),
                    pallet_previsti = VALUES(pallet_previsti),
                    data_inserimento_ordine = VALUES(data_inserimento_ordine),
                    data_chiusura_ordine = VALUES(data_chiusura_ordine),
                    stato = VALUES(stato),
                    riferimento_certificato = VALUES(riferimento_certificato)
                """;

        try (Connection conn = DriverManager.getConnection(ORDINI_JDBC_URL, SQL_USER, SQL_PASSWORD);
             PreparedStatement ps = conn.prepareStatement(upsertSql)) {
            ps.setString(1, numeroOrdine);
            ps.setString(2, descrizione);
            ps.setInt(3, quantita);
            ps.setInt(4, palletPrevisti);
            ps.setTimestamp(5, Timestamp.from(dataInserimentoOrdine != null ? dataInserimentoOrdine : Instant.now()));
            if (dataChiusuraOrdine != null) {
                ps.setTimestamp(6, Timestamp.from(dataChiusuraOrdine));
            } else {
                ps.setNull(6, java.sql.Types.TIMESTAMP);
            }
            ps.setString(7, stato != null ? stato : "APERTO");
            if (riferimentoCertificato != null && !riferimentoCertificato.isBlank()) {
                ps.setString(8, riferimentoCertificato);
            } else {
                ps.setNull(8, java.sql.Types.VARCHAR);
            }
            ps.executeUpdate();
        }
    }

    public void insertPreparedPallets(String numeroOrdine, List<PalletSeed> pallets) throws SQLException {
        if (pallets == null || pallets.isEmpty()) {
            return;
        }

        String insertSql = """
                INSERT IGNORE INTO pallet_ordine (
                    numero_ordine,
                    pallet_progressivo,
                    chiave_pallet,
                    cans_count,
                    timestamp_preparazione,
                    stato_pallet
                ) VALUES (?, ?, ?, ?, ?, ?)
                """;

        try (Connection conn = DriverManager.getConnection(ORDINI_JDBC_URL, SQL_USER, SQL_PASSWORD)) {
            conn.setAutoCommit(false);
            try (PreparedStatement ps = conn.prepareStatement(insertSql)) {
                for (PalletSeed p : pallets) {
                    ps.setString(1, numeroOrdine);
                    ps.setInt(2, p.palletProgressivo());
                    ps.setString(3, p.chiavePallet());
                    ps.setInt(4, p.cansCount());
                    ps.setTimestamp(5, Timestamp.from(p.timestampPreparazione()));
                    ps.setString(6, p.statoPallet() != null ? p.statoPallet() : "PENDING");
                    ps.addBatch();
                }
                ps.executeBatch();
                conn.commit();
            } catch (SQLException ex) {
                conn.rollback();
                throw ex;
            } finally {
                conn.setAutoCommit(true);
            }
        }
    }

    public static void main(String[] args) throws Exception {
        OrdiniDbGenerator creator = new OrdiniDbGenerator();
        creator.createDatabaseAndTables();

        String numeroOrdine = "ORD-2026-001";
        String descrizione = "Lotto lattine produzione PF - linea BE_Line01 (FIFO)";
        int quantita = 4840; // es. 5 pallet da 968 cans
        Instant inserimento = Instant.now();
        Instant chiusura = null; // valorizzata alla chiusura ordine
        String stato = "APERTO";
        String riferimentoCertificato = null; // valorizzato all'emissione del certificato CQ

        creator.upsertOrdine(
                numeroOrdine,
                descrizione,
                quantita,
                inserimento,
                chiusura,
                stato,
                riferimentoCertificato
        );

        System.out.println("Database SQL 'Ordini' e tabella 'ordini' creati e inizializzati per " + numeroOrdine);
    }
}