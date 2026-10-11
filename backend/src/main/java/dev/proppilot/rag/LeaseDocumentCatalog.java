package dev.proppilot.rag;

import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** What the public endpoints show of the stored lease documents. */
@Repository
public class LeaseDocumentCatalog {

    private final JdbcTemplate jdbc;

    public LeaseDocumentCatalog(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<LeaseDocumentView> list() {
        return jdbc.query("""
                select d.code, d.title, d.language, u.code, t.name_en, t.name_ar, d.page_count,
                       (select count(*) from lease_chunks c where c.document_id = d.id)
                from lease_documents d
                join leases l on l.id = d.lease_id
                join units u on u.id = l.unit_id
                join tenants t on t.id = l.tenant_id
                order by d.lease_id""",
                (rs, row) -> new LeaseDocumentView(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getString(6), rs.getInt(7), rs.getInt(8)));
    }

    public Optional<byte[]> pdf(String code) {
        return jdbc.query("select pdf from lease_documents where code = ?", (rs, row) -> rs.getBytes(1), code)
                .stream().findFirst();
    }
}
