package com.beaconculinary.api.accounts;

import com.beaconculinary.api.auth.AuthService;
import com.beaconculinary.api.orders.OrderAdjustmentRepository;
import com.beaconculinary.api.orders.OrderPaymentRepository;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** Stage 4 Part B — canteen accounts that pay for orders on credit, settled later via
 * {@link AccountPayment}. The balance formula deliberately reaches into {@code orders}
 * ({@link OrderPaymentRepository}/{@link OrderAdjustmentRepository}) rather than the reverse —
 * an account's balance is fundamentally derived from its orders, so this one place carries that
 * coupling instead of orders reporting balances back to accounts. */
@Service
@AllArgsConstructor
public class AccountService {
    private final AccountRepository accountRepository;
    private final AccountPaymentRepository accountPaymentRepository;
    private final OrderPaymentRepository orderPaymentRepository;
    private final OrderAdjustmentRepository orderAdjustmentRepository;
    private final AuthService authService;
    private final AccountMapper accountMapper;

    @Transactional(readOnly = true)
    public List<AccountDto> listForTillPicker(Boolean active) {
        var accounts = active != null ? accountRepository.findByActive(active) : accountRepository.findAll();
        return accounts.stream().map(accountMapper::toDto).toList();
    }

    @Transactional(readOnly = true)
    public List<AccountAdminDto> listAll() {
        return accountRepository.findAll().stream().map(accountMapper::toAdminDto).toList();
    }

    @Transactional
    public AccountAdminDto create(CreateAccountRequest request) {
        var account = new Account();
        account.setName(request.getName());
        account.setContactEmail(request.getContactEmail());
        accountRepository.save(account);
        return accountMapper.toAdminDto(account);
    }

    @Transactional
    public AccountAdminDto update(Long id, UpdateAccountRequest request) {
        var account = accountRepository.findById(id).orElseThrow(AccountNotFoundException::new);
        account.setName(request.getName());
        account.setContactEmail(request.getContactEmail());
        account.setActive(request.isActive());
        accountRepository.save(account);
        return accountMapper.toAdminDto(account);
    }

    @Transactional(readOnly = true)
    public AccountBalanceDto getBalance(Long id) {
        if (!accountRepository.existsById(id)) {
            throw new AccountNotFoundException();
        }
        return computeBalance(id);
    }

    @Transactional
    public AccountBalanceDto recordPayment(Long id, CreateAccountPaymentRequest request) {
        var account = accountRepository.findById(id).orElseThrow(AccountNotFoundException::new);

        var payment = new AccountPayment();
        payment.setAccount(account);
        payment.setAmount(request.getAmount());
        payment.setNote(request.getNote());
        payment.setRecordedBy(authService.getCurrentUser());
        accountPaymentRepository.save(payment);

        return computeBalance(id);
    }

    // outstandingBalance = totalCharged - totalReversed - totalPaid, per Stage 4 Part B.
    private AccountBalanceDto computeBalance(Long accountId) {
        var totalCharged = orderPaymentRepository.sumAmountByAccountId(accountId);
        var totalReversed = orderAdjustmentRepository.sumAmountByAccountId(accountId);
        var totalPaid = accountPaymentRepository.sumAmountByAccountId(accountId);
        var outstandingBalance = totalCharged.subtract(totalReversed).subtract(totalPaid);
        return new AccountBalanceDto(totalCharged, totalReversed, totalPaid, outstandingBalance);
    }
}
