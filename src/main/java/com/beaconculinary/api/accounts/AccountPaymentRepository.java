package com.beaconculinary.api.accounts;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;

public interface AccountPaymentRepository extends JpaRepository<AccountPayment, Long> {
    /** Stage 4 Part B balance formula's totalPaid term. */
    @Query("SELECT COALESCE(SUM(p.amount), 0) FROM AccountPayment p WHERE p.account.id = :accountId")
    BigDecimal sumAmountByAccountId(@Param("accountId") Long accountId);
}
