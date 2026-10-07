package dev.proppilot.units;

import dev.proppilot.domain.UnitRepository;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class UnitService {

    private final UnitRepository units;

    public UnitService(UnitRepository units) {
        this.units = units;
    }

    public Page<UnitView> search(UnitFilter filter, int page, int size) {
        var pageable = PageRequest.of(page, size, Sort.by("building.code", "code"));
        return units.findAll(UnitSpecifications.from(filter), pageable).map(UnitView::from);
    }

    public Optional<UnitView> findByCode(String code) {
        return units.findByCodeIgnoreCase(code.strip()).map(UnitView::from);
    }
}
