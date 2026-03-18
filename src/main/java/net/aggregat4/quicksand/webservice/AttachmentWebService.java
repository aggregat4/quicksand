package net.aggregat4.quicksand.webservice;

import io.helidon.http.HeaderNames;
import io.helidon.webserver.http.HttpRules;
import io.helidon.webserver.http.HttpService;
import io.helidon.webserver.http.ServerRequest;
import io.helidon.webserver.http.ServerResponse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

public class AttachmentWebService implements HttpService {
    @Override
    public void routing(HttpRules rules) {
        rules.get("/{attachmentId}", this::emailAttachmentHandler);
    }

    private void emailAttachmentHandler(ServerRequest request, ServerResponse response) {
        int attachmentId = RequestUtils.intPathParam(request, "attachmentId");
        if (attachmentId == 1) {
            var attachment = MockEmailData.ATTACHMENT1;
            response.headers().contentType(attachment.mediaType());
            if (attachment.name() != null) {
                response.headers().add(HeaderNames.CONTENT_DISPOSITION, "attachment; filename=\"%s\"".formatted(ResponseUtils.encodeContentDispositionFilename(attachment.name(), StandardCharsets.UTF_8)));
            }
            ResponseUtils.setCacheControlImmutable(response);
            try {
                response.send(MockEmailData.class.getResourceAsStream(MockEmailData.sampleMp3Resource).readAllBytes());
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        } else {
            response.status(404);
            response.send();
        }
    }

}
