package net.aggregat4.quicksand.webservice;

import io.helidon.http.HeaderNames;
import io.helidon.webserver.http.HttpRules;
import io.helidon.webserver.http.HttpService;
import io.helidon.webserver.http.ServerRequest;
import io.helidon.webserver.http.ServerResponse;
import java.nio.charset.StandardCharsets;
import net.aggregat4.quicksand.service.AttachmentService;

public class AttachmentWebService implements HttpService {
  private final AttachmentService attachmentService;

  public AttachmentWebService(AttachmentService attachmentService) {
    this.attachmentService = attachmentService;
  }

  @Override
  public void routing(HttpRules rules) {
    rules.get("/{attachmentId}", this::emailAttachmentHandler);
  }

  private void emailAttachmentHandler(ServerRequest request, ServerResponse response) {
    int attachmentId = RequestUtils.intPathParam(request, "attachmentId");
    var attachment = attachmentService.getStoredAttachment(attachmentId);
    if (attachment.isEmpty()) {
      response.status(404);
      response.send();
      return;
    }
    response.headers().contentType(attachment.get().mediaType());
    if (attachment.get().name() != null) {
      response
          .headers()
          .add(
              HeaderNames.CONTENT_DISPOSITION,
              "attachment; filename=\"%s\""
                  .formatted(
                      ResponseUtils.encodeContentDispositionFilename(
                          attachment.get().name(), StandardCharsets.UTF_8)));
    }
    ResponseUtils.setCacheControlImmutable(response);
    response.send(attachment.get().content());
  }
}
