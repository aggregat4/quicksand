package net.aggregat4.quicksand.configuration;

import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

import java.io.IOException;

@ControllerAdvice
public class GlobalControllerExceptionHandler {

    private final static Logger logger = LoggerFactory.getLogger("GlobalControllerExceptionHandler");

    @ExceptionHandler(value = {IllegalArgumentException.class})
    public void handleIllegalArgumentException(IllegalArgumentException e, HttpServletResponse response) throws IOException {
        logger.info("Client passed invalid data to request: ${e?.message}", e);
        response.sendError(HttpStatus.BAD_REQUEST.value());
    }

}
