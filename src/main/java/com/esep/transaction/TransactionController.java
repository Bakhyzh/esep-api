package com.esep.transaction;

import com.esep.security.CurrentUser;
import com.esep.transaction.dto.DepositRequest;
import com.esep.transaction.dto.TransactionResponse;
import com.esep.transaction.dto.TransactionResult;
import com.esep.transaction.dto.TransferRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;

@Tag(name = "Transactions", description = "Double-entry transfers and deposits. POST requests require an Idempotency-Key header: a retry with the same key returns the original result")
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class TransactionController {

    static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    static final String IDEMPOTENT_REPLAYED = "Idempotent-Replayed";

    private final TransactionService transactionService;

    @Operation(summary = "Transfer money from an own account (201; retry with the same key: 200 + Idempotent-Replayed)")
    @PostMapping("/transfers")
    public ResponseEntity<TransactionResponse> transfer(
            @AuthenticationPrincipal CurrentUser currentUser,
            @RequestHeader(IDEMPOTENCY_KEY) @NotBlank @Size(max = 64) String idempotencyKey,
            @Valid @RequestBody TransferRequest request) {
        return created(transactionService.transfer(currentUser, idempotencyKey, request));
    }

    @Operation(summary = "Deposit money from the system funding account (ADMIN only)")
    @PostMapping("/deposits")
    public ResponseEntity<TransactionResponse> deposit(
            @AuthenticationPrincipal CurrentUser currentUser,
            @RequestHeader(IDEMPOTENCY_KEY) @NotBlank @Size(max = 64) String idempotencyKey,
            @Valid @RequestBody DepositRequest request) {
        return created(transactionService.deposit(currentUser, idempotencyKey, request));
    }

    @Operation(summary = "Get a transaction with its ledger entries (participants and ADMIN)")
    @GetMapping("/transactions/{id}")
    public TransactionResponse getById(@AuthenticationPrincipal CurrentUser currentUser, @PathVariable Long id) {
        return transactionService.getById(currentUser, id);
    }

    // first call -> 201 Created; repeated call with the same key -> 200 OK with the saved result
    private static ResponseEntity<TransactionResponse> created(TransactionResult result) {
        TransactionResponse body = result.response();
        if (result.replayed()) {
            return ResponseEntity.ok().header(IDEMPOTENT_REPLAYED, "true").body(body);
        }
        URI location = ServletUriComponentsBuilder.fromCurrentContextPath()
                .path("/api/transactions/{id}")
                .buildAndExpand(body.id())
                .toUri();
        return ResponseEntity.created(location).body(body);
    }
}
