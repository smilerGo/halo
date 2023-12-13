package run.halo.app.config;

import static org.springframework.util.ResourceUtils.FILE_URL_PREFIX;
import static org.springframework.web.reactive.function.server.RequestPredicates.accept;
import static org.springframework.web.reactive.function.server.RequestPredicates.method;
import static org.springframework.web.reactive.function.server.RequestPredicates.path;
import static org.springframework.web.reactive.function.server.RouterFunctions.route;
import static run.halo.app.infra.utils.FileUtils.checkDirectoryTraversal;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Objects;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.web.WebProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.codec.CodecConfigurer;
import org.springframework.http.codec.HttpMessageWriter;
import org.springframework.http.codec.ServerCodecConfigurer;
import org.springframework.http.codec.json.Jackson2JsonDecoder;
import org.springframework.http.codec.json.Jackson2JsonEncoder;
import org.springframework.lang.NonNull;
import org.springframework.web.reactive.config.ResourceHandlerRegistration;
import org.springframework.web.reactive.config.ResourceHandlerRegistry;
import org.springframework.web.reactive.config.WebFluxConfigurer;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.reactive.resource.EncodedResourceResolver;
import org.springframework.web.reactive.resource.PathResourceResolver;
import org.springframework.web.reactive.result.view.ViewResolutionResultHandler;
import org.springframework.web.reactive.result.view.ViewResolver;
import reactor.core.publisher.Mono;
import run.halo.app.console.ConsoleProxyFilter;
import run.halo.app.console.WebSocketRequestPredicate;
import run.halo.app.core.extension.endpoint.CustomEndpoint;
import run.halo.app.core.extension.endpoint.CustomEndpointsBuilder;
import run.halo.app.infra.properties.HaloProperties;

@Configuration
public class WebFluxConfig implements WebFluxConfigurer {

    private final ObjectMapper objectMapper;

    private final HaloProperties haloProp;


    private final WebProperties.Resources resourceProperties;

    private final ApplicationContext applicationContext;

    public WebFluxConfig(ObjectMapper objectMapper,
        HaloProperties haloProp,
        WebProperties webProperties,
        ApplicationContext applicationContext) {
        this.objectMapper = objectMapper;
        this.haloProp = haloProp;
        this.resourceProperties = webProperties.getResources();
        this.applicationContext = applicationContext;
    }

    @Bean
    ServerResponse.Context context(CodecConfigurer codec,
        ViewResolutionResultHandler resultHandler) {
        return new ServerResponse.Context() {
            @Override
            @NonNull
            public List<HttpMessageWriter<?>> messageWriters() {
                return codec.getWriters();
            }

            @Override
            @NonNull
            public List<ViewResolver> viewResolvers() {
                return resultHandler.getViewResolvers();
            }
        };
    }

    @Override
    public void configureHttpMessageCodecs(ServerCodecConfigurer configurer) {
        // 我们需要自定义Jackson2Json[Decoder][Encoder]来序列化和反序列化特殊类型，
        // 例如:Instant, LocalDateTime。
        // 所以我们使用外部创建的ObjectMapper。
        configurer.defaultCodecs().jackson2JsonDecoder(new Jackson2JsonDecoder(objectMapper));
        configurer.defaultCodecs().jackson2JsonEncoder(new Jackson2JsonEncoder(objectMapper));
    }

    @Bean
    RouterFunction<ServerResponse> customEndpoints(ApplicationContext context) {
        var builder = new CustomEndpointsBuilder();
        context.getBeansOfType(CustomEndpoint.class).values().forEach(builder::add);
        return builder.build();
    }


    /**
     * 将控制台首页的请求重定向路由器生成一个Bean。
     * 该路由器会检测请求的方法是否为GET，
     * 路径是否匹配 "/console/**"（不包括 "/console/assets/**"），
     * 是否TEXT_HTML媒体类型，
     * 是否满足非 WebSocketRequestPredicate 的条件。
     * 如果满足上述条件，则会调用 serveConsoleIndex 方法来处理该请求。
     */
    @Bean
    RouterFunction<ServerResponse> consoleIndexRedirection() {
        var consolePredicate = method(HttpMethod.GET)
            .and(path("/console/**").and(path("/console/assets/**").negate()))
            .and(accept(MediaType.TEXT_HTML))
            .and(new WebSocketRequestPredicate().negate());
        return route(consolePredicate, this::serveConsoleIndex);
    }


    private Mono<ServerResponse> serveConsoleIndex(ServerRequest request) {
        var indexLocation = haloProp.getConsole().getLocation() + "index.html";
        var indexResource = applicationContext.getResource(indexLocation);
        try {
            return ServerResponse.ok()
                .cacheControl(CacheControl.noStore())
                .body(BodyInserters.fromResource(indexResource));
        } catch (Throwable e) {
            return Mono.error(e);
        }
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        var attachmentsRoot = haloProp.getWorkDir().resolve("attachments");
        final var cacheControl = resourceProperties.getCache()
            .getCachecontrol()
            .toHttpCacheControl();
        final var useLastModified = resourceProperties.getCache().isUseLastModified();

        // Mandatory resource mapping
        var uploadRegistration = registry.addResourceHandler("/upload/**")
            .addResourceLocations(FILE_URL_PREFIX + attachmentsRoot.resolve("upload") + "/")
            .setUseLastModified(useLastModified)
            .setCacheControl(cacheControl);

        // For console assets
        registry.addResourceHandler("/console/assets/**")
            .addResourceLocations(haloProp.getConsole().getLocation() + "assets/")
            .setCacheControl(cacheControl)
            .setUseLastModified(useLastModified)
            .resourceChain(true)
            .addResolver(new EncodedResourceResolver())
            .addResolver(new PathResourceResolver());

        // Additional resource mappings
        var staticResources = haloProp.getAttachment().getResourceMappings();
        staticResources.forEach(staticResource -> {
            ResourceHandlerRegistration registration;
            if (Objects.equals(staticResource.getPathPattern(), "/upload/**")) {
                registration = uploadRegistration;
            } else {
                registration = registry.addResourceHandler(staticResource.getPathPattern());
            }
            staticResource.getLocations().forEach(location -> {
                var path = attachmentsRoot.resolve(location);
                checkDirectoryTraversal(attachmentsRoot, path);
                registration.addResourceLocations(FILE_URL_PREFIX + path + "/")
                    .setCacheControl(cacheControl)
                    .setUseLastModified(useLastModified);
            });
        });


        var haloStaticPath = haloProp.getWorkDir().resolve("static");
        registry.addResourceHandler("/**")
            .addResourceLocations(FILE_URL_PREFIX + haloStaticPath + "/")
            .addResourceLocations(resourceProperties.getStaticLocations())
            .setCacheControl(CacheControl.noCache())
            .setUseLastModified(true);
    }


    @ConditionalOnProperty(name = "halo.console.proxy.enabled", havingValue = "true")
    @Bean
    ConsoleProxyFilter consoleProxyFilter() {
        return new ConsoleProxyFilter(haloProp);
    }
}
