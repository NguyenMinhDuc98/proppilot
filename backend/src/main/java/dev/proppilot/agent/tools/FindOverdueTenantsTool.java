package dev.proppilot.agent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.proppilot.domain.OverdueRow;
import dev.proppilot.domain.PaymentRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

@Component
public class FindOverdueTenantsTool implements Tool {

    private static final int MAX_ROWS = 500;
    private static final int SHOWN = 15;

    private final PaymentRepository payments;
    private final Clock clock;
    private final ObjectMapper json;

    public FindOverdueTenantsTool(PaymentRepository payments, Clock clock, ObjectMapper json) {
        this.payments = payments;
        this.clock = clock;
        this.json = json;
    }

    @Override
    public String name() {
        return "find_overdue_tenants";
    }

    @Override
    public String description() {
        return "List tenants whose oldest unpaid rent is more than N days past its due date, "
                + "most overdue first. Returns the total count and the " + SHOWN + " most overdue.";
    }

    @Override
    public JsonNode inputSchema() {
        return ToolSchema.object()
                .integer("min_days_overdue", "Only tenants more than this many days overdue (default 0 = any overdue rent)")
                .build();
    }

    @Override
    public ToolResult execute(JsonNode input) {
        int minDays = new ToolArgs(input).integer("min_days_overdue", 0, 3650).orElse(0);
        var today = LocalDate.now(clock);
        List<OverdueRow> rows = payments.findOverdue(today.minusDays(minDays), PageRequest.of(0, MAX_ROWS));

        var entries = rows.stream().limit(SHOWN).map(r -> new Entry(
                r.nameEn(), r.nameAr(), r.unitCode(), r.unpaidMonths(), r.unpaidAmount(),
                ChronoUnit.DAYS.between(r.oldestDueDate(), today))).toList();
        var totalAmount = rows.stream().map(OverdueRow::unpaidAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        var data = new Result(minDays, rows.size(), rows.size() > SHOWN ? SHOWN : rows.size(), totalAmount, entries);
        return ToolResult.ok(json, data, rows.size() + " tenants overdue by more than " + minDays + " days");
    }

    record Result(int minDaysOverdue, int totalTenants, int shown, BigDecimal totalUnpaidAmount, List<Entry> tenants) {
    }

    record Entry(String nameEn, String nameAr, String unitCode, long unpaidMonths,
                 BigDecimal unpaidAmount, long daysOverdue) {
    }
}
