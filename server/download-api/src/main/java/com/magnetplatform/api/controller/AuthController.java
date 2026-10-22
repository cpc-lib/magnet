package com.magnetplatform.api.controller;

import com.magnetplatform.api.dto.Dtos.AuthResponse;
import com.magnetplatform.api.dto.Dtos.LoginRequest;
import com.magnetplatform.api.dto.Dtos.RegisterRequest;
import com.magnetplatform.api.dto.Dtos.UserView;
import com.magnetplatform.application.AuthService;
import com.magnetplatform.application.JwtService;
import com.magnetplatform.domain.user.AppUser;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;
    private final JwtService jwtService;

    public AuthController(AuthService authService, JwtService jwtService) {
        this.authService = authService;
        this.jwtService = jwtService;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public AuthResponse register(@Valid @RequestBody RegisterRequest request) {
        AppUser user = authService.register(request.username(), request.password());
        return new AuthResponse(jwtService.issue(user), UserView.from(user));
    }

    @PostMapping("/login")
    public AuthResponse login(@Valid @RequestBody LoginRequest request) {
        AppUser user = authService.login(request.username(), request.password());
        return new AuthResponse(jwtService.issue(user), UserView.from(user));
    }
}
