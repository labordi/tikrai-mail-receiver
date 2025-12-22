package com.tikrai.mailreceiver.forward;

import com.tikrai.mailreceiver.model.IncomingEmailPayload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;

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
    log.info("HTTP POST request - URL: {}, FROM: {}, TO: {}, SUBJECT: {}", 
        url, payload.mailFrom(), payload.rcptTo(), payload.subject());
    log.debug("HTTP POST request payload size - headers: {}, text: {}, html: {}, rawBase64: {}", 
        payload.headers().size(),
        payload.textBody() != null ? payload.textBody().length() : 0,
        payload.htmlBody() != null ? payload.htmlBody().length() : 0,
        payload.rawBase64() != null ? payload.rawBase64().length() : 0);
    
    try {
      client.post()
          .uri(url)
          .contentType(MediaType.APPLICATION_JSON)
          .headers(h -> {
            if (apiKey != null && !apiKey.isBlank()) {
              h.add(authHeaderName, apiKey);
              log.debug("HTTP POST request - added auth header: {}", authHeaderName);
            }
          })
          .bodyValue(payload)
          .retrieve()
          .toBodilessEntity()
          .timeout(Duration.ofSeconds(10))
          .doOnSuccess(response -> {
            log.info("HTTP POST response - Status: {}, FROM: {}, TO: {}", 
                response.getStatusCode(), payload.mailFrom(), payload.rcptTo());
          })
          .doOnError(e -> {
            log.error("HTTP POST request failed - URL: {}, FROM: {}, TO: {}, ERROR: {}", 
                url, payload.mailFrom(), payload.rcptTo(), e.getMessage(), e);
          })
          .block();
    } catch (Exception e) {
      log.error("HTTP POST request exception - URL: {}, FROM: {}, TO: {}, ERROR: {}", 
          url, payload.mailFrom(), payload.rcptTo(), e.getMessage(), e);
      throw e;
    }
  }
}
