package dev.proppilot.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "units")
@Getter
@NoArgsConstructor
public class Unit {

    @Id
    private Long id;
    private String code;
    private int floor;
    private int bedrooms;
    private int areaSqm;
    private BigDecimal monthlyRent;

    @Enumerated(EnumType.STRING)
    private UnitStatus status;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "building_id")
    private Building building;
}
