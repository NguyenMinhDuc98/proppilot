package dev.proppilot.agent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.proppilot.domain.LeaseRepository;
import dev.proppilot.domain.PaymentRepository;
import dev.proppilot.units.UnitService;
import dev.proppilot.units.UnitView;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import org.springframework.stereotype.Component;

@Component
public class GetUnitDetailsTool implements Tool {

    private final UnitService units;
    private final LeaseRepository leases;
    private final PaymentRepository payments;
    private final Clock clock;
    private final ObjectMapper json;

    public GetUnitDetailsTool(UnitService units, LeaseRepository leases, PaymentRepository payments,
                              Clock clock, ObjectMapper json) {
        this.units = units;
        this.leases = leases;
        this.payments = payments;
        this.clock = clock;
        this.json = json;
    }

    @Override
    public String name() {
        return "get_unit_details";
    }

    @Override
    public String description() {
        return "Get everything about one unit: specs, rent, status, current tenant, lease dates and unpaid rent.";
    }

    @Override
    public JsonNode inputSchema() {
        return ToolSchema.object().string("unit_code", "Unit code such as A-203").required("unit_code").build();
    }

    @Override
    public ToolResult execute(JsonNode input) {
        var code = new ToolArgs(input).requireString("unit_code");
        var unit = units.findByCode(code);
        if (unit.isEmpty()) {
            return ToolResult.error("No unit with code '" + code + "'. Codes look like A-203 (building letter A-F, then floor and number).");
        }
        var lease = leases.findActiveByUnitCode(unit.get().code());
        var tenant = lease.map(l -> {
            var overdue = payments.findByLeaseIdAndPaidOnIsNullAndDueDateBeforeOrderByPeriod(l.getId(), LocalDate.now(clock));
            var unpaid = overdue.stream().map(p -> p.getAmount()).reduce(BigDecimal.ZERO, BigDecimal::add);
            return new TenantInfo(l.getTenant().getNameEn(), l.getTenant().getNameAr(),
                    l.getStartDate(), l.getEndDate(), overdue.size(), unpaid);
        }).orElse(null);
        return ToolResult.ok(json, new Result(unit.get(), tenant), "Unit " + unit.get().code() + " (" + unit.get().status() + ")");
    }

    record Result(UnitView unit, TenantInfo currentTenant) {
    }

    record TenantInfo(String nameEn, String nameAr, LocalDate leaseStart, LocalDate leaseEnd,
                      int overdueMonths, BigDecimal overdueAmount) {
    }
}
