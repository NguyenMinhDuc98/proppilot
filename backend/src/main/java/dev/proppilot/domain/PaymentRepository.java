package dev.proppilot.domain;

import jakarta.persistence.LockModeType;
import java.time.LocalDate;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

    /** Invoices of a lease that are due and not settled, oldest first: the order a payment is applied in. */
    String OUTSTANDING = """
            select p from Payment p
            where p.lease.id = :leaseId and p.paidOn is null and p.dueDate <= :asOf
            order by p.period, p.id""";

    @Query("""
            select new dev.proppilot.domain.OverdueRow(
                t.id, t.nameEn, t.nameAr, u.code, count(p), sum(p.amount - p.paidAmount), min(p.dueDate))
            from Payment p join p.lease l join l.tenant t join l.unit u
            where p.paidOn is null and p.dueDate < :cutoff and l.active = true
            group by t.id, t.nameEn, t.nameAr, u.code
            order by min(p.dueDate), t.id""")
    List<OverdueRow> findOverdue(@Param("cutoff") LocalDate cutoff, Pageable pageable);

    @Query("""
            select p from Payment p join fetch p.lease l join fetch l.unit u join fetch l.tenant
            where upper(u.code) = upper(:unitCode)
            order by p.period desc, p.id desc""")
    List<Payment> findByUnitCode(@Param("unitCode") String unitCode, Pageable pageable);

    List<Payment> findByLeaseIdOrderByPeriodDesc(Long leaseId, Pageable pageable);

    List<Payment> findByLeaseIdAndPaidOnIsNullAndDueDateBeforeOrderByPeriod(Long leaseId, LocalDate before);

    @Query(OUTSTANDING)
    List<Payment> findOutstanding(@Param("leaseId") Long leaseId, @Param("asOf") LocalDate asOf);

    /** Same rows, locked until the transaction ends so two payments cannot be applied to them at once. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(OUTSTANDING)
    List<Payment> lockOutstanding(@Param("leaseId") Long leaseId, @Param("asOf") LocalDate asOf);

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
