package run.halo.app.infra.properties;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.net.URL;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.Errors;
import org.springframework.validation.Validator;
import org.springframework.validation.annotation.Validated;

/**
 * @author guqing
 * @since 2022-04-12
 */
@Data
@ConfigurationProperties(prefix = "halo")
@Validated
public class HaloProperties implements Validator {

    @NotNull
    private Path workDir;

    /**
     * 外部URL必须是一个URL，它可以为空。
     */
    private URL externalUrl;

    /**
     * 标识是否对帖子、页面、类别、标签等使用绝对永久链接
     */
    private boolean useAbsolutePermalink;

    private Set<String> initialExtensionLocations = new HashSet<>();

    /**
     * 此属性可能会停止初始化类路径中定义的所需扩展。
     * See {@link run.halo.app.infra.ExtensionResourceInitializer#REQUIRED_EXTENSION_LOCATIONS}
     * for more.
     */
    private boolean requiredExtensionDisabled;

    @Valid
    private final ExtensionProperties extension = new ExtensionProperties();

    @Valid
    private final SecurityProperties security = new SecurityProperties();

    @Valid
    private final ConsoleProperties console = new ConsoleProperties();

    @Valid
    private final ThemeProperties theme = new ThemeProperties();

    @Valid
    private final AttachmentProperties attachment = new AttachmentProperties();

    @Valid
    private final Map<String, CacheProperties> caches = new LinkedHashMap<>();

    @Override
    public boolean supports(Class<?> clazz) {
        return HaloProperties.class.isAssignableFrom(clazz);
    }

    @Override
    public void validate(Object target, Errors errors) {
        var props = (HaloProperties) target;
        if (props.isUseAbsolutePermalink() && props.getExternalUrl() == null) {
            errors.rejectValue("externalUrl", "external-url.required.when-using-absolute-permalink",
                "External URL is required when property `use-absolute-permalink` is set to true.");
        }
    }
}
