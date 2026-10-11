package dev.proppilot.config;

import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationPropertiesBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.core.convert.TypeDescriptor;
import org.springframework.core.convert.converter.ConditionalGenericConverter;
import org.springframework.stereotype.Component;

/**
 * Reads a blank value of a number that has a {@link DefaultValue} as no value, so that its default applies. Spring does
 * this for the empty string but not for a value of only spaces, which a host's dashboard or a workflow input can hand
 * over: that one does not parse, and the backend does not start. Every other value is converted as it always was.
 */
@Component
@ConfigurationPropertiesBinding
class BlankToDefaultConverter implements ConditionalGenericConverter {

    @Override
    public Set<ConvertiblePair> getConvertibleTypes() {
        return Set.of(new ConvertiblePair(String.class, Number.class));
    }

    @Override
    public boolean matches(TypeDescriptor sourceType, TypeDescriptor targetType) {
        return targetType.hasAnnotation(DefaultValue.class);
    }

    @Override
    public Object convert(Object source, TypeDescriptor sourceType, TypeDescriptor targetType) {
        if (((String) source).isBlank()) {
            return null;
        }
        return ApplicationConversionService.getSharedInstance().convert(source, sourceType, targetType);
    }
}
