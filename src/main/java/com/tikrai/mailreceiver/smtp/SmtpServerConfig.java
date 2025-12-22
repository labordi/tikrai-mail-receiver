package com.tikrai.mailreceiver.smtp;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.subethamail.smtp.MessageHandlerFactory;
import org.subethamail.smtp.server.SMTPServer;

@Configuration
public class SmtpServerConfig implements AutoCloseable {

  private SMTPServer server;

  @Bean
  public SMTPServer smtpServer(DomainFilterMessageHandler handler,
                              org.springframework.core.env.Environment env) {

    String host = env.getProperty("app.smtp.host", "0.0.0.0");
    int port = Integer.parseInt(env.getProperty("app.smtp.port", "2525"));

    MessageHandlerFactory factory = ctx -> handler; // MVP: singleton handler
    SMTPServer s = new SMTPServer(factory);
    s.setHostName(host);
    s.setPort(port);

    // MVP: no STARTTLS (add later if needed)
    s.setHideTLS(true);

    s.start();
    this.server = s;
    return s;
  }

  @Override
  public void close() {
    if (server != null) server.stop();
  }
}
