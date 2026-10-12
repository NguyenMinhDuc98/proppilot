package dev.proppilot.domain;

import dev.proppilot.text.Folding;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TenantRepository extends JpaRepository<Tenant, Long> {

    /**
     * The tenants whose name contains the text, as it was written or spelled the way {@code Folding} spells it: "احمد"
     * finds "أحمد", and "Al Harbi" finds "Al-Harbi". A name that matched as written still does, so folding only adds.
     */
    default List<Tenant> searchByName(String query) {
        var folded = Folding.fold(Folding.withoutDiacritics(query)).replace('-', ' ');
        return matching(query, folded);
    }

    /**
     * @param folded the query as {@code Folding} spells it, with a space for a hyphen; the Arabic names are folded
     *               with {@code translate} to the same letters ({@code أإآىئؤة} to {@code اااييوه})
     */
    @Query(value = """
            select t.* from tenants t
            where lower(t.name_en) like lower('%' || :q || '%')
               or t.name_ar like '%' || :q || '%'
               or replace(lower(t.name_en), '-', ' ') like '%' || :folded || '%'
               or translate(t.name_ar, 'أإآىئؤة', 'اااييوه') like '%' || :folded || '%'
            order by t.id""", nativeQuery = true)
    List<Tenant> matching(@Param("q") String query, @Param("folded") String folded);

    /** Only the two names of every tenant: what the grounding guardrail looks for in an answer. */
    @Query("select t.nameEn as nameEn, t.nameAr as nameAr from Tenant t order by t.id")
    List<TenantNameView> allNames();

    interface TenantNameView {
        String getNameEn();

        String getNameAr();
    }
}
