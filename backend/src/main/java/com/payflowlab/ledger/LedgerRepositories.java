package com.payflowlab.ledger;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public final class LedgerRepositories {

    private LedgerRepositories() {
    }

    public interface AccountRepository extends JpaRepository<Account, UUID> {

        Optional<Account> findByCode(String code);
    }

    public interface JournalEntryRepository
            extends JpaRepository<JournalEntry, UUID>, JpaSpecificationExecutor<JournalEntry> {

        boolean existsBySourceTypeAndSourceIdAndEventType(String sourceType, UUID sourceId, String eventType);

        List<JournalEntry> findBySourceTypeAndSourceIdOrderByPostedAtAsc(String sourceType, UUID sourceId);
    }
}
