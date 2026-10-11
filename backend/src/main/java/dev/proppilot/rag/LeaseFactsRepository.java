package dev.proppilot.rag;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class LeaseFactsRepository {

    private final JdbcTemplate jdbc;

    public LeaseFactsRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** The first {@code count} active leases, lowest lease id first: the leases that get a document. */
    public List<LeaseFacts> firstActive(int count) {
        return jdbc.query("""
                select l.id, u.code, b.name, c.name_en, c.name_ar, b.address, u.floor, u.bedrooms, u.area_sqm,
                       t.name_en, t.name_ar, t.preferred_language, l.start_date, l.end_date, l.monthly_rent
                from leases l
                join units u on u.id = l.unit_id
                join buildings b on b.id = u.building_id
                join cities c on c.id = b.city_id
                join tenants t on t.id = l.tenant_id
                where l.active
                order by l.id
                limit ?""", this::facts, count);
    }

    private LeaseFacts facts(ResultSet rs, int row) throws SQLException {
        return new LeaseFacts(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                rs.getString(6), rs.getInt(7), rs.getInt(8), rs.getInt(9), rs.getString(10), rs.getString(11),
                DocumentLanguage.fromCode(rs.getString(12)), rs.getObject(13, LocalDate.class),
                rs.getObject(14, LocalDate.class), rs.getBigDecimal(15));
    }
}
