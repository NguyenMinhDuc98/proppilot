package dev.proppilot.rag;

import dev.proppilot.rag.chunk.LeaseChunk;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Reads and writes the stored lease documents and their chunks. */
@Repository
public class LeaseDocumentStore {

    private final JdbcTemplate jdbc;

    public LeaseDocumentStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Whether the lease has a document that was written by this version of the generator. */
    public boolean hasDocument(long leaseId, int generatorVersion) {
        return jdbc.queryForObject(
                "select count(*) from lease_documents where lease_id = ? and generator_version = ?", Integer.class,
                leaseId, generatorVersion) > 0;
    }

    /**
     * Stores the document with its chunks, in place of any document the lease has, in one transaction. Two writers
     * for one lease take turns, and the second finds the first one's document and writes nothing.
     *
     * @return whether the document was written
     */
    @Transactional
    public boolean replace(NewDocument document) {
        jdbc.query("select pg_advisory_xact_lock(hashtextextended(?, 0))", rs -> {
        }, "lease_document:" + document.leaseId());
        if (hasDocument(document.leaseId(), document.generatorVersion())) {
            return false;
        }
        jdbc.update("delete from lease_documents where lease_id = ?", document.leaseId());
        long id = jdbc.queryForObject("""
                insert into lease_documents (lease_id, code, title, language, page_count, generator_version, pdf)
                values (?, ?, ?, ?, ?, ?, ?)
                returning id""", Long.class, document.leaseId(), document.code(), document.title(),
                document.language().code(), document.pageCount(), document.generatorVersion(), document.pdf());
        insertChunks(id, document.chunks());
        return true;
    }

    public CorpusCounts counts() {
        return jdbc.queryForObject("""
                select (select count(*) from lease_documents), (select count(*) from lease_chunks)""",
                (rs, row) -> new CorpusCounts(rs.getInt(1), rs.getInt(2)));
    }

    private void insertChunks(long documentId, List<LeaseChunk> chunks) {
        jdbc.batchUpdate("""
                insert into lease_chunks (document_id, ref, ordinal, section_no, section_title, page, content,
                                          search_text, token_estimate)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?)""", chunks, chunks.size(),
                (PreparedStatement ps, LeaseChunk chunk) -> bind(ps, documentId, chunk));
    }

    private static void bind(PreparedStatement ps, long documentId, LeaseChunk chunk) throws SQLException {
        ps.setLong(1, documentId);
        ps.setString(2, chunk.ref());
        ps.setInt(3, chunk.ordinal());
        if (chunk.sectionNo() == null) {
            ps.setNull(4, Types.INTEGER);
        } else {
            ps.setInt(4, chunk.sectionNo());
        }
        ps.setString(5, chunk.sectionTitle());
        ps.setInt(6, chunk.page());
        ps.setString(7, chunk.content());
        ps.setString(8, chunk.searchText());
        ps.setInt(9, chunk.tokenEstimate());
    }
}
