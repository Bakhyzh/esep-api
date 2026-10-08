package com.esep.transaction.dto;

/** replayed = true: the request was already processed earlier, this is the saved result. */
public record TransactionResult(TransactionResponse response, boolean replayed) {

    public static TransactionResult created(TransactionResponse response) {
        return new TransactionResult(response, false);
    }

    public static TransactionResult replayed(TransactionResponse response) {
        return new TransactionResult(response, true);
    }
}
