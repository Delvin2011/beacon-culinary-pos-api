package com.beaconculinary.api.accounts;

import jakarta.validation.Valid;
import lombok.AllArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@AllArgsConstructor
@RestController
public class AccountController {
    private final AccountService accountService;

    @GetMapping("/accounts")
    public List<AccountDto> listForTillPicker(@RequestParam(required = false) Boolean active) {
        return accountService.listForTillPicker(active);
    }

    @GetMapping("/admin/accounts")
    public List<AccountAdminDto> listAll() {
        return accountService.listAll();
    }

    @PostMapping("/admin/accounts")
    public ResponseEntity<AccountAdminDto> create(@Valid @RequestBody CreateAccountRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(accountService.create(request));
    }

    @PutMapping("/admin/accounts/{id}")
    public AccountAdminDto update(@PathVariable Long id, @Valid @RequestBody UpdateAccountRequest request) {
        return accountService.update(id, request);
    }

    @GetMapping("/admin/accounts/{id}/balance")
    public AccountBalanceDto getBalance(@PathVariable Long id) {
        return accountService.getBalance(id);
    }

    @PostMapping("/admin/accounts/{id}/payments")
    public AccountBalanceDto recordPayment(@PathVariable Long id, @Valid @RequestBody CreateAccountPaymentRequest request) {
        return accountService.recordPayment(id, request);
    }

    @ExceptionHandler(AccountNotFoundException.class)
    public ResponseEntity<Void> handleNotFound() {
        return ResponseEntity.notFound().build();
    }
}
