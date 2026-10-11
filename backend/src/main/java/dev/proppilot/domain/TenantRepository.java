package dev.proppilot.domain;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TenantRepository extends JpaRepository<Tenant, Long> {

    @Query("""
            select t from Tenant t
            where lower(t.nameEn) like lower(concat('%', :q, '%')) or t.nameAr like concat('%', :q, '%')
            order by t.id""")
    List<Tenant> searchByName(@Param("q") String query);

    /** Only the two names of every tenant: what the grounding guardrail looks for in an answer. */
    @Query("select t.nameEn as nameEn, t.nameAr as nameAr from Tenant t order by t.id")
    List<TenantNameView> allNames();

    interface TenantNameView {
        String getNameEn();

        String getNameAr();
    }
}
