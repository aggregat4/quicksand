package net.aggregat4.quicksand.webservice;

import io.helidon.webserver.BadRequestException;
import io.helidon.webserver.ServerRequest;

public class RequestUtils {

    static int intPathParam(ServerRequest request, String paramName) {
        String param = request.path().param(paramName);
        if (param == null) {
            throw new BadRequestException("Parameter %s is required".formatted(paramName));
        }
        return Integer.parseInt(param);
    }
}
