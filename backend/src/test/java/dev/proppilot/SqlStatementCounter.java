package dev.proppilot;

import jakarta.persistence.EntityManagerFactory;
import java.util.function.Supplier;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;

/** Counts the SQL statements Hibernate prepares, to prove that a code path does not query once per row. */
public final class SqlStatementCounter {

    private final Statistics statistics;

    public SqlStatementCounter(EntityManagerFactory entityManagerFactory) {
        this.statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
    }

    public long during(Supplier<?> action) {
        long before = statistics.getPrepareStatementCount();
        action.get();
        return statistics.getPrepareStatementCount() - before;
    }
}
