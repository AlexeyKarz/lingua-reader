package com.example.reader.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class BootstrapAdminInitializer implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(BootstrapAdminInitializer.class);

    private final AuthBootstrapProperties properties;
    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;

    public BootstrapAdminInitializer(
            AuthBootstrapProperties properties,
            UserRepository users,
            PasswordEncoder passwordEncoder
    ) {
        this.properties = properties;
        this.users = users;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (users.adminExists()) {
            log.info("Admin bootstrap skipped: at least one enabled admin user already exists");
            return;
        }

        String email = UserRepository.normalizeEmail(properties.getAdminEmail());
        String password = properties.getAdminPassword();
        if (email.isBlank() || password.isBlank()) {
            log.info("Admin bootstrap skipped: MLR_BOOTSTRAP_ADMIN_EMAIL/PASSWORD are not both configured");
            return;
        }
        if (password.length() < 12) {
            log.warn("Admin bootstrap skipped: configured bootstrap password is shorter than 12 characters");
            return;
        }

        long userId = users.createUser(
                email,
                properties.getAdminDisplayName(),
                passwordEncoder.encode(password),
                true
        );
        users.addRole(userId, "USER");
        users.addRole(userId, "ADMIN");
        log.info("Admin bootstrap created initial admin user '{}'", email);
    }
}
