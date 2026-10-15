package za.co.mrhdigital.audex.auth;

import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

/**
 * Real email goes through SMTP when SPRING_MAIL_HOST (and the usual SPRING_MAIL_* settings) are provided; any provider
 * that offers SMTP works. Without them, emails are written to the server log so development still works. A reset link in
 * a log is only acceptable on a developer's machine, so production must set the SMTP variables.
 */
@Configuration
public class MailConfig {
    private static final Logger log = LoggerFactory.getLogger(MailConfig.class);

    @Bean
    MailSender mailSender(Optional<JavaMailSender> smtp, @Value("${audex.mail.from:no-reply@localhost}") String from) {
        if (smtp.isPresent()) {
            return (to, subject, body) -> {
                SimpleMailMessage m = new SimpleMailMessage();
                m.setFrom(from); m.setTo(to); m.setSubject(subject); m.setText(body);
                smtp.get().send(m);
            };
        }
        log.warn("No SMTP server configured (SPRING_MAIL_HOST): emails will only be written to this log.");
        return (to, subject, body) -> log.info("EMAIL to={} subject={}\n{}", to, subject, body);
    }
}
