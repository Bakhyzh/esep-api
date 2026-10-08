package com.esep.outbox;

import com.esep.account.Account;
import com.esep.transaction.LedgerTransaction;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.util.UUID;

/** Turns a completed transfer into an outbox row inside the caller's transaction. */
@Component
@RequiredArgsConstructor
public class TransferEventWriter {

    private final OutboxRepository outboxRepository;
    private final JsonMapper jsonMapper;

    // MANDATORY: fail loudly if someone calls it outside a transaction - the event would be orphaned
    @Transactional(propagation = Propagation.MANDATORY)
    public TransferCompletedEvent transferCompleted(LedgerTransaction tx, Account from, Account to) {
        TransferCompletedEvent event = new TransferCompletedEvent(
                UUID.randomUUID(),
                tx.getId(),
                from.getId(),
                from.getUser().getId(),
                to.getId(),
                to.getUser().getId(),
                tx.getEntries().getFirst().getAmount(),
                from.getCurrency().getCurrencyCode(),
                tx.getCreatedAt());
        outboxRepository.append(event.eventId(), "transaction", tx.getId(), TransferCompletedEvent.TYPE,
                // key = sender account: all events of one account land in one partition, in order
                String.valueOf(from.getId()),
                jsonMapper.writeValueAsString(event));
        return event;
    }
}
