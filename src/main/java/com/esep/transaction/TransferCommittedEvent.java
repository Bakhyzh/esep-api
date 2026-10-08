package com.esep.transaction;

/** In-process Spring event, delivered to @TransactionalEventListener after the transfer commits. */
public record TransferCommittedEvent(Long transactionId, Long senderUserId, Long receiverUserId) {
}
