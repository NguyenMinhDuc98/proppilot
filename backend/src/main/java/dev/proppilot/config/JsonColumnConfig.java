package dev.proppilot.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.cfg.JsonNodeFeature;
import org.hibernate.cfg.AvailableSettings;
import org.hibernate.type.format.jackson.JacksonJsonFormatMapper;
import org.springframework.boot.autoconfigure.orm.jpa.HibernatePropertiesCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class JsonColumnConfig {

    /**
     * Money in a jsonb column must come back exactly as it went in. By default Jackson reads {@code 4800.00} as the
     * double {@code 4800.0}, so a stored result would not equal the one the approver got when it was made.
     */
    @Bean
    HibernatePropertiesCustomizer exactDecimalsInJsonColumns() {
        var mapper = new ObjectMapper().findAndRegisterModules()
                .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .configure(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES, false);
        return properties -> properties.put(AvailableSettings.JSON_FORMAT_MAPPER, new JacksonJsonFormatMapper(mapper));
    }
}
