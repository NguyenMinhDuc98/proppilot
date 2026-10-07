package dev.proppilot.agent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.proppilot.domain.BuildingRepository;
import dev.proppilot.domain.PaymentRepository;
import dev.proppilot.domain.UnitRepository;
import dev.proppilot.domain.UnitStatus;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.EnumMap;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class GetOccupancySummaryTool implements Tool {

    private final UnitRepository units;
    private final PaymentRepository payments;
    private final BuildingRepository buildings;
    private final Clock clock;
    private final ObjectMapper json;

    public GetOccupancySummaryTool(UnitRepository units, PaymentRepository payments, BuildingRepository buildings,
                                   Clock clock, ObjectMapper json) {
        this.units = units;
        this.payments = payments;
        this.buildings = buildings;
        this.clock = clock;
        this.json = json;
    }

    @Override
    public String name() {
        return "get_occupancy_summary";
    }

    @Override
    public String description() {
        return "Occupancy and rent collection for a month: unit counts by status, occupancy rate, "
                + "rent collected vs expected. Omit building for the whole portfolio, omit month for the current month.";
    }

    @Override
    public JsonNode inputSchema() {
        return ToolSchema.object()
                .string("building", "Building code A-F; omit for all buildings")
                .string("month", "Month as YYYY-MM; omit for the current month")
                .build();
    }

    @Override
    public ToolResult execute(JsonNode input) {
        var args = new ToolArgs(input);
        var buildingCode = args.string("building").map(String::toUpperCase).orElse(null);
        if (buildingCode != null && buildings.findByCodeIgnoreCase(buildingCode).isEmpty()) {
            var valid = buildings.findAllByOrderByCode().stream().map(b -> b.getCode()).toList();
            return ToolResult.error("Unknown building '" + buildingCode + "'. Valid codes: " + valid);
        }
        var month = args.string("month").map(GetOccupancySummaryTool::parseMonth).orElse(YearMonth.now(clock));

        Map<UnitStatus, Long> counts = new EnumMap<>(UnitStatus.class);
        var rentRoll = BigDecimal.ZERO;
        for (var row : units.countByStatus(buildingCode)) {
            counts.put(row.getStatus(), row.getTotal());
            rentRoll = rentRoll.add(row.getRentRoll());
        }
        long total = counts.values().stream().mapToLong(Long::longValue).sum();
        long occupied = counts.getOrDefault(UnitStatus.OCCUPIED, 0L);

        var totals = payments.totalsForMonth(month.atDay(1), buildingCode);
        var collected = orZero(totals.getCollected());
        var expected = orZero(totals.getExpected());

        var data = new Result(
                buildingCode == null ? "ALL" : buildingCode, month.toString(), total,
                occupied, counts.getOrDefault(UnitStatus.VACANT, 0L), counts.getOrDefault(UnitStatus.MAINTENANCE, 0L),
                percent(occupied, total), rentRoll, collected, expected,
                expected.signum() == 0 ? BigDecimal.ZERO : collected.multiply(BigDecimal.valueOf(100)).divide(expected, 1, RoundingMode.HALF_UP),
                totals.getUnpaid() == null ? 0 : totals.getUnpaid());
        return ToolResult.ok(json, data, data.building() + " " + data.month() + ": " + data.occupancyRatePercent() + "% occupied");
    }

    private static YearMonth parseMonth(String raw) {
        try {
            return YearMonth.parse(raw);
        } catch (DateTimeParseException e) {
            throw new ToolInputException("month must look like 2026-03");
        }
    }

    private static BigDecimal orZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private static BigDecimal percent(long part, long whole) {
        return whole == 0 ? BigDecimal.ZERO
                : BigDecimal.valueOf(part * 100).divide(BigDecimal.valueOf(whole), 1, RoundingMode.HALF_UP);
    }

    record Result(String building, String month, long totalUnits, long occupiedUnits, long vacantUnits,
                  long maintenanceUnits, BigDecimal occupancyRatePercent, BigDecimal monthlyRentRoll,
                  BigDecimal rentCollected, BigDecimal rentExpected, BigDecimal collectionRatePercent,
                  long unpaidInvoices) {
    }
}
