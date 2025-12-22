package com.tikrai.mailreceiver.forward;

import com.tikrai.mailreceiver.model.IncomingEmailPayload;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;

@Component
public class ForwardClient {

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
    client.post()
        .uri(url)
        .contentType(MediaType.APPLICATION_JSON)
        .headers(h -> {
          if (apiKey != null && !apiKey.isBlank()) {
            h.add(authHeaderName, apiKey);
          }
        })
        .bodyValue(payload)
        .retrieve()
        .toBodilessEntity()
        .timeout(Duration.ofSeconds(10))
        .doOnError(e -> System.err.println("Forward failed: " + e.getMessage()))
        .block();
  }
}
