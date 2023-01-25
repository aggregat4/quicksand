package net.aggregat4.quicksand.webservice;

import io.helidon.webserver.Routing;
import io.helidon.webserver.ServerRequest;
import io.helidon.webserver.ServerResponse;
import io.helidon.webserver.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

public class AttachmentWebService implements Service {
    @Override
    public void update(Routing.Rules rules) {
        rules.get("/{attachmentId}", this::emailAttachmentHandler);
    }

    private void emailAttachmentHandler(ServerRequest request, ServerResponse response) {
        int attachmentId = RequestUtils.intPathParam(request, "attachmentId");
        if (attachmentId == 1) {
            var attachment = MockEmailData.ATTACHMENT1;
            response.headers().contentType(attachment.mediaType());
            if (attachment.name() != null) {
                response.headers().add("Content-Disposition", "attachment; filename=\"%s\"".formatted(ResponseUtils.encodeContentDispositionFilename(attachment.name(), StandardCharsets.UTF_8)));
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
