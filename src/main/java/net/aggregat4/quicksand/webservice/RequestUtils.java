package net.aggregat4.quicksand.webservice;

import io.helidon.http.BadRequestException;
import io.helidon.webserver.http.ServerRequest;

public class RequestUtils {

  static int intPathParam(ServerRequest request, String paramName) {
    String param = request.path().pathParameters().first(paramName).orElse(null);
    if (param == null) {
      throw new BadRequestException("Parameter %s is required".formatted(paramName));
    }
    return Integer.parseInt(param);
  }
}
