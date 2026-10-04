package com.apprentice.login;

import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.server.ResponseStatusException;

import static org.springframework.http.HttpStatus.NOT_FOUND;

@Controller
public class PagesController {

    private final Directory directory;

    public PagesController(Directory directory) {
        this.directory = directory;
    }

    @GetMapping("/")
    public String inicio() {
        return "redirect:/login";
    }

    @GetMapping("/login")
    public String login() {
        return "login";
    }

    /** Página pública de la empresa: /c/acme */
    @GetMapping("/c/{slug}")
    public String empresa(@PathVariable String slug, Model model) {
        Company company = directory.findCompany(slug)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Empresa no encontrada"));
        model.addAttribute("company", company);
        return "empresa";
    }

    /** Zona del experto: aquí graba su experiencia, que entrena a la IA. */
    @GetMapping("/c/{slug}/experto")
    public String experto(@PathVariable String slug, Model model, Authentication auth) {
        Company company = directory.findCompany(slug)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Empresa no encontrada"));
        model.addAttribute("company", company);
        model.addAttribute("email", auth.getName().split("\\|", 2)[1]);
        return "experto";
    }

    /** Zona del aspirante: aquí aprende con lo que la IA genera. */
    @GetMapping("/c/{slug}/aspirante")
    public String aspirante(@PathVariable String slug, Model model, Authentication auth) {
        Company company = directory.findCompany(slug)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Empresa no encontrada"));
        model.addAttribute("company", company);
        model.addAttribute("email", auth.getName().split("\\|", 2)[1]);
        return "aspirante";
    }
}
