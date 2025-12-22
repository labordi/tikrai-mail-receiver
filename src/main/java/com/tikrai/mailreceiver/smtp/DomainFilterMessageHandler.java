package com.tikrai.mailreceiver.smtp;

import com.tikrai.mailreceiver.forward.ForwardClient;
import com.tikrai.mailreceiver.model.IncomingEmailPayload;
import jakarta.mail.Header;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.springframework.stereotype.Component;
import org.subethamail.smtp.MessageHandler;
import org.subethamail.smtp.RejectException;

import java.io.InputStream;
import java.util.*;
import java.util.Base64;

@Component
public class DomainFilterMessageHandler implements MessageHandler {

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
  }

  @Override
  public void recipient(String recipient) throws RejectException {
    String r = recipient.trim().toLowerCase(Locale.ROOT);
    if (!r.endsWith("@" + acceptedDomain)) {
      throw new RejectException(550, "Relaying denied");
    }
    rcptTo.add(r);
  }

  @Override
  public void data(InputStream data) throws RejectException {
    try {
      byte[] rawBytes = data.readAllBytes();
      String rawB64 = Base64.getEncoder().encodeToString(rawBytes);

      Session session = Session.getInstance(new Properties());
      MimeMessage msg = new MimeMessage(session, new java.io.ByteArrayInputStream(rawBytes));

      String subject = Optional.ofNullable(msg.getSubject()).orElse("");

      Map<String, List<String>> headers = new LinkedHashMap<>();
      @SuppressWarnings("unchecked")
      Enumeration<Header> allHeaders = msg.getAllHeaders();
      while (allHeaders.hasMoreElements()) {
        Header h = allHeaders.nextElement();
        headers.computeIfAbsent(h.getName(), k -> new ArrayList<>()).add(h.getValue());
      }

      BodyParts bodies = extractBodies(msg);

      IncomingEmailPayload payload = new IncomingEmailPayload(
          Optional.ofNullable(mailFrom).orElse(""),
          List.copyOf(rcptTo),
          subject,
          bodies.text,
          bodies.html,
          headers,
          rawB64
      );

      forwardClient.forward(payload);

    } catch (Exception e) {
      System.err.println("Failed to process email: " + e.getMessage());
      throw new RejectException(451, "Processing error");
    }
  }

  @Override
  public void done() {
    mailFrom = null;
    rcptTo.clear();
  }

  private static class BodyParts {
    final String text;
    final String html;
    BodyParts(String text, String html) { this.text = text; this.html = html; }
  }

  private static BodyParts extractBodies(Part part) throws Exception {
    String text = null;
    String html = null;

    if (part.isMimeType("text/plain")) {
      text = Objects.toString(part.getContent(), "");
    } else if (part.isMimeType("text/html")) {
      html = Objects.toString(part.getContent(), "");
    } else if (part.isMimeType("multipart/*")) {
      Multipart mp = (Multipart) part.getContent();
      for (int i = 0; i < mp.getCount(); i++) {
        BodyParts bp = extractBodies(mp.getBodyPart(i));
        if (text == null && bp.text != null) text = bp.text;
        if (html == null && bp.html != null) html = bp.html;
      }
    }
    return new BodyParts(
        text != null ? text : "",
        html != null ? html : ""
    );
  }
}
