package com.edtech.plugtify.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;

import javax.persistence.*;
import javax.validation.constraints.Email;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Pattern;
import javax.validation.constraints.Size;

@Entity
@Table(name="tbl_user")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank
    @Column(name = "login", length = 50, nullable = false)
    @Size(min = 4, max = 50)
    @Pattern(regexp = "^[_.@A-Za-z0-9-]+$", message = "login pattern dont match")
    private String login;

    @NotBlank
    @Column(name = "email", length = 254, nullable = false)
    @Email(message = "Email not valid")
    private String email;

    @NotBlank
    @JsonIgnore
    @Size(min = 6, max = 60)
    @Column(name = "password_hash", length = 60, nullable = false)
    private String password_hash;

    @Override
    public String toString() {
        return "User:{}";
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getLogin() {
        return login;
    }

    public void setLogin(String login) {
        this.login = login;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getPassword_hash() {
        return password_hash;
    }

    public void setPassword_hash(String password_hash) {
        this.password_hash = password_hash;
    }
}