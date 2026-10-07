package dev.proppilot.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "tenants")
@Getter
@NoArgsConstructor
public class Tenant {

    @Id
    private Long id;
    private String nameEn;
    private String nameAr;
    private String phone;
    private String email;
    private String preferredLanguage;
}
