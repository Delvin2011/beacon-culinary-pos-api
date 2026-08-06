package com.beaconculinary.api.admin;

import com.beaconculinary.api.common.ErrorDto;
import jakarta.validation.Valid;
import lombok.AllArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@AllArgsConstructor
@RestController
@RequestMapping("/admin")
public class AdminController {
    private final AdminAuthorizationService adminAuthorizationService;

    @GetMapping("/hello")
    public String sayHello() {
        return "Hello Admin!";
    }

    @PostMapping("/authorize")
    public AuthorizeResponse authorize(@Valid @RequestBody AuthorizeRequest request) {
        return adminAuthorizationService.authorize(request);
    }

    @ExceptionHandler(InvalidPinException.class)
    public ResponseEntity<ErrorDto> handleInvalidPin(InvalidPinException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(new ErrorDto(ex.getMessage()));
    }
}
