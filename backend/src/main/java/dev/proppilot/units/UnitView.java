package dev.proppilot.units;

import dev.proppilot.domain.Unit;
import java.math.BigDecimal;

public record UnitView(
        String code,
        String building,
        String buildingCode,
        String cityEn,
        String cityAr,
        int floor,
        int bedrooms,
        int areaSqm,
        BigDecimal monthlyRent,
        String status) {

    public static UnitView from(Unit unit) {
        var building = unit.getBuilding();
        return new UnitView(
                unit.getCode(),
                building.getName(),
                building.getCode(),
                building.getCity().getNameEn(),
                building.getCity().getNameAr(),
                unit.getFloor(),
                unit.getBedrooms(),
                unit.getAreaSqm(),
                unit.getMonthlyRent(),
                unit.getStatus().name());
    }
}
