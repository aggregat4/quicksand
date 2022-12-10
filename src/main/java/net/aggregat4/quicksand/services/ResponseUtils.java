package net.aggregat4.quicksand.services;

import io.helidon.common.http.Http;
import io.helidon.webserver.HttpException;
import io.helidon.webserver.ServerResponse;

import java.net.URI;
import java.nio.charset.Charset;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ResponseUtils {
    public static void redirectAfterPost(ServerResponse response, URI location) {
        response.status(303);
        response.headers().location(location);
        response.send();
    }

    /**
     * From the ContentDisposition class in Spring
     * <p>
     * <a href="https://github.com/spring-projects/spring-framework/blob/main/spring-web/src/main/java/org/springframework/http/ContentDisposition.java">ContentDisposition in Spring</a>
     */
    public static String encodeContentDispositionFilename(String input, Charset charset) {
        byte[] source = input.getBytes(charset);
        int len = source.length;
        StringBuilder sb = new StringBuilder(len << 1);
        sb.append(charset.name());
        sb.append("''");
        for (byte b : source) {
            if (isRFC5987AttrChar(b)) {
                sb.append((char) b);
            }
            else {
                sb.append('%');
                char hex1 = Character.toUpperCase(Character.forDigit((b >> 4) & 0xF, 16));
                char hex2 = Character.toUpperCase(Character.forDigit(b & 0xF, 16));
                sb.append(hex1);
                sb.append(hex2);
            }
        }
        return sb.toString();
    }

    /**
     * From the ContentDisposition class in Spring
     * <p>
     * <a href="https://github.com/spring-projects/spring-framework/blob/main/spring-web/src/main/java/org/springframework/http/ContentDisposition.java">ContentDisposition in Spring</a>
     */
    private static boolean isRFC5987AttrChar(byte c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') ||
                c == '!' || c == '#' || c == '$' || c == '&' || c == '+' || c == '-' ||
                c == '.' || c == '^' || c == '_' || c == '`' || c == '|' || c == '~';
    }

    static void setCacheControlImmutable(ServerResponse response) {
        response.headers().add(Http.Header.CACHE_CONTROL, "max-age=365000000, immutable");
    }

    private static Logger LOGGER = LoggerFactory.getLogger(ResponseUtils.class);

    static Consumer<Throwable> asyncExceptionConsumer(ServerResponse response) {
        return throwable -> {
            if (throwable instanceof CompletionException completionException && completionException.getCause() instanceof HttpException httpException) {
                response.send(httpException);
            } else if (throwable instanceof HttpException httpException) {
                response.send(httpException);
            } else {
                LOGGER.error("Internal error occurred: " + throwable.getMessage(), throwable);
                response.status(500);
                response.send();
            }
        };
    }
}
