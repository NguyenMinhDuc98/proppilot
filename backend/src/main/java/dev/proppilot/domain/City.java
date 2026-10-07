package dev.proppilot.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "cities")
@Getter
@NoArgsConstructor
public class City {

    @Id
    private Long id;
    private String nameEn;
    private String nameAr;
}
