package com.apprentice.login;

/**
 * Usuario de una empresa. El correo solo existe dentro de su empresa:
 * el mismo correo puede ser experto en una empresa y aspirante en otra.
 */
public record AppUser(String email, String password, Role role, String companySlug) {
}
