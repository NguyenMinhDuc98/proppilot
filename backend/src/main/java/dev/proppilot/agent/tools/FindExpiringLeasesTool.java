package dev.proppilot.agent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.proppilot.domain.Lease;
import dev.proppilot.domain.LeaseRepository;
import dev.proppilot.rag.LeaseDocumentCatalog;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

@Component
public class FindExpiringLeasesTool implements Tool {

    static final int SHOWN = 15;
    private static final int DEFAULT_WITHIN_DAYS = 60;
    private static final int MAX_WITHIN_DAYS = 365;

    private final LeaseRepository leases;
    private final LeaseDocumentCatalog documents;
    private final Clock clock;
    private final ObjectMapper json;

    public FindExpiringLeasesTool(LeaseRepository leases, LeaseDocumentCatalog documents, Clock clock,
                                  ObjectMapper json) {
        this.leases = leases;
        this.documents = documents;
        this.clock = clock;
        this.json = json;
    }

    @Override
    public String name() {
        return "find_expiring_leases";
    }

    @Override
    public String description() {
        return "List the active leases whose end date is from today to N days from today, soonest first, with the "
                + "unit, the tenant, the end date, the days left, the rent and whether a lease document is on "
                + "file. Returns the total count and the " + SHOWN + " that end first. Use it first when a "
                + "question combines a time window with a lease term, then search the lease documents of the units "
                + "it returned.";
    }

    @Override
    public JsonNode inputSchema() {
        return ToolSchema.object()
                .integer("within_days", "Leases ending within this many days from today, 1 to " + MAX_WITHIN_DAYS
                        + " (default " + DEFAULT_WITHIN_DAYS + ")")
                .build();
    }

    @Override
    public ToolResult execute(JsonNode input) {
        int withinDays = new ToolArgs(input).integer("within_days", 1, MAX_WITHIN_DAYS).orElse(DEFAULT_WITHIN_DAYS);
        var today = LocalDate.now(clock);
        var page = leases.findActiveEndingBetween(today, today.plusDays(withinDays), PageRequest.of(0, SHOWN));
        var withDocument = documents.leasesWithDocument(page.getContent().stream().map(Lease::getId).toList());

        var entries = page.getContent().stream().map(lease -> new Entry(lease.getUnit().getCode(),
                lease.getTenant().getNameEn(), lease.getTenant().getNameAr(), lease.getEndDate(),
                ChronoUnit.DAYS.between(today, lease.getEndDate()), lease.getMonthlyRent(),
                withDocument.contains(lease.getId()))).toList();
        var data = new Result(withinDays, page.getTotalElements(), entries.size(), entries);
        var summary = data.totalLeases() + (data.totalLeases() == 1 ? " lease ends" : " leases end") + " within "
                + withinDays + " days";
        return ToolResult.ok(json, data, summary);
    }

    record Result(int withinDays, long totalLeases, int shown, List<Entry> leases) {
    }

    record Entry(String unitCode, String tenantNameEn, String tenantNameAr, LocalDate endDate, long daysLeft,
                 BigDecimal monthlyRent, boolean hasLeaseDocument) {
    }
}
