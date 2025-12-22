package com.tikrai.mailreceiver.smtp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.subethamail.smtp.MessageHandlerFactory;
import org.subethamail.smtp.server.SMTPServer;

@Configuration
public class SmtpServerConfig implements AutoCloseable {

  private static final Logger log = LoggerFactory.getLogger(SmtpServerConfig.class);

  private SMTPServer server;

  @Bean
  public SMTPServer smtpServer(DomainFilterMessageHandler handler,
                              org.springframework.core.env.Environment env) {

    String host = env.getProperty("app.smtp.host", "0.0.0.0");
    int port = Integer.parseInt(env.getProperty("app.smtp.port", "2525"));

    log.info("Starting SMTP server - host: {}, port: {}", host, port);

    MessageHandlerFactory factory = ctx -> {
      log.info("SMTP NEW CONNECTION - RemoteAddress: {}", ctx != null ? ctx.getRemoteAddress() : "unknown");
      return handler;
    };
    SMTPServer s = new SMTPServer(factory);
    s.setHostName(host);
    s.setPort(port);

    // MVP: no STARTTLS (add later if needed)
    s.setHideTLS(true);
    
    // Enable verbose logging for connections
    s.setSoftwareName("TikraiMailReceiver");

    s.start();
    log.info("SMTP server started successfully - host: {}, port: {}, listening on all interfaces", host, port);
    this.server = s;
    return s;
  }

  @Override
  public void close() {
    if (server != null) {
      log.info("Stopping SMTP server");
      server.stop();
      log.info("SMTP server stopped");
    }
  }
}
