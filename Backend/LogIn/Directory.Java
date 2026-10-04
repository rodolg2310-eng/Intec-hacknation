package com.apprentice.login;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Directorio en memoria de empresas y usuarios.
 * Sustitúyelo por una base de datos cuando lo necesites:
 * basta con cambiar esta clase, el resto del código no cambia.
 */
@Component
public class Directory {

    private final Map<String, Company> companies = new ConcurrentHashMap<>();
    // clave: companySlug + "|" + email
    private final Map<String, AppUser> users = new ConcurrentHashMap<>();

    public Directory(PasswordEncoder encoder) {
        // Empresa de ejemplo
        registerCompany(new Company("acme", "Acme Corp"));

        // Usuarios de ejemplo (cámbialos o bórralos)
        registerUser(new AppUser("experto@acme.com", encoder.encode("experto123"), Role.EXPERT, "acme"));
        registerUser(new AppUser("aspirante@acme.com", encoder.encode("aspirante123"), Role.ASPIRANT, "acme"));
    }

    public void registerCompany(Company company) {
        companies.put(company.slug(), company);
    }

    public void registerUser(AppUser user) {
        users.put(key(user.companySlug(), user.email()), user);
    }

    public Optional<Company> findCompany(String slug) {
        return Optional.ofNullable(companies.get(slug));
    }

    public Optional<AppUser> findUser(String companySlug, String email) {
        return Optional.ofNullable(users.get(key(companySlug, email.toLowerCase())));
    }

    private String key(String companySlug, String email) {
        return companySlug + "|" + email.toLowerCase();
    }
}
