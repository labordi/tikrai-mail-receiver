package com.tikrai.mailreceiver.smtp;

import com.tikrai.mailreceiver.forward.ForwardClient;
import com.tikrai.mailreceiver.model.IncomingEmailPayload;
import jakarta.mail.Header;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.subethamail.smtp.MessageHandler;
import org.subethamail.smtp.RejectException;

import java.io.InputStream;
import java.util.*;
import java.util.Base64;

@Component
public class DomainFilterMessageHandler implements MessageHandler {

  private static final Logger log = LoggerFactory.getLogger(DomainFilterMessageHandler.class);

  private final String acceptedDomain;
  private final ForwardClient forwardClient;

  private String mailFrom;
  private final List<String> rcptTo = new ArrayList<>();

  public DomainFilterMessageHandler(
      org.springframework.core.env.Environment env,
      ForwardClient forwardClient
  ) {
    this.acceptedDomain = Objects.requireNonNull(env.getProperty("app.smtp.acceptedDomain", "tikrai.com"));
    this.forwardClient = forwardClient;
  }

  @Override
  public void from(String from) throws RejectException {
    this.mailFrom = from;
    log.info("SMTP MAIL FROM: {}", from);
  }

  @Override
  public void recipient(String recipient) throws RejectException {
    String r = recipient.trim().toLowerCase(Locale.ROOT);
    log.info("SMTP RCPT TO: {}", r);
    if (!r.endsWith("@" + acceptedDomain)) {
      log.warn("SMTP RCPT TO rejected - not ending with @{}: {}", acceptedDomain, r);
      throw new RejectException(550, "Relaying denied");
    }
    rcptTo.add(r);
    log.debug("SMTP RCPT TO accepted: {}", r);
  }

  @Override
  public void data(InputStream data) throws RejectException {
    try {
      log.info("SMTP DATA received - FROM: {}, TO: {}", mailFrom, rcptTo);
      byte[] rawBytes = data.readAllBytes();
      log.debug("SMTP DATA size: {} bytes", rawBytes.length);
      String rawB64 = Base64.getEncoder().encodeToString(rawBytes);

      Session session = Session.getInstance(new Properties());
      MimeMessage msg = new MimeMessage(session, new java.io.ByteArrayInputStream(rawBytes));

      String subject = Optional.ofNullable(msg.getSubject()).orElse("");
      log.info("SMTP EMAIL SUBJECT: {}", subject);

      Map<String, List<String>> headers = new LinkedHashMap<>();
      @SuppressWarnings("unchecked")
      Enumeration<Header> allHeaders = msg.getAllHeaders();
      while (allHeaders.hasMoreElements()) {
        Header h = allHeaders.nextElement();
        headers.computeIfAbsent(h.getName(), k -> new ArrayList<>()).add(h.getValue());
      }
      log.debug("SMTP EMAIL HEADERS: {}", headers.keySet());

      log.info("SMTP EMAIL Content-Type: {}", msg.getContentType());
      BodyParts bodies = extractBodies(msg, rawBytes);
      log.info("SMTP EMAIL BODY EXTRACTED - text length: {}, html length: {}", 
          bodies.text != null ? bodies.text.length() : 0,
          bodies.html != null ? bodies.html.length() : 0);
      if (bodies.text != null && !bodies.text.isEmpty()) {
        log.debug("SMTP EMAIL TEXT preview (first 200 chars): {}", 
            bodies.text.substring(0, Math.min(200, bodies.text.length())));
      }
      if (bodies.html != null && !bodies.html.isEmpty()) {
        log.debug("SMTP EMAIL HTML preview (first 200 chars): {}", 
            bodies.html.substring(0, Math.min(200, bodies.html.length())));
      }

      IncomingEmailPayload payload = new IncomingEmailPayload(
          Optional.ofNullable(mailFrom).orElse(""),
          List.copyOf(rcptTo),
          subject,
          bodies.text,
          bodies.html,
          headers,
          rawB64
      );

      log.info("Forwarding email - FROM: {}, TO: {}, SUBJECT: {}", mailFrom, rcptTo, subject);
      forwardClient.forward(payload);
      log.info("Email forwarded successfully - FROM: {}, TO: {}", mailFrom, rcptTo);

    } catch (Exception e) {
      log.error("Failed to process email - FROM: {}, TO: {}, ERROR: {}", 
          mailFrom, rcptTo, e.getMessage(), e);
      throw new RejectException(451, "Processing error");
    }
  }

  @Override
  public void done() {
    log.debug("SMTP transaction done - clearing FROM: {}, TO: {}", mailFrom, rcptTo);
    mailFrom = null;
    rcptTo.clear();
  }

  private static class BodyParts {
    final String text;
    final String html;
    BodyParts(String text, String html) { this.text = text; this.html = html; }
  }

  private static BodyParts extractBodies(Part part) throws Exception {
    return extractBodies(part, null);
  }
  
