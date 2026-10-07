package dev.proppilot.units;

import dev.proppilot.domain.UnitStatus;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/units")
@Validated
public class UnitController {

    private final UnitService service;

    public UnitController(UnitService service) {
        this.service = service;
    }

    @GetMapping
    public UnitPage list(
            @RequestParam(required = false) String city,
            @RequestParam(required = false) String building,
            @RequestParam(required = false) UnitStatus status,
            @RequestParam(required = false) Integer bedrooms,
            @RequestParam(required = false) BigDecimal minRent,
            @RequestParam(required = false) BigDecimal maxRent,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        var result = service.search(new UnitFilter(city, building, status, bedrooms, minRent, maxRent), page, size);
        return new UnitPage(result.getContent(), result.getTotalElements(), page, size);
    }

    @GetMapping("/{code}")
    public ResponseEntity<UnitView> get(@PathVariable String code) {
        return service.findByCode(code).map(ResponseEntity::ok).orElse(ResponseEntity.notFound().build());
    }

    public record UnitPage(List<UnitView> items, long total, int page, int size) {
    }
}
