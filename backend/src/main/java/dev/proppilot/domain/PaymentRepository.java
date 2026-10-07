package dev.proppilot.domain;

import java.time.LocalDate;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

    @Query("""
            select new dev.proppilot.domain.OverdueRow(
                t.id, t.nameEn, t.nameAr, u.code, count(p), sum(p.amount), min(p.dueDate))
            from Payment p join p.lease l join l.tenant t join l.unit u
            where p.paidOn is null and p.dueDate < :cutoff and l.active = true
            group by t.id, t.nameEn, t.nameAr, u.code
            order by min(p.dueDate), t.id""")
    List<OverdueRow> findOverdue(@Param("cutoff") LocalDate cutoff, Pageable pageable);

    @Query("""
            select p from Payment p join fetch p.lease l
            where l.tenant.id = :tenantId
            order by p.period desc""")
    List<Payment> findByTenant(@Param("tenantId") Long tenantId, Pageable pageable);

    @Query("""
            select p from Payment p join fetch p.lease l join fetch l.unit u
            where upper(u.code) = upper(:unitCode)
            order by p.period desc""")
    List<Payment> findByUnitCode(@Param("unitCode") String unitCode, Pageable pageable);

    @Query("""
            select sum(p.paidAmount) as collected, sum(p.amount) as expected,
                   count(p) as invoices, sum(case when p.paidOn is null then 1 else 0 end) as unpaid
            from Payment p
            where p.period = :period and (:building is null or p.lease.unit.building.code = :building)""")
    MonthlyTotals totalsForMonth(@Param("period") LocalDate period, @Param("building") String building);

    interface MonthlyTotals {
        java.math.BigDecimal getCollected();

        java.math.BigDecimal getExpected();

        long getInvoices();

        Long getUnpaid();
    }
}