  private static BodyParts extractBodies(Part part, byte[] rawBytes) throws Exception {
    String text = null;
    String html = null;

    try {
      String contentType = part.getContentType();
      log.debug("Extracting body from part with Content-Type: {}", contentType);
      
      if (part.isMimeType("text/plain")) {
        Object content = part.getContent();
        log.info("Found text/plain part, content type: {}", content != null ? content.getClass() : "null");
        if (content instanceof String) {
          text = (String) content;
        } else if (content instanceof InputStream) {
          text = new String(((InputStream) content).readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } else {
          text = Objects.toString(content, "");
        }
        log.info("Extracted text/plain: {} chars", text != null ? text.length() : 0);
        if (text != null && !text.isEmpty() && text.length() < 500) {
          log.info("Text content preview: {}", text.substring(0, Math.min(200, text.length())));
        }
      } else if (part.isMimeType("text/html")) {
        Object content = part.getContent();
        log.info("Found text/html part, content type: {}", content != null ? content.getClass() : "null");
        if (content instanceof String) {
          html = (String) content;
        } else if (content instanceof InputStream) {
          html = new String(((InputStream) content).readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } else {
          html = Objects.toString(content, "");
        }
        log.info("Extracted text/html: {} chars", html != null ? html.length() : 0);
        if (html != null && !html.isEmpty() && html.length() < 500) {
          log.info("HTML content preview: {}", html.substring(0, Math.min(200, html.length())));
        }
      } else if (part.isMimeType("multipart/*")) {
        log.info("Found multipart/*, extracting sub-parts, Content-Type: {}", contentType);
        Multipart mp = null;
        try {
          // First, try to get content directly using getContent()
          try {
            Object content = part.getContent();
            log.debug("getContent() returned: {}", content != null ? content.getClass().getName() : "null");
            if (content instanceof MimeMultipart) {
              mp = (MimeMultipart) content;
              log.debug("Successfully got MimeMultipart from getContent()");
            } else if (content instanceof Multipart) {
              mp = (Multipart) content;
              log.debug("Successfully got Multipart from getContent()");
            } else {
              log.warn("getContent() returned unexpected type: {}", content != null ? content.getClass().getName() : "null");
            }
          } catch (ClassCastException e) {
            log.warn("ClassCastException getting multipart content: {}", e.getMessage());
            // Try alternative approach: parse from raw bytes if available
            if (rawBytes != null && part instanceof MimeMessage) {
              try {
                log.debug("Attempting to parse MimeMessage from raw bytes");
                Session session = Session.getInstance(new Properties());
                MimeMessage rawMsg = new MimeMessage(session, new java.io.ByteArrayInputStream(rawBytes));
                Object rawContent = rawMsg.getContent();
                if (rawContent instanceof MimeMultipart) {
                  mp = (MimeMultipart) rawContent;
                  log.debug("Successfully parsed MimeMultipart from raw bytes");
                } else if (rawContent instanceof Multipart) {
                  mp = (Multipart) rawContent;
                  log.debug("Successfully parsed Multipart from raw bytes");
                }
              } catch (Exception e2) {
                log.warn("Failed to parse multipart from raw bytes: {}", e2.getMessage());
              }
            }
          } catch (Exception e) {
            log.warn("Exception getting multipart content: {}", e.getMessage(), e);
          }
        } catch (Exception e) {
          log.error("Unexpected exception getting multipart content: {}", e.getMessage(), e);
        }
        
        if (mp != null) {
          log.info("Multipart has {} parts", mp.getCount());
          for (int i = 0; i < mp.getCount(); i++) {
            try {
              Part bodyPart = mp.getBodyPart(i);
              String partContentType = bodyPart.getContentType();
              log.info("Processing multipart part {} of {} - Content-Type: {}", i + 1, mp.getCount(), partContentType);
              
              // Pass rawBytes recursively so nested multiparts can be parsed
              BodyParts bp = extractBodies(bodyPart, rawBytes);
              
              if (text == null && bp.text != null && !bp.text.isEmpty()) {
                text = bp.text;
                log.info("Found text from multipart part {}: {} chars", i + 1, text.length());
              }
              if (html == null && bp.html != null && !bp.html.isEmpty()) {
                html = bp.html;
                log.info("Found html from multipart part {}: {} chars", i + 1, html.length());
              }
            } catch (Exception e) {
              log.error("Error extracting body from multipart part {}: {}", i + 1, e.getMessage(), e);
            }
          }
        } else {
          log.error("Could not extract multipart content from part with Content-Type: {}", contentType);
        }
      } else {
        log.debug("Skipping part with MIME type: {}", contentType);
      }
    } catch (ClassCastException e) {
      log.warn("ClassCastException while extracting body from part with MIME type: {}, error: {}", 
          part.getContentType(), e.getMessage(), e);
      // Return empty bodies if we can't parse
    } catch (Exception e) {
      log.warn("Exception while extracting body from part with MIME type: {}, error: {}", 
          part.getContentType(), e.getMessage(), e);
    }
    
    return new BodyParts(
        text != null ? text : "",
        html != null ? html : ""
    );
  }
  
  private static String extractBoundary(String contentType) {
    if (contentType == null) return null;
    int idx = contentType.indexOf("boundary=");
    if (idx == -1) return null;
    String boundary = contentType.substring(idx + "boundary=".length());
    // Remove quotes if present
    if (boundary.startsWith("\"")) {
      boundary = boundary.substring(1);
    }
    if (boundary.endsWith("\"")) {
      boundary = boundary.substring(0, boundary.length() - 1);
    }
    return boundary.trim();
  }
}
