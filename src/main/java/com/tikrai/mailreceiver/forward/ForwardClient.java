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

  public ForwardClient(
      WebClient.Builder builder,
      @Value("${app.forward.url}") String url,
      @Value("${app.forward.timeoutMs}") long timeoutMs
  ) {
    this.url = url;

    this.client = builder
        .codecs(c -> c.defaultCodecs().maxInMemorySize(10 * 1024 * 1024))
        .build();
  }

  public void forward(IncomingEmailPayload payload) {
    log.info("=== FORWARD CLIENT CALLED ===");
    log.info("Forwarding email to: {}", url);
    
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
    // Always send text and html parameters, even if empty
    formData.add("text", payload.textBody() != null ? payload.textBody() : "");
    formData.add("html", payload.htmlBody() != null ? payload.htmlBody() : "");
    if (!headersStr.isEmpty()) {
      formData.add("headers", headersStr);
    }
    
    // Log all form parameters for debugging
    log.info("=== HTTP POST REQUEST DETAILS ===");
    log.info("URL: {}", url);
    log.info("Content-Type: application/x-www-form-urlencoded");
    log.info("Form parameters:");
    formData.forEach((key, values) -> {
      if (key.equals("text") || key.equals("html") || key.equals("headers")) {
        log.info("  {}: [{} bytes]", key, values.get(0) != null ? values.get(0).length() : 0);
        if (values.get(0) != null && values.get(0).length() < 500) {
          log.info("    Content preview: {}", values.get(0).substring(0, Math.min(200, values.get(0).length())));
        }
      } else {
        log.info("  {}: {}", key, values.get(0));
      }
    });
    
    // Build curl command for manual testing
    StringBuilder curlCmd = new StringBuilder("curl -X POST '").append(url).append("'");
    curlCmd.append(" -H 'Content-Type: application/x-www-form-urlencoded'");
    curlCmd.append(" -d '");
    boolean first = true;
    for (String key : formData.keySet()) {
      if (!first) curlCmd.append("&");
      first = false;
      String value = formData.getFirst(key);
      // URL encode the value
      value = value != null ? value.replace("'", "'\\''") : "";
      curlCmd.append(key).append("=").append(value);
    }
    curlCmd.append("'");
    log.info("CURL command to reproduce:");
    log.info("{}", curlCmd.toString());
    log.info("=== END REQUEST DETAILS ===");
    
    log.info("Sending HTTP POST request NOW...");
    try {
      var response = client.post()
          .uri(url)
          .contentType(MediaType.APPLICATION_FORM_URLENCODED)
          .bodyValue(formData)
          .retrieve()
          .toEntity(String.class)
          .timeout(Duration.ofSeconds(10))
          .doOnSubscribe(subscription -> {
            log.info("HTTP POST request SUBSCRIBED - starting request to: {}", url);
          })
          .doOnNext(responseEntity -> {
            log.info("HTTP POST RESPONSE RECEIVED - Status: {}, FROM: {}, TO: {}", 
                responseEntity.getStatusCode(), payload.mailFrom(), toEmail);
            log.info("Response headers: {}", responseEntity.getHeaders());
            if (responseEntity.getBody() != null) {
              log.info("Response body: {}", responseEntity.getBody());
            }
          })
          .doOnSuccess(responseEntity -> {
            log.info("HTTP POST SUCCESS - Status: {}, FROM: {}, TO: {}", 
                responseEntity.getStatusCode(), payload.mailFrom(), toEmail);
          })
          .doOnError(e -> {
            log.error("HTTP POST ERROR - URL: {}, FROM: {}, TO: {}, ERROR: {}", 
                url, payload.mailFrom(), toEmail, e.getMessage(), e);
            if (e instanceof org.springframework.web.reactive.function.client.WebClientResponseException) {
              org.springframework.web.reactive.function.client.WebClientResponseException wcre = 
                  (org.springframework.web.reactive.function.client.WebClientResponseException) e;
              log.error("Response status: {}, Response body: {}", 
                  wcre.getStatusCode(), wcre.getResponseBodyAsString());
            }
          })
          .block();
      
      if (response != null) {
        log.info("HTTP POST request COMPLETED - Status: {}, Body: {}", 
            response.getStatusCode(), response.getBody());
      } else {
        log.warn("HTTP POST request COMPLETED but response is NULL");
      }
    } catch (Exception e) {
      log.error("HTTP POST EXCEPTION - URL: {}, FROM: {}, TO: {}, ERROR: {}", 
          url, payload.mailFrom(), toEmail, e.getMessage(), e);
      log.error("Exception stack trace:", e);
      throw e;
    }
    log.info("=== FORWARD CLIENT FINISHED ===");
  }
}
