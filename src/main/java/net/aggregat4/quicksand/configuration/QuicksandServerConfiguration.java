package net.aggregat4.quicksand.configuration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.filter.HiddenHttpMethodFilter;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class QuicksandServerConfiguration implements WebMvcConfigurer {

    private final String staticFilesBasePath;

    public QuicksandServerConfiguration(
            @Value("${quicksand_static_files_path}") String staticFilesBasePath) {
        this.staticFilesBasePath = staticFilesBasePath;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // noop so far
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/css/**")
                .addResourceLocations("classpath:/static/css/", staticFilesBasePath + "css/");
        registry.addResourceHandler("/js/**")
                .addResourceLocations("classpath:/static/js/", staticFilesBasePath + "js/");
        registry.addResourceHandler("/images/**")
                .addResourceLocations("classpath:/static/images/", staticFilesBasePath + "images/");
    }

    /**
     * This filter allows us to simulate DELETE and PUT using a hidden parameter on a form POST.
     */
    @Bean
    public HiddenHttpMethodFilter hiddenHttpMethodFilter() {
        return new HiddenHttpMethodFilter();
    }

}
