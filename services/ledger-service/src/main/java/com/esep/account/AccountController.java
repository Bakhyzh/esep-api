package com.esep.account;

import com.esep.account.dto.AccountResponse;
import com.esep.account.dto.CreateAccountRequest;
import com.esep.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.List;

@Tag(name = "Accounts", description = "Wallets of the current user")
@RestController
@RequestMapping("/api/accounts")
@RequiredArgsConstructor
public class AccountController {

    private final AccountService accountService;

    @Operation(summary = "Open an account in a currency (one active account per currency)")
    @PostMapping
    public ResponseEntity<AccountResponse> create(@AuthenticationPrincipal CurrentUser currentUser,
                                                  @Valid @RequestBody CreateAccountRequest request) {
        AccountResponse created = accountService.create(currentUser, request);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(created.id())
                .toUri();
        return ResponseEntity.created(location).body(created);
    }

    @Operation(summary = "Get an own account (ADMIN: any)")
    @GetMapping("/{id}")
    public AccountResponse getById(@AuthenticationPrincipal CurrentUser currentUser, @PathVariable Long id) {
        return accountService.getById(currentUser, id);
    }

    @Operation(summary = "List own accounts (ADMIN: ?userId= for another user)")
    // my accounts; ADMIN may pass ?userId= to see another user's accounts
    @GetMapping
    public List<AccountResponse> getByUser(@AuthenticationPrincipal CurrentUser currentUser,
                                           @RequestParam(required = false) Long userId) {
        return accountService.getByUser(currentUser, userId);
    }

    @Operation(summary = "Close an own account with zero balance")
    @PostMapping("/{id}/close")
    public AccountResponse close(@AuthenticationPrincipal CurrentUser currentUser, @PathVariable Long id) {
        return accountService.close(currentUser, id);
    }
}
