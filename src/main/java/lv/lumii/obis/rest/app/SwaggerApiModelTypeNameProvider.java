package lv.lumii.obis.rest.app;

import com.google.common.base.Strings;
import io.swagger.annotations.ApiModel;
import org.springframework.context.annotation.Primary;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.stereotype.Component;
import springfox.documentation.swagger.schema.ApiModelTypeNameProvider;

import java.util.Optional;

/**
 * Workaround to display different version models having the same class names.
 * Instead of class simple name, the full package name will be used.
 */

@Component
@Primary
public class SwaggerApiModelTypeNameProvider extends ApiModelTypeNameProvider {

    @Override
    public String nameFor(Class<?> type) {
        ApiModel annotation = AnnotationUtils.findAnnotation(type, ApiModel.class);
        String defaultTypeName = type.getTypeName();

       return annotation != null ? Optional.ofNullable(Strings.emptyToNull(annotation.value())).orElse(defaultTypeName) : defaultTypeName;

    }

}
