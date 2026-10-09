package com.esep.transaction;

import com.esep.account.AccountRepository;
import com.esep.common.exception.InvalidRequestException;
import com.esep.common.exception.ResourceNotFoundException;
import com.esep.common.time.TimeZones;
import com.esep.common.web.PageResponse;
import com.esep.security.CurrentUser;
import com.esep.transaction.OperationHistoryRepository.HistoryFilter;
import com.esep.transaction.dto.OperationResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

/**
 * The current user's statement, newest first.
 * Offset pagination: simple and gives page numbers for the UI; for very deep pages keyset pagination
 * (WHERE (created_at, id) < (:lastCreatedAt, :lastId)) would be faster.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class OperationHistoryService {

    private final OperationHistoryRepository repository;
    private final AccountRepository accountRepository;

    public PageResponse<OperationResponse> history(CurrentUser currentUser, Long accountId,
                                                   LocalDate from, LocalDate to, ZoneId zone,
                                                   int page, int size) {
        if (accountId != null && !accountRepository.existsByIdAndUser_Id(accountId, currentUser.id())) {
            throw new ResourceNotFoundException("Account", accountId);   // 404: do not reveal other accounts
        }
        if (from != null && to != null && from.isAfter(to)) {
            throw new InvalidRequestException("'from' must not be after 'to'");
        }
        ZoneId validZone = TimeZones.requireRegion(zone);
        HistoryFilter filter = new HistoryFilter(currentUser.id(), accountId,
                from == null ? null : startOf(from, validZone),
                to == null ? null : startOf(to.plusDays(1), validZone));

        long total = repository.count(filter);
        List<OperationResponse> content = total == 0 ? List.of() : repository.find(filter, page, size);
        return PageResponse.of(content, page, size, total);
    }

    private static Instant startOf(LocalDate day, ZoneId zone) {
        return day.atStartOfDay(zone).toInstant();
    }
}
