package com.tikrai.mailreceiver.forward;

import com.tikrai.mailreceiver.model.IncomingEmailPayload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.stream.Collectors;

@Component
public class ForwardClient {

  private static final Logger log = LoggerFactory.getLogger(ForwardClient.class);

  private final WebClient client;
  private final String url;
  private final String authHeaderName;
  private final String apiKey;

  public ForwardClient(
      WebClient.Builder builder,
      @Value("${app.forward.url}") String url,
      @Value("${app.forward.timeoutMs}") long timeoutMs,
      @Value("${app.forward.authHeaderName}") String authHeaderName,
      @Value("${APP_FORWARD_API_KEY:}") String apiKey
  ) {
    this.url = url;
    this.authHeaderName = authHeaderName;
    this.apiKey = apiKey;

    this.client = builder
        .codecs(c -> c.defaultCodecs().maxInMemorySize(10 * 1024 * 1024))
        .build();
  }

  public void forward(IncomingEmailPayload payload) {
    // Extract first recipient email (controller expects single "to" parameter)
    String toEmail = payload.rcptTo() != null && !payload.rcptTo().isEmpty() 
        ? payload.rcptTo().get(0) 
        : "";
    
    log.info("HTTP POST request - URL: {}, FROM: {}, TO: {}, SUBJECT: {}", 
        url, payload.mailFrom(), toEmail, payload.subject());
    log.debug("HTTP POST request payload size - headers: {}, text: {}, html: {}, rawBase64: {}", 
        payload.headers().size(),
        payload.textBody() != null ? payload.textBody().length() : 0,
        payload.htmlBody() != null ? payload.htmlBody().length() : 0,
        payload.rawBase64() != null ? payload.rawBase64().length() : 0);
    
    // Serialize headers Map to String format
    String headersStr = payload.headers().entrySet().stream()
        .map(entry -> entry.getKey() + ": " + String.join(", ", entry.getValue()))
        .collect(Collectors.joining("\n"));
    
    // Build form-urlencoded body
    MultiValueMap<String, String> formData = new LinkedMultiValueMap<>();
    formData.add("to", toEmail);
    formData.add("from", payload.mailFrom() != null ? payload.mailFrom() : "");
    formData.add("subject", payload.subject() != null ? payload.subject() : "");
    if (payload.textBody() != null && !payload.textBody().isEmpty()) {
      formData.add("text", payload.textBody());
    }
    if (payload.htmlBody() != null && !payload.htmlBody().isEmpty()) {
      formData.add("html", payload.htmlBody());
    }
    if (!headersStr.isEmpty()) {
      formData.add("headers", headersStr);
    }
    
    try {
      client.post()
          .uri(url)
          .contentType(MediaType.APPLICATION_FORM_URLENCODED)
          .headers(h -> {
            if (apiKey != null && !apiKey.isBlank()) {
              h.add(authHeaderName, apiKey);
              log.debug("HTTP POST request - added auth header: {}", authHeaderName);
            }
          })
          .bodyValue(formData)
          .retrieve()
          .toBodilessEntity()
          .timeout(Duration.ofSeconds(10))
          .doOnSuccess(response -> {
            log.info("HTTP POST response - Status: {}, FROM: {}, TO: {}", 
                response.getStatusCode(), payload.mailFrom(), toEmail);
          })
          .doOnError(e -> {
            log.error("HTTP POST request failed - URL: {}, FROM: {}, TO: {}, ERROR: {}", 
                url, payload.mailFrom(), toEmail, e.getMessage(), e);
          })
          .block();
    } catch (Exception e) {
      log.error("HTTP POST request exception - URL: {}, FROM: {}, TO: {}, ERROR: {}", 
          url, payload.mailFrom(), toEmail, e.getMessage(), e);
      throw e;
    }
  }
}
