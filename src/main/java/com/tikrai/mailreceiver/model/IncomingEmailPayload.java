package com.tikrai.mailreceiver.model;

import java.util.List;
import java.util.Map;

public record IncomingEmailPayload(
    String mailFrom,
    List<String> rcptTo,
    String subject,
    String textBody,
    String htmlBody,
    Map<String, List<String>> headers,
    String rawBase64
) {}
