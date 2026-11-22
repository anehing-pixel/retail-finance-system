package com.company.retail.core.application;

import com.company.platform.exception.BusinessException;
import com.company.retail.core.domain.Loan;
import com.company.retail.core.persistence.JdbcLoanRepository;
import com.company.retail.domain.exception.RFErrorCode;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/**
 * 债权用例（W5 前半）：债权建立（合同生效 → 生成债权记录）。
 * 入金核销 / 余额对拍 / 结清归 W6，不在本服务本波次。
 */
@Service
public class LoanApplicationService {

    private final JdbcLoanRepository loanRepository;

    public LoanApplicationService(JdbcLoanRepository loanRepository) {
        this.loanRepository = loanRepository;
    }

    /** POST /api/loans：建立债权（PENDING_DISBURSEMENT；uk_loan_contract 幂等兜底）。 */
    @Transactional
    public LoanView create(long contractId, long principal, long feeAmount, String traceId) {
        if (contractId <= 0) {
            throw invalid("contractId 必须为正整数业务主键");
        }
        if (principal <= 0) {
            throw invalid("principal 必须为正整数日元");
        }
        if (feeAmount < 0) {
            throw invalid("feeAmount 必须为非负整数日元");
        }
        try {
            long id = loanRepository.insert(Loan.create(contractId, principal, feeAmount), traceId);
            Loan loan = loanRepository.findById(id).orElseThrow();
            return view(loan);
        } catch (DuplicateKeyException e) {
            throw new BusinessException(RFErrorCode.RF_CONFLICT_001, "该合同已存在债权记录（uk_loan_contract）");
        }
    }

    public LoanView get(long loanId) {
        return view(load(loanId));
    }

    public BalanceView balance(long loanId) {
        Loan loan = load(loanId);
        return new BalanceView(loan.loanId(), loan.balance(), loan.status().name());
    }

    private Loan load(long loanId) {
        return loanRepository.findById(loanId)
                .orElseThrow(() -> new BusinessException(RFErrorCode.RF_VALID_001,
                        "债权不存在", Map.of("loanId", String.valueOf(loanId))));
    }

    private static BusinessException invalid(String message) {
        return new BusinessException(RFErrorCode.RF_VALID_001, message);
    }

    private static LoanView view(Loan loan) {
        return new LoanView(loan.loanId(), loan.contractId(),
                JdbcLoanRepository.loanNoOf(loan.contractId()), loan.status().name(),
                loan.principal(), loan.feeAmount(), loan.balance(), loan.disbursedAt(), loan.version());
    }

    public record LoanView(long loanId, long contractId, String loanNo, String status,
                           long principal, long feeAmount, long balance,
                           java.time.Instant disbursedAt, int version) {
    }

    public record BalanceView(long loanId, long balance, String status) {
    }
}
