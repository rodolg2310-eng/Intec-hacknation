package com.apprentice.login;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

import java.util.List;

@Configuration
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * El login pide: empresa (slug), correo y contraseña.
     * Spring Security usa "username"; nosotros le pasamos "slug|correo".
     */
    @Bean
    public UserDetailsService userDetailsService(Directory directory) {
        return username -> {
            String[] parts = username.split("\\|", 2);
            if (parts.length != 2) {
                throw new UsernameNotFoundException("Formato inválido");
            }
            AppUser user = directory.findUser(parts[0], parts[1])
                    .orElseThrow(() -> new UsernameNotFoundException("Usuario no encontrado"));
            return new User(
                    username,
                    user.password(),
                    List.of(new SimpleGrantedAuthority("ROLE_" + user.role().name()))
            );
        };
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, Directory directory) throws Exception {
        http
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/", "/login", "/css/**").permitAll()
                .requestMatchers("/c/*/experto/**").hasRole("EXPERT")
                .requestMatchers("/c/*/aspirante/**").hasRole("ASPIRANT")
                .anyRequest().authenticated()
            )
            .formLogin(form -> form
                .loginPage("/login")
                .loginProcessingUrl("/login")
                // Tras iniciar sesión, cada quien va a su página según su rol
                .successHandler((request, response, authentication) -> {
                    String[] parts = authentication.getName().split("\\|", 2);
                    String slug = parts[0];
                    boolean expert = authentication.getAuthorities().stream()
                            .anyMatch(a -> a.getAuthority().equals("ROLE_EXPERT"));
                    String destino = expert
                            ? "/c/" + slug + "/experto"
                            : "/c/" + slug + "/aspirante";
                    response.sendRedirect(destino);
                })
                .failureUrl("/login?error")
                .permitAll()
            )
            .logout(logout -> logout.logoutSuccessUrl("/login?saliste").permitAll());
        return http.build();
    }
}
