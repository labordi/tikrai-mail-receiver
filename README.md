# tikrai-mail-receiver

Spring Boot (Java 21) SMTP receiver, which accepts all emails addressed to `*@tikrai.com` and forwards them to an internal HTTP service: `tikrai-server-service`.

## What it does
- Exposes SMTP listener on port **2525** (container).
- Validates all RCPT recipients end with `@tikrai.com` (configurable).
- Parses the email (subject, headers, text/html bodies) and also includes the raw RFC822 as base64.
- Sends JSON payload via HTTP POST to `tikrai-server-service`.

## Quick start (local)
1) Build:
```bash
mvn -DskipTests package
```

2) Run:
```bash
java -jar target/tikrai-mail-receiver-0.1.0.jar
```

3) Test with swaks:
```bash
swaks --to test@tikrai.com --from sender@example.com --server 127.0.0.1:2525 --data "Subject: Hello\n\nHello world"
```

## Kubernetes (DigitalOcean / DOKS)
See `k8s/` manifests.
- Deploy Service type LoadBalancer and point `mail.tikrai.com` A-record to the LB IP
- Set `tikrai.com` MX to `mail.tikrai.com`

## Configuration
- `app.smtp.acceptedDomain` (default `tikrai.com`)
- `app.forward.url` (default `http://tikrai-server-service:8080/api/incoming-email`)
- Optional API key header: `APP_FORWARD_API_KEY` (sent as `X-Api-Key` by default)

## Notes
This is an MVP receiver. For production:
- Add STARTTLS
- Add spam/rate-limiting
- Consider queue between receiver and downstream service
